"""Fixed original provider/port compiler; never imports or executes domain code."""
import argparse
import hashlib
import importlib.util
import json
import marshal
import os
from pathlib import Path
import platform
import py_compile
import re
import types

MODULES = (('dkp_provider_jobs', 'tools/runtime/provider-jobs.py', 'provider-jobs.pyc'),
           ('dkp_tool_diagnostics', 'tools/runtime/tool-diagnostics.py', 'tool-diagnostics.pyc'))
MAX_SOURCE = 4 * 1024 * 1024
MAX_COMPILED = 2 * 1024 * 1024
MAX_RECORD = 4 * 1024 * 1024
MAX_FRAMES = 32768


def require(value):
    if not value:
        raise ValueError('LOG_TOOL_COMPILE_INVALID')


def sha(value):
    return hashlib.sha256(value).hexdigest()


def code_catalog(code, module, source):
    """Resolve exact original constant locators without executing any code."""
    require((module, source) in {(name, path) for name, path, _ in MODULES})
    result, seen = [], set()

    def visit(actual, locator):
        require(type(actual) is types.CodeType and len(locator) <= 32
            and actual.co_filename == source and id(actual) not in seen
            and len(result) < MAX_FRAMES)
        seen.add(id(actual))
        suffix = '.'.join(map(str, locator)) if locator else 'ROOT'
        symbol = module + '.' + actual.co_qualname + '$' + suffix
        require(len(symbol) <= 256 and re.fullmatch(r'[A-Za-z0-9_.$<>-]+', symbol)
            and type(actual.co_firstlineno) is int and actual.co_firstlineno >= 0)
        result.append(dict(module=module, locator=list(locator), qualname=actual.co_qualname,
            firstLine=actual.co_firstlineno, symbol=symbol, source=source))
        for index, value in enumerate(actual.co_consts):
            if type(value) is types.CodeType:
                visit(value, locator + (index,))

    visit(code, ())
    return result


def compile_distribution(source, output):
    require(platform.python_version() == '3.14.10')
    source, output = Path(source), Path(output)
    require(source.is_absolute() and source.resolve(strict=True) == source
        and output.is_absolute() and output.resolve(strict=True) == output
        and output.is_dir() and not any(output.iterdir())
        and output.stat().st_uid == os.getuid() and not output.stat().st_mode & 0o077)
    modules, total_compiled, total_frames = [], 0, 0
    for name, relative, destination in MODULES:
        original, compiled = source / relative, output / destination
        require(original.resolve(strict=True) == original and original.is_file()
            and 0 < original.stat().st_size <= MAX_SOURCE)
        data = original.read_bytes()
        require(len(data) <= MAX_SOURCE)
        py_compile.compile(str(original), cfile=str(compiled), dfile=relative, doraise=True,
            optimize=0, invalidation_mode=py_compile.PycInvalidationMode.CHECKED_HASH)
        require(original.read_bytes() == data)
        compiled.chmod(0o600)
        bytecode = compiled.read_bytes()
        require(16 < len(bytecode) <= MAX_COMPILED and bytecode[:4] == importlib.util.MAGIC_NUMBER
            and bytecode[4:8] == b'\x03\x00\x00\x00'
            and bytecode[8:16] == importlib.util.source_hash(data))
        total_compiled += len(bytecode)
        require(total_compiled <= 4 * 1024 * 1024)
        frames = code_catalog(marshal.loads(bytecode[16:]), name, relative)
        total_frames += len(frames)
        require(total_frames <= MAX_FRAMES)
        modules.append(dict(name=name, source=relative, sourceSha256=sha(data),
            compiledSha256=sha(bytecode), compiledBytes=len(bytecode), frames=frames))
    record = dict(schemaVersion=1, python=platform.python_version(),
        magic=importlib.util.MAGIC_NUMBER.hex(), modules=modules)
    encoded = json.dumps(record, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
    require(len(encoded) <= MAX_RECORD)
    descriptor = os.open(output / 'compiler-record.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(encoded)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        compile_distribution(args.source, args.output)
    except Exception:
        print('{"result":"FAIL","code":"LOG_TOOL_COMPILE_INVALID"}')
        return 1
    print('{"result":"COMPILED","modules":2}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
