import { EventEmitter } from 'node:events';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  spawn: vi.fn(),
  connect: vi.fn(),
  remove: vi.fn(),
  mkdir: vi.fn(),
  executable: vi.fn(),
}));
vi.mock('node:child_process', () => ({ spawn: mocks.spawn }));
vi.mock('node:fs/promises', () => ({ mkdtemp: mocks.mkdir, rm: mocks.remove }));
vi.mock('node:os', () => ({ tmpdir: () => '/tmp' }));
vi.mock('@playwright/test', () => ({
  chromium: { executablePath: mocks.executable, connectOverCDP: mocks.connect },
}));
import {
  nativeArguments,
  loopbackEndpoint,
  nativeBoundaryBrowser,
} from '../e2e-real/native-boundary-browser';

const executable = '/ms-playwright/chromium-1248/chrome-linux64/chrome';
const profile = '/tmp/dkp-native-boundary-abc123';
const url = 'ws://127.0.0.1:12345/devtools/browser/synthetic-owned';
class Child extends EventEmitter {
  pid: number | undefined = 123;
  exitCode: number | null = null;
  signalCode: string | null = null;
  stderr = new EventEmitter();
  signals: string[] = [];
  ignore = false;
  delayClose = false;
  kill(signal: string) {
    this.signals.push(signal);
    if (!this.ignore) {
      this.signalCode = signal;
      this.emit('exit');
      if (!this.delayClose) this.emit('close');
    }
    return true;
  }
}
let child: Child;
let disconnect: ReturnType<typeof vi.fn>;
let contexts: ReturnType<typeof vi.fn>;
beforeEach(() => {
  vi.clearAllMocks();
  child = new Child();
  disconnect = vi.fn().mockResolvedValue(undefined);
  contexts = vi
    .fn()
    .mockReturnValue([
      { pages: () => [{ synthetic: 'empty page unit only' }] },
    ]);
  mocks.mkdir.mockResolvedValue(profile);
  mocks.remove.mockResolvedValue(undefined);
  mocks.executable.mockReturnValue(executable);
  mocks.connect.mockResolvedValue({ contexts, close: disconnect });
  mocks.spawn.mockImplementation(() => {
    queueMicrotask(() =>
      child.stderr.emit('data', Buffer.from(`DevTools listening on ${url}\n`)),
    );
    return child;
  });
});
afterEach(() => vi.useRealTimers());

