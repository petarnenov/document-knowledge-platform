import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { PortalTheme } from '../../theme';
import { CollectionsWorkspace } from './CollectionsWorkspace';
import type { CollectionView, CollectionsView } from './collection-view';

const first: CollectionView = {
  collectionId: 'collection-a',
  title: 'Synthetic collection A',
  description: 'Authorized metadata',
  status: 'ACTIVE',
  classificationCeiling: 'development-restricted',
  classificationPolicyRevision: 1,
  memberships: {
    kind: 'available',
    data: [
      {
        membershipId: 'membership-a',
        documentId: 'document-a',
        state: 'ACTIVE',
      },
    ],
  },
  audience: {
    kind: 'available',
    data: {
      revision: 2,
      rules: [{ effect: 'ALLOW', selectorLabel: 'Synthetic verified subject' }],
    },
  },
};
const second: CollectionView = {
  ...first,
  collectionId: 'collection-b',
  title: 'Synthetic collection B',
  description: '',
  status: 'ARCHIVED',
  classificationCeiling: null,
  classificationPolicyRevision: null,
  memberships: { kind: 'available', data: [] },
  audience: { kind: 'available', data: { revision: 3, rules: [] } },
};
const ready: CollectionsView = { kind: 'available', data: [first, second] };
function workspace(state?: CollectionsView) {
  return (
    <PortalTheme>
      <CollectionsWorkspace {...(state ? { state } : {})} />
    </PortalTheme>
  );
}
afterEach(() => vi.unstubAllGlobals());

it('preserves collection, membership, create and audience structure without fabricating live data or making requests', async () => {
  const fetcher = vi.fn();
  vi.stubGlobal('fetch', fetcher);
  render(workspace());
  expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(
    'Knowledge Collections',
  );
  expect(
    screen.getByRole('region', { name: 'Accessible collections' }),
  ).toBeTruthy();
  expect(
    screen.getByRole('region', { name: 'Collection details' }),
  ).toBeTruthy();
  expect(
    screen.getByRole('region', { name: 'Collection memberships' }),
  ).toBeTruthy();
  expect(
    screen.getByRole('region', { name: 'Audience and policy' }),
  ).toBeTruthy();
  expect(
    (screen.getByLabelText('Add existing document') as HTMLSelectElement)
      .disabled,
  ).toBe(true);
  expect(
    (screen.getByLabelText('Collection name') as HTMLInputElement).disabled,
  ).toBe(true);
  for (const button of screen.getAllByRole('button')) {
    expect((button as HTMLButtonElement).disabled).toBe(true);
    await userEvent.click(button);
  }
  expect(screen.queryByRole('link')).toBeNull();
  expect(screen.queryByText(/No accessible collections/)).toBeNull();
  expect(
    screen.getByText('Content access').nextElementSibling?.textContent,
  ).toBe('Not evaluated');
  expect(fetcher).not.toHaveBeenCalled();
});

it('selects only supplied authorized records by keyboard and never treats audience ALLOW or membership as content authority', async () => {
  render(workspace(ready));
  const user = userEvent.setup();
  const details = screen.getByRole('region', { name: 'Collection details' });
  expect(
    within(details).getByRole('heading', { name: first.title }),
  ).toBeTruthy();
  expect(within(details).getByText('Document document-a')).toBeTruthy();
  expect(screen.getByText('ALLOW · Synthetic verified subject')).toBeTruthy();
  expect(
    screen.getByText('Content access').nextElementSibling?.textContent,
  ).toBe('Not evaluated');
  expect(
    screen.getByText(
      /Collection membership does not grant document content access/,
    ),
  ).toBeTruthy();
  expect(screen.queryByRole('link')).toBeNull();
  expect(
    screen.queryByRole('button', { name: /Download|Open content|Publish/ }),
  ).toBeNull();
  expect(
    (screen.getByRole('button', { name: 'Remove' }) as HTMLButtonElement)
      .disabled,
  ).toBe(true);
  const card = screen.getByRole('button', {
    name: `Select collection ${second.title}`,
  });
  card.focus();
  await user.keyboard('{Enter}');
  expect(card.getAttribute('aria-pressed')).toBe('true');
  expect(
    within(details).getByRole('heading', { name: second.title }),
  ).toBeTruthy();
  expect(within(details).queryByText('Document document-a')).toBeNull();
  expect(
    screen.getByText('No accessible memberships on this page.'),
  ).toBeTruthy();
  expect(screen.getByText('No audience rules returned.')).toBeTruthy();
  expect(
    screen.getByText('Collection status').nextElementSibling?.textContent,
  ).toBe('ARCHIVED');
  expect(
    screen.getByText('Classification ceiling').nextElementSibling?.textContent,
  ).toBe('Unknown');
  expect(
    screen.getByText('Classification policy revision').nextElementSibling
      ?.textContent,
  ).toBe('Unknown');
  expect(screen.queryByText(/1 linked/)).toBeNull();
});

