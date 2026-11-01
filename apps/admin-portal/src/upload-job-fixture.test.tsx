import { expect, test } from 'vitest';
import { createHash } from 'node:crypto';
import { sourcePdf } from '../e2e-real/upload-job-fixture';

test('public synthetic workload has exact readable PDF offsets and bounded genuine content', () => {
  const bytes = sourcePdf();
  expect(bytes.length).toBeGreaterThan(30_000_000);
  expect(bytes.length).toBeLessThan(33_000_000);
  const text = bytes.toString('ascii');
  expect(Buffer.from(text, 'ascii').equals(bytes)).toBe(true);
  expect(text.startsWith('%PDF-1.7\n')).toBe(true);
  const xref = Number(/startxref\n(\d+)\n%%EOF\n$/.exec(text)?.[1]);
  expect(text.slice(xref, xref + 9)).toBe('xref\n0 5\n');
  const rows = text.slice(xref).split('\n').slice(3, 7);
  for (const [index, row] of rows.entries()) {
    const offset = Number(row.slice(0, 10));
    expect(text.slice(offset, offset + 7)).toBe(`${index + 1} 0 obj`);
  }
  const match = /\/Length (\d+) >>\nstream\n/.exec(text)!;
  const start = match.index + match[0].length;
  expect(
    text.slice(start + Number(match[1]), start + Number(match[1]) + 9),
  ).toBe('endstream');
  expect(text).not.toMatch(/JavaScript|OpenAction|EmbeddedFile|Encrypt/);
  expect(createHash('sha256').update(bytes).digest('hex')).toBe(
    createHash('sha256').update(sourcePdf()).digest('hex'),
  );
});
