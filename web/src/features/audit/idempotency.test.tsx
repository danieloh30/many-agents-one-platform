import { render, screen } from '@testing-library/react';
import { invocations } from '../../test/fixtures';
import { IdempotencyCounts } from './InvocationDetail';

const base = invocations.items[0]!;

describe('idempotency counts', () => {
  it('highlights a replayed call: requested twice, executed once', () => {
    render(<IdempotencyCounts inv={{ ...base, requests: 2, executions: 1 }} />);
    expect(screen.getByTestId('count-requests')).toHaveTextContent('Requests2');
    expect(screen.getByTestId('count-requests')).toHaveClass('text-warn');
    expect(screen.getByTestId('count-executions')).toHaveTextContent('Executions1');
    expect(screen.getByTestId('count-executions')).toHaveClass('text-teal');
    expect(screen.getByTestId('replay-callout')).toHaveTextContent('Requested 2 times, executed once.');
  });

  it('shows plain counts and no callout for a single request', () => {
    render(<IdempotencyCounts inv={{ ...base, requests: 1, executions: 1 }} />);
    expect(screen.getByTestId('count-requests')).not.toHaveClass('text-warn');
    expect(screen.queryByTestId('replay-callout')).toBeNull();
  });

  it('says Unavailable rather than zero when a count is unknown', () => {
    render(<IdempotencyCounts inv={{ ...base, requests: null, executions: null }} />);
    expect(screen.getByTestId('count-requests')).toHaveTextContent('RequestsUnavailable');
  });
});
