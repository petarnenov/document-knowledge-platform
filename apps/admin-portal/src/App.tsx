import { Paper, Title } from '@mantine/core';
import { PortalTheme } from './theme';
import { Link, Route, Routes } from 'react-router-dom';
import { Identity } from './Identity';
import { Documents } from './Documents';
import { ServerState } from './query';
import { HomeWorkspace } from './features/home/HomeWorkspace';
import { CapabilityPage } from './features/home/CapabilityPage';
import { WorkspaceShell } from './app-shell/WorkspaceShell';

export function App() {
  return (
    <PortalTheme>
      <ServerState>
        <Shell />
      </ServerState>
    </PortalTheme>
  );
}

function Shell() {
  return (
    <WorkspaceShell>
      <Routes>
        <Route path="/" element={<Overview />} />
        <Route path="/foundation" element={<Foundation />} />
        <Route path="/identity" element={<Identity />} />
        <Route path="/documents" element={<Documents />} />
        {[
          'collections',
          'reviews',
          'search',
          'operations',
          'governance',
          'audit',
        ].map((capability) => (
          <Route
            key={capability}
            path={`/${capability}`}
            element={<CapabilityPage capability={capability} />}
          />
        ))}
        <Route path="*" element={<NotFound />} />
      </Routes>
    </WorkspaceShell>
  );
}

function Overview() {
  return <HomeWorkspace />;
}

function Foundation() {
  return (
    <>
      <p className="eyebrow">DKP / FOUNDATION</p>
      <Title order={1}>Built to grow with care.</Title>
      <p className="lead">
        This is an engineering foundation, with authorized metadata-only draft
        management. Knowledge workflows remain planned.
      </p>
      <Paper
        component="section"
        className="info-card"
        aria-labelledby="principles-title"
      >
        <h2 id="principles-title">Guiding principles</h2>
        <ul className="principles">
          <li>Independent domain ownership and releases.</li>
          <li>Inward dependencies with clear ports and adapters.</li>
          <li>Versioned contracts and repeatable quality checks.</li>
          <li>
            Identity and tenant context independently verified by backend
            services.
          </li>
        </ul>
      </Paper>
      <Paper
        component="section"
        className="info-card"
        aria-labelledby="scope-title"
      >
        <h2 id="scope-title">What you can do here</h2>
        <p>
          Navigate the shell, choose a display theme and verify your workspace
          access. Verified uploads are available for authorized documents.
          Searching, publishing and purging are not available. Draft metadata,
          bounded access rules and legal holds are available in Documents.
        </p>
      </Paper>
    </>
  );
}

function NotFound() {
  return (
    <>
      <p className="eyebrow">DKP / 404</p>
      <Title order={1}>Page not found.</Title>
      <p className="lead">This route is not part of the foundation shell.</p>
      <Link className="primary-link" to="/">
        Return to overview
      </Link>
    </>
  );
}
