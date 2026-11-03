import { useEffect, useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { attachQueryLogging } from './query-logging';

export function ServerState({ children }: { children: ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { retry: false, refetchOnWindowFocus: true, staleTime: 0 },
          mutations: { retry: false },
        },
      }),
  );
  useEffect(() => attachQueryLogging(client), [client]);
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
