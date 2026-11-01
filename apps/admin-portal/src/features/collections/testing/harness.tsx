/** Browser-only test entry. Never imported by the production application. */
import { createRoot } from 'react-dom/client';
import { MantineProvider, createTheme } from '@mantine/core';
import '@mantine/core/styles.css';
import './harness.css';
import { CollectionsWorkspace } from '../CollectionsWorkspace';
import type { CollectionsView } from '../collection-view';

const params = new URLSearchParams(location.search);
const scheme = params.get('theme') === 'light' ? 'light' : 'dark';
const mode = params.get('state');
const state: CollectionsView =
  mode === 'available'
    ? {
        kind: 'available',
        data: [
          {
            collectionId: 'synthetic-a',
            title: 'Corporate Policies',
            description: '',
            status: 'ACTIVE',
            classificationCeiling: 'development-restricted',
            classificationPolicyRevision: 1,
            memberships: {
              kind: 'available',
              data: [
                {
                  membershipId: 'synthetic-member',
                  documentId: '11111111-1111-4111-8111-111111111111',
                  state: 'ACTIVE',
                },
              ],
            },
            audience: {
              kind: 'available',
              data: {
                revision: 1,
                rules: [
                  { effect: 'ALLOW', selectorLabel: 'Synthetic subject' },
                ],
              },
            },
          },
          {
            collectionId: 'synthetic-b',
            title: 'Engineering',
            description: '',
            status: 'ARCHIVED',
            classificationCeiling: 'development-restricted',
            classificationPolicyRevision: 1,
            memberships: { kind: 'available', data: [] },
            audience: { kind: 'unknown' },
          },
        ],
      }
    : {
        kind:
          mode === 'unknown' || mode === 'denied' || mode === 'conflict'
            ? mode
            : 'unavailable',
      };

createRoot(document.getElementById('root')!).render(
  <MantineProvider
    forceColorScheme={scheme}
    theme={createTheme({
      fontFamily:
        'Inter, system-ui, -apple-system, Segoe UI, Arial, sans-serif',
    })}
  >
    <p className="fixture-notice">
      Test-only presentation fixtures. No live backend or authorization
      evidence.
    </p>
    <main className="visual-stage">
      <CollectionsWorkspace state={state} />
    </main>
  </MantineProvider>,
);
