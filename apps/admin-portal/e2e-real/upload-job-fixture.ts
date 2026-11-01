// Readable valid one-page PDF with a32MB comment content stream. It remains
// below every approved source/page/object bound and supplies real scan work.
export function sourcePdf() {
  const content = '%' + 'Public synthetic scan work '.repeat(1_200_000) + '\n';
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> /Contents 4 0 R >>',
    `<< /Length ${content.length} >>\nstream\n${content}endstream`,
  ];
  let text = '%PDF-1.7\n';
  const offsets = [];
  for (const [i, object] of objects.entries()) {
    offsets.push(text.length);
    text += `${i + 1} 0 obj\n${object}\nendobj\n`;
  }
  const xref = text.length;
  text += 'xref\n0 5\n0000000000 65535 f \n';
  for (const offset of offsets)
    text += `${String(offset).padStart(10, '0')} 00000 n \n`;
  return Buffer.from(
    text + `trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`,
    'ascii',
  );
}
