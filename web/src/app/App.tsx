import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, MemoryRouter, Route, Routes } from 'react-router';
import { Toast, Tooltip } from 'radix-ui';
import { ApiError } from '../api/client';
import { Shell } from './Shell';

export function createQueryClient(opts: { retry?: boolean } = {}) {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 5_000,
        refetchOnWindowFocus: false,
        retry:
          opts.retry === false
            ? false
            : (count, err) => !(err instanceof ApiError && err.status >= 400 && err.status < 500) && count < 2,
      },
      mutations: { retry: false },
    },
  });
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Shell />} />
      <Route path="/runs/:runId" element={<Shell />} />
      <Route path="/runs/:runId/audit/:invocationId" element={<Shell />} />
      <Route path="*" element={<Shell />} />
    </Routes>
  );
}

export function App({ queryClient, initialEntries }: { queryClient: QueryClient; initialEntries?: string[] }) {
  const routes = <AppRoutes />;
  return (
    <QueryClientProvider client={queryClient}>
      <Tooltip.Provider delayDuration={250}>
        <Toast.Provider swipeDirection="right" label="Notification">
          {initialEntries ? <MemoryRouter initialEntries={initialEntries}>{routes}</MemoryRouter> : <BrowserRouter>{routes}</BrowserRouter>}
        </Toast.Provider>
      </Tooltip.Provider>
    </QueryClientProvider>
  );
}
