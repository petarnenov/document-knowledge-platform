import { Button, Paper, Title } from '@mantine/core';
import { Link } from 'react-router-dom';

const screens: Record<string, { title: string; sections: string[] }> = {
  collections: {
    title: 'Knowledge Collections',
    sections: [
      'Collections',
      'Collection membership',
      'Audience and policy compatibility',
    ],
  },
  reviews: {
    title: 'Review & Publication',
    sections: ['Review candidate', 'Exact-version approval', 'Publication'],
  },
  search: {
    title: 'Knowledge Search',
    sections: ['Search knowledge', 'Published matches', 'Source citations'],
  },
  operations: { title: 'Processing operations', sections: ['Processing runs'] },
  governance: {
    title: 'Governance & Compliance',
    sections: ['Lifecycle constraints', 'Preservation and withdrawal'],
  },
  audit: { title: 'Audit History', sections: ['Audit records'] },
};

/** Navigation remains inspectable without presenting synthetic records or commands. */
export function CapabilityPage({ capability }: { capability: string }) {
  const screen = screens[capability] ?? {
    title: 'Capability unavailable',
    sections: ['Capability'],
  };
  return (
    <>
      <div className="breadcrumbs">
        Workspace / {capability.charAt(0).toUpperCase() + capability.slice(1)}
      </div>
      <Title order={1}>{screen.title}</Title>
      <p className="muted small" role="status">
        This capability is unavailable in the current integration. No records or
        successful operations are simulated.
      </p>
      {screen.sections.map((section) => (
        <Paper className="card" component="section" key={section}>
          <Title order={2}>{section}</Title>
          <p className="muted small">
            Unavailable until its trusted service contract is implemented and
            verified.
          </p>
        </Paper>
      ))}
      <Button component={Link} to="/documents">
        Open documents
      </Button>
    </>
  );
}
