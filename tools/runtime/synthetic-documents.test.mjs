import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  chmodSync,
  existsSync,
  linkSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import {
  crc32,
  documents,
  generate,
  main,
  ROOT,
} from './synthetic-documents.mjs';

const parent = path.join(ROOT, 'local-data');
mkdirSync(parent, { recursive: true });
function temporary(t) {
  const directory = mkdtempSync(path.join(parent, 'dkp-147-test-'));
  t.after(() => {
    chmodSync(directory, 0o700);
    rmSync(directory, { recursive: true, force: true });
  });
  return directory;
}

test('standard CRC32 vectors and deterministic exact four synthetic names', () => {
  assert.equal(crc32(Buffer.from('123456789')), 0xcbf43926);
  assert.equal(crc32(Buffer.alloc(0)), 0);
  const first = documents(),
    second = documents();
  assert.deepEqual(
    [...first.keys()],
    ['synthetic.pdf', 'synthetic.docx', 'synthetic.pptx', 'synthetic.xlsx'],
  );
  for (const [name, data] of first) {
    assert.deepEqual(data, second.get(name));
    assert.ok(data.length > 100 && data.length < 4096);
    assert.equal(createHash('sha256').update(data).digest('hex').length, 64);
  }
});

test('PDF xref and trailer resolve all three genuine objects and one blank page', () => {
  const data = documents().get('synthetic.pdf').toString('ascii');
  assert.ok(data.startsWith('%PDF-1.7\n'));
  const pointer = Number(data.match(/startxref\n(\d+)\n%%EOF\n$/)[1]);
  assert.ok(data.slice(pointer).startsWith('xref\n0 4\n'));
  const offsets = [...data.matchAll(/^(\d{10}) 00000 n $/gm)].map((match) =>
    Number(match[1]),
  );
  assert.equal(offsets.length, 3);
  offsets.forEach((offset, index) =>
    assert.ok(data.slice(offset).startsWith(`${index + 1} 0 obj\n`)),
  );
  assert.match(data, /\/Count 1/);
  assert.match(data, /\/Root 1 0 R/);
});

test('each ZIP has canonical full-coverage local/central parity, CRC and OOXML relationships', () => {
  for (const [format, part, root] of [
    ['docx', 'word/document.xml', 'document'],
    ['pptx', 'ppt/presentation.xml', 'presentation'],
    ['xlsx', 'xl/workbook.xml', 'workbook'],
  ]) {
    const data = documents().get(`synthetic.${format}`);
    const end = data.length - 22;
    assert.equal(data.readUInt32LE(end), 0x06054b50);
    assert.equal(data.readUInt16LE(end + 8), 3);
    assert.equal(data.readUInt16LE(end + 10), 3);
    assert.equal(data.readUInt16LE(end + 20), 0);
    let central = data.readUInt32LE(end + 16),
      local = 0;
    const centralStart = central,
      entries = new Map();
    for (let index = 0; index < 3; index++) {
      assert.equal(data.readUInt32LE(central), 0x02014b50);
      assert.equal(data.readUInt32LE(local), 0x04034b50);
      assert.equal(data.readUInt32LE(central + 42), local);
      assert.equal(data.readUInt16LE(local + 6), 0x800);
      assert.equal(data.readUInt16LE(local + 8), 0);
      assert.equal(data.readUInt16LE(central + 8), 0x800);
      assert.equal(data.readUInt16LE(central + 10), 0);
      assert.equal(data.readUInt16LE(local + 28), 0);
      assert.equal(data.readUInt16LE(central + 30), 0);
      const length = data.readUInt32LE(local + 22),
        nameLength = data.readUInt16LE(local + 26);
      assert.equal(data.readUInt32LE(local + 18), length);
      assert.equal(data.readUInt32LE(central + 20), length);
      assert.equal(data.readUInt32LE(central + 24), length);
      const name = data
        .subarray(local + 30, local + 30 + nameLength)
        .toString();
      assert.equal(
        data.subarray(central + 46, central + 46 + nameLength).toString(),
        name,
      );
      assert.equal(data.readUInt16LE(central + 28), nameLength);
      const bytes = data.subarray(
        local + 30 + nameLength,
        local + 30 + nameLength + length,
      );
      assert.equal(crc32(bytes), data.readUInt32LE(local + 14));
      assert.equal(
        data.readUInt32LE(local + 14),
        data.readUInt32LE(central + 16),
      );
      entries.set(name, bytes.toString());
      local += 30 + nameLength + length;
      central += 46 + nameLength;
    }
    assert.equal(local, centralStart);
    assert.equal(central, end);
    assert.equal(data.readUInt32LE(end + 12), end - centralStart);
    assert.deepEqual(
      [...entries.keys()],
      ['[Content_Types].xml', '_rels/.rels', part],
    );
    assert.ok(
      entries.get('[Content_Types].xml').includes(`PartName="/${part}"`),
    );
    assert.ok(entries.get('_rels/.rels').includes(`Target="${part}"`));
    assert.ok(entries.get(part).startsWith(`<x:${root} `));
  }
});

