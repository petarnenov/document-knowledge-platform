import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join } from 'node:path';
import { chromium, type Browser } from '@playwright/test';

export function nativeArguments(
  executable: string,
  profile: string,
  address: string,
) {
  if (
    !/^\/ms-playwright\/chromium-[0-9]+\/chrome-linux64\/chrome$/.test(
      executable,
    ) ||
    dirname(profile) !== tmpdir() ||
    !/^dkp-native-boundary-[a-zA-Z0-9]{6}$/.test(basename(profile)) ||
    !/^\d{1,3}(\.\d{1,3}){3}$/.test(address) ||
    address.split('.').some((part) => Number(part) > 255)
  )
    throw new Error(
      'Pinned Linux browser, private profile and fixed IdP IPv4 required',
    );
  return [
    '--headless',
    '--no-sandbox',
    '--no-first-run',
    '--no-default-browser-check',
    '--remote-debugging-address=127.0.0.1',
    '--remote-debugging-port=0',
    `--user-data-dir=${profile}`,
    `--host-resolver-rules=MAP idp.localhost ${address}`,
    'about:blank',
  ];
}

export function loopbackEndpoint(buffer: string): string | undefined {
  // A stderr chunk is not a protocol record: never attach or reject a partial line.
  const lines = buffer.split('\n');
  lines.pop();
  for (const complete of lines) {
    const line = complete.endsWith('\r') ? complete.slice(0, -1) : complete;
    const match = /^DevTools listening on (.*)$/.exec(line);
    if (!match) continue;
    const endpoint = match[1]!;
    const canonical =
      /^ws:\/\/127\.0\.0\.1:([1-9][0-9]{0,4})\/devtools\/browser\/[a-zA-Z0-9-]+$/.exec(
        endpoint,
      );
    if (!canonical || Number(canonical[1]) > 65535)
      throw new Error('Owned loopback CDP endpoint required');
    return endpoint;
  }
}

function endpoint(child: ChildProcess): Promise<string> {
  return new Promise((resolve, reject) => {
    let output = '';
    let outputBytes = 0;
    const finish = (error?: Error, value?: string) => {
      clearTimeout(timer);
      child.stderr!.off('data', data);
      child.off('error', failed);
      child.off('exit', exited);
      if (error) reject(error);
      else resolve(value!);
    };
    const failed = () => finish(new Error('Native browser launch failed'));
    const exited = () =>
      finish(new Error('Native browser exited before attachment'));
    const data = (chunk: Buffer) => {
      outputBytes += chunk.byteLength;
      output += chunk.toString('utf8');
      if (outputBytes > 8192) {
        finish(new Error('Bounded native browser startup output exceeded'));
        return;
      }
      try {
        const value = loopbackEndpoint(output);
        if (value) finish(undefined, value);
      } catch {
        finish(new Error('Owned loopback CDP endpoint required'));
      }
    };
    const timer = setTimeout(
      () => finish(new Error('Native browser startup deadline exceeded')),
      10_000,
    );
    child.stderr!.on('data', data);
    child.once('error', failed);
    child.once('exit', exited);
  });
}

async function stop(child: ChildProcess) {
  if (child.pid === undefined) return;
  for (const [signal, timeout] of [
    ['SIGTERM', 3000],
    ['SIGKILL', 2000],
  ] as const) {
    if (child.exitCode !== null || child.signalCode !== null) return;
    const exited = new Promise<boolean>((resolve) => {
      const done = () => {
        clearTimeout(timer);
        resolve(true);
      };
      const timer = setTimeout(() => {
        child.off('exit', done);
        resolve(false);
      }, timeout);
      child.once('exit', done);
    });
    child.kill(signal);
    if (await exited) return;
  }
  throw new Error('Owned native browser process did not settle');
}

/** No defaults restores native tab visibility; it never dispatches fabricated visibility or query events.
 * This context starts empty and performs its own real OIDC flow. Trust is inherited from the pinned runner.
 */
export async function nativeBoundaryBrowser(address: string) {
  const profile = await mkdtemp(join(tmpdir(), 'dkp-native-boundary-'));
  let child: ChildProcess | undefined, browser: Browser | undefined;
  let closed = false;
  const close = async () => {
    if (closed) return;
    // Stop the actual owned process first, including on attach/test failures. Never kill by executable/name.
    if (child) await stop(child);
    if (browser) {
      let timer: ReturnType<typeof setTimeout> | undefined;
      try {
        await Promise.race([
          browser.close(),
          new Promise<never>((_, reject) => {
            timer = setTimeout(
              () =>
                reject(new Error('Native CDP disconnect deadline exceeded')),
              2000,
            );
          }),
        ]);
      } finally {
        clearTimeout(timer);
      }
    }
    await rm(profile, { recursive: true });
    closed = true;
  };
  try {
    const executable = chromium.executablePath();
    const args = nativeArguments(executable, profile, address);
    child = spawn(executable, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    const url = await endpoint(child);
    browser = await chromium.connectOverCDP(url, {
      noDefaults: true,
      timeout: 10_000,
    });
    const contexts = browser.contexts();
    if (contexts.length !== 1 || contexts[0]!.pages().length !== 1)
      throw new Error('Exactly one fresh native default context/page required');
    return { context: contexts[0]!, page: contexts[0]!.pages()[0]!, close };
  } catch (error) {
    await close();
    throw error;
  }
}