describe('native browser lifecycle without Playwright focus emulation', () => {
  it('uses only pinned local executable, private profile, fixed loopback CDP and finite IdP mapping', () => {
    expect(nativeArguments(executable, profile, '172.18.0.2')).toEqual([
      '--headless',
      '--no-sandbox',
      '--no-first-run',
      '--no-default-browser-check',
      '--remote-debugging-address=127.0.0.1',
      '--remote-debugging-port=0',
      `--user-data-dir=${profile}`,
      '--host-resolver-rules=MAP idp.localhost 172.18.0.2',
      'about:blank',
    ]);
    for (const args of [
      [executable, '/tmp/dkp-native-boundary-abc123/../foreign', '1.2.3.4'],
      ['/usr/bin/chromium', profile, '1.2.3.4'],
      [executable, profile, '256.1.1.1'],
      [executable, profile, 'host --ignore-certificate-errors'],
      [executable, profile, ''],
    ])
      expect(() =>
        nativeArguments(...(args as [string, string, string])),
      ).toThrow();
  });
  it('accepts only canonical bounded loopback websocket endpoints', () => {
    expect(loopbackEndpoint('nonendpoint startup')).toBeUndefined();
    expect(loopbackEndpoint(`prefix\nDevTools listening on ${url}\n`)).toBe(
      url,
    );
    for (const candidate of [
      'ws://0.0.0.0:123/devtools/browser/a',
      'ws://localhost:123/devtools/browser/a',
      'wss://127.0.0.1:123/devtools/browser/a',
      'ws://127.0.0.1:65536/devtools/browser/a',
      'ws://127.0.0.1:0123/devtools/browser/a',
      'ws://127.0.0.1:123/devtools/browser/a?token=x',
    ])
      expect(() =>
        loopbackEndpoint(`DevTools listening on ${candidate}\n`),
      ).toThrow('Owned loopback');
  });
  it('waits for every byte boundary of LF/CRLF endpoint lines before any attachment', async () => {
    for (const ending of ['\n', '\r\n']) {
      const line = `DevTools listening on ${url}${ending}`;
      for (let split = 0; split < line.length; split++) {
        mocks.spawn.mockReset();
        mocks.connect.mockClear();
        child = new Child();
        mocks.spawn.mockImplementation(() => {
          queueMicrotask(() =>
            child.stderr.emit('data', Buffer.from(line.slice(0, split))),
          );
          return child;
        });
        const starting = nativeBoundaryBrowser('1.2.3.4');
        await Promise.resolve();
        await Promise.resolve();
        expect(mocks.spawn).toHaveBeenCalledOnce();
        expect(mocks.connect).not.toHaveBeenCalled();
        child.stderr.emit('data', Buffer.from(line.slice(split)));
        const native = await starting;
        expect(mocks.connect).toHaveBeenCalledExactlyOnceWith(url, {
          noDefaults: true,
          timeout: 10000,
        });
        await native.close();
      }
    }
  });
  it('never treats an unfinished endpoint as a valid address or invalid completed line', () => {
    for (const line of [
      `DevTools listening on ${url}`,
      'DevTools listening on ws://127.',
      'DevTools listening on ws://0.0.0.0:123/devtools/browser/a',
    ])
      expect(loopbackEndpoint(line)).toBeUndefined();
    expect(loopbackEndpoint(`noise\r\nDevTools listening on ${url}\r\n`)).toBe(
      url,
    );
    expect(() => loopbackEndpoint('DevTools listening on \n')).toThrow(
      'Owned loopback',
    );
  });
  it('rejects malformed completed lines and oversized multi-byte startup without early attachment', async () => {
    for (const chunks of [
      [
        `DevTools listening on ws://127.0.0.1:123/devtools/browser/a`,
        '?unsafe\n',
      ],
      ['é'.repeat(4097)],
    ]) {
      mocks.connect.mockClear();
      child = new Child();
      mocks.spawn.mockImplementation(() => {
        queueMicrotask(() => {
          for (const chunk of chunks)
            child.stderr.emit('data', Buffer.from(chunk));
        });
        return child;
      });
      await expect(nativeBoundaryBrowser('1.2.3.4')).rejects.toThrow();
      expect(mocks.connect).not.toHaveBeenCalled();
      expect(child.signals).toEqual(['SIGTERM']);
    }
  });
  it('incomplete endpoint followed by process exit fails without attaching', async () => {
    mocks.spawn.mockImplementation(() => {
      queueMicrotask(() => {
        child.stderr.emit('data', Buffer.from(`DevTools listening on ${url}`));
        child.exitCode = 1;
        child.emit('exit');
        child.emit('close');
      });
      return child;
    });
    await expect(nativeBoundaryBrowser('1.2.3.4')).rejects.toThrow(
      'exited before',
    );
    expect(mocks.connect).not.toHaveBeenCalled();
    expect(mocks.remove).toHaveBeenCalledOnce();
  });
  it('attaches only fresh default context without overrides and stops only owned PID before profile removal', async () => {
    const native = await nativeBoundaryBrowser('172.18.0.2');
    expect(mocks.connect).toHaveBeenCalledWith(url, {
      noDefaults: true,
      timeout: 10000,
    });
    expect(mocks.spawn).toHaveBeenCalledWith(executable, expect.any(Array), {
      stdio: ['ignore', 'ignore', 'pipe'],
    });
    expect(native.page).toEqual({ synthetic: 'empty page unit only' });
    expect(child.signals).toEqual([]);
    await native.close();
    await native.close();
    expect(child.signals).toEqual(['SIGTERM']);
    expect(disconnect).toHaveBeenCalledOnce();
    expect(mocks.remove).toHaveBeenCalledExactlyOnceWith(profile, {
      recursive: true,
    });
    expect(disconnect.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.remove.mock.invocationCallOrder[0]!,
    );
  });
  it('does not remove the profile while inherited browser stdio remains open after exit', async () => {
    const native = await nativeBoundaryBrowser('1.2.3.4');
    child.delayClose = true;
    const closing = native.close();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(child.signalCode).toBe('SIGTERM');
    expect(mocks.remove).not.toHaveBeenCalled();
    child.emit('close');
    await closing;
    expect(mocks.remove).toHaveBeenCalledOnce();
  });
  it('removes unused private profile on invalid options without spawning any process', async () => {
    await expect(nativeBoundaryBrowser('bad')).rejects.toThrow();
    expect(mocks.spawn).not.toHaveBeenCalled();
    expect(mocks.remove).toHaveBeenCalledOnce();
  });
  it('cleans owned process and profile after CDP attachment failure without adopting another context', async () => {
    mocks.connect.mockRejectedValue(new Error('bounded attach failed'));
    await expect(nativeBoundaryBrowser('1.2.3.4')).rejects.toThrow(
      'bounded attach',
    );
    expect(child.signals).toEqual(['SIGTERM']);
    expect(mocks.remove).toHaveBeenCalledOnce();
  });
  it.each([
    { value: [] },
    { value: [{ pages: () => [] }] },
    { value: [{ pages: () => [1, 2] }] },
    { value: [{ pages: () => [1] }, { pages: () => [2] }] },
  ])('rejects nonfresh context/page topology %#', async ({ value }) => {
    contexts.mockReturnValue(value);
    await expect(nativeBoundaryBrowser('1.2.3.4')).rejects.toThrow(
      'Exactly one fresh',
    );
    expect(disconnect).toHaveBeenCalledOnce();
    expect(child.signals).toEqual(['SIGTERM']);
  });
  it.each(['badendpoint', 'oversize', 'error', 'exit'])(
    'fails bounded startup %s and removes only its owned resources',
    async (kind) => {
      mocks.spawn.mockImplementation(() => {
        queueMicrotask(() => {
          if (kind === 'badendpoint')
            child.stderr.emit(
              'data',
              Buffer.from(
                'DevTools listening on ws://0.0.0.0:123/devtools/browser/a\n',
              ),
            );
          else if (kind === 'oversize')
            child.stderr.emit('data', Buffer.alloc(8193));
          else if (kind === 'error') {
            child.pid = undefined;
            child.emit('error', new Error('private OS error never surfaced'));
          } else {
            child.exitCode = 1;
            child.emit('exit');
            child.emit('close');
          }
        });
        return child;
      });
      await expect(nativeBoundaryBrowser('1.2.3.4')).rejects.toThrow();
      expect(mocks.connect).not.toHaveBeenCalled();
      expect(mocks.remove).toHaveBeenCalledOnce();
      expect(child.stderr.listenerCount('data')).toBe(0);
    },
  );
  it('bounds startup to ten seconds and terminates the exact owned child', async () => {
    vi.useFakeTimers();
    mocks.spawn.mockReturnValue(child);
    const result = nativeBoundaryBrowser('1.2.3.4');
    const rejected = expect(result).rejects.toThrow('startup deadline');
    await vi.advanceTimersByTimeAsync(10000);
    await rejected;
    expect(child.signals).toEqual(['SIGTERM']);
    expect(mocks.remove).toHaveBeenCalledOnce();
  });
  it('escalates only owned PID to SIGKILL when TERM does not settle', async () => {
    const native = await nativeBoundaryBrowser('1.2.3.4');
    vi.useFakeTimers();
    child.ignore = true;
    const original = child.kill.bind(child);
    child.kill = (signal) => {
      if (signal === 'SIGKILL') child.ignore = false;
      return original(signal);
    };
    const closing = native.close();
    await vi.advanceTimersByTimeAsync(3000);
    await closing;
    expect(child.signals).toEqual(['SIGTERM', 'SIGKILL']);
    expect(mocks.remove).toHaveBeenCalledOnce();
  });
  it('retains the private profile if actual process settlement cannot be proven', async () => {
    const native = await nativeBoundaryBrowser('1.2.3.4');
    vi.useFakeTimers();
    child.ignore = true;
    const closing = native.close();
    const rejected = expect(closing).rejects.toThrow('did not settle');
    await vi.advanceTimersByTimeAsync(5000);
    await rejected;
    expect(mocks.remove).not.toHaveBeenCalled();
    expect(disconnect).not.toHaveBeenCalled();
  });
  it('retains the profile when exit occurs but inherited stdio never closes, without signaling an exited PID', async () => {
    const native = await nativeBoundaryBrowser('1.2.3.4');
    child.delayClose = true;
    vi.useFakeTimers();
    const closing = native.close();
    const rejected = expect(closing).rejects.toThrow('did not settle');
    await vi.advanceTimersByTimeAsync(5000);
    await rejected;
    expect(child.signals).toEqual(['SIGTERM']);
    expect(mocks.remove).not.toHaveBeenCalled();
    expect(disconnect).not.toHaveBeenCalled();
  });
  it('surfaces profile removal failure without force, retry or claiming successful closure', async () => {
    const native = await nativeBoundaryBrowser('1.2.3.4');
    const failure = Object.assign(new Error('synthetic removal failure'), {
      code: 'ENOTEMPTY',
    });
    mocks.remove.mockRejectedValueOnce(failure);
    await expect(native.close()).rejects.toBe(failure);
    expect(mocks.remove).toHaveBeenCalledExactlyOnceWith(profile, {
      recursive: true,
    });
  });
  it('bounds CDP disconnect even after child process has already exited', async () => {
    const native = await nativeBoundaryBrowser('1.2.3.4');
    child.exitCode = 0;
    child.emit('close');
    disconnect.mockReturnValue(new Promise(() => {}));
    vi.useFakeTimers();
    const closing = native.close();
    const rejected = expect(closing).rejects.toThrow('disconnect deadline');
    await vi.advanceTimersByTimeAsync(2000);
    await rejected;
    expect(child.signals).toEqual([]);
    expect(mocks.remove).not.toHaveBeenCalled();
  });
});
