import { UploadJourney } from './features/uploads/UploadJourney';
import {
  Alert,
  Button,
  FileInput,
  Group,
  NativeSelect,
  Paper,
  Text,
  Stack,
  VisuallyHidden,
} from '@mantine/core';
import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { SessionView } from './identity-api';
import { useSessionAuthority } from './features/identity/authority';
import { documentError } from './document-api';
import {
  appendUpload,
  completeUpload,
  discoverVersion,
  downloadVersion,
  formats,
  initializeUpload,
  inspectUpload,
  transferUpload,
  type Format,
} from './upload-api';

type Job = {
  file: File;
  format: Format;
  revision: number;
  keys: [string, string, string, string];
  uploadId?: string;
  receiptId?: string;
  appended?: boolean;
};
export function Uploads({
  documentId,
  revision,
  session,
}: {
  documentId: string;
  revision: number;
  session: SessionView;
}) {
  const client = useQueryClient();
  const authority = useSessionAuthority(session);
  const [file, setFile] = useState<File | null>(null);
  const [format, setFormat] = useState<Format>('PDF');
  const [stage, setStage] = useState('');
  const [uploadId, setUploadId] = useState<string>();
  const job = useRef<Job | null>(null);
  const active = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  const scope = [
    'documents',
    session.issuer,
    session.subject,
    session.selectedTenant,
    session.csrfToken,
  ] as const;
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      active.current?.abort();
      job.current = null;
    };
  }, []);
  const status = useQuery({
    gcTime: 0,
    queryKey: [...scope, 'upload', documentId, uploadId],
    queryFn: ({ signal }) =>
      authority.run(signal, (guard) => inspectUpload(uploadId!, guard)),
    enabled: !!uploadId && !authority.blocked,
  });
  const mutation = useMutation({
    mutationKey: [...scope, 'upload-command', documentId],
    gcTime: 0,
    mutationFn: async () => {
      authority.request(new AbortController().signal).assertCurrent();
      const pending = job.current;
      if (!pending) throw new Error('Choose a file');
      const control = new AbortController();
      active.current = control;
      const signal = control.signal;
      const request = authority.request(signal);
      const update = (value: string) => {
        request.assertCurrent();
        if (mounted.current && !signal.aborted) setStage(value);
      };
      try {
        if (!pending.uploadId) {
          update('Reserving upload capacity…');
          pending.uploadId = (
            await initializeUpload(
              documentId,
              pending.revision,
              pending.format,
              pending.file,
              session,
              pending.keys[0],
              request,
            )
          ).uploadId;
          if (mounted.current && !signal.aborted) setUploadId(pending.uploadId);
        }
        if (!pending.receiptId) {
          update('Transferring into quarantine…');
          await transferUpload(
            pending.uploadId,
            pending.file,
            session,
            pending.keys[1],
            request,
          );
          update('Checking malware and document structure…');
          pending.receiptId = (
            await completeUpload(
              pending.uploadId,
              session,
              pending.keys[2],
              request,
            )
          ).receiptId;
        }
        if (!pending.appended) {
          update('Appending the verified version…');
          await appendUpload(
            documentId,
            pending.revision,
            pending.receiptId,
            session,
            pending.keys[3],
            request,
          );
          pending.appended = true;
        }
        update('Checking permission to inspect the version…');
        const result = await discoverVersion(
          documentId,
          pending.receiptId,
          request,
        );
        request.assertCurrent();
        if (signal.aborted || !mounted.current) throw new Error('Cancelled');
        update(
          'Verified version appended. Publication and processing have not started.',
        );
        return { versionId: result.versionId, format: pending.format };
      } finally {
        if (active.current === control) active.current = null;
      }
    },
    onSettled: () => {
      if (mounted.current && !authority.blocked) {
        if (job.current?.uploadId) void status.refetch();
        void client.invalidateQueries({
          queryKey: [...scope, 'detail', documentId],
          exact: true,
        });
      }
    },
  });
  const download = useMutation({
    mutationKey: [...scope, 'download-command', documentId],
    gcTime: 0,
    mutationFn: async () => {
      const guard = authority.request(new AbortController().signal);
      guard.assertCurrent();
      if (!mutation.data) throw new Error('No version');
      const control = new AbortController();
      active.current = control;
      try {
        const blob = await downloadVersion(
          documentId,
          mutation.data.versionId,
          mutation.data.format,
          authority.request(control.signal),
        );
        guard.assertCurrent();
        if (control.signal.aborted || !mounted.current) return;
        const url = URL.createObjectURL(blob);
        try {
          const anchor = document.createElement('a');
          anchor.href = url;
          anchor.download = `${mutation.data.versionId}.${mutation.data.format.toLowerCase()}`;
          anchor.click();
        } finally {
          URL.revokeObjectURL(url);
        }
      } finally {
        if (active.current === control) active.current = null;
      }
    },
  });
  function start() {
    try {
      authority.request(new AbortController().signal).assertCurrent();
    } catch {
      return;
    }
    if (!file) return;
    if (!job.current)
      job.current = {
        file,
        format,
        revision,
        keys: [
          crypto.randomUUID(),
          crypto.randomUUID(),
          crypto.randomUUID(),
          crypto.randomUUID(),
        ],
      };
    mutation.mutate();
  }
  function reset() {
    active.current?.abort();
    job.current = null;
    mutation.reset();
    download.reset();
    setUploadId(undefined);
    setStage('');
    setFile(null);
  }
  const busy = mutation.isPending || download.isPending;
  return (
    <Paper
      component="section"
      className="card"
      aria-labelledby={`upload-${documentId}`}
    >
      <Stack gap="md">
        <VisuallyHidden>
          <h2 id={`upload-${documentId}`}>Upload a verified version</h2>
        </VisuallyHidden>
        <NativeSelect
          label="Document format"
          data={[...formats]}
          value={format}
          disabled={busy || !mutation.isIdle}
          onChange={(event) => setFormat(event.currentTarget.value as Format)}
        />
        <FileInput
          label="Choose document file"
          placeholder="Choose file…"
          value={file}
          onChange={setFile}
          disabled={busy || !mutation.isIdle}
          accept=".pdf,.docx,.pptx,.xlsx"
        />
        <Text size="sm" c="dimmed">
          PDF, DOCX, PPTX or XLSX · up to 100 MB (exactly 100,000,000 bytes).
          Selecting a file does not verify its contents. Server checks determine
          whether content is safe. Appending preserves the active version.
        </Text>
        <UploadJourney
          verified={
            !!mutation.data ||
            status.data?.state === 'VERIFIED' ||
            status.data?.state === 'CONSUMED'
          }
          recorded={!!mutation.data}
        />
        {file && (file.size < 1 || file.size > 100_000_000) && (
          <Alert role="alert">Choose a nonempty file of at most 100 MB.</Alert>
        )}
        <Group>
          <Button
            onClick={start}
            disabled={
              busy ||
              !file ||
              file.size < 1 ||
              file.size > 100_000_000 ||
              mutation.isSuccess
            }
          >
            {mutation.isError ? 'Retry upload' : 'Upload and append'}
          </Button>
          <Button variant="default" onClick={reset}>
            Cancel and clear
          </Button>
        </Group>
        {stage && (
          <Text role="status" aria-live="polite">
            {stage}
          </Text>
        )}
        {mutation.isError && (
          <Text size="sm">
            The last operation may have completed. Retry upload reuses the
            original command keys to reconcile its outcome; it does not start a
            second version.
          </Text>
        )}
        {status.data && <p>Upload state: {status.data.state}</p>}
        {[mutation.error, download.error, status.error]
          .filter(Boolean)
          .map((error, index) => (
            <Alert role="alert" key={index}>
              {documentError(error)}
            </Alert>
          ))}
        {mutation.data && (
          <>
            <p>Version {mutation.data.versionId}</p>
            <Button onClick={() => download.mutate()} disabled={busy}>
              Download verified content
            </Button>
          </>
        )}
      </Stack>
    </Paper>
  );
}
