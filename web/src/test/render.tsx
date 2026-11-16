import { render } from '@testing-library/react';
import { vi } from 'vitest';
import { useLiveStore } from '../api/liveStore';
import { App, createQueryClient } from '../app/App';
import { useNotify } from '../app/notify';
import { useSession } from '../app/session';
import { useUi } from '../app/uiStore';
import { FakeEventSource } from './fakes';

const initialUi = useUi.getState();

export function resetStores({ token }: { token?: string } = {}) {
  try {
    sessionStorage.clear();
    localStorage.clear();
  } catch {
    /* ignore */
  }
  useLiveStore.setState({ status: 'connecting', events: [], history: [] });
  useUi.setState({ ...initialUi, themeChoice: null, presenter: false, view: 'topology', inspectorTab: 'summary', lastRunByScenario: {}, focusNode: null });
  useNotify.setState({ toasts: [], announcement: null });
  if (token) {
    sessionStorage.setItem('maop.presenterToken', token);
    useSession.setState({ token, state: 'checking' });
  } else {
    useSession.setState({ token: null, state: 'none' });
  }
  FakeEventSource.reset();
  vi.stubGlobal('EventSource', FakeEventSource);
}

export function renderApp(path = '/', queryClient = createQueryClient({ retry: false })) {
  const utils = render(<App queryClient={queryClient} initialEntries={[path]} />);
  return { ...utils, queryClient };
}
