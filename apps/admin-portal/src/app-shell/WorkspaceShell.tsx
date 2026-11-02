import { useEffect, useRef, type ReactNode } from 'react';
import {
  AppShell,
  Badge,
  Box,
  Button,
  NavLink,
  Text,
  useMantineColorScheme,
} from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation } from 'react-router-dom';
import { getSession, IdentityRequestError } from '../identity-api';
import { sessionKey } from '../session-key';
import { useIdentityCommands } from '../features/identity/useIdentityCommands';
import { useSessionAuthority } from '../features/identity/authority';

const navigation = [
  { to: '/', label: 'Home', icon: '⌂' },
  { to: '/documents', label: 'Documents', icon: '▤' },
  { to: '/collections', label: 'Collections', icon: '▧' },
  { to: '/reviews', label: 'Reviews', icon: '✓' },
  { to: '/search', label: 'Search', icon: '⌕' },
  { to: '/operations', label: 'Operations', icon: '◷' },
  { to: '/governance', label: 'Governance', icon: '♢' },
  { to: '/audit', label: 'Audit', icon: '◴' },
];
const secondary = [
  { to: '/identity', label: 'Workspace access', icon: '⚿' },
  { to: '/foundation', label: 'Foundation', icon: 'ⓘ' },
];

export function WorkspaceShell({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const previousScope = useRef<{
    identity: string;
    csrfToken: string | undefined;
  } | null>(null);
  const location = useLocation();
  const { colorScheme, setColorScheme } = useMantineColorScheme();
  const session = useQuery({
    queryKey: sessionKey,
    queryFn: ({ signal }) => getSession(signal),
  });
  const authenticated = session.data && !session.error ? session.data : null;
  const commands = useIdentityCommands();
  const authority = useSessionAuthority(authenticated);
  const issuer = authenticated?.issuer;
  const subject = authenticated?.subject;
  const tenant = authenticated?.selectedTenant;
  const csrfToken = authenticated?.csrfToken;
  useEffect(() => {
    const identity = JSON.stringify([issuer, subject, tenant]);
    const rotated =
      previousScope.current?.identity === identity &&
      previousScope.current.csrfToken !== csrfToken;
    previousScope.current = { identity, csrfToken };
    const obsolete = (query: { queryKey: readonly unknown[] }) => {
      const key = query.queryKey;
      if (key[0] === 'session') return false;
      if (!issuer || !subject || !tenant || !csrfToken) return true;
      if (key[0] === 'documents')
        return (
          key[1] !== issuer ||
          key[2] !== subject ||
          key[3] !== tenant ||
          key[4] !== csrfToken
        );
      if (key[0] === 'context')
        // Legacy context keys have no session token; discard them on session rotation.
        return (
          (key.length >= 6 ? key[4] !== csrfToken : rotated) ||
          key[1] !== issuer ||
          key[2] !== subject ||
          key[3] !== tenant
        );
      // Unknown protected key shapes cannot establish an authenticated scope.
      return true;
    };
    void client.cancelQueries({ predicate: obsolete });
    client.removeQueries({ predicate: obsolete });
  }, [client, issuer, subject, tenant, csrfToken]);
  const workspace = authenticated
    ? (authenticated.selectedTenant ?? 'No workspace selected')
    : session.isPending
      ? 'Checking workspace…'
      : session.error instanceof IdentityRequestError &&
          session.error.status === 401
        ? 'Sign in to select a workspace'
        : session.data === null
          ? 'Signed out'
          : 'Workspace unavailable';

  function navigationLink({ to, label, icon }: (typeof navigation)[number]) {
    const active =
      location.pathname === to ||
      (to !== '/' && location.pathname.startsWith(to + '/'));
    return (
      <NavLink
        key={to}
        component={Link}
        to={to}
        label={<span className="nav-text">{label}</span>}
        leftSection={
          <span className="nav-icon" aria-hidden="true">
            {icon}
          </span>
        }
        aria-label={label}
        title={label}
        active={active}
        aria-current={active ? 'page' : undefined}
        onClick={() => document.getElementById('main-content')?.focus()}
      />
    );
  }
  return (
    <AppShell className="shell app" padding={0}>
      <a
        className="skip-link"
        href="#main-content"
        onClick={() => document.getElementById('main-content')?.focus()}
      >
        Skip to content
      </a>
      <Box component="header" className="topbar">
        <div>
          <Link
            className="brand"
            to="/"
            aria-label="Document Knowledge Platform home"
          >
            ▦ Document Knowledge
          </Link>
          <div className="sub">Knowledge administration</div>
        </div>
        <div className="header-right">
          <Badge className="badge" variant="outline">
            {authenticated ? 'Authenticated' : 'Workspace access'}
          </Badge>
          <Button
            className="theme-toggle"
            role="switch"
            aria-label="Light theme"
            aria-checked={colorScheme === 'light'}
            onClick={() =>
              setColorScheme(colorScheme === 'light' ? 'dark' : 'light')
            }
          >
            <span aria-hidden="true">
              {colorScheme === 'light' ? '☀' : '☾'}
            </span>{' '}
            {colorScheme === 'light' ? 'Light' : 'Dark'}
          </Button>
        </div>
      </Box>
      <div
        className="workspace workspace-context-bar"
        aria-label="Current workspace"
      >
        <div>
          <Text component="span" className="workspace-field-label">
            Workspace (verified membership)
          </Text>
          <Box
            component="select"
            className="workspace-field"
            aria-label="Workspace (verified membership)"
            value={authenticated?.selectedTenant ?? ''}
            disabled={!authenticated?.tenants.length || commands.busy}
            aria-busy={commands.busy}
            onChange={(event) => {
              const tenant = event.currentTarget.value;
              if (
                authenticated &&
                tenant !== authenticated.selectedTenant &&
                authenticated.tenants.includes(tenant)
              )
                commands.execute(authenticated, { kind: 'tenant', tenant });
            }}
          >
            {!authenticated?.selectedTenant && (
              <option value="" disabled>
                {workspace}
              </option>
            )}
            {authenticated?.tenants.map((tenant) => (
              <option key={tenant} value={tenant}>
                {tenant}
              </option>
            ))}
          </Box>
        </div>
        <div>
          <Text component="span" className="workspace-field-label">
            Authenticated account context
          </Text>
          <Box
            component="select"
            className="workspace-field"
            disabled
            aria-label="Authenticated account context"
            value={authenticated?.displayName ?? 'Access not verified'}
          >
            <option value={authenticated?.displayName ?? 'Access not verified'}>
              {authenticated?.displayName ?? 'Access not verified'}
            </option>
          </Box>
        </div>
      </div>
      {commands.error && !authority.uncertain && (
        <p role="alert">
          {commands.error.status === 401
            ? 'Your session has expired. Sign in again to continue.'
            : 'Workspace selection was denied. Your workspace has not changed.'}
        </p>
      )}
      <AppShell.Main component="div" className="workspace-layout layout">
        <Box component="aside" className="sidebar">
          <nav aria-label="Main navigation">
            {navigation.map(navigationLink)}
          </nav>
          <div className="sidebar-spacer" />
          <nav className="secondary-navigation" aria-label="Workspace support">
            {secondary.map(navigationLink)}
          </nav>
        </Box>
        <main id="main-content" tabIndex={-1}>
          {authority.blocked &&
            (authority.uncertain ? (
              <section role="alert">
                <h1>Verify your workspace access</h1>
                <p>
                  The workspace change could not be confirmed. Protected content
                  remains unavailable until your session is verified. You may
                  need to sign in again.
                </p>
                <Button onClick={() => void authority.recover()}>
                  Retry session verification
                </Button>{' '}
                <a href="/oauth/login/keycloak">Sign in again</a>
              </section>
            ) : (
              <p role="status">Updating workspace access…</p>
            ))}
          <div
            style={{ display: authority.blocked ? 'none' : 'contents' }}
            aria-hidden={authority.blocked || undefined}
          >
            {children}
          </div>
        </main>
      </AppShell.Main>
      <footer className="foot">
        <span>Document Knowledge Platform</span>
        <span>Drafts and verified uploads · Publication unavailable</span>
      </footer>
    </AppShell>
  );
}
