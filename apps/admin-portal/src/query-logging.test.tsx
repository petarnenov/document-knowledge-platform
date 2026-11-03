import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import { attachQueryLogging } from './query-logging';

describe('real query cache logging adapter', () => {
  it('captures query/mutation pairs, ignores payloads and detaches both subscriptions', async () => {
    const client = new QueryClient({
      defaultOptions: {
        queries: { retry: false, gcTime: 0 },
        mutations: { retry: false, gcTime: 0 },
      },
    });
    const finish = vi.fn(),
      logging = { begin: vi.fn(() => finish), record: vi.fn() };
    const detach = attachQueryLogging(client, logging);
    await client.fetchQuery({
      queryKey: ['private-key'],
      queryFn: async () => ({ secret: 'private-value' }),
    });
    await expect(
      client.fetchQuery({
        queryKey: ['private-error'],
        queryFn: async () => {
          throw new Error('private-message');
        },
      }),
    ).rejects.toThrow();
    const success = client
      .getMutationCache()
      .build(client, { mutationFn: async (value: string) => value });
    expect(await success.execute('private-variable')).toBe('private-variable');
    const failure = client.getMutationCache().build(client, {
      mutationFn: async () => {
        throw new Error('private-mutation');
      },
    });
    await expect(failure.execute(undefined)).rejects.toThrow();
    expect(logging.begin.mock.calls).toEqual([
      ['QUERY'],
      ['QUERY'],
      ['MUTATION'],
      ['MUTATION'],
    ]);
    expect(finish.mock.calls).toEqual([
      ['SUCCEEDED'],
      ['FAILED'],
      ['SUCCEEDED'],
      ['FAILED'],
    ]);
    client.setQueryData(['private-cached'], 'private-value');
    expect(logging.record).toHaveBeenCalledWith(
      'QUERY',
      'operation.succeeded',
      'DEBUG',
      'SUCCEEDED',
    );
    client.invalidateQueries({ queryKey: ['private-cached'] });
    expect(
      JSON.stringify([
        logging.begin.mock.calls,
        logging.record.mock.calls,
        finish.mock.calls,
      ]),
    ).not.toMatch(/private/);
    detach();
    const before = logging.begin.mock.calls.length;
    await client.fetchQuery({
      queryKey: ['after-detach'],
      queryFn: async () => true,
    });
    expect(logging.begin).toHaveBeenCalledTimes(before);
    client.clear();
  });

  it('reports a terminal error when attachment happens after an operation started', async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
    let reject: (error: Error) => void = () => {};
    const pending = client.fetchQuery({
      queryKey: ['private'],
      queryFn: () =>
        new Promise((_, fail) => {
          reject = fail;
        }),
    });
    const logging = { begin: vi.fn(() => vi.fn()), record: vi.fn() };
    const detach = attachQueryLogging(client, logging);
    reject(new Error('private'));
    await expect(pending).rejects.toThrow();
    expect(logging.begin).not.toHaveBeenCalled();
    expect(logging.record).toHaveBeenCalledWith(
      'QUERY',
      'operation.failed',
      'ERROR',
      'FAILED',
    );
    detach();
    client.clear();
  });
});
