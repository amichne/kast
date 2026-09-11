#!/usr/bin/env python3
"""Contract-first disclosure projection. Input is detached documentation, NOT Kotlin source."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import asdict, dataclass
from enum import Enum
from pathlib import Path, PurePosixPath
from tempfile import TemporaryDirectory
from typing import TypeAlias

# Profile v1: limits are UTF-8 bytes, not tokenizer-dependent estimates.
FANOUT = 16
SUMMARY_CHARACTERS = 240
MAX_INPUT_BYTES = 8 * 1024 * 1024
PAGE_BYTES = {"index": 16384, "card": 32768, "detail": 32768}
CONTRACT_KINDS = frozenset({
    "description", "param", "return", "throws", "receiver", "property",
    "constructor", "since", "deprecated", "see", "custom",
})
DETAIL_KINDS = frozenset({"sample", "implementation"})


class FailureCode(str, Enum):
    INPUT = "invalid_input"
    DUPLICATE = "duplicate_identity"
    LIMIT = "resource_limit"
    OUTPUT = "output_mismatch"
    IO = "io_failure"


@dataclass(frozen=True)
class Rejected:
    code: FailureCode
    location: str
    message: str


@dataclass(frozen=True)
class _Section:
    ordinal: int
    kind: str
    text: str


@dataclass(frozen=True)
class _DeclarationMetadata:
    project: str
    source_set: str
    dri: str
    name: str
    signature: str
    source_path: str
    source_sha256: str

    @property
    def identity(self) -> str:
        # JSON tuple encoding avoids delimiter collisions; DRI remains opaque.
        return hashlib.sha256(_json([self.project, self.source_set, self.dri])).hexdigest()


@dataclass(frozen=True)
class _Declaration:
    metadata: _DeclarationMetadata
    sections: tuple[_Section, ...]


@dataclass(frozen=True)
class _Summary:
    path: str
    kind: str
    title: str
    summary: str
    summary_is_excerpt: bool


@dataclass(frozen=True)
class _Index:
    descriptor: _Summary
    children: tuple[_Summary, ...]


@dataclass(frozen=True)
class _Card:
    descriptor: _Summary
    declaration: _DeclarationMetadata
    documentation_status: str
    contract: tuple[_Section, ...]
    details: tuple[_Summary, ...]


@dataclass(frozen=True)
class _Detail:
    descriptor: _Summary
    owner: _Summary
    section: _Section


_Resource: TypeAlias = _Index | _Card | _Detail


@dataclass(frozen=True)
class Generated:
    """Files produced only after ingress, segmentation, and page budgets succeed."""
    files: tuple[tuple[str, bytes], ...]


def _json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def _exact(value: object, keys: set[str]) -> bool:
    return isinstance(value, dict) and set(value) == keys


def _text(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip()) and not any(
        ord(c) < 32 and c not in "\n\t" or 0xD800 <= ord(c) <= 0xDFFF for c in value
    )


def _path(value: object) -> bool:
    return isinstance(value, str) and bool(value) and bool(re.fullmatch(r"[A-Za-z0-9_./-]+", value)) and (
        not value.startswith("/") and all(part not in {"", ".", ".."} for part in value.split("/"))
    )


def _parse(raw: object) -> tuple[_Declaration, ...] | Rejected:
    if not _exact(raw, {"formatVersion", "producer", "declarations"}):
        return Rejected(FailureCode.INPUT, "$", "Expected only formatVersion, producer, declarations")
    if type(raw["formatVersion"]) is not int or raw["formatVersion"] != 1 or not _text(raw["producer"]):
        return Rejected(FailureCode.INPUT, "$", "Expected version 1 and a producer identity")
    if not isinstance(raw["declarations"], list) or not raw["declarations"]:
        return Rejected(FailureCode.INPUT, "declarations", "Expected a nonempty declaration inventory")
    declarations: list[_Declaration] = []
    identities: set[str] = set()
    for i, item in enumerate(raw["declarations"]):
        at = f"declarations[{i}]"
        fields = {"projectPath", "sourceSet", "dri", "name", "signature", "sourcePath", "sourceSha256", "sections"}
        if not _exact(item, fields):
            return Rejected(FailureCode.INPUT, at, "Unexpected or missing declaration fields")
        if not all(_text(item[key]) for key in fields - {"sections"}):
            return Rejected(FailureCode.INPUT, at, "Declaration metadata must be nonempty text")
        if not re.fullmatch(r"(?::[a-zA-Z0-9_-]+)+", item["projectPath"]):
            return Rejected(FailureCode.INPUT, at, "Invalid Gradle project path")
        if not re.fullmatch(r"[a-zA-Z0-9_-]+", item["sourceSet"]) or not _path(item["sourcePath"]):
            return Rejected(FailureCode.INPUT, at, "Invalid source set or repository-relative source path")
        if not re.fullmatch(r"[0-9a-f]{64}", item["sourceSha256"]):
            return Rejected(FailureCode.INPUT, at, "Expected a source content SHA-256")
        if len(item["name"]) > SUMMARY_CHARACTERS or "\n" in item["name"]:
            return Rejected(FailureCode.LIMIT, at, "Display name exceeds the single-line descriptor budget")
        sections = item["sections"]
        if not isinstance(sections, list):
            return Rejected(FailureCode.INPUT, at, "Expected sections; an empty list explicitly means undocumented")
        parsed: list[_Section] = []
        for j, section in enumerate(sections):
            if not _exact(section, {"kind", "text"}) or not _text(section["kind"]) or not _text(section["text"]):
                return Rejected(FailureCode.INPUT, f"{at}.sections[{j}]", "Expected only kind and nonempty text")
            if section["kind"] not in CONTRACT_KINDS | DETAIL_KINDS:
                return Rejected(FailureCode.INPUT, f"{at}.sections[{j}]", "Unsupported section kind; do not silently discard it")
            parsed.append(_Section(j, section["kind"], section["text"]))
        metadata = _DeclarationMetadata(
            item["projectPath"], item["sourceSet"], item["dri"], item["name"], item["signature"],
            item["sourcePath"], item["sourceSha256"],
        )
        if metadata.identity in identities:
            return Rejected(FailureCode.DUPLICATE, at, "Repeated project/source-set/DRI identity")
        identities.add(metadata.identity)
        declarations.append(_Declaration(metadata, tuple(parsed)))
    return tuple(sorted(declarations, key=lambda d: (d.metadata.project, d.metadata.source_set, d.metadata.name, d.metadata.identity)))


def _descriptor(path: str, kind: str, title: str, text: str) -> _Summary:
    # Summaries are routing excerpts. Complete text survives in its owning card/detail.
    paragraph = text.split("\n\n", 1)[0]
    compact = " ".join(paragraph.split())
    excerpt = len(compact) > SUMMARY_CHARACTERS
    return _Summary(path, kind, title, compact[:SUMMARY_CHARACTERS], excerpt)


def _index_tree(path: str, title: str, children: tuple[_Summary, ...], resources: list[_Resource]) -> _Summary:
    descriptor = _descriptor(path, "index", title, f"{len(children)} entries; open one child at a time.")
    if len(children) > FANOUT:
        # Balanced deterministic range buckets keep *every* level within the fanout.
        size = (len(children) + FANOUT - 1) // FANOUT
        parent = str(PurePosixPath(path).parent)
        children = tuple(
            _index_tree(f"{parent}/pages/{n:04}/index.json", f"{title}: entries {start + 1}-{min(start + size, len(children))}",
                        children[start:start + size], resources)
            for n, start in enumerate(range(0, len(children), size), 1)
        )
    resources.append(_Index(descriptor, children))
    return descriptor


def _project(declarations: tuple[_Declaration, ...]) -> tuple[_Resource, ...]:
    resources: list[_Resource] = []
    modules: dict[str, dict[str, list[_Summary]]] = {}
    for extracted in declarations:
        declaration = extracted.metadata
        base = f"declarations/{declaration.identity}"
        description = next((s.text for s in extracted.sections if s.kind == "description"), "Undocumented declaration.")
        descriptor = _descriptor(f"{base}/card.json", "card", declaration.name, description)
        details: list[_Summary] = []
        for section in extracted.sections:
            if section.kind in DETAIL_KINDS:
                detail = _descriptor(f"{base}/{section.ordinal:04}-{section.kind}.json", "detail",
                                     f"{declaration.name}: {section.kind} {section.ordinal}",
                                     f"{section.kind.capitalize()} documentation for {declaration.name}.")
                resources.append(_Detail(detail, descriptor, section))
                details.append(detail)
        resources.append(_Card(descriptor, declaration, "documented" if extracted.sections else "undocumented",
                               tuple(s for s in extracted.sections if s.kind in CONTRACT_KINDS), tuple(details)))
        modules.setdefault(declaration.project, {}).setdefault(declaration.source_set, []).append(descriptor)
    module_entries: list[_Summary] = []
    for project, source_sets in modules.items():
        # Hash the actual project identity; don't infer filesystem ownership from its spelling.
        directory = "modules/" + hashlib.sha256(project.encode()).hexdigest()
        entries = tuple(_index_tree(f"{directory}/{source_set}/index.json", f"{project} / {source_set}", tuple(cards), resources)
                        for source_set, cards in source_sets.items())
        module_entries.append(_index_tree(f"{directory}/index.json", project, entries, resources))
    _index_tree("index.json", "API knowledge", tuple(module_entries), resources)
    return tuple(resources)


def _inline(text: str) -> str:
    # An index description cannot introduce blocks, links, HTML, or headings.
    return re.sub(r"([\\`*_{}\[\]<>()#!|~])", r"\\\1", " ".join(text.split()))


def _link(origin: str, target: _Summary) -> str:
    import posixpath
    relative = posixpath.relpath(target.path.removesuffix(".json") + ".md", str(PurePosixPath(origin).parent))
    suffix = " [excerpt]" if target.summary_is_excerpt else ""
    return f"[{_inline(target.title)}]({relative}) — {_inline(target.summary)}{suffix}"


def _render(resource: _Resource, snapshot: str, producer: str) -> tuple[dict, str]:
    descriptor = resource.descriptor
    document = {"formatVersion": 1, "snapshot": snapshot, "producer": producer, **asdict(descriptor)}
    header = f"# {_inline(descriptor.title)}\n\nGenerated documentation snapshot `{snapshot}`.\n"
    if isinstance(resource, _Index):
        document["children"] = [asdict(child) for child in resource.children]
        return document, header + "\n" + "\n".join(f"- {_link(descriptor.path, child)}" for child in resource.children) + "\n"
    frontmatter = f"---\ntype: API {descriptor.kind}\ntitle: {json.dumps(descriptor.title)}\n---\n\n"
    if isinstance(resource, _Detail):
        document.update(owner=asdict(resource.owner), section=asdict(resource.section))
        return document, frontmatter + header + f"\nOwner: {_link(descriptor.path, resource.owner)}\n\n{resource.section.text}\n"
    declaration = resource.declaration
    document.update(
        declarationId=declaration.identity, projectPath=declaration.project, sourceSet=declaration.source_set,
        dri=declaration.dri, signature=declaration.signature,
        source={"relativeFilePath": declaration.source_path, "sha256": declaration.source_sha256},
        documentationStatus=resource.documentation_status,
        sections=[asdict(section) for section in resource.contract],
        details=[asdict(detail) for detail in resource.details],
    )
    fence = "`" * max(3, 1 + max((len(run) for run in re.findall(r"`+", declaration.signature)), default=0))
    body = (f"\nProject: `{declaration.project}`; source set: `{declaration.source_set}`.\n"
            f"Source: `{declaration.source_path}`; SHA-256: `{declaration.source_sha256}`.\n\n"
            f"{fence}kotlin\n{declaration.signature}\n{fence}\n")
    body += "\n".join(f"\n## {section.kind} [{section.ordinal}]\n\n{section.text}\n" for section in resource.contract)
    if resource.documentation_status == "undocumented":
        body += "\nUndocumented declaration.\n"
    if resource.details:
        body += "\n## Detail resources\n\n" + "\n".join(f"- {_link(descriptor.path, detail)}" for detail in resource.details) + "\n"
    return document, frontmatter + header + body


def generate(payload: bytes) -> Generated | Rejected:
    """Parse detached records, preserve every section exactly once, then admit bounded output."""
    if len(payload) > MAX_INPUT_BYTES:
        return Rejected(FailureCode.LIMIT, "$", "Input exceeds the profile byte budget")
    try:
        pairs: list[tuple[str, object]] = []

        def unique_object(items: list[tuple[str, object]]) -> dict:
            # Keep duplicate-key evidence; never accept JSON's usual last-key-wins semantics.
            if len(dict(items)) != len(items):
                pairs.extend(items)
            return dict(items)

        raw = json.loads(payload.decode("utf-8"), object_pairs_hook=unique_object)
        if pairs:
            return Rejected(FailureCode.INPUT, "$", "Duplicate JSON object keys")
        declarations = _parse(raw)
    except (UnicodeError, ValueError, RecursionError) as failure:
        return Rejected(FailureCode.INPUT, "$", f"Cannot decode input: {type(failure).__name__}")
    if isinstance(declarations, Rejected):
        return declarations
    # Canonical declaration order makes extraction scheduling irrelevant to artifact identity.
    canonical = {"producer": raw["producer"], "declarations": [asdict(d) for d in declarations], "profile": 1}
    snapshot = hashlib.sha256(_json(canonical)).hexdigest()
    files: list[tuple[str, bytes]] = []
    for resource in _project(declarations):
        document, markdown = _render(resource, snapshot, raw["producer"])
        for path, content in ((resource.descriptor.path, _json(document)),
                              (resource.descriptor.path.removesuffix(".json") + ".md", markdown.encode("utf-8"))):
            if len(content) > PAGE_BYTES[resource.descriptor.kind]:
                return Rejected(FailureCode.LIMIT, path, "Resource exceeds its byte budget; never truncate contract text")
            files.append((path, content))
    return Generated(tuple(sorted(files)))


def check(generated: Generated, output: Path) -> Rejected | None:
    """Check exact generated parity, including stale files and edited disclosure boundaries."""
    try:
        paths = list(output.rglob("*"))
        if output.is_symlink() or any(path.is_symlink() for path in paths):
            return Rejected(FailureCode.OUTPUT, str(output), "Symlink in generated output")
        actual = {path.relative_to(output).as_posix(): path for path in paths if path.is_file()}
        expected = dict(generated.files)
        if actual.keys() != expected.keys():
            return Rejected(FailureCode.OUTPUT, str(output), "Missing or unexpected generated files")
        for name, data in expected.items():
            if actual[name].read_bytes() != data:
                return Rejected(FailureCode.OUTPUT, name, "Generated content differs")
    except OSError as failure:
        return Rejected(FailureCode.IO, str(output), type(failure).__name__)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--check", action="store_true", help="Compare only; never modify generated files")
    args = parser.parse_args()
    try:
        result = generate(args.input.read_bytes())
        if isinstance(result, Generated):
            if args.check:
                result = check(result, args.output)
            elif args.output.exists() or args.output.is_symlink():
                result = Rejected(FailureCode.OUTPUT, str(args.output), "Output must not exist; publication never overwrites a tree")
            else:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                with TemporaryDirectory(dir=args.output.parent) as temporary:
                    stage = Path(temporary) / "bundle"
                    stage.mkdir()
                    for name, data in result.files:
                        target = stage / name
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(data)
                    stage.rename(args.output)
                result = None
    except OSError as failure:
        result = Rejected(FailureCode.IO, str(args.output), type(failure).__name__)
    if isinstance(result, Rejected):
        print(json.dumps(asdict(result)), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
