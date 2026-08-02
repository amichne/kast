#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SOURCE_SCHEMA_VERSION = 2
MANIFEST_SCHEMA_VERSION = 2
RELEASE_VERSION_TEMPLATE = "{releaseVersion}"
RELEASE_TAG_PATTERN = re.compile(r"v[0-9A-Za-z][0-9A-Za-z._-]*")
VERSION_PATTERN = re.compile(r"[0-9A-Za-z][0-9A-Za-z._-]*")
GIT_SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
BUILD_PATTERN = re.compile(r"[0-9]+(?:\.[0-9]+)*")
UNTIL_BUILD_PATTERN = re.compile(r"[0-9]+(?:\.[0-9]+)*(?:\.\*)?")

READ_CAPABILITIES = frozenset(
    (
        "RESOLVE_SYMBOL",
        "FIND_REFERENCES",
        "CALL_HIERARCHY",
        "TYPE_HIERARCHY",
        "SEMANTIC_INSERTION_POINT",
        "SEMANTIC_GRAPH",
        "DIAGNOSTICS",
        "FILE_OUTLINE",
        "WORKSPACE_SYMBOL_SEARCH",
        "WORKSPACE_SEARCH",
        "WORKSPACE_FILES",
        "IMPLEMENTATIONS",
        "CODE_ACTIONS",
        "COMPLETIONS",
    )
)
MUTATION_CAPABILITIES = frozenset(
    (
        "RENAME",
        "APPLY_EDITS",
        "FILE_OPERATIONS",
        "OPTIMIZE_IMPORTS",
        "REFRESH_WORKSPACE",
    )
)
def fail(message: str) -> None:
    print(f"error: {message}", file=sys.stderr)
    raise SystemExit(1)


def require_object(raw: object, *, field: str, keys: frozenset[str]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        fail(f"{field} must be a JSON object")
    payload: dict[str, Any] = raw
    actual = frozenset(payload)
    if actual != keys:
        fail(
            f"{field} keys must be exactly {sorted(keys)}; "
            f"missing={sorted(keys - actual)} unexpected={sorted(actual - keys)}"
        )
    return payload


def read_json(path: Path) -> object:
    if not path.is_file():
        fail(f"runtime compatibility source does not exist: {path}")
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=reject_duplicate_object_keys,
        )
    except (OSError, UnicodeDecodeError, ValueError) as error:
        fail(f"runtime compatibility source is not valid UTF-8 JSON: {error}")


def reject_duplicate_object_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate object key: {key}")
        value[key] = item
    return value


def require_positive_revision(raw: object, *, field: str) -> int:
    if not isinstance(raw, int) or isinstance(raw, bool) or raw <= 0:
        fail(f"{field} must be a positive integer")
    return raw


def require_version(raw: object, *, field: str, allow_template: bool) -> str:
    if raw == RELEASE_VERSION_TEMPLATE and allow_template:
        return RELEASE_VERSION_TEMPLATE
    if not isinstance(raw, str) or VERSION_PATTERN.fullmatch(raw) is None:
        fail(f"{field} must be a release version")
    return raw


@dataclass(frozen=True, order=True)
class Capability:
    kind: str
    name: str

    @classmethod
    def parse(cls, raw: object, *, field: str) -> "Capability":
        payload = require_object(
            raw,
            field=field,
            keys=frozenset(("kind", "name")),
        )
        kind = payload["kind"]
        name = payload["name"]
        if kind == "READ":
            allowed = READ_CAPABILITIES
        elif kind == "MUTATION":
            allowed = MUTATION_CAPABILITIES
        else:
            fail(f"{field}.kind must be READ or MUTATION")
        if not isinstance(name, str) or name not in allowed:
            fail(f"{field}.name is not a known {kind} capability: {name}")
        return cls(kind=kind, name=name)

    def value(self) -> dict[str, str]:
        return {"kind": self.kind, "name": self.name}


