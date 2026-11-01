import { Table, Text, VisuallyHidden } from '@mantine/core';
import type { Draft } from '../../document-api';

/** A bounded, authorized page: metadata is untrusted text, never permission evidence. */
export function DocumentLibrary({
  drafts,
  selected,
  onOpen,
}: {
  drafts: Draft[];
  selected: string | null;
  onOpen: (documentId: string) => void;
}) {
  if (drafts.length === 0)
    return <Text c="dimmed">No accessible drafts on this page.</Text>;
  return (
    <Table
      className="document-record"
      withRowBorders={false}
      aria-label="Document library"
    >
      <VisuallyHidden component="thead">
        <Table.Tr>
          <Table.Th scope="col">Document</Table.Th>
        </Table.Tr>
      </VisuallyHidden>
      <Table.Tbody>
        {drafts.map((draft) => (
          <Table.Tr key={draft.documentId}>
            <Table.Td>
              <div className="row" style={{ gap: 12 }}>
                <span aria-hidden="true">▤</span>
                <div className="grow">
                  <button
                    type="button"
                    className="document-record-title"
                    onClick={() => onOpen(draft.documentId)}
                    aria-pressed={selected === draft.documentId}
                  >
                    {draft.metadata.title}
                  </button>
                  <div className="sub document-metadata">
                    {draft.documentId} · {draft.classification} · Revision{' '}
                    {draft.revision}
                  </div>
                </div>
                <span className="badge">Draft</span>
              </div>
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
