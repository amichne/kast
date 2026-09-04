#!/usr/bin/env python3
"""Cold staged-runtime diagnostic for topology compiler-identity round trips."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
from typing import Any, Mapping, Sequence


def load_enterprise_acceptance():
    module_path = Path(__file__).with_name("enterprise_acceptance.py")
    specification = importlib.util.spec_from_file_location(
        "topology_identity_enterprise_acceptance",
        module_path,
    )
    if specification is None or specification.loader is None:
        raise RuntimeError("enterprise acceptance support is unavailable")
    module = importlib.util.module_from_spec(specification)
    sys.modules[specification.name] = module
    specification.loader.exec_module(module)
    return module


enterprise_acceptance = load_enterprise_acceptance()

ATTRIBUTE_PREFIX = "io.github.amichne.kast."
MISMATCH_EVENT = "kast.topology.identity.mismatch"
CANDIDATE_SPAN = "kast.topology.candidates.enumeration"
SEMANTIC_PROJECT_STORE_PREFIX = "intellij-project-"


@dataclass(frozen=True)
class HostileIdeaConfiguration:
    file_name: str
    risk: str
    payload: bytes


HOSTILE_IDEA_CONFIGURATIONS = (
    HostileIdeaConfiguration(
        "gradle.xml",
        "wrong-linked-project-root-and-gradle-jvm",
        b'''<project version="4">
  <component name="GradleSettings">
    <option name="linkedExternalProjectsSettings">
      <GradleProjectSettings>
        <option name="externalProjectPath" value="/kast-hostile/not-the-workspace" />
        <option name="gradleJvm" value="kast-hostile-missing-jvm" />
      </GradleProjectSettings>
    </option>
  </component>
</project>
''',
    ),
    HostileIdeaConfiguration(
        "compiler.xml",
        "invalid-jps-bytecode-target",
        b'''<project version="4">
  <component name="CompilerConfiguration">
    <bytecodeTargetLevel target="999" />
  </component>
</project>
''',
    ),
    HostileIdeaConfiguration(
        "kotlinc.xml",
        "unavailable-kotlin-jps-plugin-version",
        b'''<project version="4">
  <component name="KotlinJpsPluginSettings">
    <option name="version" value="0.0-kast-hostile" />
  </component>
</project>
''',
    ),
    HostileIdeaConfiguration(
        "misc.xml",
        "invalid-language-level-and-project-jdk",
        b'''<project version="4">
  <component name="ProjectRootManager" version="2" languageLevel="JDK_999"
             project-jdk-name="kast-hostile-missing-jdk" project-jdk-type="JavaSDK" />
</project>
''',
    ),
    HostileIdeaConfiguration(
        "workspace.xml",
        "disabled-external-system-auto-reload",
        b'''<project version="4">
  <component name="ExternalSystemAutoImport">
    <option name="autoReloadType" value="NONE" />
  </component>
</project>
''',
    ),
)


class DiagnosticEvidenceError(ValueError):
    """Raised when trace evidence cannot prove one closed diagnostic result."""


@dataclass(frozen=True)
class WorkspaceIdeaIsolationEvidence:
    workspace: Path
    snapshot: tuple[tuple[str, str, int], ...]

    def assert_unchanged(self) -> None:
        if workspace_idea_snapshot(self.workspace) != self.snapshot:
            raise DiagnosticEvidenceError("workspace .idea bytes or entries changed")

    def document(self) -> dict[str, object]:
        identities = {
            relative: (digest, size) for relative, digest, size in self.snapshot
        }
        return {
            "authority": "ignored-generated-state",
            "workspaceBytes": "unchanged",
            "files": [
                {
                    "path": f".idea/{configuration.file_name}",
                    "risk": configuration.risk,
                    "sha256": identities[configuration.file_name][0],
                    "bytes": identities[configuration.file_name][1],
                }
                for configuration in HOSTILE_IDEA_CONFIGURATIONS
            ],
        }


@dataclass(frozen=True)
class CompilerProjection:
    kind: str
    qualified_identity: str
    signature: str
    identity: str

    def document(self) -> dict[str, object]:
        signature = canonical_signature_document(self.signature)
        if signature["qualifiedIdentity"] != self.qualified_identity:
            raise DiagnosticEvidenceError(
                "canonical signature contradicts projection qualified identity"
            )
        return {
            "kind": self.kind,
            "qualifiedIdentity": self.qualified_identity,
            "canonicalSignature": signature,
            "compilerIdentity": self.identity,
        }


@dataclass(frozen=True)
class IdentityMismatch:
    stage: str
    cache_disposition: str
    source_file: str
    source_occurrence: tuple[int, int]
    target_file: str
    target_declaration: tuple[int, int]
    registry: CompilerProjection
    live: CompilerProjection
    qualified_identity_same: bool
    signature_same: bool
    live_symbol_runtime_kind: str
    psi_declaration_runtime_kind: str
    delta: list[str]

    def document(self) -> dict[str, Any]:
        return {
            "stage": self.stage,
            "cacheDisposition": self.cache_disposition,
            "source": {
                "file": self.source_file,
                "occurrence": range_document(self.source_occurrence),
            },
            "target": {
                "file": self.target_file,
                "declaration": range_document(self.target_declaration),
            },
            "registryProjection": self.registry.document(),
            "liveProjection": self.live.document(),
            "qualifiedIdentitySame": self.qualified_identity_same,
            "signatureSame": self.signature_same,
            "liveSymbolRuntimeKind": self.live_symbol_runtime_kind,
            "psiDeclarationRuntimeKind": self.psi_declaration_runtime_kind,
            "delta": self.delta,
        }


@dataclass(frozen=True)
class DiagnosticTrace:
    candidate_count: int
    mismatch: IdentityMismatch | None


@dataclass(frozen=True)
class ProbeExpectation:
    name: str
    query: str
    qualified_identity: str
    relation: str


PROBE_EXPECTATIONS = (
    ProbeExpectation(
        "class-reference",
        "ClassReferenceTarget",
        "diagnostic.identity.ClassReferenceTarget",
        "references",
    ),
    ProbeExpectation(
        "implicit-primary-constructor",
        "implicitPrimaryConstructorProbe",
        "diagnostic.identity.implicitPrimaryConstructorProbe",
        "callees",
    ),
    ProbeExpectation(
        "explicit-primary-constructor",
        "explicitPrimaryConstructorProbe",
        "diagnostic.identity.explicitPrimaryConstructorProbe",
        "callees",
    ),
    ProbeExpectation(
        "secondary-constructor",
        "secondaryConstructorProbe",
        "diagnostic.identity.secondaryConstructorProbe",
        "callees",
    ),
    ProbeExpectation(
        "generic-function",
        "genericFunctionProbe",
        "diagnostic.identity.genericFunctionProbe",
        "callees",
    ),
    ProbeExpectation(
        "extension-function",
        "extensionFunctionProbe",
        "diagnostic.identity.extensionFunctionProbe",
        "callees",
    ),
    ProbeExpectation(
        "property-reference",
        "propertyTarget",
        "diagnostic.identity.propertyTarget",
        "references",
    ),
    ProbeExpectation(
        "extension-property",
        "extensionPropertyTarget",
        "diagnostic.identity.extensionPropertyTarget",
        "references",
    ),
    ProbeExpectation(
        "direct-override",
        "overrideTarget",
        "diagnostic.identity.OverrideParent.overrideTarget",
        "overrides",
    ),
    ProbeExpectation(
        "type-alias-reference",
        "TypeAliasTarget",
        "diagnostic.identity.TypeAliasTarget",
        "type-uses",
    ),
)


def range_document(value: tuple[int, int]) -> dict[str, int]:
    return {"start": value[0], "end": value[1]}


def canonical_signature_document(raw: str) -> dict[str, object]:
    fields = decode_canonical_fields(raw)
    cursor = CanonicalFieldCursor(fields)
    version = cursor.next()
    if version != "canonical-signature-v1":
        raise DiagnosticEvidenceError("unsupported canonical signature version")
    kind = cursor.next()
    qualified_identity = cursor.next_non_empty("qualified identity")
    document: dict[str, object] = {
        "encoding": raw,
        "version": version,
        "kind": kind,
        "qualifiedIdentity": qualified_identity,
    }
    if kind == "function":
        document.update(
            {
                "receiver": cursor.receiver(),
                "contextReceivers": cursor.values("context receivers"),
                "valueParameters": cursor.values("value parameters"),
                "typeParameterCount": cursor.non_negative_integer("type parameter count"),
            }
        )
    elif kind == "property-v2":
        document.update(
            {
                "receiver": cursor.receiver(),
                "contextReceivers": cursor.values("context receivers"),
                "returnType": cursor.next_non_empty("return type"),
            }
        )
    elif kind not in {"type-alias", "class-like"}:
        raise DiagnosticEvidenceError("unsupported canonical signature kind")
    cursor.require_exhausted()
    return document


def decode_canonical_fields(raw: str) -> list[str]:
    encoded = raw.encode("utf-8")
    fields: list[str] = []
    offset = 0
    while offset < len(encoded):
        separator = encoded.find(b":", offset)
        if separator < 0:
            raise DiagnosticEvidenceError("canonical signature field has no length separator")
        length_bytes = encoded[offset:separator]
        if not length_bytes or any(value < ord("0") or value > ord("9") for value in length_bytes):
            raise DiagnosticEvidenceError("canonical signature field length is invalid")
        length = int(length_bytes.decode("ascii"))
        if length_bytes != str(length).encode("ascii"):
            raise DiagnosticEvidenceError("canonical signature field length is not canonical")
        offset = separator + 1
        end = offset + length
        if end > len(encoded):
            raise DiagnosticEvidenceError("canonical signature field is truncated")
        try:
            field = encoded[offset:end].decode("utf-8")
        except UnicodeDecodeError as error:
            raise DiagnosticEvidenceError("canonical signature field is not UTF-8") from error
        fields.append(field)
        offset = end
    return fields


class CanonicalFieldCursor:
    def __init__(self, fields: Sequence[str]) -> None:
        self._fields = fields
        self._index = 0

    def next(self) -> str | None:
        if self._index == len(self._fields):
            return None
        value = self._fields[self._index]
        self._index += 1
        return value

    def next_non_empty(self, name: str) -> str:
        value = self.next()
        if value is None or not value:
            raise DiagnosticEvidenceError(f"canonical signature {name} is missing")
        return value

    def non_negative_integer(self, name: str) -> int:
        raw = self.next()
        if raw is None or not raw.isascii() or not raw.isdecimal():
            raise DiagnosticEvidenceError(f"canonical signature {name} is invalid")
        value = int(raw)
        if raw != str(value):
            raise DiagnosticEvidenceError(f"canonical signature {name} is not canonical")
        return value

    def values(self, name: str) -> list[str]:
        count = self.non_negative_integer(f"{name} count")
        return [self.next_non_empty(name) for _ in range(count)]

    def receiver(self) -> dict[str, str]:
        state = self.next()
        if state == "receiver-absent":
            return {"state": "absent"}
        if state == "receiver-present":
            return {"state": "present", "type": self.next_non_empty("receiver type")}
        raise DiagnosticEvidenceError("canonical signature receiver state is invalid")

    def require_exhausted(self) -> None:
        if self._index != len(self._fields):
            raise DiagnosticEvidenceError("canonical signature has trailing fields")


def decode_any_value(document: object) -> object:
    if not isinstance(document, dict):
        raise DiagnosticEvidenceError("OTLP attribute value must be an object")
    kinds = [
        name
        for name in ("stringValue", "intValue", "boolValue", "arrayValue")
        if name in document
    ]
    if len(kinds) != 1:
        raise DiagnosticEvidenceError("OTLP attribute value must have one supported kind")
    kind = kinds[0]
    value = document[kind]
    if kind == "stringValue":
        if not isinstance(value, str):
            raise DiagnosticEvidenceError("OTLP string value is not text")
        return value
    if kind == "intValue":
        if isinstance(value, bool) or not isinstance(value, (int, str)):
            raise DiagnosticEvidenceError("OTLP integer value has an invalid representation")
        try:
            return int(value)
        except ValueError as error:
            raise DiagnosticEvidenceError("OTLP integer value is not numeric") from error
    if kind == "boolValue":
        if not isinstance(value, bool):
            raise DiagnosticEvidenceError("OTLP boolean value is not Boolean")
        return value
    if not isinstance(value, dict) or not isinstance(value.get("values"), list):
        raise DiagnosticEvidenceError("OTLP array value has an invalid representation")
    return [decode_any_value(item) for item in value["values"]]


def decode_attributes(raw: object) -> dict[str, object]:
    if raw is None:
        return {}
    if not isinstance(raw, list):
        raise DiagnosticEvidenceError("OTLP attributes must be a list")
    decoded: dict[str, object] = {}
    for attribute in raw:
        if not isinstance(attribute, dict):
            raise DiagnosticEvidenceError("OTLP attribute must be an object")
        key = attribute.get("key")
        if not isinstance(key, str) or not key:
            raise DiagnosticEvidenceError("OTLP attribute key must be non-empty text")
        if key in decoded:
            raise DiagnosticEvidenceError(f"duplicate OTLP attribute: {key}")
        decoded[key] = decode_any_value(attribute.get("value"))
    return decoded


def required_text(attributes: Mapping[str, object], suffix: str) -> str:
    key = ATTRIBUTE_PREFIX + suffix
    value = attributes.get(key)
    if not isinstance(value, str) or not value:
        raise DiagnosticEvidenceError(f"missing text attribute: {key}")
    return value


def required_integer(attributes: Mapping[str, object], suffix: str) -> int:
    key = ATTRIBUTE_PREFIX + suffix
    value = attributes.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise DiagnosticEvidenceError(f"missing non-negative integer attribute: {key}")
    return value


def required_boolean(attributes: Mapping[str, object], suffix: str) -> bool:
    key = ATTRIBUTE_PREFIX + suffix
    value = attributes.get(key)
    if not isinstance(value, bool):
        raise DiagnosticEvidenceError(f"missing Boolean attribute: {key}")
    return value


def required_delta(attributes: Mapping[str, object]) -> list[str]:
    key = ATTRIBUTE_PREFIX + "projection.delta"
    value = attributes.get(key)
    if (
        not isinstance(value, list)
        or not value
        or any(not isinstance(item, str) or not item for item in value)
        or len(set(value)) != len(value)
    ):
        raise DiagnosticEvidenceError(f"missing non-empty unique delta attribute: {key}")
    return list(value)


def exact_range(
    attributes: Mapping[str, object],
    start_suffix: str,
    end_suffix: str,
) -> tuple[int, int]:
    start = required_integer(attributes, start_suffix)
    end = required_integer(attributes, end_suffix)
    if start >= end:
        raise DiagnosticEvidenceError("diagnostic source range must be non-empty and ordered")
    return start, end


def projection(attributes: Mapping[str, object], side: str) -> CompilerProjection:
    return CompilerProjection(
        kind=required_text(attributes, f"{side}.symbol.kind"),
        qualified_identity=required_text(attributes, f"{side}.qualified.identity"),
        signature=required_text(attributes, f"{side}.signature"),
        identity=required_text(attributes, f"{side}.identity"),
    )


def mismatch_from_attributes(attributes: Mapping[str, object]) -> IdentityMismatch:
    stage = required_text(attributes, "topology.identity.stage")
    if stage not in {"reference_target", "direct_override"}:
        raise DiagnosticEvidenceError(f"unsupported mismatch stage: {stage}")
    cache_disposition = required_text(attributes, "topology.cache.disposition")
    if cache_disposition not in {"computed", "reused"}:
        raise DiagnosticEvidenceError(
            f"unsupported topology cache disposition: {cache_disposition}"
        )
    registry = projection(attributes, "registry")
    live = projection(attributes, "live")
    qualified_identity_same = required_boolean(attributes, "qualified.identity.same")
    signature_same = required_boolean(attributes, "signature.same")
    if qualified_identity_same != (
        registry.qualified_identity == live.qualified_identity
    ):
        raise DiagnosticEvidenceError("qualified-identity equality flag contradicts projections")
    if signature_same != (registry.signature == live.signature):
        raise DiagnosticEvidenceError("signature equality flag contradicts projections")
    mismatch = IdentityMismatch(
        stage=stage,
        cache_disposition=cache_disposition,
        source_file=required_text(attributes, "source.file"),
        source_occurrence=exact_range(
            attributes,
            "source.occurrence.start",
            "source.occurrence.end",
        ),
        target_file=required_text(attributes, "target.file"),
        target_declaration=exact_range(
            attributes,
            "target.declaration.start",
            "target.declaration.end",
        ),
        registry=registry,
        live=live,
        qualified_identity_same=qualified_identity_same,
        signature_same=signature_same,
        live_symbol_runtime_kind=required_text(attributes, "live.symbol.runtime.kind"),
        psi_declaration_runtime_kind=required_text(
            attributes,
            "psi.declaration.runtime.kind",
        ),
        delta=required_delta(attributes),
    )
    expected_delta = projection_delta(registry, live)
    if mismatch.delta != expected_delta:
        raise DiagnosticEvidenceError(
            "projection delta contradicts retained compiler projections"
        )
    return mismatch


def projection_delta(
    registry: CompilerProjection,
    live: CompilerProjection,
) -> list[str]:
    if registry.identity == live.identity:
        raise DiagnosticEvidenceError("mismatch event contains matching compiler identities")
    components: list[str] = []
    if registry.kind != live.kind:
        components.append("kind")
    if registry.qualified_identity != live.qualified_identity:
        components.append("qualified_identity")
    registry_signature = canonical_signature_document(registry.signature)
    live_signature = canonical_signature_document(live.signature)
    if (
        registry_signature["qualifiedIdentity"] != registry.qualified_identity
        or live_signature["qualifiedIdentity"] != live.qualified_identity
    ):
        raise DiagnosticEvidenceError(
            "canonical signature contradicts projection qualified identity"
        )
    if registry_signature["kind"] != live_signature["kind"]:
        components.append("signature_kind")
    elif registry_signature["kind"] == "function":
        compare_signature_field(components, registry_signature, live_signature, "receiver")
        compare_signature_field(
            components,
            registry_signature,
            live_signature,
            "contextReceivers",
            "context_receivers",
        )
        compare_signature_field(
            components,
            registry_signature,
            live_signature,
            "valueParameters",
            "value_parameters",
        )
        compare_signature_field(
            components,
            registry_signature,
            live_signature,
            "typeParameterCount",
            "type_parameter_count",
        )
    elif registry_signature["kind"] == "property-v2":
        compare_signature_field(components, registry_signature, live_signature, "receiver")
        compare_signature_field(
            components,
            registry_signature,
            live_signature,
            "contextReceivers",
            "context_receivers",
        )
        compare_signature_field(
            components,
            registry_signature,
            live_signature,
            "returnType",
            "return_type",
        )
    if registry.identity != live.identity:
        components.append("identity")
    if not components:
        raise DiagnosticEvidenceError("mismatch event contains equivalent compiler projections")
    return components


def compare_signature_field(
    components: list[str],
    registry: Mapping[str, object],
    live: Mapping[str, object],
    field: str,
    component: str | None = None,
) -> None:
    if registry[field] != live[field]:
        components.append(component or field)


def object_sequence(document: object, name: str) -> Sequence[object]:
    if not isinstance(document, dict):
        raise DiagnosticEvidenceError("OTLP trace node must be an object")
    value = document.get(name, [])
    if not isinstance(value, list):
        raise DiagnosticEvidenceError(f"OTLP {name} must be a list")
    return value


def trace_spans(document: object):
    for resource in object_sequence(document, "resourceSpans"):
        for scope in object_sequence(resource, "scopeSpans"):
            yield from object_sequence(scope, "spans")


def read_diagnostic_trace(path: Path) -> DiagnosticTrace:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise DiagnosticEvidenceError("trace file is unavailable") from error
    candidate_counts: set[int] = set()
    mismatches: list[IdentityMismatch] = []
    for line in lines:
        if not line.strip():
            continue
        try:
            document = json.loads(line)
        except json.JSONDecodeError as error:
            raise DiagnosticEvidenceError("trace file contains malformed JSON") from error
        for span in trace_spans(document):
            if not isinstance(span, dict):
                raise DiagnosticEvidenceError("OTLP span must be an object")
            if span.get("name") == CANDIDATE_SPAN:
                attributes = decode_attributes(span.get("attributes"))
                value = attributes.get(ATTRIBUTE_PREFIX + "file.count")
                if isinstance(value, bool) or not isinstance(value, int) or value < 1:
                    raise DiagnosticEvidenceError("candidate count is missing or invalid")
                candidate_counts.add(value)
            for event in object_sequence(span, "events"):
                if not isinstance(event, dict):
                    raise DiagnosticEvidenceError("OTLP span event must be an object")
                if event.get("name") == MISMATCH_EVENT:
                    mismatches.append(
                        mismatch_from_attributes(decode_attributes(event.get("attributes")))
                    )
    if len(candidate_counts) != 1:
        raise DiagnosticEvidenceError("trace must contain one exact candidate count")
    if len(mismatches) > 1:
        raise DiagnosticEvidenceError("trace must contain exactly one mismatch event")
    mismatch = mismatches[0] if mismatches else None
    if mismatch is not None and mismatch.cache_disposition != "computed":
        raise DiagnosticEvidenceError("cold diagnostic mismatch must be computed")
    return DiagnosticTrace(next(iter(candidate_counts)), mismatch)


def classify_mismatch(mismatch: IdentityMismatch) -> str:
    if mismatch.registry.kind != mismatch.live.kind:
        return "semantic-location-key-collision"
    if mismatch.registry.qualified_identity != mismatch.live.qualified_identity:
        return "target-mapping-or-qualified-identity-projection-defect"
    if mismatch.registry.signature != mismatch.live.signature:
        return "canonical-signature-projection-defect"
    if mismatch.registry.identity != mismatch.live.identity:
        return "compiler-identity-encoding-defect"
    raise DiagnosticEvidenceError("mismatch event contains no classifiable projection difference")


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--product-root", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--runtime-archive", type=Path, required=True)
    parser.add_argument("--idea-home", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--maximum-startup-seconds", type=int, default=240)
    parser.add_argument("--maximum-operation-seconds", type=int, default=60)
    return parser.parse_args()


def wait_for_trace(
    trace_path: Path,
    expect_mismatch: bool,
    timeout_seconds: int,
) -> DiagnosticTrace:
    deadline = time.monotonic() + timeout_seconds
    last_failure: DiagnosticEvidenceError | None = None
    while time.monotonic() < deadline:
        if trace_path.is_file():
            try:
                evidence = read_diagnostic_trace(trace_path)
                if not expect_mismatch or evidence.mismatch is not None:
                    return evidence
            except DiagnosticEvidenceError as error:
                last_failure = error
        time.sleep(0.1)
    if last_failure is not None:
        raise DiagnosticEvidenceError(
            f"cold runtime trace did not stabilize: {last_failure}"
        ) from last_failure
    raise DiagnosticEvidenceError("cold runtime trace did not become available")


def validate_inputs(args: argparse.Namespace) -> tuple[Path, Path, Path, Path]:
    executable = args.product_root.resolve() / "bin" / "kast"
    fixture = args.fixture.resolve()
    runtime_archive = args.runtime_archive.resolve()
    idea_home = args.idea_home.resolve()
    if not executable.is_file():
        raise DiagnosticEvidenceError("staged Kast executable is unavailable")
    if not fixture.is_dir():
        raise DiagnosticEvidenceError("topology identity fixture is unavailable")
    if not runtime_archive.is_file():
        raise DiagnosticEvidenceError("semantic runtime archive is unavailable")
    product_files = (
        idea_home / "product-info.json",
        idea_home / "Resources" / "product-info.json",
    )
    if not any(candidate.is_file() for candidate in product_files):
        raise DiagnosticEvidenceError("IDEA home is unavailable")
    return executable, fixture, runtime_archive, idea_home


def install_gradle_wrapper(workspace: Path) -> None:
    target = workspace / "gradle" / "wrapper" / "gradle-wrapper.jar"
    if target.is_file():
        return
    source = Path(__file__).resolve().parents[1] / "gradle" / "wrapper" / "gradle-wrapper.jar"
    if not source.is_file():
        raise DiagnosticEvidenceError("repository Gradle wrapper is unavailable")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def workspace_idea_snapshot(workspace: Path) -> tuple[tuple[str, str, int], ...]:
    idea = workspace / ".idea"
    if not idea.is_dir() or idea.is_symlink():
        raise DiagnosticEvidenceError("workspace .idea directory is unavailable or linked")
    observations: list[tuple[str, str, int]] = []
    try:
        entries = sorted(idea.rglob("*"), key=lambda entry: entry.relative_to(idea).as_posix())
        for entry in entries:
            relative = entry.relative_to(idea).as_posix()
            if entry.is_symlink():
                raise DiagnosticEvidenceError("workspace .idea contains a symbolic link")
            if entry.is_dir():
                observations.append((relative, "directory", 0))
                continue
            if not entry.is_file():
                raise DiagnosticEvidenceError("workspace .idea contains an unsupported entry")
            content = entry.read_bytes()
            observations.append(
                (relative, hashlib.sha256(content).hexdigest(), len(content))
            )
    except OSError as error:
        raise DiagnosticEvidenceError("workspace .idea cannot be observed") from error
    return tuple(observations)


def install_hostile_workspace_idea(workspace: Path) -> WorkspaceIdeaIsolationEvidence:
    idea = workspace / ".idea"
    try:
        idea.mkdir()
        for configuration in HOSTILE_IDEA_CONFIGURATIONS:
            (idea / configuration.file_name).write_bytes(configuration.payload)
    except OSError as error:
        raise DiagnosticEvidenceError("hostile workspace .idea cannot be installed") from error
    evidence = WorkspaceIdeaIsolationEvidence(workspace, workspace_idea_snapshot(workspace))
    expected = tuple(sorted(configuration.file_name for configuration in HOSTILE_IDEA_CONFIGURATIONS))
    observed = tuple(relative for relative, _, _ in evidence.snapshot)
    if observed != expected:
        raise DiagnosticEvidenceError("hostile workspace .idea file set is incomplete")
    return evidence


def physical_runtime_socket_directory(logical_runtime_directory: Path) -> Path:
    namespace = hashlib.sha256(str(logical_runtime_directory).encode("utf-8")).hexdigest()[:24]
    return Path("/tmp") / f"kast-runtime-{namespace}"


def semantic_project_stores(runtime_socket_directory: Path) -> list[Path]:
    if not runtime_socket_directory.exists():
        return []
    try:
        return sorted(
            (
                candidate.resolve(strict=True)
                for candidate in runtime_socket_directory.rglob(
                    f"{SEMANTIC_PROJECT_STORE_PREFIX}*"
                )
                if candidate.is_dir() and not candidate.is_symlink()
            ),
            key=str,
        )
    except OSError as error:
        raise DiagnosticEvidenceError("semantic project store cannot be observed") from error


def observe_semantic_project_store(
    runtime_socket_directory: Path,
    workspace: Path,
) -> dict[str, object]:
    stores = semantic_project_stores(runtime_socket_directory)
    if len(stores) != 1:
        raise DiagnosticEvidenceError(
            f"cold runtime must own one fresh semantic project store; observed {len(stores)}"
        )
    store = stores[0]
    try:
        store.relative_to(runtime_socket_directory.resolve(strict=True))
    except (OSError, ValueError) as error:
        raise DiagnosticEvidenceError("semantic project store escaped runtime state") from error
    try:
        store.relative_to(workspace.resolve(strict=True))
    except ValueError:
        pass
    except OSError as error:
        raise DiagnosticEvidenceError("workspace root cannot be observed") from error
    else:
        raise DiagnosticEvidenceError("semantic project store overlaps the workspace")
    idea = store / ".idea"
    generated_files = (
        sorted(
            entry.relative_to(idea).as_posix()
            for entry in idea.rglob("*")
            if entry.is_file() and not entry.is_symlink()
        )
        if idea.is_dir() and not idea.is_symlink()
        else []
    )
    return {
        "authority": "runtime-owned",
        "fresh": True,
        "workspaceDisjoint": True,
        "generatedFiles": generated_files,
        "gradlePolicyReadback": "required-before-bootstrap-ready",
        "offlineWork": False,
        "autoReload": "all",
    }


def acceptance_command(acceptance, *arguments: str, timeout: int) -> dict[str, Any]:
    try:
        return acceptance.command(*arguments, timeout=timeout)
    except SystemExit as error:
        raise DiagnosticEvidenceError(str(error)) from error


def exact_probe_selector(acceptance, probe: ProbeExpectation, timeout: int) -> str:
    discovery = acceptance_command(
        acceptance,
        "symbol",
        "discover",
        "--query",
        probe.query,
        "--limit",
        "25",
        timeout=timeout,
    )
    candidates = enterprise_acceptance.declaration_candidates(discovery)
    selectors: list[str] = []
    observed: list[tuple[object, object]] = []
    for candidate in candidates:
        inspection = acceptance_command(
            acceptance,
            "symbol",
            "inspect",
            "--candidate",
            candidate,
            timeout=timeout,
        )
        symbol = inspection.get("symbol")
        if isinstance(symbol, dict):
            observed.append((symbol.get("kind"), symbol.get("qualifiedIdentity")))
        if (
            inspection.get("status") == "complete"
            and isinstance(symbol, dict)
            and symbol.get("qualifiedIdentity") == probe.qualified_identity
            and isinstance(symbol.get("selector"), str)
        ):
            selectors.append(symbol["selector"])
    if len(selectors) != 1:
        raise DiagnosticEvidenceError(
            f"probe {probe.name} did not resolve to one exact symbol: {observed}"
        )
    return selectors[0]


def prove_probe_edges(acceptance, timeout: int) -> list[str]:
    matched: list[str] = []
    for probe in PROBE_EXPECTATIONS:
        selector = exact_probe_selector(acceptance, probe, timeout)
        traversal = acceptance_command(
            acceptance,
            "traversal",
            "run",
            "--selector",
            selector,
            "--relation",
            probe.relation,
            "--maximum-depth",
            "1",
            "--maximum-results",
            "25",
            timeout=timeout,
        )
        graph = traversal.get("graph")
        edges = graph.get("edges") if isinstance(graph, dict) else None
        if (
            traversal.get("status") not in {"complete", "qualified"}
            or not isinstance(edges, list)
            or not edges
        ):
            raise DiagnosticEvidenceError(
                f"probe {probe.name} produced no topology-backed {probe.relation} edge"
            )
        matched.append(probe.name)
    return matched


def run_cold_diagnostic(args: argparse.Namespace) -> dict[str, Any]:
    executable, fixture, runtime_archive, idea_home = validate_inputs(args)
    expected_candidate_count = len(list(fixture.glob("src/main/kotlin/**/*.kt")))
    if expected_candidate_count < 1:
        raise DiagnosticEvidenceError("topology identity fixture has no Kotlin candidates")
    bounds = {
        "maximumOutputBytes": 65536,
        "maximumOperationSeconds": args.maximum_operation_seconds,
        "maximumStartupSeconds": args.maximum_startup_seconds,
        "maximumReconciliationSeconds": args.maximum_operation_seconds,
    }
    ambient = enterprise_acceptance.AmbientBrokerSnapshot.capture()
    with tempfile.TemporaryDirectory(prefix="kti.", dir="/tmp") as host_text:
        host = enterprise_acceptance.IsolatedAcceptanceHost.create(
            Path(host_text),
            runtime_archive,
        )
        shutil.copytree(
            fixture,
            host.workspace,
            dirs_exist_ok=True,
            ignore=shutil.ignore_patterns(".gradle", ".idea", "build"),
        )
        install_gradle_wrapper(host.workspace)
        enterprise_acceptance.prepare_workspace_fixture(host.workspace)
        idea_isolation = install_hostile_workspace_idea(host.workspace)
        runtime_socket_directory = physical_runtime_socket_directory(
            host.runtime / "endpoints"
        )
        if semantic_project_stores(runtime_socket_directory):
            raise DiagnosticEvidenceError("isolated host contains a stale semantic project store")
        previous_idea_home = os.environ.get("KAST_ACCEPTANCE_IDEA_HOME")
        os.environ["KAST_ACCEPTANCE_IDEA_HOME"] = str(idea_home)
        try:
            acceptance = enterprise_acceptance.Acceptance(executable, host, bounds)
        finally:
            if previous_idea_home is None:
                os.environ.pop("KAST_ACCEPTANCE_IDEA_HOME", None)
            else:
                os.environ["KAST_ACCEPTANCE_IDEA_HOME"] = previous_idea_home
        try:
            startup = acceptance_command(
                acceptance,
                "start",
                "--idea-home",
                str(idea_home),
                timeout=args.maximum_startup_seconds,
            )
            if startup.get("status") != "complete" or startup.get("runtime") != "running":
                raise DiagnosticEvidenceError("cold semantic runtime did not start")
            idea_isolation.assert_unchanged()
            semantic_project_store = observe_semantic_project_store(
                runtime_socket_directory,
                host.workspace,
            )
            synchronized = acceptance_command(
                acceptance,
                "index",
                "sync",
                timeout=args.maximum_operation_seconds,
            )
            if synchronized.get("status") != "complete":
                raise DiagnosticEvidenceError("cold diagnostic index synchronization failed")
            idea_isolation.assert_unchanged()
            inspection = acceptance_command(
                acceptance,
                "product",
                "inspect",
                timeout=args.maximum_operation_seconds,
            )
            workspace_inspection = inspection.get("workspace")
            telemetry = (
                workspace_inspection.get("telemetry")
                if isinstance(workspace_inspection, dict)
                else None
            )
            trace_path_text = (
                telemetry.get("traceFilePath") if isinstance(telemetry, dict) else None
            )
            if (
                not isinstance(trace_path_text, str)
                or not Path(trace_path_text).is_absolute()
                or telemetry.get("state") != "enabled"
            ):
                raise DiagnosticEvidenceError(
                    "product inspection omitted the exact telemetry destination"
                )
            trace_path = Path(trace_path_text)
            topology = acceptance_command(
                acceptance,
                "topology",
                "build",
                timeout=args.maximum_startup_seconds,
            )
            topology_complete = topology.get("status") == "complete"
            topology_mismatch = (
                topology.get("status") == "rejected"
                and topology.get("reason") == "extraction-failed"
                and topology.get("failure") == "compiler-identity-mismatch"
            )
            if not topology_complete and not topology_mismatch:
                raise DiagnosticEvidenceError(
                    f"cold topology build returned an unrelated outcome: {topology}"
                )
            idea_isolation.assert_unchanged()
            trace = wait_for_trace(
                trace_path,
                topology_mismatch,
                args.maximum_operation_seconds,
            )
            if trace.candidate_count != expected_candidate_count:
                raise DiagnosticEvidenceError(
                    "cold runtime candidate count contradicts the copied fixture"
                )
            if topology_complete and trace.mismatch is not None:
                raise DiagnosticEvidenceError(
                    "completed topology build emitted contradictory mismatch evidence"
                )
            if topology_mismatch and trace.mismatch is None:
                raise DiagnosticEvidenceError(
                    "compiler-identity rejection omitted its mismatch event"
                )
            if trace.mismatch is None:
                matched_probes = prove_probe_edges(
                    acceptance,
                    args.maximum_operation_seconds,
                )
                result: dict[str, Any] = {
                    "status": "complete",
                    "runtimeInstance": "new-isolated",
                    "candidateCount": trace.candidate_count,
                    "topologyOutcome": "published",
                    "diagnosis": "all-probe-projections-matched",
                    "matchedProbes": matched_probes,
                }
                idea_isolation.assert_unchanged()
            else:
                mismatch = trace.mismatch
                if topology.get("file") != mismatch.source_file:
                    raise DiagnosticEvidenceError(
                        "public rejection file contradicts mismatch source evidence"
                    )
                result = {
                    "status": "complete",
                    "runtimeInstance": "new-isolated",
                    "candidateCount": trace.candidate_count,
                    "topologyOutcome": "compiler-identity-mismatch",
                    "diagnosis": classify_mismatch(mismatch),
                    "mismatch": mismatch.document(),
                }
            semantic_project_store = observe_semantic_project_store(
                runtime_socket_directory,
                host.workspace,
            )
            result["workspaceIdeaIsolation"] = idea_isolation.document()
            result["semanticProjectStore"] = semantic_project_store
        finally:
            try:
                stopped = acceptance_command(
                    acceptance,
                    "stop",
                    timeout=args.maximum_operation_seconds,
                )
                if stopped.get("status") != "complete" or stopped.get("runtime") != "stopped":
                    raise DiagnosticEvidenceError("diagnostic runtime did not stop cleanly")
                idea_isolation.assert_unchanged()
            finally:
                try:
                    host.retire_broker(args.maximum_operation_seconds)
                finally:
                    try:
                        host.assert_confined()
                    finally:
                        ambient.assert_unchanged()
    return result


def write_report(path: Path, result: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(result, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    mismatch_path = path.with_name("topology-identity-mismatch.json")
    if result.get("topologyOutcome") == "compiler-identity-mismatch":
        mismatch_path.write_text(
            json.dumps(result, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    else:
        mismatch_path.unlink(missing_ok=True)


def main() -> int:
    args = parse_arguments()
    try:
        result = run_cold_diagnostic(args)
        write_report(args.report.resolve(), result)
    except DiagnosticEvidenceError as error:
        print(f"topology-identity-diagnostic: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