def parse_capabilities(raw: object, *, field: str) -> tuple[Capability, ...]:
    if not isinstance(raw, list):
        fail(f"{field} must be a JSON array")
    capabilities = tuple(Capability.parse(value, field=f"{field}[{index}]") for index, value in enumerate(raw))
    if len(set(capabilities)) != len(capabilities):
        fail(f"{field} must not contain duplicates")
    if tuple(sorted(capabilities, key=lambda value: (value.kind != "READ", value.name))) != capabilities:
        fail(f"{field} must use deterministic kind/name ordering")
    return capabilities


@dataclass(frozen=True)
class RuntimeBuildRange:
    since_build: str
    until_build: str | None

    @classmethod
    def parse(cls, raw: object) -> "RuntimeBuildRange":
        payload = require_object(
            raw,
            field="runtimeBuildRange",
            keys=frozenset(("sinceBuild", "untilBuild")),
        )
        since_build = payload["sinceBuild"]
        until_build = payload["untilBuild"]
        if not isinstance(since_build, str) or BUILD_PATTERN.fullmatch(since_build) is None:
            fail("runtimeBuildRange.sinceBuild must be a numeric runtime build")
        if until_build is not None and (
            not isinstance(until_build, str)
            or UNTIL_BUILD_PATTERN.fullmatch(until_build) is None
        ):
            fail("runtimeBuildRange.untilBuild must be null or a numeric runtime build range")
        result = cls(since_build=since_build, until_build=until_build)
        result.require_ordered()
        return result

    def require_ordered(self) -> None:
        if self.until_build is None:
            return
        since_parts = tuple(int(part) for part in self.since_build.split("."))
        wildcard = self.until_build.endswith(".*")
        raw_until = self.until_build.removesuffix(".*") if wildcard else self.until_build
        until_parts = tuple(int(part) for part in raw_until.split("."))
        if wildcard:
            if until_parts < since_parts[: len(until_parts)]:
                fail("runtimeBuildRange.untilBuild is below sinceBuild")
            return
        width = max(len(since_parts), len(until_parts))
        if until_parts + (0,) * (width - len(until_parts)) < since_parts + (0,) * (width - len(since_parts)):
            fail("runtimeBuildRange.untilBuild is below sinceBuild")

    def value(self) -> dict[str, str | None]:
        return {"sinceBuild": self.since_build, "untilBuild": self.until_build}


@dataclass(frozen=True)
class RuntimeIdentityTemplate:
    implementation_version: str

    @classmethod
    def parse(
        cls,
        raw: object,
        *,
        field: str,
        allow_template: bool,
    ) -> "RuntimeIdentityTemplate":
        payload = require_object(
            raw,
            field=field,
            keys=frozenset(("implementationVersion",)),
        )
        return cls(
            implementation_version=require_version(
                payload["implementationVersion"],
                field=f"{field}.implementationVersion",
                allow_template=allow_template,
            ),
        )


