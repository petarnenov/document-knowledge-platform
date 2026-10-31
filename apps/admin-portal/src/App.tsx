import { useState } from 'react';
import { Link, NavLink, Route, Routes } from 'react-router-dom';
import { Identity } from './Identity';
import { Documents } from './Documents';
import { ServerState } from './query';

export function App() {
  return (
    <ServerState>
      <Shell />
    </ServerState>
  );
}

function Shell() {
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');

  return (
    <div className="shell" data-theme={theme}>
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <header className="topbar">
        <Link
          className="brand"
          to="/"
          aria-label="Document Knowledge Platform home"
        >
          <span className="brand-mark" aria-hidden="true">
            D
          </span>
          <span>
            Document Knowledge Platform
            <span className="brand-caption">Administration foundation</span>
          </span>
        </Link>
        <button
          className="theme-toggle"
          type="button"
          aria-label="Light theme"
          aria-pressed={theme === 'light'}
          onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
        >
          <span aria-hidden="true">◐</span>{' '}
          {theme === 'dark' ? 'Light theme' : 'Dark theme'}
        </button>
      </header>
      <div className="workspace">
        <aside className="sidebar">
          <p className="nav-label">Workspace</p>
          <nav aria-label="Main navigation">
            <NavLink to="/" end>
              Overview
            </NavLink>
            <NavLink to="/foundation">Foundation</NavLink>
            <NavLink to="/identity">Workspace access</NavLink>
            <NavLink to="/documents">Documents</NavLink>
          </nav>
          <p className="sidebar-note">
            Built around clear boundaries.
            <br />
            Ready for deliberate next steps.
          </p>
        </aside>
        <main id="main-content" tabIndex={-1}>
          <Routes>
            <Route path="/" element={<Overview />} />
            <Route path="/foundation" element={<Foundation />} />
            <Route path="/identity" element={<Identity />} />
            <Route path="/documents" element={<Documents />} />
            <Route path="*" element={<NotFound />} />
          </Routes>
        </main>
      </div>
      <footer>
        Independent platform · Metadata-only drafts · No content uploads
      </footer>
    </div>
  );
}

function Overview() {
  return (
    <>
      <p className="eyebrow">DKP / OVERVIEW</p>
      <div className="page-heading">
        <h1>A foundation for knowledge.</h1>
        <span className="status">Foundation shell</span>
      </div>
      <p className="lead">
        A clear starting point for secure document and knowledge workflows. The
        engineering foundation is taking shape.
      </p>
      <section className="welcome-card" aria-labelledby="welcome-title">
        <span className="card-kicker">START HERE</span>
        <h2 id="welcome-title">Structure before content.</h2>
        <p>
          This shell establishes navigation and presentation. Metadata-only
          document drafts are available through verified workspace access.
          Collections and search remain planned.
        </p>
        <Link className="primary-link" to="/foundation">
          Explore the foundation <span aria-hidden="true">↗</span>
        </Link>
      </section>
      <section aria-labelledby="boundaries-title">
        <div className="section-heading">
          <h2 id="boundaries-title">Platform boundaries</h2>
          <span>Three independent domains</span>
        </div>
        <div className="domain-grid">
          <DomainCard
            number="01"
            title="Document Management"
            description="Document lifecycle and authoritative access policy."
          />
          <DomainCard
            number="02"
            title="Knowledge Base"
            description="Collections, audiences and membership policy."
          />
          <DomainCard
            number="03"
            title="Knowledge Search"
            description="Evidence-grounded retrieval and derived indexes."
          />
        </div>
      </section>
    </>
  );
}

function DomainCard({
  number,
  title,
  description,
}: {
  number: string;
  title: string;
  description: string;
}) {
  return (
    <article className="domain-card">
      <span className="domain-number">{number}</span>
      <h3>{title}</h3>
      <p>{description}</p>
      <span className="planned">
        {title === 'Document Management'
          ? 'Metadata drafts available'
          : 'Planned capability'}
      </span>
    </article>
  );
}

function Foundation() {
  return (
    <>
      <p className="eyebrow">DKP / FOUNDATION</p>
      <h1>Built to grow with care.</h1>
      <p className="lead">
        This is an engineering foundation, with authorized metadata-only draft
        management. Knowledge workflows remain planned.
      </p>
      <section className="info-card" aria-labelledby="principles-title">
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
      </section>
      <section className="info-card" aria-labelledby="scope-title">
        <h2 id="scope-title">What you can do here</h2>
        <p>
          Navigate the shell, choose a display theme and verify your workspace
          access. Uploading, searching, publishing and purging are not
          available. Draft metadata, bounded access rules and legal holds are
          available in Documents.
        </p>
      </section>
    </>
  );
}

function NotFound() {
  return (
    <>
      <p className="eyebrow">DKP / 404</p>
      <h1>Page not found.</h1>
      <p className="lead">This route is not part of the foundation shell.</p>
      <Link className="primary-link" to="/">
        Return to overview
      </Link>
    </>
  );
}
