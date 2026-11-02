import { describe, expect, it } from 'vitest';
import { documentSchema } from '../../document-api';
import { uploadStatus } from '../../upload-api';
import {
  futureCapabilities,
  toDraftView,
  toRecordedVersionView,
  toUploadView,
} from './lifecycle';

const documentId = '10000000-0000-4000-8000-000000000001';
const versionId = '10000000-0000-4000-8000-000000000002';
const uploadId = '10000000-0000-4000-8000-000000000003';

describe('authoritative lifecycle adapters', () => {
  it('preserves draft facts without inventing versions or mutation permission', () => {
    const draft = documentSchema.parse({
      documentId,
      tenant: 'synthetic-workspace',
      creator: { issuer: 'https://synthetic.invalid', subject: 'actor' },
      createdAt: '2026-11-01T00:00:00Z',
      revision: 4,
      lifecycle: 'DRAFT',
      metadata: { title: '<untrusted title>', description: 'Synthetic only' },
      classification: 'development-restricted',
      classificationPolicyRevision: 1,
      activeVersionId: null,
      retention: {
        policyRef: 'development-unresolved',
        policyRevision: 1,
        resolution: 'UNRESOLVED',
        retainUntil: null,
        destructiveEligible: false,
      },
    });
    expect(toDraftView(draft)).toEqual({
      documentId,
      title: '<untrusted title>',
      description: 'Synthetic only',
      aggregateRevision: 4,
      lifecycle: 'DRAFT',
      activePublishedVersionId: null,
      latestVersion: 'unknown',
      classificationLabel: 'development-restricted',
      retentionResolution: 'UNRESOLVED',
      actionAvailability: 'unknown',
    });
    expect(draft.revision).toBe(4);
  });

  it.each([
    ['INITIALIZED', 'Ready to transfer'],
    ['RECEIVING', 'Receiving file'],
    ['QUARANTINED', 'File quarantined'],
    ['VERIFYING', 'Verifying file'],
    ['VERIFIED', 'File verified; version not confirmed'],
    ['CONSUMED', 'Receipt consumed; reconcile version'],
    ['REJECTED', 'File rejected'],
    ['EXPIRED', 'Upload expired'],
  ])(
    'maps %s without claiming a version, processing or progress',
    (state, label) => {
      const upload = uploadStatus.parse({
        uploadId,
        state,
        expiresAt: '2026-11-01T01:00:00Z',
        failureCode: null,
      });
      expect(toUploadView(upload)).toEqual({
        ...upload,
        label,
        versionConfirmation: 'unknown',
        processing: 'unknown',
      });
      expect(toUploadView(upload)).not.toHaveProperty('progress');
      expect(toUploadView(upload)).not.toHaveProperty('allowedActions');
    },
  );

  it('retains a failure without translating dependency outage into clearance', () => {
    const upload = uploadStatus.parse({
      uploadId,
      state: 'QUARANTINED',
      expiresAt: '2026-11-01T01:00:00Z',
      failureCode: 'DEPENDENCY_UNAVAILABLE',
    });
    expect(toUploadView(upload)).toMatchObject({
      failureCode: 'DEPENDENCY_UNAVAILABLE',
      processing: 'unknown',
      versionConfirmation: 'unknown',
    });
  });

  it('records only the exact version discovered by the server', () => {
    expect(toRecordedVersionView({ documentId, versionId })).toEqual({
      documentId,
      versionId,
      immutable: true,
      label: 'Version recorded',
      processing: 'unknown',
      approval: 'unknown',
      publication: 'unknown',
    });
  });

  it('keeps unimplemented workflows unavailable', () => {
    expect(Object.keys(futureCapabilities)).toEqual([
      'processing',
      'review',
      'publication',
      'search',
      'preview',
      'purge',
    ]);
    expect(
      Object.values(futureCapabilities).every(
        (state) => state === 'unavailable',
      ),
    ).toBe(true);
  });
});