@dataclass(frozen=True)
class SupportedPair:
    relation: str
    indexer_version: str
    cli_version: str
    protocol_revision: int
    workspace_metadata_revision: int
    runtime: RuntimeIdentityTemplate
    required_capabilities: tuple[Capability, ...]
    optional_capabilities: tuple[Capability, ...]
    evidence: tuple[str, ...]

    @classmethod
    def parse(
        cls,
        raw: object,
        *,
        index: int,
        repo_root: Path | None,
        release_version: str | None,
    ) -> "SupportedPair":
        field = f"supportedPairs[{index}]"
        payload = require_object(
            raw,
            field=field,
            keys=frozenset(
                (
                    "relation",
                    "indexerVersion",
                    "cliVersion",
                    "protocolRevision",
                    "workspaceMetadataRevision",
                    "runtime",
                    "requiredCapabilities",
                    "optionalCapabilities",
                    "evidence",
                )
            ),
        )
        relation = payload["relation"]
        if relation not in ("same-release", "adjacent-release"):
            fail(f"{field}.relation must be same-release or adjacent-release")
        indexer_version = require_version(
            payload["indexerVersion"],
            field=f"{field}.indexerVersion",
            allow_template=release_version is None,
        )
        cli_version = require_version(
            payload["cliVersion"],
            field=f"{field}.cliVersion",
            allow_template=release_version is None,
        )
        runtime = RuntimeIdentityTemplate.parse(
            payload["runtime"],
            field=f"{field}.runtime",
            allow_template=release_version is None,
        )
        if relation == "same-release":
            expected_version = RELEASE_VERSION_TEMPLATE if release_version is None else release_version
            if (indexer_version, cli_version, runtime.implementation_version) != (expected_version,) * 3:
                fail(f"{field} same-release versions must all equal {expected_version}")
        else:
            if RELEASE_VERSION_TEMPLATE in (
                indexer_version,
                cli_version,
                runtime.implementation_version,
            ):
                fail(f"{field} adjacent-release versions must be explicit")
            if indexer_version == cli_version:
                fail(f"{field} adjacent-release indexer and CLI versions must differ")
            if runtime.implementation_version not in (indexer_version, cli_version):
                fail(f"{field} adjacent runtime version must equal the indexer or CLI version")

        required = parse_capabilities(
            payload["requiredCapabilities"], field=f"{field}.requiredCapabilities"
        )
        optional = parse_capabilities(
            payload["optionalCapabilities"], field=f"{field}.optionalCapabilities"
        )
        if set(required) & set(optional):
            fail(f"{field} required and optional capabilities must be disjoint")
        expected_capabilities = {
            *(Capability("READ", name) for name in READ_CAPABILITIES),
            *(Capability("MUTATION", name) for name in MUTATION_CAPABILITIES),
        }
        if set(required) | set(optional) != expected_capabilities:
            fail(f"{field} must classify every known capability exactly once")

        evidence_raw = payload["evidence"]
        if not isinstance(evidence_raw, list) or not evidence_raw:
            fail(f"{field}.evidence must be a non-empty JSON array")
        if not all(isinstance(value, str) and value for value in evidence_raw):
            fail(f"{field}.evidence entries must be non-empty repository-relative paths")
        evidence = tuple(evidence_raw)
        if len(set(evidence)) != len(evidence) or tuple(sorted(evidence)) != evidence:
            fail(f"{field}.evidence must be unique and sorted")
        for path in evidence:
            candidate = Path(path)
            if candidate.is_absolute() or ".." in candidate.parts:
                fail(f"{field}.evidence must be repository-relative: {path}")
            if repo_root is not None and not (repo_root / candidate).is_file():
                fail(f"{field}.evidence does not name an existing repository file: {path}")

        return cls(
            relation=relation,
            indexer_version=indexer_version,
            cli_version=cli_version,
            protocol_revision=require_positive_revision(
                payload["protocolRevision"], field=f"{field}.protocolRevision"
            ),
            workspace_metadata_revision=require_positive_revision(
                payload["workspaceMetadataRevision"],
                field=f"{field}.workspaceMetadataRevision",
            ),
            runtime=runtime,
            required_capabilities=required,
            optional_capabilities=optional,
            evidence=evidence,
        )

    def key(self) -> tuple[object, ...]:
        return (
            self.indexer_version,
            self.cli_version,
            self.protocol_revision,
            self.workspace_metadata_revision,
            self.runtime,
        )

    def sort_key(self) -> tuple[object, ...]:
        return (
            self.indexer_version,
            self.cli_version,
            self.protocol_revision,
            self.workspace_metadata_revision,
            self.runtime.implementation_version,
        )

    def render(self, release_version: str) -> dict[str, object]:
        def resolve(value: str) -> str:
            return release_version if value == RELEASE_VERSION_TEMPLATE else value

        return {
            "relation": self.relation,
            "indexerVersion": resolve(self.indexer_version),
            "cliVersion": resolve(self.cli_version),
            "protocolRevision": self.protocol_revision,
            "workspaceMetadataRevision": self.workspace_metadata_revision,
            "runtime": {
                "implementationVersion": resolve(self.runtime.implementation_version),
            },
            "requiredCapabilities": [value.value() for value in self.required_capabilities],
            "optionalCapabilities": [value.value() for value in self.optional_capabilities],
            "evidence": list(self.evidence),
        }


