import { render, screen, within } from '@testing-library/react';
import { parseRunEvent, type LiveEvent } from '../../api/events';
import type { ToolInvocation } from '../../api/types';
import { useUi } from '../../app/uiStore';
import { mockFetch, reply } from '../../test/fakes';
import { config, invocations, run, runList, RUN_ID, scenarios, snapshot } from '../../test/fixtures';
import { renderApp, resetStores } from '../../test/render';
import { EffectText, sandboxOf } from '../audit/InvocationDetail';
import { checklist, GUIDE_STEPS } from '../presenter/checklist';
import { FALLBACK_PRESETS, PRESET_ORDER, resolvePresets } from '../scenarios/presets';

const sandboxed: ToolInvocation = {
  ...invocations.items[0]!,
  invocationId: 'inv-sb',
  taskId: 'task-4',
  toolName: 'analysis.run_script',
  toolClass: 'SANDBOXED',
  arguments: { service: 'checkout', script: 'group errors by signature; top 3', windowMinutes: 30 },
  result: { dataOrigin: 'SYNTHETIC', sandbox: { mode: 'SIMULATED', executed: false }, signatures: [] },
};

const ev = (data: Record<string, unknown>, runId = RUN_ID): LiveEvent =>
  parseRunEvent(
    {
      specversion: '1.0',
      id: crypto.randomUUID(),
      source: '/maop/tool-service',
      type: 'io.github.danieloh30.maop.tool.invocation.completed.v1',
      maoprunid: runId,
      data,
    },
    null,
  )!;

describe('sandboxed analysis scenario', () => {
  it('is appended as the fifth preset and leaves the first four untouched', () => {
    expect(PRESET_ORDER).toEqual(['SUCCESS', 'POLICY_DENIAL', 'BURST', 'REDELIVERY', 'SANDBOX']);
    expect(GUIDE_STEPS.map((g) => g.id)).toEqual(PRESET_ORDER);
    const presets = resolvePresets(undefined);
    expect(presets.map((p) => p.id)).toEqual(PRESET_ORDER);
    const withSetting = FALLBACK_PRESETS.filter((p) => !!p.request && 'analysisMode' in p.request);
    expect(withSetting.map((p) => p.id)).toEqual(['SANDBOX']);
    expect(presets[4]!.request).toMatchObject({ analysisMode: 'SANDBOXED_SCRIPT', remediationMode: 'PROPOSE_ONLY', faultInjection: 'NONE' });
  });

  it('ticks the guide only on a real sandboxed call and the log analysis evidence', () => {
    const base = { runId: RUN_ID, run: undefined, history: [], burstStartedAt: null };
    const read = ev({ invocationId: 'i0', toolName: 'deployment.get_history', toolClass: 'READ', status: 'EXECUTED' });
    expect(checklist('SANDBOX', { ...base, events: [read] }).every((i) => !i.done)).toBe(true);
    const denied = ev({ invocationId: 'i1', toolName: 'analysis.run_script', toolClass: 'SANDBOXED', status: 'DENIED' });
    expect(checklist('SANDBOX', { ...base, events: [denied] })[0]!.done).toBe(false);
    const executed = ev({ invocationId: 'i2', toolName: 'analysis.run_script', toolClass: 'SANDBOXED', status: 'EXECUTED' });
    const withEvidence = run({
      state: 'AWAITING_APPROVAL',
      evidence: [{ ...run().evidence[0]!, topic: 'LOG_ANALYSIS' }],
    });
    const items = checklist('SANDBOX', { ...base, run: withEvidence, events: [executed] });
    expect(items.map((i) => i.done)).toEqual([true, true, true]);
  });

  it('reports the sandbox mode from the recorded result and assumes nothing when it is missing', () => {
    expect(sandboxOf(sandboxed.result)).toEqual({ mode: 'SIMULATED', executed: false });
    expect(sandboxOf({})).toEqual({ mode: null, executed: null });
    expect(sandboxOf(null)).toEqual({ mode: null, executed: null });
    const { rerender } = render(<EffectText inv={sandboxed} />);
    expect(screen.getByTestId('sandbox-effect')).toHaveTextContent('Sandbox: simulated. The script was recorded, not executed');
    rerender(<EffectText inv={{ ...sandboxed, result: {} }} />);
    expect(screen.getByTestId('sandbox-effect')).toHaveTextContent('Sandbox mode: not reported');
    rerender(<EffectText inv={invocations.items[0]!} />);
    expect(screen.queryByTestId('sandbox-effect')).toBeNull();
  });

  it('tags the run and explains the extra task; other runs show neither', async () => {
    const routes = (r: ReturnType<typeof run>, items: ToolInvocation[]) =>
      mockFetch({
        'GET /api/v1/config': () => config(),
        'GET /api/v1/scenarios': () => scenarios,
        'GET /api/v1/runs': () => runList(r),
        'GET /api/v1/ops/snapshot': () => snapshot(),
        'GET /api/v1/bursts/current': () => ({ active: false, burstId: null, runs: null, completed: null, startedAt: null }),
        'GET /api/v1/session': () => reply(401),
        [`GET /api/v1/runs/${RUN_ID}`]: () => r,
        [`GET /api/v1/runs/${RUN_ID}/timeline`]: () => ({ items: [] }),
        'GET /api/v1/audit/invocations': () => ({ items }),
      });

    resetStores();
    useUi.setState({ view: 'timeline' });
    routes(run({ scenario: 'SANDBOX', analysisMode: 'SANDBOXED_SCRIPT' }), [invocations.items[0]!, sandboxed]);
    const first = renderApp(`/runs/${RUN_ID}`);
    expect(await screen.findByTestId('sandbox-tag')).toHaveTextContent('Sandboxed analysis');
    const note = await screen.findByTestId('sandbox-note');
    expect(note).toHaveTextContent('Sandbox mode: SIMULATED (the script was recorded, not executed).');
    expect(within(note).getByRole('link', { name: 'Open in Tool audit' })).toHaveAttribute('href', `/runs/${RUN_ID}/audit/inv-sb`);
    first.unmount();

    resetStores();
    useUi.setState({ view: 'timeline' });
    routes(run({ scenario: 'SUCCESS' }), [invocations.items[0]!]);
    renderApp(`/runs/${RUN_ID}`);
    await screen.findByRole('list', { name: 'Task lanes' });
    expect(screen.queryByTestId('sandbox-tag')).toBeNull();
    expect(screen.queryByTestId('sandbox-note')).toBeNull();
  });
});
