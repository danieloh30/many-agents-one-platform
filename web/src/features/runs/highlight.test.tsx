import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Highlighted, segments } from './highlight';

const ROLLBACK = { actionType: 'ROLLBACK_DEPLOYMENT' as const, target: 'checkout' };
const TEXT =
  'The latest release (2.14.0) reduced payment client timeout from 2000 ms to 300 ms, causing errors to rise from 0.2% to 7.1%. Rolling back checkout to v2.13.2 should restore it.';

describe('recommendation keyword highlighting', () => {
  it('marks the action and target, versions and measured values', () => {
    const marked = segments(TEXT, ROLLBACK)
      .filter((s) => s.kind)
      .map((s) => `${s.kind}:${s.text}`);
    expect(marked).toEqual([
      'version:2.14.0',
      'metric:2000 ms',
      'metric:300 ms',
      'metric:0.2%',
      'metric:7.1%',
      'action:Rolling back',
      'action:checkout',
      'version:v2.13.2',
    ]);
  });

  it('keeps the full text and renders no HTML from the model', () => {
    const evil = 'Roll back <img src=x onerror=alert(1)> checkout to 2.13.2';
    const { container } = render(<Highlighted text={evil} action={ROLLBACK} />);
    expect(container.textContent).toBe(evil);
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelectorAll('[data-highlight]')).toHaveLength(3);
  });

  it('works without an action', () => {
    expect(segments('Observe for 15 minutes.', null).filter((s) => s.kind)).toEqual([{ text: '15 minutes', kind: 'metric' }]);
  });
});
