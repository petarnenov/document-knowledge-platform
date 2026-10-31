#!/usr/bin/env node
/** Readable, deterministic synthetic E2E fixtures; no encoded document blobs.
 * Usage: node tools/runtime/synthetic-documents.mjs [--directory <repo-child>]
 * Default: apps/admin-portal/e2e-real/fixtures. Existing identical generated files
 * are accepted; symlinks, hardlinks, foreign content and destinations outside the
 * canonical checkout are rejected. Trusted local checkout/operator, no providers.
 */
import {
  constants,
  closeSync,
  existsSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  realpathSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = realpathSync(
  fileURLToPath(new URL('../..', import.meta.url)),
);
const DEFAULT = path.join(ROOT, 'apps/admin-portal/e2e-real/fixtures');

export function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++)
      crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pdf() {
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << >> >>',
  ];
  let text = '%PDF-1.7\n';
  const offsets = [0];
  for (const [index, object] of objects.entries()) {
    offsets.push(Buffer.byteLength(text, 'ascii'));
    text += `${index + 1} 0 obj\n${object}\nendobj\n`;
  }
  const xref = Buffer.byteLength(text, 'ascii');
  text += 'xref\n0 4\n0000000000 65535 f \n';
  for (const offset of offsets.slice(1))
    text += `${String(offset).padStart(10, '0')} 00000 n \n`;
  text += `trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(text, 'ascii');
}

function zip(entries) {
  // ZIP32, stored bytes, UTF-8, fixed 1980-01-01 timestamp, no extras,
  // descriptors or comments. Local and central records share exact facts.
  const local = [],
    central = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const filename = Buffer.from(name, 'utf8'),
      data = Buffer.from(text, 'utf8');
    const crc = crc32(data),
      header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50);
    header.writeUInt16LE(20, 4);
    header.writeUInt16LE(0x800, 6);
    header.writeUInt16LE(0x21, 12);
    header.writeUInt32LE(crc, 14);
    header.writeUInt32LE(data.length, 18);
    header.writeUInt32LE(data.length, 22);
    header.writeUInt16LE(filename.length, 26);
    local.push(header, filename, data);
    const directory = Buffer.alloc(46);
    directory.writeUInt32LE(0x02014b50);
    directory.writeUInt16LE(20, 4);
    directory.writeUInt16LE(20, 6);
    directory.writeUInt16LE(0x800, 8);
    directory.writeUInt16LE(0x21, 14);
    directory.writeUInt32LE(crc, 16);
    directory.writeUInt32LE(data.length, 20);
    directory.writeUInt32LE(data.length, 24);
    directory.writeUInt16LE(filename.length, 28);
    directory.writeUInt32LE(offset, 42);
    central.push(directory, filename);
    offset += header.length + filename.length + data.length;
  }
  const directory = Buffer.concat(central),
    end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, directory, end]);
}

export function documents() {
  const result = new Map([['synthetic.pdf', pdf()]]);
  const types = 'http://schemas.openxmlformats.org/package/2006/content-types';
  const relationships =
    'http://schemas.openxmlformats.org/package/2006/relationships';
  const office =
    'http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument';
  for (const [extension, part, type, xml] of [
    [
      'docx',
      'word/document.xml',
      'wordprocessingml.document',
      '<x:document xmlns:x="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><x:body><x:p/></x:body></x:document>',
    ],
    [
      'pptx',
      'ppt/presentation.xml',
      'presentationml.presentation',
      '<x:presentation xmlns:x="http://schemas.openxmlformats.org/presentationml/2006/main"><x:sldMasterIdLst/><x:sldIdLst/><x:sldSz cx="9144000" cy="6858000"/><x:notesSz cx="6858000" cy="9144000"/></x:presentation>',
    ],
    [
      'xlsx',
      'xl/workbook.xml',
      'spreadsheetml.sheet',
      '<x:workbook xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><x:sheets/></x:workbook>',
    ],
  ]) {
    result.set(
      `synthetic.${extension}`,
      zip([
        [
          '[Content_Types].xml',
          `<Types xmlns="${types}"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/${part}" ContentType="application/vnd.openxmlformats-officedocument.${type}.main+xml"/></Types>`,
        ],
        [
          '_rels/.rels',
          `<Relationships xmlns="${relationships}"><Relationship Id="rId1" Type="${office}" Target="${part}"/></Relationships>`,
        ],
        [part, xml],
      ]),
    );
  }
  return result;
}

function destination(directory) {
  const target = path.resolve(directory);
  const relative = path.relative(ROOT, target);
  if (
    !relative ||
    relative.startsWith(`..${path.sep}`) ||
    relative === '..' ||
    path.isAbsolute(relative)
  )
    throw new Error('Use a child of the canonical checkout');
  let current = path.parse(target).root;
  for (const component of target.slice(current.length).split(path.sep)) {
    current = path.join(current, component);
    if (existsSync(current)) {
      if (
        !lstatSync(current).isDirectory() ||
        lstatSync(current).isSymbolicLink()
      )
        throw new Error('Only real output directories are allowed');
    } else {
      // existsSync returns false for dangling symlinks too; mkdir never follows
      // such an entry and fails closed without writing a document.
      mkdirSync(current);
    }
  }
  return target;
}

export function generate(directory = DEFAULT) {
  const target = destination(directory),
    fixtures = documents();
  // Preflight every output before writing any: foreign content stays untouched.
  for (const [name, bytes] of fixtures) {
    const file = path.join(target, name);
    let stat;
    try {
      stat = lstatSync(file);
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    if (
      stat &&
      (!stat.isFile() ||
        stat.isSymbolicLink() ||
        stat.nlink !== 1 ||
        stat.size !== bytes.length ||
        !readFileSync(file).equals(bytes))
    )
      throw new Error('Refusing foreign fixture output');
  }
  const created = [];
  try {
    for (const [name, bytes] of fixtures) {
      const file = path.join(target, name);
      if (existsSync(file)) continue;
      const fd = openSync(
        file,
        constants.O_WRONLY |
          constants.O_CREAT |
          constants.O_EXCL |
          constants.O_NOFOLLOW,
        0o644,
      );
      created.push(file);
      try {
        writeFileSync(fd, bytes);
      } finally {
        closeSync(fd);
      }
    }
  } catch (error) {
    for (const file of created) unlinkSync(file);
    throw error;
  }
  return [...fixtures.keys()].map((name) => path.join(target, name));
}

export function main(args) {
  if (args.length !== 0 && (args.length !== 2 || args[0] !== '--directory'))
    throw new Error(
      'Usage: synthetic-documents.mjs [--directory <repo-child>]',
    );
  return generate(args[1] ?? DEFAULT);
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  try {
    main(process.argv.slice(2));
    console.log('Four deterministic synthetic document fixtures generated.');
  } catch {
    console.error(
      'Synthetic fixture generation failed; existing foreign output preserved.',
    );
    process.exitCode = 1;
  }
}
