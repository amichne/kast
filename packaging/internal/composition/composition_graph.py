"""Read only the module closure selected from a pinned IntelliJ runtime repository."""

from __future__ import annotations

import hashlib
import re
import zipfile
from collections import deque
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path

from defusedxml import ElementTree as XML
from defusedxml.common import DefusedXmlException


class GraphFailure(StrEnum):
    INVALID_IDENTITY = "INVALID_IDENTITY"
    INVALID_REPOSITORY = "INVALID_REPOSITORY"
    INVALID_MODULE = "INVALID_MODULE"
    IDENTITY_MISMATCH = "IDENTITY_MISMATCH"
    UNKNOWN_MODULE = "UNKNOWN_MODULE"
    DUPLICATE_MODULE = "DUPLICATE_MODULE"
    UNSAFE_RESOURCE = "UNSAFE_RESOURCE"
    LIMIT_EXCEEDED = "LIMIT_EXCEEDED"


@dataclass(frozen=True, slots=True)
class GraphRejected:
    reason: GraphFailure
    subject: str


@dataclass(frozen=True, slots=True, order=True)
class ModuleId:
    name: str
    namespace: str = "jetbrains"

    def __post_init__(self) -> None:
        if not _valid_identity(self.name, self.namespace):
            raise ValueError("Module identity needs admitted names")

    @property
    def descriptor(self) -> str:
        suffix = "" if self.namespace == "jetbrains" else "_" + self.namespace
        return self.name + suffix + ".xml"


def _valid_identity(name: str, namespace: str) -> bool:
    return all(re.fullmatch(r"[A-Za-z0-9_$][A-Za-z0-9_.$-]{0,239}", v) for v in (name, namespace))


def module_id(name: str, namespace: str = "jetbrains") -> ModuleId | GraphRejected:
    if not _valid_identity(name, namespace):
        return GraphRejected(GraphFailure.INVALID_IDENTITY, name)
    return ModuleId(name, namespace)


@dataclass(frozen=True, slots=True, order=True)
class ModuleResource:
    path: str

    def __post_init__(self) -> None:
        if not _safe_relative(self.path):
            raise ValueError("Module resource needs an admitted relative file")


def _safe_relative(value: str) -> bool:
    parts = value.split("/")
    return (
        len(parts) >= 2
        and parts[0] in {"lib", "plugins"}
        and all(part not in {"", ".", ".."} for part in parts)
        and not any(c in value for c in ("\\", "\x00", ":"))
    )


@dataclass(frozen=True, slots=True)
class RuntimeModule:
    identity: ModuleId
    dependencies: tuple[ModuleId, ...]
    resources: tuple[ModuleResource, ...]
    descriptor_sha256: str


@dataclass(frozen=True, slots=True)
class ClosureMember:
    module: RuntimeModule
    # Roots carry no predecessor. Every other member keeps its dependency witness.
    predecessor: ModuleId | None


@dataclass(frozen=True, slots=True)
class ModuleClosure:
    roots: tuple[ModuleId, ...]
    members: tuple[ClosureMember, ...]

    @property
    def resources(self) -> tuple[ModuleResource, ...]:
        return tuple(sorted({r for member in self.members for r in member.module.resources}))


def parse_module(data: bytes, expected: ModuleId) -> RuntimeModule | GraphRejected:
    if len(data) > 1_048_576:
        return GraphRejected(GraphFailure.LIMIT_EXCEEDED, expected.descriptor)
    try:
        root = XML.fromstring(data)
    except (XML.ParseError, DefusedXmlException):
        return GraphRejected(GraphFailure.INVALID_MODULE, expected.descriptor)
    identity = module_id(root.get("name", ""), root.get("namespace", "jetbrains"))
    if isinstance(identity, GraphRejected):
        return identity
    if root.tag != "module" or identity != expected:
        return GraphRejected(GraphFailure.IDENTITY_MISMATCH, expected.descriptor)
    dependencies: list[ModuleId] = []
    for node in root.findall("./dependencies/module"):
        dependency = module_id(node.get("name", ""), node.get("namespace", "jetbrains"))
        if isinstance(dependency, GraphRejected):
            return dependency
        dependencies.append(dependency)
    resources: list[ModuleResource] = []
    for node in root.findall("./resources/resource-root"):
        raw = node.get("path", "")
        if not raw.startswith("../") or not _safe_relative(raw[3:]):
            return GraphRejected(GraphFailure.UNSAFE_RESOURCE, raw)
        # The repository lives under modules/. Resources start at the installation root.
        resources.append(ModuleResource(raw[3:]))
    if len(dependencies) > 1024 or len(resources) > 1024:
        return GraphRejected(GraphFailure.LIMIT_EXCEEDED, expected.descriptor)
    if len(set(dependencies)) != len(dependencies) or len(set(resources)) != len(resources):
        return GraphRejected(GraphFailure.INVALID_MODULE, expected.descriptor)
    return RuntimeModule(
        identity, tuple(dependencies), tuple(resources), hashlib.sha256(data).hexdigest()
    )


def read_closure(repository: Path, roots: tuple[ModuleId, ...]) -> ModuleClosure | GraphRejected:
    """The effect adapter reads exact roots before it reads their declared dependencies."""
    if not roots or len(set(roots)) != len(roots):
        return GraphRejected(GraphFailure.INVALID_IDENTITY, "roots")
    pending: deque[tuple[ModuleId, ModuleId | None]] = deque((root, None) for root in sorted(roots))
    visited: dict[ModuleId, ClosureMember] = {}
    try:
        with zipfile.ZipFile(repository) as archive:
            names = archive.namelist()
            while pending:
                identity, predecessor = pending.popleft()
                if identity in visited:
                    continue
                if len(visited) >= 4096:
                    return GraphRejected(GraphFailure.LIMIT_EXCEEDED, identity.descriptor)
                occurrences = names.count(identity.descriptor)
                if occurrences == 0:
                    return GraphRejected(GraphFailure.UNKNOWN_MODULE, identity.descriptor)
                if occurrences != 1:
                    return GraphRejected(GraphFailure.DUPLICATE_MODULE, identity.descriptor)
                entry = archive.getinfo(identity.descriptor)
                if entry.flag_bits & 1:
                    return GraphRejected(GraphFailure.INVALID_REPOSITORY, identity.descriptor)
                if entry.file_size > 1_048_576:
                    return GraphRejected(GraphFailure.LIMIT_EXCEEDED, identity.descriptor)
                parsed = parse_module(archive.read(entry), identity)
                if isinstance(parsed, GraphRejected):
                    return parsed
                visited[identity] = ClosureMember(parsed, predecessor)
                pending.extend((dependency, identity) for dependency in sorted(parsed.dependencies))
    except (OSError, zipfile.BadZipFile):
        return GraphRejected(GraphFailure.INVALID_REPOSITORY, str(repository))
    return ModuleClosure(tuple(sorted(roots)), tuple(visited[k] for k in sorted(visited)))
