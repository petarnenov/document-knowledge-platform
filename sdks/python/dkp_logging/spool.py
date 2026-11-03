"""Private bounded atomic snapshot adapter. Contains sanitized events, never tokens."""
import fcntl
import os
from pathlib import Path
import stat
import struct
import uuid

from .wire import Prepared, identifier, queue_binding, require

SNAPSHOT_BYTES, DISK_BYTES = 9 * 1024 * 1024, 64 * 1024 * 1024
MAGIC = b'DKPPYQ01'
NAMES = frozenset(('pending.bin', 'pending.next', 'queue.lock'))


class FileLogSpool:
    def __init__(self, directory, identity, credential_fingerprint):
        self.directory = Path(directory)
        self.binding = queue_binding(identity, credential_fingerprint)
        self._fd, self._loaded, self._closed = None, False, False
        try:
            require(self.directory.is_absolute())
            if not self.directory.exists():
                self.directory.mkdir(mode=0o700)
            self._check_directory()
            lock = self.directory / 'queue.lock'
            if lock.exists():
                self._check_file(lock, 0)
            self._fd = os.open(lock, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            self._check_file(lock, 0)
            fcntl.flock(self._fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if (self.directory / 'pending.next').exists():
                self._check_file(self.directory / 'pending.next', SNAPSHOT_BYTES)
                require((self.directory / 'pending.bin').is_file())
        except Exception:
            self.close()
            raise OSError('LOG_SPOOL_UNAVAILABLE') from None

    def _check_directory(self):
        require(not self._closed and self.directory.resolve(strict=True) == self.directory)
        info = self.directory.lstat()
        require(stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o700 and info.st_uid == os.getuid())
        for entry in self.directory.iterdir():
            require(entry.name in NAMES)
            self._check_file(entry, 0 if entry.name == 'queue.lock' else SNAPSHOT_BYTES)

    @staticmethod
    def _check_file(path, limit):
        info = path.lstat()
        require(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600
            and info.st_uid == os.getuid() and info.st_size <= limit)
        return info

    @staticmethod
    def _validate(events):
        require(type(events) is list and len(events) <= 2048)
        total, ids = 0, set()
        for event in events:
            require(type(event) is Prepared and identifier(event.event_id) and event.event_id not in ids)
            require(type(event.data) is bytes and 0 < len(event.data) <= 8192)
            ids.add(event.event_id)
            total += len(event.data)
            require(total <= 8 * 1024 * 1024)

    def load(self):
        try:
            self._check_directory()
            path = self.directory / 'pending.bin'
            if not path.exists():
                self._loaded = True
                return []
            before = self._check_file(path, SNAPSHOT_BYTES)
            descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(descriptor, 'rb') as source:
                data = source.read(SNAPSHOT_BYTES + 1)
                after = os.fstat(source.fileno())
            require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
                == (after.st_dev, after.st_ino, len(data), after.st_mtime_ns))
            expected = MAGIC + struct.pack('>I', len(self.binding)) + self.binding
            require(len(data) <= SNAPSHOT_BYTES and data.startswith(expected))
            offset = len(expected)
            count = struct.unpack_from('>I', data, offset)[0]
            require(count <= 2048)
            offset += 4
            events = []
            for _ in range(count):
                event_id = str(uuid.UUID(bytes=data[offset:offset + 16]))
                size = struct.unpack_from('>I', data, offset + 16)[0]
                require(0 < size <= 8192 and offset + 20 + size <= len(data))
                events.append(Prepared(event_id, data[offset + 20:offset + 20 + size]))
                offset += 20 + size
            require(offset == len(data))
            self._validate(events)
            self._loaded = True
            return events
        except Exception:
            raise OSError('LOG_SPOOL_UNAVAILABLE') from None

    def replace(self, events):
        try:
            self._check_directory()
            require(self._loaded)
            self._validate(events)
            data = MAGIC + struct.pack('>I', len(self.binding)) + self.binding + struct.pack('>I', len(events))
            data += b''.join(uuid.UUID(event.event_id).bytes + struct.pack('>I', len(event.data)) + event.data for event in events)
            require(len(data) <= SNAPSHOT_BYTES)
            stable, staging = self.directory / 'pending.bin', self.directory / 'pending.next'
            retained = self._check_file(stable, SNAPSHOT_BYTES).st_size if stable.exists() else 0
            require(retained + len(data) <= DISK_BYTES)
            if staging.exists():
                self._check_file(staging, SNAPSHOT_BYTES)
                staging.unlink()
            descriptor = os.open(staging, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, 'wb') as target:
                target.write(data)
                target.flush()
                os.fsync(target.fileno())
            self._check_file(staging, SNAPSHOT_BYTES)
            os.replace(staging, stable)
            descriptor = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        except Exception:
            raise OSError('LOG_SPOOL_UNAVAILABLE') from None

    def close(self):
        if not self._closed:
            self._closed = True
            if self._fd is not None:
                os.close(self._fd)
                self._fd = None