test('confined generated outputs are idempotent and nested directories supported', (t) => {
  const directory = path.join(temporary(t), 'nested', 'fixtures');
  const files = generate(directory);
  assert.deepEqual(files, generate(directory));
  assert.deepEqual(
    readdirSync(directory).sort(),
    [...documents().keys()].sort(),
  );
  for (const file of files)
    assert.deepEqual(readFileSync(file), documents().get(path.basename(file)));
  assert.deepEqual(main(['--directory', directory]), files);
});

test('foreign regular output is preserved and preflight avoids any partial output', (t) => {
  const directory = temporary(t),
    foreign = path.join(directory, 'synthetic.xlsx');
  writeFileSync(foreign, 'user-authored');
  assert.throws(() => generate(directory), /foreign fixture/);
  assert.equal(readFileSync(foreign, 'utf8'), 'user-authored');
  assert.deepEqual(readdirSync(directory), ['synthetic.xlsx']);
});

test('refuse output symlink, hardlink, directory and dangling symlink', (t) => {
  const base = temporary(t);
  const source = path.join(base, 'source');
  writeFileSync(source, documents().get('synthetic.pdf'));
  for (const kind of ['symlink', 'dangling', 'hardlink', 'directory']) {
    const directory = path.join(base, kind);
    mkdirSync(directory);
    const output = path.join(directory, 'synthetic.pdf');
    if (kind === 'directory') mkdirSync(output);
    else if (kind === 'hardlink') linkSync(source, output);
    else
      symlinkSync(
        kind === 'dangling' ? path.join(base, 'absent') : source,
        output,
      );
    assert.throws(() => generate(directory), /foreign fixture/);
    assert.equal(existsSync(path.join(directory, 'synthetic.docx')), false);
  }
});

test('reject escaping root, root itself, ancestor symlink and non-directory ancestor', (t) => {
  const base = temporary(t);
  assert.throws(() => generate(ROOT), /child/);
  assert.throws(() => generate(path.dirname(ROOT)), /child/);
  const real = path.join(base, 'real');
  mkdirSync(real);
  const link = path.join(base, 'link');
  symlinkSync(real, link);
  assert.throws(
    () => generate(path.join(link, 'nested')),
    /real output directories/,
  );
  const dangling = path.join(base, 'dangling');
  symlinkSync(path.join(base, 'absent'), dangling);
  assert.throws(() => generate(dangling));
  const file = path.join(base, 'file');
  writeFileSync(file, 'foreign');
  assert.throws(
    () => generate(path.join(file, 'nested')),
    /real output directories/,
  );
  assert.deepEqual(readdirSync(real), []);
});

test('strict CLI rejects unknown/trailing arguments, sanitizes failure, and generates via Node24', (t) => {
  assert.throws(() => main(['--unknown']), /Usage/);
  assert.throws(() => main(['--directory', 'x', 'extra']), /Usage/);
  const script = path.join(ROOT, 'tools/runtime/synthetic-documents.mjs');
  const directory = temporary(t);
  const success = spawnSync(
    process.execPath,
    [script, '--directory', directory],
    { encoding: 'utf8' },
  );
  assert.equal(success.status, 0);
  assert.match(success.stdout, /Four deterministic/);
  const failure = spawnSync(
    process.execPath,
    [script, '--directory', path.dirname(ROOT)],
    { encoding: 'utf8' },
  );
  assert.equal(failure.status, 1);
  assert.match(failure.stderr, /existing foreign output preserved/);
  assert.ok(!failure.stderr.includes(ROOT));
});
