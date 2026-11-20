import { describe, expect, it } from 'vitest';
import { run } from '../../test/fixtures';
import { ACTIVE_NODES, EDGES, NODE_ORDER, NODE_POSITIONS } from './graph';
import { stageCaption } from './Topology';

describe('topology stage caption', () => {
  it('narrates a live run from its real state', () => {
    const gathering = run({
      state: 'GATHERING_EVIDENCE',
      tasks: [
        { taskId: 'a', kind: 'EVIDENCE', topic: 'DEPLOYMENT_HISTORY', state: 'COMPLETED', deliveries: 1, startedAt: null, finishedAt: null },
        { taskId: 'b', kind: 'EVIDENCE', topic: 'CONFIG_DIFF', state: 'DISPATCHED', deliveries: 1, startedAt: null, finishedAt: null },
      ],
    });
    expect(stageCaption(gathering).text).toContain('1 of 2 tasks done');
    expect(stageCaption(run({ state: 'AWAITING_APPROVAL' })).denied).toBe(false);
  });

  it('names the policy denial and its reason', () => {
    const c = stageCaption(
      run({
        state: 'AWAITING_APPROVAL',
        executionAttempt: { invocationId: 'i', status: 'DENIED', decisionId: 'd', reasons: ['approval_required'] },
      }),
    );
    expect(c.denied).toBe(true);
    expect(c.text).toContain('approval_required');
  });
});

describe('compact topology layout', () => {
  it('keeps every node inside a projector-friendly box and every edge on known nodes', () => {
    const xs = NODE_ORDER.map((id) => NODE_POSITIONS[id].x);
    const ys = NODE_ORDER.map((id) => NODE_POSITIONS[id].y);
    expect(Math.max(...xs) + 210).toBeLessThanOrEqual(1250);
    expect(Math.max(...ys)).toBeLessThanOrEqual(400);
    for (const e of EDGES) {
      expect(NODE_ORDER).toContain(e.source);
      expect(NODE_ORDER).toContain(e.target);
    }
    for (const ids of Object.values(ACTIVE_NODES)) for (const id of ids) expect(NODE_ORDER).toContain(id);
  });
});
