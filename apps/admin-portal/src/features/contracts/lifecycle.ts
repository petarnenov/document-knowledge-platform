import type { z } from 'zod';
import type { Draft } from '../../document-api';
import type { discoverVersion, uploadStatus } from '../../upload-api';

/** Presentation only. These values never authorize a request. */
export type CapabilityState = 'unknown' | 'unavailable';

export const futureCapabilities = {
  processing: 'unavailable',
  review: 'unavailable',
  publication: 'unavailable',
  search: 'unavailable',
  preview: 'unavailable',
  purge: 'unavailable',
} as const satisfies Record<string, CapabilityState>;

/** Call only after the API boundary has validated resource and tenant binding. */
export function toDraftView(draft: Draft) {
  return {
    documentId: draft.documentId,
    title: draft.metadata.title,
    description: draft.metadata.description,
    aggregateRevision: draft.revision,
    lifecycle: draft.lifecycle,
    activePublishedVersionId: draft.activeVersionId,
    latestVersion: 'unknown' as const,
    classificationLabel: draft.classification,
    retentionResolution: draft.retention.resolution,
    // A successful read is not evidence of mutation rights.
    actionAvailability: 'unknown' as const,
  };
}

const uploadLabels = {
  INITIALIZED: 'Ready to transfer',
  RECEIVING: 'Receiving file',
  QUARANTINED: 'File quarantined',
  VERIFYING: 'Verifying file',
  VERIFIED: 'File verified; version not confirmed',
  CONSUMED: 'Receipt consumed; reconcile version',
  REJECTED: 'File rejected',
  EXPIRED: 'Upload expired',
} as const satisfies Record<z.infer<typeof uploadStatus>['state'], string>;

export function toUploadView(upload: z.infer<typeof uploadStatus>) {
  return {
    uploadId: upload.uploadId,
    state: upload.state,
    label: uploadLabels[upload.state],
    expiresAt: upload.expiresAt,
    failureCode: upload.failureCode,
    // Neither verification nor consumption returns an exact version identity.
    versionConfirmation: 'unknown' as const,
    processing: 'unknown' as const,
  };
}

export function toRecordedVersionView(
  version: Awaited<ReturnType<typeof discoverVersion>>,
) {
  return {
    documentId: version.documentId,
    versionId: version.versionId,
    immutable: true as const,
    label: 'Version recorded',
    // BP-03 discovery proves version identity, not processing or publication.
    processing: 'unknown' as const,
    approval: 'unknown' as const,
    publication: 'unknown' as const,
  };
}
