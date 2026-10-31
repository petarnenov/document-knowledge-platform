import { z } from 'zod';
import type { SessionView } from './identity-api';
import { DocumentRequestError } from './document-api';

const root = '/api/v1/dm/documents';
const errorCode = z.enum([
  'INVALID_REQUEST',
  'UNAUTHENTICATED',
  'TENANT_DENIED',
  'OPERATION_DENIED',
  'NOT_FOUND',
  'REVISION_CONFLICT',
  'IDEMPOTENCY_CONFLICT',
  'UPLOAD_STATE_CONFLICT',
  'RECEIPT_CONSUMED',
  'UPLOAD_EXPIRED',
  'PAYLOAD_TOO_LARGE',
  'UNSUPPORTED_MEDIA_TYPE',
  'SIZE_MISMATCH',
  'INVALID_FILE',
  'MALWARE_DETECTED',
  'STRUCTURE_LIMIT',
  'UNSUPPORTED_CONTENT',
  'RANGE_NOT_SATISFIABLE',
  'QUOTA_EXCEEDED',
  'UNAVAILABLE',
]);
const uuid = z.uuid();
const upload = z.object({ uploadId: uuid }).strict();
const verified = z.object({ uploadId: uuid, receiptId: uuid }).strict();
const receipt = z
  .object({
    documentId: uuid,
    revision: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
  })
  .strict();
const version = z.object({ documentId: uuid, versionId: uuid }).strict();
export const formats = ['PDF', 'DOCX', 'PPTX', 'XLSX'] as const;
export type Format = (typeof formats)[number];
const mimes: Record<Format, string> = {
  PDF: 'application/pdf',
  DOCX: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  PPTX: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  XLSX: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
};
export const uploadStatus = z
  .object({
    uploadId: uuid,
    state: z.enum([
      'INITIALIZED',
      'RECEIVING',
      'QUARANTINED',
      'VERIFYING',
      'VERIFIED',
      'CONSUMED',
      'REJECTED',
      'EXPIRED',
    ]),
    expiresAt: z.iso.datetime({ offset: true }),
    failureCode: z
      .enum([
        'INVALID_FILE',
        'MALWARE_DETECTED',
        'STRUCTURE_LIMIT',
        'UNSUPPORTED_CONTENT',
        'DEPENDENCY_UNAVAILABLE',
        'UPLOAD_EXPIRED',
        'TRANSFER_INCOMPLETE',
      ])
      .nullable(),
  })
  .strict();