@dataclass(frozen=True)
class RuntimeCompatibilitySource:
    runtime_build_range: RuntimeBuildRange
    supported_pairs: tuple[SupportedPair, ...]

    @classmethod
    def load(cls, path: Path) -> "RuntimeCompatibilitySource":
        payload = require_object(
            read_json(path),
            field="runtime compatibility source",
            keys=frozenset(("schemaVersion", "runtimeBuildRange", "supportedPairs")),
        )
        if payload["schemaVersion"] != SOURCE_SCHEMA_VERSION:
            fail(f"runtime compatibility source schemaVersion must be {SOURCE_SCHEMA_VERSION}")
        pairs_raw = payload["supportedPairs"]
        if not isinstance(pairs_raw, list) or not pairs_raw:
            fail("supportedPairs must be a non-empty JSON array")
        repo_root = Path(__file__).resolve().parents[3]
        pairs = tuple(
            SupportedPair.parse(
                raw,
                index=index,
                repo_root=repo_root,
                release_version=None,
            )
            for index, raw in enumerate(pairs_raw)
        )
        if len({pair.key() for pair in pairs}) != len(pairs):
            fail("supportedPairs must not contain duplicate negotiation rows")
        if not any(pair.relation == "same-release" for pair in pairs):
            fail("supportedPairs must contain the tested same-release row")
        return cls(
            runtime_build_range=RuntimeBuildRange.parse(payload["runtimeBuildRange"]),
            supported_pairs=pairs,
        )


@dataclass(frozen=True)
class RuntimeCompatibilityManifest:
    release_tag: str
    release_sha: str
    runtime_build_range: RuntimeBuildRange
    supported_pairs: tuple[SupportedPair, ...]

    @classmethod
    def load(cls, path: Path, *, expected_release_tag: str) -> "RuntimeCompatibilityManifest":
        payload = require_object(
            read_json(path),
            field="runtime compatibility manifest",
            keys=frozenset(
                (
                    "schemaVersion",
                    "releaseTag",
                    "releaseSha",
                    "runtimeBuildRange",
                    "supportedPairs",
                )
            ),
        )
        if payload["schemaVersion"] != MANIFEST_SCHEMA_VERSION:
            fail(f"runtime compatibility manifest schemaVersion must be {MANIFEST_SCHEMA_VERSION}")
        release_tag = payload["releaseTag"]
        if not isinstance(release_tag, str) or require_release_tag(release_tag) != expected_release_tag:
            fail("runtime compatibility manifest releaseTag does not match the expected release tag")
        release_sha = payload["releaseSha"]
        if not isinstance(release_sha, str) or GIT_SHA_PATTERN.fullmatch(release_sha) is None:
            fail("runtime compatibility manifest releaseSha must be 40 lowercase hexadecimal characters")
        pairs_raw = payload["supportedPairs"]
        if not isinstance(pairs_raw, list) or not pairs_raw:
            fail("runtime compatibility manifest supportedPairs must be a non-empty array")
        release_version = release_tag.removeprefix("v")
        pairs = tuple(
            SupportedPair.parse(
                raw,
                index=index,
                repo_root=None,
                release_version=release_version,
            )
            for index, raw in enumerate(pairs_raw)
        )
        if len({pair.key() for pair in pairs}) != len(pairs):
            fail("runtime compatibility manifest supportedPairs contain duplicate negotiation rows")
        if pairs != tuple(sorted(pairs, key=SupportedPair.sort_key)):
            fail("runtime compatibility manifest supportedPairs are not deterministically ordered")
        if not any(pair.relation == "same-release" for pair in pairs):
            fail("runtime compatibility manifest must contain the tested same-release row")
        return cls(
            release_tag=release_tag,
            release_sha=release_sha,
            runtime_build_range=RuntimeBuildRange.parse(payload["runtimeBuildRange"]),
            supported_pairs=pairs,
        )