it.each(['unavailable', 'unknown', 'denied', 'conflict'] as const)(
  'removes every protected field on a %s boundary rather than rendering stale rows',
  (kind) => {
    const view = render(workspace(ready));
    expect(screen.getByText('Document document-a')).toBeTruthy();
    view.rerender(workspace({ kind }));
    expect(screen.queryByText(first.title)).toBeNull();
    expect(screen.queryByText('Document document-a')).toBeNull();
    expect(screen.queryByText('Authorized metadata')).toBeNull();
    expect(screen.queryByText('ALLOW · Synthetic verified subject')).toBeNull();
    const expected = {
      unavailable: 'Unavailable. This capability is not connected.',
      unknown: 'Unknown. Current access and data could not be verified.',
      denied: 'Unavailable. Access could not be verified for this request.',
      conflict:
        'Changed since it was checked. Refresh authoritative data before continuing.',
    }[kind];
    expect(
      screen.getAllByRole('status').map((notice) => notice.textContent),
    ).toEqual([expected, expected, expected]);
    for (const button of screen.getAllByRole('button'))
      expect((button as HTMLButtonElement).disabled).toBe(true);
    expect(
      screen.queryByRole('button', { name: /Select collection/ }),
    ).toBeNull();
  },
);

it.each(['unavailable', 'unknown', 'denied', 'conflict'] as const)(
  'keeps independently %s memberships and audience fail-closed while authorized collection metadata remains visible',
  (kind) => {
    render(
      workspace({
        kind: 'available',
        data: [{ ...first, memberships: { kind }, audience: { kind } }],
      }),
    );
    expect(screen.getByRole('heading', { name: first.title })).toBeTruthy();
    expect(screen.queryByText('Document document-a')).toBeNull();
    expect(screen.queryByText('ALLOW · Synthetic verified subject')).toBeNull();
    expect(screen.getAllByRole('status')).toHaveLength(2);
    expect(
      (
        screen.getByRole('button', {
          name: 'Add membership',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  },
);

it('does not retain a selected collection when the supplied authorized scope is replaced', async () => {
  const view = render(workspace(ready));
  await userEvent.click(
    screen.getByRole('button', { name: `Select collection ${second.title}` }),
  );
  view.rerender(
    workspace({
      kind: 'available',
      data: [{ ...first, title: 'Replacement scope' }],
    }),
  );
  expect(
    screen.getByRole('heading', { name: 'Replacement scope' }),
  ).toBeTruthy();
  expect(screen.queryByText(second.title)).toBeNull();
  view.rerender(workspace({ kind: 'available', data: [] }));
  expect(
    screen.getByText('No accessible collections on this page.'),
  ).toBeTruthy();
  expect(screen.queryByText('Replacement scope')).toBeNull();
  expect(screen.queryByText('Document document-a')).toBeNull();
});

it('renders untrusted metadata, member identifiers and audience selectors as text, never executable markup', () => {
  const hostile = '<img src=x onerror=alert(1)>';
  render(
    workspace({
      kind: 'available',
      data: [
        {
          ...first,
          title: hostile,
          description: hostile,
          memberships: {
            kind: 'available',
            data: [
              { membershipId: 'x', documentId: hostile, state: 'INACTIVE' },
            ],
          },
          audience: {
            kind: 'available',
            data: {
              revision: 1,
              rules: [{ effect: 'DENY', selectorLabel: hostile }],
            },
          },
        },
      ],
    }),
  );
  expect(screen.getByText(`Document ${hostile}`)).toBeTruthy();
  expect(screen.getByText(`DENY · ${hostile}`)).toBeTruthy();
  expect(screen.getByText('INACTIVE')).toBeTruthy();
  expect(document.querySelector('img')).toBeNull();
  expect(document.querySelector('script')).toBeNull();
});
