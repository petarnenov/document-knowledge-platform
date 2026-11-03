import { afterEach, describe, expect, it, vi } from 'vitest';
import { portalFetch } from './portal-http';
import { portalLogging } from './portal-logging';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
describe('explicit portal HTTP observation', () => {
  it.each([200, 503])(
    'preserves the exact %s response and body without copying sensitive inputs',
    async (status) => {
      const finish = vi.fn(),
        begin = vi.spyOn(portalLogging, 'begin').mockReturnValue(finish);
      const response = new Response('private response', { status });
      const fetcher = vi.fn().mockResolvedValue(response);
      vi.stubGlobal('fetch', fetcher);
      const init = {
        headers: { 'X-CSRF-TOKEN': 'private-secret' },
        body: 'private upload',
        method: 'POST',
      };
      expect(await portalFetch('/api/documents?private=true', init)).toBe(
        response,
      );
      expect(fetcher).toHaveBeenCalledWith('/api/documents?private=true', init);
      expect(response.bodyUsed).toBe(false);
      expect(begin).toHaveBeenCalledWith('HTTP');
      expect(finish).toHaveBeenCalledWith(
        status === 200 ? 'SUCCEEDED' : 'FAILED',
      );
      expect(JSON.stringify([begin.mock.calls, finish.mock.calls])).not.toMatch(
        /private/,
      );
    },
  );
  it.each([false, true])(
    'preserves thrown failures and distinguishes cancellation %s',
    async (aborted) => {
      const finish = vi.fn();
      vi.spyOn(portalLogging, 'begin').mockReturnValue(finish);
      const failure = new Error('private failure');
      vi.stubGlobal('fetch', vi.fn().mockRejectedValue(failure));
      const controller = new AbortController();
      if (aborted) controller.abort();
      await expect(
        portalFetch('/api', { signal: controller.signal }),
      ).rejects.toBe(failure);
      expect(finish).toHaveBeenCalledWith(aborted ? 'CANCELLED' : 'FAILED');
    },
  );
  it('leaves unrelated UI work runnable while the real business fetch is pending', async () => {
    const finish = vi.fn();
    vi.spyOn(portalLogging, 'begin').mockReturnValue(finish);
    let complete: (response: Response) => void = () => {};
    vi.stubGlobal(
      'fetch',
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            complete = resolve;
          }),
      ),
    );
    const pending = portalFetch('/api');
    let action = 0;
    action++;
    expect(action).toBe(1);
    expect(finish).not.toHaveBeenCalled();
    const response = new Response();
    complete(response);
    expect(await pending).toBe(response);
  });
});