def require_release_tag(raw: str) -> str:
    if RELEASE_TAG_PATTERN.fullmatch(raw) is None:
        fail("release tag must start with v and contain only tag-safe characters")
    return raw


def render_manifest(
    source: RuntimeCompatibilitySource,
    *,
    release_tag: str,
    release_sha: str,
) -> dict[str, object]:
    require_release_tag(release_tag)
    if GIT_SHA_PATTERN.fullmatch(release_sha) is None:
        fail("release SHA must be 40 lowercase hexadecimal characters")
    release_version = release_tag.removeprefix("v")
    pairs = [pair.render(release_version) for pair in source.supported_pairs]
    pairs.sort(key=rendered_pair_sort_key)
    return {
        "schemaVersion": MANIFEST_SCHEMA_VERSION,
        "releaseTag": release_tag,
        "releaseSha": release_sha,
        "runtimeBuildRange": source.runtime_build_range.value(),
        "supportedPairs": pairs,
    }


def rendered_pair_sort_key(pair: dict[str, object]) -> tuple[object, ...]:
    runtime = pair["runtime"]
    if not isinstance(runtime, dict):
        fail("rendered runtime identity must be an object")
    return (
        str(pair["indexerVersion"]),
        str(pair["cliVersion"]),
        int(pair["protocolRevision"]),
        int(pair["workspaceMetadataRevision"]),
        str(runtime["implementationVersion"]),
    )


def validate_source_command(args: argparse.Namespace) -> None:
    source = RuntimeCompatibilitySource.load(Path(args.source))
    print(f"Validated runtime compatibility source ({len(source.supported_pairs)} row(s))")


def validate_manifest_command(args: argparse.Namespace) -> None:
    manifest = RuntimeCompatibilityManifest.load(
        Path(args.manifest),
        expected_release_tag=args.release_tag,
    )
    print(
        "Validated runtime compatibility manifest "
        f"({len(manifest.supported_pairs)} row(s))"
    )


def render_command(args: argparse.Namespace) -> None:
    source = RuntimeCompatibilitySource.load(Path(args.source))
    output = Path(args.output)
    if output.exists():
        fail(f"output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = render_manifest(
        source,
        release_tag=args.release_tag,
        release_sha=args.release_sha,
    )
    output.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Rendered runtime compatibility manifest for {args.release_tag}")


def runtime_build_range_command(args: argparse.Namespace) -> None:
    value = RuntimeCompatibilitySource.load(Path(args.source)).runtime_build_range.value()[args.field]
    if value is not None:
        print(value)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate and render Kast's typed runtime compatibility matrix."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate-source")
    validate.add_argument("--source", required=True)
    validate.set_defaults(handler=validate_source_command)

    validate_manifest = subparsers.add_parser("validate-manifest")
    validate_manifest.add_argument("--manifest", required=True)
    validate_manifest.add_argument("--release-tag", required=True)
    validate_manifest.set_defaults(handler=validate_manifest_command)

    render = subparsers.add_parser("render")
    render.add_argument("--source", required=True)
    render.add_argument("--release-tag", required=True)
    render.add_argument("--release-sha", required=True)
    render.add_argument("--output", required=True)
    render.set_defaults(handler=render_command)

    runtime_range = subparsers.add_parser("runtime-build-range")
    runtime_range.add_argument("--source", required=True)
    runtime_range.add_argument("--field", choices=("sinceBuild", "untilBuild"), required=True)
    runtime_range.set_defaults(handler=runtime_build_range_command)

    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.handler(args)


if __name__ == "__main__":
    main()
