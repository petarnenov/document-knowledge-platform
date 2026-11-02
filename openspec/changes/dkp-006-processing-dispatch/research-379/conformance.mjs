import { createHash } from 'node:crypto';

// Research data conversion/comparison only; this is not a runtime canonicalizer.
export function inputRows(vectors) {
  return (
    vectors
      .map((vector) => {
        if (vector.ieee754) return `${vector.id}\tIEEE754\t${vector.ieee754}`;
        const bytes = vector.utf8Bytes
          ? Buffer.from(vector.utf8Bytes)
          : Buffer.from(vector.json, 'utf8');
        return `${vector.id}\tUTF8\t${bytes.toString('base64')}`;
      })
      .join('\n') + '\n'
  );
}

export function assess(vectors, javaOutput, reference) {
  const rows = new Map();
  for (const line of javaOutput.trim().split('\n')) {
    const fields = line.split('\t');
    if (fields.length !== 2 || rows.has(fields[0]))
      throw new Error('Invalid probe row');
    rows.set(fields[0], fields[1]);
  }
  if (rows.size !== vectors.length) throw new Error('Incomplete probe results');
  return vectors.map((vector) => {
    const actual = rows.get(vector.id);
    if (actual === undefined) throw new Error('Unknown probe identity');
    const expected =
      vector.expected === 'REJECT'
        ? 'REJECT'
        : Buffer.from(vector.canonical).toString('base64');
    let referenceResult = 'RAW_INPUT_VALIDATION_REQUIRED';
    if (vector.expected === 'CANONICAL') {
      let value;
      if (vector.ieee754) {
        const bytes = Buffer.from(vector.ieee754, 'hex');
        value = { n: bytes.readDoubleBE() };
      } else {
        value = JSON.parse(vector.json);
      }
      const canonical = reference(value);
      const hash = createHash('sha256').update(canonical).digest('hex');
      referenceResult =
        canonical === vector.canonical && hash === vector.sha256
          ? 'PASS'
          : 'FAIL';
    }
    return {
      id: vector.id,
      expected: vector.expected,
      java: actual === expected ? 'PASS' : 'FAIL',
      reference: referenceResult,
    };
  });
}
