import { lstatSync, readFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve } from 'node:path';

const keys = ['schemaVersion', 'profile', 'project', 'runId', 'securityDirectory',
  'runtimeEnvironmentFile', 'portalId', 'keycloakId', 'networkName', 'networkId',
  'portalImageId', 'keycloakImageId'].sort();
const id = /^[a-f0-9]{64}$/;
const image = /^sha256:[a-f0-9]{64}$/;
const reject = () => { throw new Error('Owned KB browser runtime verification failed'); };

/** This record identifies an already verified owned runtime; it grants no actor authority. */
export function loadKbBrowserState(file, { root, securityDirectory, runtimeEnvironmentFile }) {
  if (!file || !securityDirectory || !runtimeEnvironmentFile || !isAbsolute(file)) reject();
  const parent = dirname(file);
  const allowed = resolve(root, 'local-data/security');
  const child = relative(allowed, parent);
  if (!child || child.startsWith('..') || isAbsolute(child) || file !== resolve(parent, 'browser-state.json')) reject();
  for (let path = file; ; path = dirname(path)) {
    const info = lstatSync(path);
    if (info.isSymbolicLink() || info.uid !== process.getuid()
        || (path === file ? !info.isFile() || info.size > 8192 || (info.mode & 0o077) !== 0
          : !info.isDirectory() || (info.mode & 0o022) !== 0)) reject();
    if (path === resolve(root)) break;
  }
  const state = JSON.parse(readFileSync(file, 'utf8'));
  if (!state || Array.isArray(state) || Object.keys(state).sort().join(',') !== keys.join(',')
      || state.schemaVersion !== 1 || state.profile !== 'dkp-bp04'
      || !/^dkp-bp04-[a-f0-9]{12}$/.test(state.project)
      || !/^[a-f0-9]{32}$/.test(state.runId)
      || state.securityDirectory !== resolve(parent, 'security/identity')
      || state.runtimeEnvironmentFile !== resolve(parent, 'security/runtime.env')
      || securityDirectory !== state.securityDirectory || runtimeEnvironmentFile !== state.runtimeEnvironmentFile
      || !id.test(state.portalId) || !id.test(state.keycloakId) || state.portalId === state.keycloakId
      || !id.test(state.networkId) || state.networkName !== `${state.project}_default`
      || !image.test(state.portalImageId) || !image.test(state.keycloakImageId)) reject();
  for (const protectedFile of [runtimeEnvironmentFile, resolve(securityDirectory, 'runtime.env'), resolve(securityDirectory, 'ca.crt')]) {
    for (let path = protectedFile; path !== parent; path = dirname(path)) {
      const info = lstatSync(path);
      if (info.isSymbolicLink() || info.uid !== process.getuid() || (info.mode & 0o077) !== 0
          || (path === protectedFile ? !info.isFile() : !info.isDirectory())) reject();
    }
  }
  return state;
}

export function validateKbBrowserContainers(state, portal, provider, network) {
  for (const [value, service, containerId, imageId] of [
    [portal, 'admin-portal', state.portalId, state.portalImageId],
    [provider, 'keycloak', state.keycloakId, state.keycloakImageId],
  ]) {
    const labels = value?.Config?.Labels;
    if (value?.Id !== containerId || value.Image !== imageId || value.State?.Running !== true
        || value.State?.Health?.Status !== 'healthy'
        || labels?.['com.docker.compose.project'] !== state.project
        || labels?.['com.docker.compose.service'] !== service
        || labels?.['dkp.run-id'] !== state.runId || labels?.['dkp.profile'] !== state.profile
        || value.NetworkSettings?.Networks?.[state.networkName]?.NetworkID !== state.networkId) reject();
  }
  if (Object.keys(portal.NetworkSettings.Networks).length !== 1
      || network?.Id !== state.networkId || network.Name !== state.networkName
      || network.Labels?.['com.docker.compose.project'] !== state.project
      || network.Labels?.['dkp.run-id'] !== state.runId
      || network.Labels?.['dkp.profile'] !== state.profile) reject();
  return provider.NetworkSettings.Networks[state.networkName].IPAddress;
}
