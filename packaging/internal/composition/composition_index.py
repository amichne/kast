"""The pinned 262 loader's version-2 plugin classpath format, with opaque native XML."""

from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum, StrEnum


class IndexFailure(StrEnum):
    UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION"
    TRUNCATED = "TRUNCATED"
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    INVALID_PATH = "INVALID_PATH"
    DUPLICATE_DIRECTORY = "DUPLICATE_DIRECTORY"
    TRAILING_BYTES = "TRAILING_BYTES"


@dataclass(frozen=True, slots=True)
class IndexRejected:
    reason: IndexFailure
    offset: int


class ClasspathNormalization(IntEnum):
    NATIVE = 0
    NORMALIZED = 1


def _path(value: str) -> bool:
    return (
        value.isascii()
        and 0 < len(value) <= 65_535
        and not any(c in value for c in ("\\", "\x00", ":"))
        and all(part not in {"", ".", ".."} for part in value.split("/"))
    )


@dataclass(frozen=True, slots=True)
class PluginClasspath:
    directory: str
    descriptor: bytes
    files: tuple[str, ...]

    def __post_init__(self) -> None:
        if (
            not _path(self.directory)
            or not 0 < len(self.descriptor) <= 4_194_304
            or not 0 < len(self.files) <= 8192
            or len(set(self.files)) != len(self.files)
            or not all(_path(p) for p in self.files)
        ):
            raise ValueError("Plugin classpath needs admitted native fields")


@dataclass(frozen=True, slots=True)
class PluginIndex:
    core_descriptor: bytes
    plugins: tuple[PluginClasspath, ...]
    normalization: ClasspathNormalization = ClasspathNormalization.NORMALIZED

    def __post_init__(self) -> None:
        if (
            not 0 < len(self.core_descriptor) <= 4_194_304
            or len(self.plugins) > 4096
            or len({p.directory for p in self.plugins}) != len(self.plugins)
        ):
            raise ValueError("Plugin index needs a nonempty descriptor and unique directories")


class _Input:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.offset = 0

    def take(self, size: int) -> bytes | IndexRejected:
        if size < 0 or self.offset + size > len(self.data):
            return IndexRejected(IndexFailure.TRUNCATED, self.offset)
        value = self.data[self.offset : self.offset + size]
        self.offset += size
        return value

    def number(self, size: int) -> int | IndexRejected:
        value = self.take(size)
        return value if isinstance(value, IndexRejected) else int.from_bytes(value, "big")

    def blob(self) -> bytes | IndexRejected:
        size = self.number(4)
        if isinstance(size, IndexRejected):
            return size
        if not 0 < size <= 4_194_304:
            return IndexRejected(IndexFailure.LIMIT_EXCEEDED, self.offset)
        return self.take(size)

    def path(self) -> str | IndexRejected:
        size = self.number(2)
        if isinstance(size, IndexRejected):
            return size
        value = self.take(size)
        if isinstance(value, IndexRejected):
            return value
        # The pinned distribution uses ASCII paths. Reject unsupported modified-UTF input.
        if not value.isascii():
            return IndexRejected(IndexFailure.INVALID_PATH, self.offset)
        text = value.decode("ascii")
        if not _path(text):
            return IndexRejected(IndexFailure.INVALID_PATH, self.offset)
        return text


def parse_plugin_index(data: bytes) -> PluginIndex | IndexRejected:
    if len(data) > 16_777_216:
        return IndexRejected(IndexFailure.LIMIT_EXCEEDED, 0)
    source = _Input(data)
    version = source.number(1)
    if isinstance(version, IndexRejected):
        return version
    if version != 2:
        return IndexRejected(IndexFailure.UNSUPPORTED_VERSION, 0)
    mode = source.number(1)
    if isinstance(mode, IndexRejected):
        return mode
    if mode not in (0, 1):
        return IndexRejected(IndexFailure.UNSUPPORTED_VERSION, 1)
    core = source.blob()
    if isinstance(core, IndexRejected):
        return core
    count = source.number(2)
    if isinstance(count, IndexRejected):
        return count
    if count > 4096:
        return IndexRejected(IndexFailure.LIMIT_EXCEEDED, source.offset)
    records: list[PluginClasspath] = []
    directories: set[str] = set()
    for _ in range(count):
        files = source.number(2)
        if isinstance(files, IndexRejected):
            return files
        if not 0 < files <= 8192:
            return IndexRejected(IndexFailure.LIMIT_EXCEEDED, source.offset)
        directory = source.path()
        if isinstance(directory, IndexRejected):
            return directory
        if directory in directories:
            return IndexRejected(IndexFailure.DUPLICATE_DIRECTORY, source.offset)
        descriptor = source.blob()
        if isinstance(descriptor, IndexRejected):
            return descriptor
        paths: list[str] = []
        for _ in range(files):
            path = source.path()
            if isinstance(path, IndexRejected):
                return path
            if path in paths:
                return IndexRejected(IndexFailure.INVALID_PATH, source.offset)
            paths.append(path)
        records.append(PluginClasspath(directory, descriptor, tuple(paths)))
        directories.add(directory)
    if source.offset != len(data):
        return IndexRejected(IndexFailure.TRAILING_BYTES, source.offset)
    return PluginIndex(core, tuple(records), ClasspathNormalization(mode))


def encode_plugin_index(index: PluginIndex) -> bytes:
    def path(value: str) -> bytes:
        encoded = value.encode("ascii")
        return len(encoded).to_bytes(2, "big") + encoded

    def blob(value: bytes) -> bytes:
        return len(value).to_bytes(4, "big") + value

    result = bytearray(bytes((2, index.normalization)) + blob(index.core_descriptor))
    result.extend(len(index.plugins).to_bytes(2, "big"))
    for plugin in index.plugins:
        result.extend(len(plugin.files).to_bytes(2, "big"))
        result.extend(path(plugin.directory))
        result.extend(blob(plugin.descriptor))
        for file in plugin.files:
            result.extend(path(file))
    return bytes(result)