async function bytes(
  response: Response,
  limit: number,
): Promise<Uint8Array<ArrayBuffer>> {
  const reader = response.body?.getReader();
  if (!reader) throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      total += chunk.value.byteLength;
      if (total > limit)
        throw new DocumentRequestError(503, 'INVALID_RESPONSE');
      chunks.push(chunk.value);
    }
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error;
  }
  const result = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return result;
}
async function send(path: string, signal: AbortSignal, init: RequestInit = {}) {
  let response: Response;
  try {
    response = await fetch(`${root}${path}`, {
      ...init,
      signal: AbortSignal.any([signal, AbortSignal.timeout(300_000)]),
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
    });
  } catch {
    throw new DocumentRequestError(503, 'UNAVAILABLE');
  }
  if (!response.ok) {
    let code = 'UNAVAILABLE';
    try {
      code = z
        .object({ code: errorCode })
        .strict()
        .parse(
          JSON.parse(new TextDecoder().decode(await bytes(response, 16384))),
        ).code;
    } catch {
      /* Never display diagnostic bodies. */
    }
    throw new DocumentRequestError(response.status, code);
  }
  return response;
}
async function json<T>(
  path: string,
  schema: z.ZodType<T>,
  signal: AbortSignal,
  status: number,
  init: RequestInit = {},
) {
  const response = await send(path, signal, init);
  if (
    response.status !== status ||
    response.headers
      .get('content-type')
      ?.split(';')[0]
      ?.trim()
      .toLowerCase() !== 'application/json'
  )
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  try {
    return schema.parse(
      JSON.parse(new TextDecoder().decode(await bytes(response, 16384))),
    );
  } catch {
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  }
}
function headers(session: SessionView, key: string, type = 'application/json') {
  return {
    'Content-Type': type,
    Accept: 'application/json',
    'X-CSRF-TOKEN': session.csrfToken,
    'Idempotency-Key': key,
  };
}
function id(value: string) {
  if (!uuid.safeParse(value).success)
    throw new DocumentRequestError(400, 'INVALID_REQUEST');
  return value;
}
export function initializeUpload(
  documentId: string,
  revision: number,
  format: Format,
  file: File,
  session: SessionView,
  key: string,
  signal: AbortSignal,
) {
  if (
    !formats.includes(format) ||
    !Number.isSafeInteger(revision) ||
    revision < 0 ||
    file.size < 1 ||
    file.size > 100_000_000
  )
    throw new DocumentRequestError(400, 'INVALID_REQUEST');
  return json('/uploads', upload, signal, 201, {
    method: 'POST',
    headers: headers(session, key),
    body: JSON.stringify({
      documentId: id(documentId),
      expectedRevision: revision,
      format,
      sizeBytes: file.size,
    }),
  });
}
export async function transferUpload(
  uploadId: string,
  file: File,
  session: SessionView,
  key: string,
  signal: AbortSignal,
) {
  const result = await json(
    `/uploads/${id(uploadId)}/content`,
    upload,
    signal,
    200,
    {
      method: 'PUT',
      headers: headers(session, key, 'application/octet-stream'),
      body: file,
    },
  );
  if (result.uploadId !== uploadId)
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  return result;
}
export async function completeUpload(
  uploadId: string,
  session: SessionView,
  key: string,
  signal: AbortSignal,
) {
  const result = await json(
    `/uploads/${id(uploadId)}/complete`,
    verified,
    signal,
    200,
    { method: 'POST', headers: headers(session, key), body: '{}' },
  );
  if (result.uploadId !== uploadId)
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  return result;
}
export async function appendUpload(
  documentId: string,
  revision: number,
  receiptId: string,
  session: SessionView,
  key: string,
  signal: AbortSignal,
) {
  const result = await json(
    `/${id(documentId)}/versions`,
    receipt,
    signal,
    201,
    {
      method: 'POST',
      headers: headers(session, key),
      body: JSON.stringify({
        expectedRevision: revision,
        receiptId: id(receiptId),
      }),
    },
  );
  if (result.documentId !== documentId)
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  return result;
}
export async function discoverVersion(
  documentId: string,
  receiptId: string,
  signal: AbortSignal,
) {
  const result = await json(
    `/${id(documentId)}/upload-receipts/${id(receiptId)}/version`,
    version,
    signal,
    200,
  );
  if (result.documentId !== documentId)
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  return result;
}
export async function inspectUpload(uploadId: string, signal: AbortSignal) {
  const result = await json(
    `/uploads/${id(uploadId)}/status`,
    uploadStatus,
    signal,
    200,
  );
  if (result.uploadId !== uploadId)
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  return result;
}
export async function downloadVersion(
  documentId: string,
  versionId: string,
  format: Format,
  signal: AbortSignal,
) {
  const response = await send(
    `/${id(documentId)}/versions/${id(versionId)}/content`,
    signal,
    { headers: { Accept: mimes[format] } },
  );
  const length = response.headers.get('content-length');
  if (
    response.status !== 200 ||
    response.headers.get('content-type') !== mimes[format] ||
    response.headers.get('content-disposition') !==
      `attachment; filename="${versionId}.${format.toLowerCase()}"` ||
    !length ||
    !/^[1-9][0-9]{0,8}$/.test(length) ||
    Number(length) > 100_000_000 ||
    response.headers.get('cache-control') !== 'no-store' ||
    response.headers.get('x-content-type-options') !== 'nosniff'
  )
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  const data = await bytes(response, Number(length));
  if (data.byteLength !== Number(length))
    throw new DocumentRequestError(503, 'INVALID_RESPONSE');
  return new Blob([data], { type: mimes[format] });
}
