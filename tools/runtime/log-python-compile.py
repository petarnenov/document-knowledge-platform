"""Fixed offline compiler used only inside the pinned owned build container."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import py_compile

MODULES = ('__init__', 'https', 'producer', 'spool', 'wire')


def require(value):
    if not value:
        raise ValueError('LOG_PYTHON_COMPILE_INVALID')


def compile_package(source, output):
    require(platform.python_version() == '3.14.10')
    source, output = Path(source), Path(output)
    require(source.is_absolute() and source.resolve(strict=True) == source
        and output.is_absolute() and output.resolve(strict=True) == output)
    require(set(path.name for path in source.iterdir()) == {name + '.py' for name in MODULES})
    require(not any(output.iterdir()))
    target = output / 'dkp_logging'
    target.mkdir(mode=0o700)
    rows = []
    for name in MODULES:
        original, compiled = source / (name + '.py'), target / (name + '.pyc')
        require(not original.is_symlink() and original.is_file() and original.stat().st_size <= 65536)
        data = original.read_bytes()
        py_compile.compile(str(original), cfile=str(compiled),
            dfile='sdks/python/dkp_logging/' + name + '.py', doraise=True,
            invalidation_mode=py_compile.PycInvalidationMode.CHECKED_HASH)
        require(original.read_bytes() == data)
        bytecode = compiled.read_bytes()
        require(16 < len(bytecode) <= 262144 and bytecode[:4] == importlib.util.MAGIC_NUMBER
            and bytecode[4:8] == b'\x03\x00\x00\x00' and bytecode[8:16] == importlib.util.source_hash(data))
        compiled.chmod(0o600)
        rows.append({'name':name, 'sourceSha256':hashlib.sha256(data).hexdigest(),
            'compiledSha256':hashlib.sha256(bytecode).hexdigest(), 'compiledBytes':len(bytecode)})
    record = {'schemaVersion':1, 'python':platform.python_version(),
        'magic':importlib.util.MAGIC_NUMBER.hex(), 'modules':rows}
    descriptor = os.open(output / 'compiler-record.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as stream:
        json.dump(record, stream, separators=(',', ':'))
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        compile_package(args.source, args.output)
    except Exception:
        print('{"result":"FAIL","code":"LOG_PYTHON_COMPILE_INVALID"}')
        return 1
    print('{"result":"COMPILED","modules":5}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
