#!/usr/bin/env python3
"""Opt-in native reproduction. Setup/import and read-only replay are separate commands."""
import argparse
import base64
from collections import Counter
from dataclasses import asdict, dataclass, field, replace
from enum import Enum
import hashlib
import json
import os
import select
from pathlib import Path
import shutil
import subprocess
import time

from run_host_acceptance import wait_for
from native_host_selection import select_native_host, admit_same_host, RejectedNativeHost
from replay_requests import (WorkloadProfile, Workload, NEGATIVE_DECLARATION_NAME,
    KAST_DIRECTORY, KAST_SCOPED_DIRECTORY, comparison_requests, budget as request_budget)
import replay_requests as replay_wire
from retained_pages import (presentation, PresentationComplete, PresentationNext, PresentationRejected,
    PresentationFailure)
from hosted_timing import (OBSERVATION_POLICY, ObservationWindow, JoinedTiming, UnavailableTiming,
    TimingFailure, collect_observations, read_observations, join_timing, load_window, release_observed)

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
FIXTURE = HERE / "semantic-fixture"
FIELDS = ["NAME", "LOCATION", "SIGNATURE"]
# Independent changed-owner requirement. The native script must supply actual
# resources; candidate archive admission separately matches these bytes.
TRY_BRANCH_NATIVE_OWNERS = frozenset({
    'io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter',
    'io.github.amichne.kast.relation.intellij.IntellijValueFlowNative',
    'io.github.amichne.kast.relation.intellij.NativeTryBranchResult',
    'io.github.amichne.kast.relation.intellij.TryBranchResultPosition',
    'io.github.amichne.kast.relation.intellij.IntellijCompilerTypeProofKt',
    'io.github.amichne.kast.relation.intellij.NativeCompilerTypeProof',
    'io.github.amichne.kast.relation.contract.ValueTransferEvidence$NormalBranchResult',
})
LOCAL_IDENTITY_NATIVE_OWNERS = frozenset({
    'io.github.amichne.kast.workspace.intellij.read.IntellijLocalIdentityObservationKt',
    'io.github.amichne.kast.symbol.intellij.IntellijLocalDeclarationAddressProjectionKt',
    'io.github.amichne.kast.symbol.intellij.IntellijLocalDeclarationAnchorsKt',
    'io.github.amichne.kast.symbol.intellij.IntellijLocalCompilerTypeProofKt',
    'io.github.amichne.kast.symbol.intellij.LocalCompilerTypeProof',
    'io.github.amichne.kast.source.intellij.IntellijLocalCompilerTypeProofKt',
    'io.github.amichne.kast.source.intellij.LocalCompilerTypeProof',
    'io.github.amichne.kast.source.intellij.IntellijSourceCompilerProjectionKt',
    'io.github.amichne.kast.source.intellij.SourceLocalOwnerCallableIdentityKt',
    'io.github.amichne.kast.source.intellij.SourceLocalOwnerCallableIdentityObservationKt',
    'io.github.amichne.kast.symbol.contract.LocalDeclarationAddress',
    'io.github.amichne.kast.relation.intellij.IntellijK2RelationProjectionKt',
    'io.github.amichne.kast.relation.intellij.IntellijK2SymbolIdentityKt',
    'io.github.amichne.kast.relation.intellij.IntellijLocalDeclarationAnchorsKt',
    'io.github.amichne.kast.relation.intellij.RelationLocalOwnerCallableIdentityKt',
    'io.github.amichne.kast.relation.intellij.RelationLocalOwnerCallableIdentityObservationKt',
    'io.github.amichne.kast.relation.intellij.IntellijLocalRelationOwnerSignatureKt',
})
TRY_LOCAL_NATIVE_OWNERS = TRY_BRANCH_NATIVE_OWNERS | LOCAL_IDENTITY_NATIVE_OWNERS
NATIVE_COMMON_OWNERS = frozenset({
    'io.github.amichne.kast.workspace.intellij.read.DetachedModelLimits',
    'io.github.amichne.kast.workspace.intellij.read.LiveNamedGradleSourceScopeCapture',
    'io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryExecutorKt',
    'io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadDiagnostics',
    'io.github.amichne.kast.workspace.intellij.read.hosted.HostedQueryService',
    'io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadTraceIdentity',
    'io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadDiagnosticEncodingKt',
    'io.github.amichne.kast.runtime.hosted.HostedEndpointService',
    'io.github.amichne.kast.runtime.hosted.HostedTransportTrace',
    'io.github.amichne.kast.runtime.hosted.LoggingHostedEndpointObserver',
    'io.github.amichne.kast.runtime.hosted.HostedSmartModeWaitKt',
    'io.github.amichne.kast.runtime.hosted.HostedCanonicalQueryKt',
    'io.github.amichne.kast.symbol.intellij.IntellijNativeDiscoveryQueryKt',
    'io.github.amichne.kast.relation.intellij.IntellijK2RelationSearch',
    'io.github.amichne.kast.relation.intellij.IntellijCallbackFlowRead',
    'io.github.amichne.kast.relation.intellij.IntellijCallbackFlowScan',
    'io.github.amichne.kast.relation.intellij.CallbackFormalWorklist',
    'io.github.amichne.kast.relation.intellij.CallbackFlowRetention',
    'io.github.amichne.kast.relation.intellij.CallbackRetainedRecords',
    'io.github.amichne.kast.relation.intellij.CallbackParameterSummaries',
    'io.github.amichne.kast.relation.contract.CompleteCallbackForwardingGraph',
    'io.github.amichne.kast.relation.contract.CompleteStaticCallbackGraph',
    'io.github.amichne.kast.relation.contract.RelationReferenceOccurrence',
})


RELATION_WORK_NATIVE_OWNERS = frozenset({
    'io.github.amichne.kast.query.service.QueryTraceTasksKt',
    'io.github.amichne.kast.relation.intellij.AdmittedRelationScopes',
    'io.github.amichne.kast.relation.intellij.IntellijRelationScopeCompilerKt',
    'io.github.amichne.kast.relation.intellij.CompiledRelationScope',
    'io.github.amichne.kast.relation.intellij.RelationFileEnumerationPlan',
    'io.github.amichne.kast.relation.intellij.RelationFileInventory',
    'io.github.amichne.kast.relation.intellij.CompleteRelationFileUniverse',
    'io.github.amichne.kast.relation.intellij.EnumeratedRelationScope',
    'io.github.amichne.kast.relation.intellij.ObservedRelationSearchKt',
    'io.github.amichne.kast.topology.intellij.IntellijSemanticDependencyCapture',
    'io.github.amichne.kast.topology.intellij.SemanticDependencyReadInputs',
    'io.github.amichne.kast.topology.intellij.SemanticNativeSdkFilePlan',
    'io.github.amichne.kast.topology.intellij.SemanticNativeFileMemo',
    'io.github.amichne.kast.runtime.hosted.HostedSemanticCallbackFacts',
    'io.github.amichne.kast.runtime.hosted.HostedReadCallbackPartitions',
    'io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadActionAccounting',
    'io.github.amichne.kast.workspace.intellij.read.hosted.HostedReadSearchAccounting',
    'io.github.amichne.kast.workspace.intellij.read.IntellijReadCall',
})


class QualificationSlice(str, Enum):
    TRY_BRANCH_RESULTS = 'TRY_BRANCH_RESULTS'
    TRY_LOCAL_IDENTITIES = 'TRY_LOCAL_IDENTITIES'
    RELATION_WORK_REDUCTION = 'RELATION_WORK_REDUCTION'

    @classmethod
    def admit(cls, value):
        try:
            return cls(value)
        except (ValueError, TypeError):
            raise ValueError('INVALID_QUALIFICATION_SLICE') from None

    @property
    def changed_owners(self):
        return {
            self.TRY_BRANCH_RESULTS: TRY_BRANCH_NATIVE_OWNERS,
            self.TRY_LOCAL_IDENTITIES: TRY_LOCAL_NATIVE_OWNERS,
            self.RELATION_WORK_REDUCTION: TRY_BRANCH_NATIVE_OWNERS | RELATION_WORK_NATIVE_OWNERS,
        }[self]

    @property
    def public_contract_version(self):
        return {self.TRY_BRANCH_RESULTS: 6, self.TRY_LOCAL_IDENTITIES: 7,
                self.RELATION_WORK_REDUCTION: 14}[self]


def admit_native_owner_profile(slice_, owners):
    slice_ = QualificationSlice.admit(slice_)
    if set(owners) != NATIVE_COMMON_OWNERS | slice_.changed_owners:
        raise ValueError('NATIVE_OWNER_SLICE_MISMATCH')
    return slice_


def admit_public_contract_slice(slice_, observed):
    slice_ = QualificationSlice.admit(slice_)
    if type(observed) is not int or observed != slice_.public_contract_version:
        raise ValueError('PUBLIC_CONTRACT_SLICE_MISMATCH')
    return slice_


class Finding(str, Enum):
    REPRODUCED = "reproduced"
    NOT_REPRODUCED = "not reproduced"
    CHANGED = "behavior changed"
    BLOCKED = "blocked"


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class InventoryPathKind(str, Enum):
    SOURCE = 'SOURCE'
    LOCAL_OUTPUT = 'LOCAL_OUTPUT'


class InventoryDirectoryRole(str, Enum):
    ROOT = 'ROOT'
    BUILD_SCRIPT_OWNER = 'BUILD_SCRIPT_OWNER'
    GENERATED_FIXTURE_PROJECT = 'GENERATED_FIXTURE_PROJECT'
    CONTENT = 'CONTENT'


class SourceInventoryFailure(str, Enum):
    LINKED_ENTRY = 'SOURCE_INVENTORY_LINKED_ENTRY'
    DIRECTORY_UNAVAILABLE = 'SOURCE_INVENTORY_DIRECTORY_UNAVAILABLE'
    FIXTURE_BUILD_OWNERSHIP_UNPROVEN = 'SOURCE_INVENTORY_FIXTURE_BUILD_OWNERSHIP_UNPROVEN'


@dataclass(frozen=True)
class InventoryBuildOwnership:
    projects: frozenset[Path]


def generated_fixture_settings(projects):
    return ('rootProject.name = "kast-semantic-fixture"\ninclude(' +
            ', '.join(json.dumps(':' + name) for name in projects) + ')\n')


def inventory_build_ownership(directory):
    """Only the harness's exact generated Gradle composition admits scriptless project roots."""
    metadata = directory / 'fixture-parameters.json'
    if not metadata.exists() and not metadata.is_symlink():
        return InventoryBuildOwnership(frozenset())
    failure = SourceInventoryFailure.FIXTURE_BUILD_OWNERSHIP_UNPROVEN.value
    files = (metadata, directory / 'settings.gradle.kts', directory / 'build.gradle.kts')
    if any(path.is_symlink() or not path.is_file() or path.stat().st_size > 65536 for path in files):
        raise ValueError(failure)
    try:
        parameters = json.loads(metadata.read_text())
        count = parameters.get('noiseModules') if isinstance(parameters, dict) else None
        if (type(count) is not int or not 1 <= count <= 1500 or
                type(parameters.get('gradleProjectCount')) is not int or parameters['gradleProjectCount'] != count + 3):
            raise ValueError(failure)
        projects = ('core', 'logging', *(f'noise{i}' for i in range(count)))
        if (files[1].read_text() != generated_fixture_settings(projects) or
                files[2].read_bytes() != (FIXTURE / 'build.gradle.kts').read_bytes()):
            raise ValueError(failure)
    except (OSError, UnicodeError, ValueError):
        raise ValueError(failure) from None
    roots = frozenset(directory / name for name in projects)
    if any(path.is_symlink() or not path.is_dir() for path in roots):
        raise ValueError(failure)
    return InventoryBuildOwnership(roots)


def inventory_child_kind(name, owner):
    if name in ('.gradle', '.idea', '.kotlin', '.git'):
        return InventoryPathKind.LOCAL_OUTPUT
    if name == 'build' and owner in (InventoryDirectoryRole.ROOT, InventoryDirectoryRole.BUILD_SCRIPT_OWNER,
                                    InventoryDirectoryRole.GENERATED_FIXTURE_PROJECT):
        return InventoryPathKind.LOCAL_OUTPUT
    return InventoryPathKind.SOURCE


def inventory(directory):
    """Prune outputs at their owning build boundary; source/package names carry no output proof."""
    directory = Path(directory)
    ownership = inventory_build_ownership(directory)
    result = {}
    def rejected_walk(error):
        raise ValueError(SourceInventoryFailure.DIRECTORY_UNAVAILABLE.value) from None
    for parent, children, files in os.walk(directory, topdown=True, followlinks=False, onerror=rejected_walk):
        parent = Path(parent)
        owner = (InventoryDirectoryRole.ROOT if parent == directory else
                 InventoryDirectoryRole.BUILD_SCRIPT_OWNER if {'build.gradle.kts', 'build.gradle'} & set(files) else
                 InventoryDirectoryRole.GENERATED_FIXTURE_PROJECT if parent in ownership.projects else
                 InventoryDirectoryRole.CONTENT)
        children[:] = sorted(name for name in children
                             if inventory_child_kind(name, owner) == InventoryPathKind.SOURCE)
        files = sorted(name for name in files
                       if inventory_child_kind(name, InventoryDirectoryRole.CONTENT) == InventoryPathKind.SOURCE)
        for name in (*children, *files):
            if (parent / name).is_symlink():
                raise ValueError(SourceInventoryFailure.LINKED_ENTRY.value)
        for name in files:
            path = parent / name
            if path.is_file():
                result[path.relative_to(directory).as_posix()] = digest(path)
    return dict(sorted(result.items()))


def fresh(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=False)
    return path.resolve(strict=True)


def capture(command, cwd, stdin="", timeout=60):
    started = time.monotonic_ns()
    try:
        p = subprocess.run([str(x) for x in command], cwd=cwd, input=stdin, text=True,
                           capture_output=True, timeout=timeout)
        return dict(command=[str(x) for x in command], cwd=str(cwd), stdin=stdin,
                    exitCode=p.returncode, stdout=p.stdout, stderr=p.stderr,
                    elapsedNanos=time.monotonic_ns() - started, outcome="completed")
    except OSError:
        return dict(command=[str(x) for x in command], cwd=str(cwd), stdin=stdin,
                    outcome="launch-rejected", failure="PROCESS_LAUNCH_UNAVAILABLE", stdout="", stderr="",
                    elapsedNanos=time.monotonic_ns() - started)
    except KeyboardInterrupt:
        return dict(command=[str(x) for x in command], cwd=str(cwd), stdin=stdin,
                    outcome="canceled", stdout="", stderr="", elapsedNanos=time.monotonic_ns() - started)
    except subprocess.TimeoutExpired as failure:
        # Harness timeout is explicitly different from a native semantic deadline.
        def decoded(value):
            return value.decode(errors="replace") if isinstance(value, bytes) else value or ""
        return dict(command=[str(x) for x in command], cwd=str(cwd), stdin=stdin,
                    outcome="harness-timeout", stdout=decoded(failure.stdout),
                    stderr=decoded(failure.stderr), elapsedNanos=time.monotonic_ns() - started)


def setup(args):
    root = args.fixture.absolute()
    if root.exists():
        raise ValueError("SETUP_REQUIRES_FRESH_DIRECTORY")
    shutil.copytree(FIXTURE, root)
    root = root.resolve(strict=True)
    shutil.copy(REPO / "gradlew", root / "gradlew")
    shutil.copytree(REPO / "gradle/wrapper", root / "gradle/wrapper")
    # A fixed selected package; only unrelated Gradle projects/declarations vary.
    noise = [f"noise{i}" for i in range(args.noise_modules)]
    (root / "settings.gradle.kts").write_text(generated_fixture_settings(('core', 'logging', *noise)))
    for name in noise:
        (root / name).mkdir(exist_ok=True)
    folder = root / "noise0/src/main/kotlin/repro/noise"
    folder.mkdir(parents=True)
    # Unique names scale independently from module count; fixed file batches keep compilation small.
    for first in range(0, args.noise_names, 200):
        (folder / f"Noise{first}.kt").write_text("package repro.noise\n" + "\n".join(
            (f"class UnrelatedName{i:06d}" if args.noise_kind == "class" else f"fun unrelatedFunction{i:06d}() = Unit") for i in range(first, min(first + 200, args.noise_names))) + "\n")
    if args.comparison_workloads:
        reliability = root / "logging/src/main/kotlin/reliability"
        reliability.mkdir(parents=True)
        for name in ("ReadDenseReferenceTarget.kt", "ReadDenseReferences.kt", "ReadPageBudget.kt"):
            shutil.copy(FIXTURE / "read-reliability" / name, reliability / name)
    logger = root / "logging/src/main/kotlin/repro/logging/FixtureLogger.kt"
    extra = {"kotlin": "", "java": "; java.lang.System.nanoTime()", "outside": "; repro.external.outsideHelper()"}[args.callee]
    logger.write_text(logger.read_text().replace("fun trace() { loggerFunction() }", "fun trace() { loggerFunction()" + extra + " }"))
    if args.java_references:
        java = root / "logging/src/main/java/repro/logging/JavaManagerUse.java"
        java.parent.mkdir(parents=True)
        java.write_text("package repro.logging;\npublic class JavaManagerUse {\n    public FixtureLoggerManager manager() { return new FixtureLoggerManager(); }\n}\n")
    (root / "gradle.properties").write_text("org.gradle.jvmargs=-Xmx1024m\norg.gradle.workers.max=2\n")
    write(root / "fixture-parameters.json", dict(noiseModules=args.noise_modules, noiseNames=args.noise_names,
          callee=args.callee, javaReferences=args.java_references, noiseKind=args.noise_kind, gradleProjectCount=3 + args.noise_modules))
    evidence = fresh(args.output)
    write(evidence / "fixture.json", dict(root=str(root), sourceHashes=inventory(root)))
    result = capture([root / "gradlew", "classes", "testClasses"], root, timeout=600)
    write(evidence / "compile.json", result)
    print(json.dumps(dict(fixture=str(root), compilation=result.get("exitCode"),
                         next="Explicitly import this Gradle project into IDEA, then pin and replay.")))
    return 0 if result.get("exitCode") == 0 else 2


def reject_native_pin(value):
    stages = {"HOST_ADMISSION", "PLUGIN_ADMISSION", "PROJECT_ADMISSION", "CONFIGURATION_CAPTURE",
              "ARTIFACT_CAPTURE", "MODEL_CAPTURE", "PUBLICATION"}
    if (not isinstance(value, dict) or set(value) != {'type', 'stage'} or
            value['type'] != 'PIN_CAPTURE_REJECTED' or value['stage'] not in stages):
        raise ValueError('INVALID_NATIVE_PIN_REJECTION')
    raise ValueError('PIN_CAPTURE_REJECTED:' + value['stage'])


@dataclass(frozen=True)
class NativePinRequest:
    project: str
    hostPid: int
    qualificationSlice: str | None


def pin(args):
    output = fresh(args.output)
    cli = args.cli.resolve(strict=True)
    launcher = args.idea_contents / "MacOS/idea"
    # Reuse the hosted acceptance original-process admission; no replacement worker or launch fallback.
    rows = subprocess.check_output(["ps", "-ww", "-axo", "pid=,comm="], text=True)
    selected = select_native_host(rows, launcher, getattr(args, 'host_pid', None))
    if isinstance(selected, RejectedNativeHost):
        raise ValueError(selected.failure.value)
    request = NativePinRequest(project=str(args.fixture.resolve(strict=True)), hostPid=selected.pid,
                               qualificationSlice=getattr(args, 'qualification_slice', None))
    write(output / "input.json", asdict(request))
    script = (HERE / "semantic-reproduction-pin.kts.template").read_text().replace(
        "@INPUT_BASE64@", base64.b64encode(str(output / "input.json").encode()).decode())
    (output / "pin.kts").write_text(script)
    write(output / "script-process.json", capture([launcher, "ideScript", output / "pin.kts"], REPO))
    wait_for("native pin receipt", lambda: (output / "host.json").exists() or (output / "pin-rejection.json").exists(), seconds=45)
    if (output / "pin-rejection.json").exists():
        reject_native_pin(json.loads((output / "pin-rejection.json").read_text()))
    host = json.loads((output / "host.json").read_text())
    slice_ = admit_native_owner_profile(getattr(args, 'qualification_slice', None), host['plugin']['classResources'])
    profile = json.loads((output / 'profile.json').read_text())
    if profile['type'] != 'NATIVE_PROFILE_PATHS' or profile['hostPid'] != host['pid'] or profile['processStart'] != host['processStart']:
        raise ValueError('NATIVE_PROFILE_PIN_MISMATCH')
    mcp = getattr(args, "public_mcp", False)
    rpc = getattr(args, "public_rpc", False) or mcp
    catalog_cli = getattr(args, 'catalog_rpc', None) or (cli.parent / "kast-tool-rpc-complete" if mcp else cli)
    version = capture([cli, "--version"], args.fixture) if not rpc else None
    schema = capture([catalog_cli, "catalog"] if rpc else [cli, "--schema"], args.fixture)
    if version is not None: write(output / "version-process.json", version)
    write(output / "schema-process.json", schema)
    if schema.get("exitCode") != 0:
        raise ValueError("INSTALLED_SCHEMA_UNAVAILABLE")
    catalog = json.loads(schema['stdout'])
    if catalog.get('type') != 'catalog':
        raise ValueError('PUBLIC_CONTRACT_SLICE_MISMATCH')
    admit_public_contract_slice(slice_, catalog.get('catalog', {}).get('schemaVersion'))
    (output / "installed-schema.json").write_text(schema["stdout"])
    plugin_path = Path(host["plugin"]["path"])
    plugin_files = {str(p): digest(p) for p in sorted((plugin_path / "lib").glob("*.jar"))}
    source = args.source_tree.resolve(strict=True) if args.source_tree else REPO
    patch = capture(["git", "diff", "--binary", "HEAD"], source)
    (output / "source.patch").write_text(patch["stdout"])
    source_head = capture(["git", "rev-parse", "HEAD"], source)["stdout"].strip()
    untracked_paths = capture(["git", "ls-files", "--others", "--exclude-standard"], source)["stdout"].splitlines()
    untracked = {name: digest(source / name) for name in untracked_paths if (source / name).is_file()}
    metadata = dict(schemaVersion=1, qualificationSlice=slice_.value, publicContractVersion=slice_.public_contract_version,
        cli=dict(requested=str(args.cli), executable=str(cli),
        sha256=digest(cli), transport="MCP_SESSION" if mcp else "TOOL_RPC", catalogExecutableSha256=digest(catalog_cli), version=version["stdout"].strip() if not rpc else "unavailable; RPC exposes catalog version",
        jars={str(p): digest(p) for p in sorted((cli.parent.parent / "lib").glob("*.jar"))}),
        plugin=dict(native=host["plugin"], jars=plugin_files), host=host, profile=profile,
        installedSchemaSha256=digest(output / "installed-schema.json"),
        source=dict(path=str(source), commit=source_head, patchSha256=digest(output / "source.patch"), untrackedHashes=untracked,
                    runtimeCorrespondence="unproven unless independently matched to a release artifact or build receipt"),
        fixture=dict(root=str(args.fixture.resolve(strict=True)), hashes=inventory(args.fixture)),
        limits=dict(native=host["limits"], diagnosticAvailability=host["diagnosticAvailability"]))
    write(output / "pin.json", metadata)
    print(output / "pin.json")
    return 0 if host["model"]["smart"] and host["model"]["gradleProjectCount"] > 0 else 2


@dataclass(frozen=True)
class Case:
    name: str
    source: dict
    identities: tuple[str, ...] = ()
    steps: tuple[dict, ...] = ()
    select: tuple[str, ...] = tuple(FIELDS)
    reported: str = "complete"
    occurrences: str = ""
    tokens: tuple[str, ...] = ()
    schema_valid: bool = True
    unique_tokens: bool = True
    issued: tuple[dict, ...] = ()


class ToolSurface(str, Enum):
    PUBLIC = "public-tools-v1"

    @staticmethod
    def admit(tools):
        names = {tool["name"] for tool in tools}
        public = {"check_diagnostics", "query_symbols"}
        if public <= names and "query" not in names:
            return ToolSurface.PUBLIC
        raise ValueError("UNSUPPORTED_TOOL_SURFACE")


def public_scope(selected):
    if selected is None:
        return None
    fields = {"DIRECTORY": ("relativeDirectoryPath", "includeSubdirectories"),
              "PACKAGE": ("packageName", "includeSubpackages")}
    path, recursive = fields[selected["type"]]
    return {"type": selected["type"], path: selected["value"], recursive: selected["containment"] == "RECURSIVE",
            "sourceSetNames": selected.get("sourceSets")}


def invocation(case, surface):
    if surface is not ToolSurface.PUBLIC:
        raise ValueError("UNSUPPORTED_TOOL_SURFACE")
    source = case.source
    kinds = source.get("kinds")
    if source["type"] == "SEARCH":
        arguments = dict(declarationName=source["query"], nameMatch=source["match"],
                         scope=public_scope(source.get("scope")), declarationKinds=kinds)
        lowered = dict(type="SEARCH_DECLARATIONS", **arguments)
    elif source["type"] == "ALL":
        lowered = dict(type="ALL_DECLARATIONS", declarationKinds=kinds, scope=public_scope(source.get("scope")))
    elif source["type"] == "REFS":
        lowered = dict(type="SYMBOL_REFS", symbolRefs=source["refs"])
    else:
        raise ValueError("UNSUPPORTED_REPLAY_SOURCE")
    steps = []
    for step in case.steps:
        if step["type"] == "FILTER":
            steps.append(dict(type="WHERE", predicate=dict(type="VISIBILITY",
                values=step["visibility"])))
        elif step["type"] == "EXPAND":
            steps.append(dict(type="EXPAND_RELATION", relation=step["relation"]))
        elif step["type"] == "DISTINCT":
            steps.append(dict(type="DISTINCT_SYMBOLS"))
        else:
            raise ValueError("UNSUPPORTED_REPLAY_STEP")
    return "query_symbols", ["tool", "query_symbols"], dict(request=dict(type="RUN", source=lowered,
        steps=steps, output=dict(type="SYMBOLS", fields=list(case.select))))


def search(name, scope=None, match="EXACT"):
    return dict(type="SEARCH", query=name, match=match, **({"scope": scope} if scope else {}))


def scope(kind, value, containment="RECURSIVE", source_sets=None):
    return dict(type=kind, value=value, containment=containment,
                **({"sourceSets": source_sets} if source_sets else {}))


def cases(expected, parameters=None):
    ids = lambda *keys: tuple(expected["declarations"][key][1] for key in keys)
    core = scope("DIRECTORY", "core/src/main/kotlin/repro/core", "DIRECT")
    alias = scope("PACKAGE", "repro.core", "DIRECT")
    logging = scope("DIRECTORY", "logging", "RECURSIVE")
    result = [
        Case("all-logging-recursive", dict(type="ALL", scope=logging), tuple(expected["loggingRecursive"]), reported="budget-exceeded"),
        Case("all-core-direct", dict(type="ALL", scope=core), tuple(expected["coreDirect"]), reported="budget-exceeded"),
        Case("all-core-alias-direct", dict(type="ALL", scope=alias, kinds=["TYPE_ALIAS"]), ids("alias"), reported="budget-exceeded"),
    ]
    # Identical selected scopes and names across the unrelated-load experiments.
    for label, selected, name, wanted in (("logging", logging, "FixtureLogger", ids("logger", "manager")),
        ("core", core, "TraceLabel", ids("alias")), ("core-alias", alias, "TraceLabel", ids("alias"))):
        for match in ("EXACT", "FUZZY"):
            result.append(Case(f"{label}-{match.lower()}", search(name, selected, match),
                               wanted if match == "FUZZY" else wanted[:1]))
    for key in ("logger", "helper", "boolean", "alias", "manager", "unused", "trace", "test", "child", "base"):
        result.append(Case("exact-" + key, search(expected["declarations"][key][1].split(".")[-1]), ids(key)))
    for kind, selected in (("DIRECTORY", "logging/src/main/kotlin/repro/logging"), ("PACKAGE", "repro.logging")):
        for containment in ("DIRECT", "RECURSIVE"):
            result.append(Case(f"{kind.lower()}-{containment.lower()}", search("ChildMarker", scope(kind, selected, containment)),
                               ids("child") if containment == "RECURSIVE" else ()))
        result.append(Case(f"{kind.lower()}-foreign", search("FixtureLogger", scope(kind,
            "core/src/main/kotlin/repro/core" if kind == "DIRECTORY" else "repro.core")), ()))
    for source_set in ("main", "test"):
        result.append(Case("source-set-" + source_set, search("TestOnlyLogger", scope("DIRECTORY", ".", source_sets=[source_set])),
                           ids("test") if source_set == "test" else ()))
    for visibility in ("PRIVATE", "PUBLIC"):
        result.append(Case("visibility-" + visibility.lower(), search("loggerFunction"), ids("helper") if visibility == "PRIVATE" else (),
                           steps=({"type": "FILTER", "visibility": [visibility]},)))
    result.append(Case("distinct-five", search("sharedOperation"), tuple(expected["sameName"]), steps=({"type": "DISTINCT"},)))
    for relation in ("REFERENCES", "CALLERS"):
        result.append(Case("helper-" + relation.lower(), search("loggerFunction"), tuple(expected["helperCallers"]),
                           steps=({"type": "EXPAND", "relation": relation},), occurrences="helperOccurrences"))
    result.append(Case("alias-type-uses", search("TraceLabel"), tuple(expected["aliasUses"]),
                       steps=({"type": "EXPAND", "relation": "TYPE_USES"},), occurrences="aliasOccurrences"))
    for relation in ("IMPLEMENTATIONS", "INHERITORS"):
        result.append(Case("sink-" + relation.lower(), search("FixtureSink"), tuple(expected["descendants"]),
                           steps=({"type": "EXPAND", "relation": relation},)))
    result.append(Case("sink-overrides", search("transmit", scope("PACKAGE", "repro.hierarchy")), tuple(expected["overrides"]),
        steps=({"type": "EXPAND", "relation": "OVERRIDES"}, {"type": "DISTINCT"})))
    for label, selected in (("default", None), ("directory", scope("DIRECTORY", "logging")), ("package", scope("PACKAGE", "repro.logging"))):
        callee_ids = ids("helper")
        if (parameters or {}).get("callee") == "outside":
            # Public schema: discovery scope does not restrict expansion destinations.
            callee_ids += ("repro.external.outsideHelper",)
        result.append(Case("trace-callees-" + label, search("trace", selected), callee_ids,
                           steps=({"type": "EXPAND", "relation": "CALLEES"},), reported="relation-incomplete-one"))
    result.append(Case("manager-references", search("FixtureLoggerManager"), (),
                       steps=({"type": "EXPAND", "relation": "REFERENCES"},), reported="relation-incomplete-zero"))
    result.append(Case("unused-references", search("UnusedMarker"), (), steps=({"type": "EXPAND", "relation": "REFERENCES"},)))
    result.append(Case("invalid-reference", dict(type="REFS", refs=["exact:v3:NOT_ISSUED"]), reported="malformed-reference"))
    result.append(Case("invalid-reference-syntax", dict(type="REFS", refs=["not-a-reference"]), reported="invalid-arguments", schema_valid=False))
    result.extend([
        Case("class-query", search("FixtureLogger"), ids("logger")),
        Case("function-query", search("loggerFunction"), ids("helper")),
        Case("function-overloads", search("sharedOperation"), tuple(expected["sameName"])),
        Case("class-case-sensitive-negative", search("fixturelogger")),
    ])
    return result


def expected_occurrences(root, definition):
    lines = (root / definition["file"]).read_text().splitlines(keepends=True)
    result = []
    positions = definition.get("positions", [(line, 1) for line in definition.get("lines", [])])
    for line, ordinal in positions:
        column = -1
        for _ in range(ordinal):
            column = lines[line - 1].index(definition["text"], column + 1)
        start = sum(len(s) for s in lines[:line - 1]) + column
        result.append((str(root / definition["file"]), start, start + len(definition["text"])))
    return sorted(result)


def assess(case, response, root, expected):
    items = response.get("items", [])
    actual_ids = [(item.get("signature") or {}).get("qualifiedIdentity") for item in items]
    checks = {}
    if "SIGNATURE" in case.select:
        checks["exactIdentities"] = Counter(actual_ids) == Counter(case.identities)
    checks["resultCount"] = len(items) == len(case.identities)
    checks["exactReferences"] = all(item.get("type") == "exact-symbol" and
        isinstance(item.get("ref"), str) and item["ref"].startswith("exact:v") for item in items)
    if case.unique_tokens:
        checks["distinctTokens"] = len({item["ref"] for item in items}) == len(items)
    if case.tokens:
        checks["opaqueReferencesPreserved"] = sorted(item["ref"] for item in items) == sorted(case.tokens)
    if case.issued:
        # Issuer output is used only to check preservation, never as the semantic oracle.
        original = {item["ref"]: item for item in case.issued}
        checks["issuedProjectionsPreserved"] = len(items) == len(original) and all(
            item["ref"] in original and all(item.get(field.lower()) ==
                original[item["ref"]].get(field.lower()) for field in case.select) for item in items)
    for field_name in FIELDS:
        checks["projection-" + field_name] = all((item.get(field_name.lower()) is not None) == (field_name in case.select) for item in items)
    locations = []
    for item in items:
        location = item.get("location")
        if location:
            p = Path(location["file"])
            start, end = location["range"]["startInclusive"], location["range"]["endExclusive"]
            valid = p.is_relative_to(root) and p.is_file() and 0 <= start < end <= len(p.read_text())
            declaration = next((v for v in expected["declarations"].values() if v[1] == (item.get("signature") or {}).get("qualifiedIdentity")), None)
            if valid and declaration:
                valid = p == root / declaration[0] and p.read_text()[start:end].startswith(declaration[2])
            locations.append(valid)
    checks["sourceRanges"] = all(locations)
    connections = [c for item in items for c in item.get("connections", [])]
    if connections:
        checks["exactConnectionCoverage"] = all(c.get("coverage") == "exact-compiler-confirmed" and
            c.get("provenance") == "k2-authored-source" for c in connections)
        checks["compilerEndpoints"] = all(endpoint.get("compilerEvidence", {}).get("identity") and
            endpoint.get("compilerEvidence", {}).get("signature", {}).get("qualifiedIdentity") == endpoint.get("qualifiedIdentity")
            for c in connections for endpoint in (c["source"], c["target"]))
    if case.occurrences:
        target = expected["declarations"][expected[case.occurrences].get("target", "alias" if case.occurrences == "aliasOccurrences" else "helper")][1]
        checks["orientedConnections"] = Counter((c["source"].get("qualifiedIdentity"), c["target"].get("qualifiedIdentity")) for c in connections) == Counter((identity, target) for identity in case.identities)
    occurrences = sorted((c["occurrence"]["file"], c["occurrence"]["range"]["startInclusive"],
                          c["occurrence"]["range"]["endExclusive"]) for c in connections if c.get("occurrence"))
    if case.occurrences:
        checks["exactOccurrences"] = occurrences == expected_occurrences(root, expected[case.occurrences])
    qualification = response.get("qualification", {})
    limitations = qualification.get("limitations", [])
    status = response.get("status", response.get("outcome"))
    if case.reported == "complete":
        matches = status == "complete" and not limitations and not response.get("failures") and all(checks.values())
    elif case.reported.startswith("relation-incomplete"):
        minimum = 1 if case.reported.endswith("one") else 0
        matches = status == "qualified" and len(items) == minimum and qualification.get("knownMinimum") == minimum and "relation-incomplete" in limitations and all(checks.values())
    elif case.reported == "budget-exceeded":
        matches = response.get("failure") == "BUDGET_EXCEEDED" and response.get("stage") == "SEMANTIC_READ"
    elif case.reported == "malformed-reference":
        matches = status == "rejected" and not items and response.get("rejection", {}).get("type") == "reference-rejected" and response["rejection"].get("reason") == "malformed"
    else:
        matches = response.get("failure") == "INVALID_ARGUMENTS" or response.get("reason") in {"invalid-arguments", "arguments-rejected"}
    stale = response.get("rejection", {}).get("reason") == "stale-authority"
    finding = Finding.BLOCKED if stale else Finding.REPRODUCED if matches else Finding.NOT_REPRODUCED
    return dict(finding=finding.value,
        **({"prerequisite": "Stable host and project epoch between exact discovery and REFS"} if stale else {}),
        assertions=checks, expectedIdentities=list(case.identities), actualIdentities=actual_ids,
        itemCount=len(items), occurrenceCount=len(occurrences), occurrences=occurrences,
        aggregateCoverage=dict(status=status, qualification=qualification),
        connectionCoverage=[dict(meaning=c.get("meaning"), coverage=c.get("coverage"), provenance=c.get("provenance")) for c in connections],
        live=response.get("live"), failures=response.get("failures", []))


def compile_provider(cli, directory):
    jars = sorted((cli.parent.parent / "lib").glob("*.jar"))
    owners = [p for p in jars if p.name.startswith("app-server-")]
    if len(owners) != 1:
        raise ValueError("EXACT_PROVIDER_JAR_UNAVAILABLE")
    classpath = os.pathsep.join(str(p) for p in jars)
    target = directory / "provider.jar"
    result = capture(["kotlinc", HERE / "SemanticReproductionProvider.kt", "-jvm-target", "25", "-classpath",
        classpath, "-Xfriend-paths=" + str(owners[0]), "-d", target], REPO, timeout=120)
    write(directory / "provider-compile.json", result)
    if result.get("exitCode") != 0:
        raise ValueError("PROVIDER_HARNESS_COMPILATION_REJECTED")
    return ["java", "-classpath", str(target) + os.pathsep + classpath,
            "io.github.amichne.kast.appserver.manual.SemanticReproductionProvider"]


def replay(args):
    import jsonschema
    output = fresh(args.output)
    root = args.fixture.resolve(strict=True)
    cli = args.cli.resolve(strict=True)
    expected = json.loads((FIXTURE / "expected.json").read_text())
    pinned = json.loads(args.pin.read_text())
    schema = json.loads((args.pin.parent / "installed-schema.json").read_text())
    tools = schema["serverProjection"]["hostedBootstrap"]["tools"]
    surface = ToolSurface.admit(tools)
    tool_schemas = {tool["name"]: tool["inputSchema"] for tool in tools}
    if not pinned["host"]["model"]["smart"] or pinned["host"]["model"]["gradleProjectCount"] == 0:
        raise ValueError("IMPORTED_SMART_MODEL_NOT_PINNED")
    if pinned["cli"]["executable"] != str(cli) or pinned["cli"]["sha256"] != digest(cli):
        raise ValueError("PINNED_CLI_MISMATCH")
    if pinned["fixture"]["root"] != str(root) or pinned["fixture"]["hashes"] != inventory(root):
        raise ValueError("PINNED_FIXTURE_MISMATCH")
    write(output / "pin.json", pinned)
    write(output / "expected.json", expected)
    provider = compile_provider(cli, output) if args.surface == "provider" else None
    receipts, seeds, first_live = [], {}, None

    def invoke(case, phase):
        nonlocal first_live
        directory = fresh(output / (case.name + "-" + phase))
        if case.name == "invalid-reference-syntax":
            case = replace(case, schema_valid=True, reported="malformed-reference")
        tool, command, request = invocation(case, surface)
        query_schema = tool_schemas[tool]
        write(directory / "route.json", dict(tool=tool, command=command, surface=surface.value))
        log_before = args.idea_log.stat() if args.idea_log else None
        validation = list(jsonschema.Draft202012Validator(query_schema).iter_errors(request))
        if bool(validation) == case.schema_valid:
            raise ValueError("UNEXPECTED_SCHEMA_ADMISSION")
        write(directory / "schema-validation.json", dict(expectedValid=case.schema_valid, failures=len(validation)))
        write(directory / "request.json", request)
        if provider:
            result = capture([*provider, cli, root, directory / "request.json", directory / "provider.json", tool], root, timeout=30)
            native = [json.loads(p.read_text()) for p in sorted(directory.glob("process-*.json"))]
            queries = [p for p in native if p.get("arguments") == command]
            raw = queries[0] if len(queries) == 1 else {}
            document = raw.get("stdout") or raw.get("stderr")
        else:
            result = capture([cli, *command], root, json.dumps(request), timeout=15)
            raw = result
            document = result.get("stdout") or result.get("stderr")
        write(directory / "process.json", result)
        diagnostics = []
        if args.idea_log:
            observations = collect_observations(args.idea_log, log_before)
            diagnostics, phases = list(observations.diagnostics), list(observations.phases)
            write(directory / "native-diagnostics.json", diagnostics)
            write(directory / "native-phases.json", phases)
            write(directory / "hosted-observations.json", asdict(observations))
        try:
            response = json.loads(document) if document else None
        except json.JSONDecodeError:
            response = None
        if response is None and provider and not case.schema_valid and (directory / "provider.json").exists():
            provider_response = json.loads((directory / "provider.json").read_text())
            receipt = dict(case=case.name, phase=phase, surface="provider", canonicalInvoked=False,
                finding=(Finding.REPRODUCED if provider_response.get("failureCode") == "INVALID_ARGUMENTS" else Finding.NOT_REPRODUCED).value,
                providerRejection=provider_response, elapsedNanos=result["elapsedNanos"],
                prerequisite="none; argument rejection precedes the CLI by contract")
        elif response is None:
            receipt = dict(case=case.name, phase=phase, finding=Finding.BLOCKED.value,
                           prerequisite="One canonical CLI response from the admitted host", processOutcome=result["outcome"])
        else:
            write(directory / "response.json", response)
            receipt = dict(case=case.name, phase=phase, reported=case.reported, surface=args.surface,
                exitCode=raw.get("exitCode"), elapsedNanos=raw.get("elapsedNanos"),
                **assess(case, response, root, expected))
            receipt["nativeDiagnosticRecords"] = len(diagnostics)
            receipt["diagnosticCorrelation"] = "one-record" if len(diagnostics) == 1 else "unavailable" if not diagnostics else "ambiguous"
            live = response.get("live")
            if len(diagnostics) == 1 and live:
                correlation = diagnostics[0].get("correlation", {})
                receipt["diagnosticCorrelation"] = "matched" if correlation.get("host") == live["host"] and correlation.get("epoch") == live["epoch"] else "mismatched"
            if live:
                if first_live is None:
                    first_live = live
                receipt["sameHostAndEpoch"] = live == first_live
                if live != first_live:
                    receipt["finding"] = Finding.BLOCKED.value
                    receipt["prerequisite"] = "Stable host and project epoch throughout this replay; finish setup/import before repinning"
            if provider and (directory / "provider.json").exists():
                envelope = json.loads((directory / "provider.json").read_text()).get("providerEnvelope", {})
                receipt["providerPreservesCanonicalResponse"] = envelope.get("document") == response
            if case.name.startswith("exact-") and response.get("status") == "complete" and len(response.get("items", [])) == 1:
                seeds[case.name[6:]] = response["items"][0]
        write(directory / "receipt.json", receipt)
        receipts.append(receipt)
        write(output / "receipts.json", receipts)
        print(f"{case.name} {phase}: {receipt['finding']}", flush=True)
        return response

    for case in cases(expected, json.loads((root / "fixture-parameters.json").read_text())):
        if args.verify_corrections and (case.reported == "budget-exceeded" or case.reported.startswith("relation-incomplete")):
            identities = case.identities
            if case.name == "manager-references" and json.loads((root / "fixture-parameters.json").read_text()).get("javaReferences"):
                identities = ("repro.logging.JavaManagerUse.manager",) * 2
                case = replace(case, unique_tokens=False, occurrences="managerJavaOccurrences")
            case = replace(case, reported="complete", identities=identities)
        if args.cases and not any(case.name.startswith(prefix) for prefix in args.cases.split(",")):
            continue
        for repeat in range(args.repeats):
            response = invoke(case, "first" if repeat == 0 else f"warm-{repeat}")
            # A separate, successful exact query must demonstrate the same owner remains usable.
            if response and (response.get("status") != "complete" or response.get("failure")):
                invoke(Case(case.name + "-recovery", search("FixtureLogger"),
                            (expected["declarations"]["logger"][1],)), f"after-{repeat}")
    if not args.cases:
        for name, keys in (("refs-roundtrip", ("logger",)), ("refs-multiple", ("logger", "alias")),
                           ("refs-deduplicate", ("logger", "logger"))):
            if not all(key in seeds for key in keys):
                receipts.append(dict(case=name, finding=Finding.BLOCKED.value, prerequisite="Successful exact discovery of " + ", ".join(keys)))
                continue
            unique = tuple(dict.fromkeys(keys))
            tokens = tuple(seeds[key]["ref"] for key in unique)
            invoke(Case(name, dict(type="REFS", refs=[seeds[key]["ref"] for key in keys]),
                        tuple(expected["declarations"][key][1] for key in unique), steps=({"type": "DISTINCT"},),
                        tokens=tokens, issued=tuple(seeds[key] for key in unique)), "first")
        if "helper" in seeds:
            for projection in FIELDS:
                token = seeds["helper"]["ref"]
                invoke(Case("projection-" + projection.lower(), dict(type="REFS", refs=[token]),
                    (expected["declarations"]["helper"][1],), select=(projection,), tokens=(token,), issued=(seeds["helper"],)), "first")
    write(output / "receipts.json", receipts)
    if pinned["fixture"]["hashes"] != inventory(root):
        raise ValueError("FIXTURE_CHANGED_DURING_REPLAY")
    write(output / "summary.json", dict(schemaVersion=1, fixture=str(root), surface=args.surface,
        queryBudgets="unchanged production budgets", cachePolicy="no cache deletion or invalidation",
        counts=dict(Counter(r["finding"] for r in receipts)), pin=str(args.pin), receipts="receipts.json"))
    # Observational differences are successful measurements. Missing execution evidence is not.
    if any(r["finding"] == Finding.BLOCKED.value for r in receipts):
        return 2
    return 1 if args.verify_corrections and any(r["finding"] != Finding.REPRODUCED.value for r in receipts) else 0


# Complete-workload comparison extends the same CLI capture, pin and native receipts above.
# Public documents are contract-defined opaque data here; they are kept verbatim on disk.
@dataclass(frozen=True)
class ReplayCall:
    action: str
    request: dict
    process: dict
    response: dict | None
    diagnostics: list
    phases: list
    correlation: str
    hostedObservations: ObservationWindow = field(default_factory=lambda:
        ObservationWindow(failures=(TimingFailure.NOT_CAPTURED,)))
    observationCaptureNanos: int = 0


def workload_wall_nanos(elapsed_nanos, previous_calls):
    """The supplied monotonic elapsed interval contains every previous capture interval exactly once."""
    return elapsed_nanos - sum(call.observationCaptureNanos for call in previous_calls)


@dataclass(frozen=True)
class JoinedTimingComparison:
    baseline: tuple[JoinedTiming, ...]
    candidate: tuple[JoinedTiming, ...]
    type: str = field(default='JOINED', init=False)


@dataclass(frozen=True)
class UnavailableTimingComparison:
    baseline: tuple[JoinedTiming | UnavailableTiming, ...] = ()
    candidate: tuple[JoinedTiming | UnavailableTiming, ...] = ()
    type: str = field(default='UNAVAILABLE', init=False)


class DiagnosticCorrelationFailure(str, Enum):
    UNAVAILABLE = 'UNAVAILABLE'
    AMBIGUOUS = 'AMBIGUOUS'
    MISMATCHED = 'MISMATCHED'


@dataclass(frozen=True)
class MatchedDiagnostic:
    document: dict


@dataclass(frozen=True)
class RejectedDiagnosticCorrelation:
    failure: DiagnosticCorrelationFailure


def correlate_diagnostic(response, diagnostics):
    basis = response.get('live') if isinstance(response, dict) else None
    if (not diagnostics or not isinstance(basis, dict) or not isinstance(basis.get('host'), str) or
        type(basis.get('epoch')) is not int):
        return RejectedDiagnosticCorrelation(DiagnosticCorrelationFailure.UNAVAILABLE)
    matching = []
    for doc in diagnostics:
        bound = doc.get('correlation')
        if (isinstance(bound, dict) and bound.get('type') == 'bound' and
            isinstance(doc.get('readId'), str) and bound.get('host') == basis['host'] and
            type(bound.get('epoch')) is int and bound['epoch'] == basis['epoch']): matching.append(doc)
    if len(matching) == 1: return MatchedDiagnostic(matching[0])
    return RejectedDiagnosticCorrelation(DiagnosticCorrelationFailure.AMBIGUOUS if matching else
                                         DiagnosticCorrelationFailure.MISMATCHED)


def response_read_released(response, window):
    correlated = correlate_diagnostic(response, window.diagnostics)
    return isinstance(correlated, MatchedDiagnostic) and release_observed(window, {correlated.document.get('readId')})


def call_timing(call):
    observations = call.hostedObservations
    if observations.failures:
        return UnavailableTiming(observations.failures, observations.joins, observations.transport,
                                 observations.smartModeWaits)
    correlated = correlate_diagnostic(call.response, observations.diagnostics)
    if (call.correlation != 'MATCHED' or len(call.diagnostics) != 1 or
        not isinstance(correlated, MatchedDiagnostic) or correlated.document != call.diagnostics[0]):
        return UnavailableTiming((TimingFailure.SEMANTIC_CORRELATION_UNAVAILABLE,),
            call.hostedObservations.joins, call.hostedObservations.transport, call.hostedObservations.smartModeWaits)
    return join_timing(call.hostedObservations, call.diagnostics[0].get('readId'))


def compare_hosted_timings(baseline, candidate):
    a, b = tuple(call_timing(call) for call in baseline), tuple(call_timing(call) for call in candidate)
    if a and b and all(isinstance(result, JoinedTiming) for result in (*a, *b)):
        return JoinedTimingComparison(a, b)
    return UnavailableTimingComparison(a, b)


class TrialState(str, Enum):
    COMPLETE = 'COMPLETE'
    REJECTED = 'REJECTED'
    INCOMPLETE = 'INCOMPLETE'
    CANCELED = 'CANCELED'
    UNAVAILABLE = 'UNAVAILABLE'


class ComparisonState(str, Enum):
    EQUIVALENT = 'EQUIVALENT'
    INCOMPLETE = 'INCOMPLETE'
    MISSING_EVIDENCE = 'MISSING_EVIDENCE'
    SEMANTIC_REGRESSION = 'SEMANTIC_REGRESSION'


@dataclass(frozen=True)
class ReplayTrial:
    type: TrialState
    workload: str
    repetition: int
    warmup: bool
    calls: list[ReplayCall]
    semantic: dict | None
    measurements: dict
    unavailable: list[str]
    schemaVersion: int = 2


@dataclass(frozen=True)
class TrialComparison:
    type: ComparisonState
    lessWork: bool
    deltas: dict
    unavailable: list[str]
    hostedTimings: JoinedTimingComparison | UnavailableTimingComparison = field(default_factory=UnavailableTimingComparison)


@dataclass(frozen=True)
class ReplayRun:
    type: str
    schemaVersion: int
    artifact: dict
    fixture: dict
    environment: dict
    limits: dict
    requests: dict
    warmups: int
    repetitions: int
    concurrency: int
    maxCalls: int
    timeoutSeconds: int
    cachePolicy: str
    evidenceLevel: str
    trials: list[str]
    transport: str


@dataclass(frozen=True)
class ReplayComparisonReport:
    type: str
    schemaVersion: int
    baseline: str
    candidate: str
    baselineArtifact: dict
    candidateArtifact: dict
    repeatability: bool
    evidenceLevel: str
    incompatible: list[str]
    trials: list[dict]
    lessWork: bool


# Fixed, identical grants for RUN and every RESUME. Never enlarge a grant during replay.
COMPARISON_BUDGET = asdict(request_budget(WorkloadProfile.RELIABILITY_FIXTURE))
MEASUREMENT_KEYS = {'publicCalls', 'encodedBytes', 'firstUsableNanos', 'totalNanos',
                    'counters', 'nativePages', 'nativePhaseDurations', 'stageDurations'}
LOCATOR_RETAINED_COUNTER = 'REVALIDATION_LOCATORS_RETAINED/NONE'
LOCATOR_REJECTED_COUNTER = 'REVALIDATION_LOCATORS_REJECTED/NONE'
LOCATOR_OUTCOME_COUNTERS = {LOCATOR_RETAINED_COUNTER, LOCATOR_REJECTED_COUNTER}


KAST_PACKAGE = 'io.github.amichne.kast.query.contract'


def comparison_budget(profile):
    return asdict(request_budget(profile))


def workload_profile(requests):
    for profile in WorkloadProfile:
        if requests == comparison_requests(profile): return profile
    raise ValueError('UNSUPPORTED_WORKLOAD_PROFILE')


def exact_identity(profile):
    if not isinstance(profile, WorkloadProfile): raise ValueError('UNSUPPORTED_WORKLOAD_PROFILE')
    return KAST_PACKAGE + '.QueryPlanSyntax' if profile == WorkloadProfile.KAST_SOURCE else 'repro.logging.FixtureLogger'


def exact_source_file(profile):
    if not isinstance(profile, WorkloadProfile): raise ValueError('UNSUPPORTED_WORKLOAD_PROFILE')
    return (KAST_DIRECTORY + '/io/github/amichne/kast/query/contract/QueryPlan.kt'
            if profile == WorkloadProfile.KAST_SOURCE else 'logging/src/main/kotlin/repro/logging/FixtureLogger.kt')


def archive_inventory(path):
    import tarfile
    result = {}
    with tarfile.open(path, 'r:') as archive:
        for member in archive:
            name = Path(member.name)
            if name.is_absolute() or '..' in name.parts or member.issym() or member.islnk():
                raise ValueError('SOURCE_ARCHIVE_ENTRY_REJECTED')
            # The retained Git archive owns the source inventory. Never apply local-output
            # exclusions to tracked inputs; an unsupported live layout must reject admission.
            if member.isfile():
                if member.name in result: raise ValueError('SOURCE_ARCHIVE_DUPLICATE_ENTRY')
                with archive.extractfile(member) as stream:
                    result[member.name] = hashlib.file_digest(stream, 'sha256').hexdigest()
    return result


def admit_kast_source_fixture(fixture):
    if set(fixture) != {'root', 'hashes', 'type', 'sourceArchive'} or fixture['type'] != 'REPRESENTATIVE':
        raise ValueError('REPRESENTATIVE_SOURCE_EVIDENCE_UNAVAILABLE')
    archive = fixture['sourceArchive']
    if (not isinstance(archive, dict) or set(archive) != {'path', 'sha256', 'commit', 'repository'} or
            archive['repository'] != 'amichne/kast' or not isinstance(archive['commit'], str) or
            len(archive['commit']) != 40 or any(c not in '0123456789abcdef' for c in archive['commit'])):
        raise ValueError('INVALID_SOURCE_ARCHIVE_IDENTITY')
    path = Path(archive['path'])
    if path.stat().st_size > 100 * 1024 * 1024: raise ValueError('SOURCE_ARCHIVE_LIMIT_REACHED')
    process = subprocess.run(['git', 'archive', '--format=tar', archive['commit']], cwd=REPO, capture_output=True)
    if process.returncode != 0 or hashlib.sha256(process.stdout).hexdigest() != archive['sha256']:
        raise ValueError('SOURCE_COMMIT_ARCHIVE_MISMATCH')
    if (digest(path) != archive['sha256'] or archive_inventory(path) != fixture['hashes'] or
            inventory(Path(fixture['root'])) != fixture['hashes']):
        raise ValueError('SOURCE_ARCHIVE_CONTENT_MISMATCH')
    if not all(name in fixture['hashes'] for name in (exact_source_file(WorkloadProfile.KAST_SOURCE),
            KAST_DIRECTORY + '/io/github/amichne/kast/query/contract/QuerySteps.kt', 'settings.gradle.kts')):
        raise ValueError('KAST_SOURCE_PROFILE_UNAVAILABLE')


def kast_source_evidence(workload, semantic):
    """Compiler exhaustive coverage and exact cross-artifact equality establish the full answer.

    Independently selected production declarations/sites are sufficiency witnesses, not a
    compiler-free enumeration oracle. They never replace the full retained semantic answer.
    """
    unavailable, items = [], semantic['items']
    if semantic['terminal']['failures'] or semantic['terminal']['omissions']:
        unavailable.append('PRODUCTION_EVIDENCE_INCOMPLETE')
    identities = [i.get('signature', {}).get('qualifiedIdentity') for i in items]
    if workload == 'exact-source':
        if (identities != [exact_identity(WorkloadProfile.KAST_SOURCE)] or
                not items[0].get('source', {}).get('text')):
            unavailable.append('REQUESTED_DECLARATION_OR_SOURCE_UNPROVEN')
    elif workload == 'scoped-all':
        witnesses = {'io.github.amichne.kast.kernel.NamedRoot.Companion.parse',
                     'io.github.amichne.kast.kernel.OperationId.Companion.parse',
                     'io.github.amichne.kast.kernel.CapabilityId.Companion.parse'}
        if len(items) <= COMPARISON_BUDGET['maxResults'] or not witnesses <= set(identities):
            unavailable.append('PRODUCTION_DECLARATION_WITNESSES_MISSING')
        for item in items:
            if (item.get('kind') != 'function' or not item.get('location', {}).get('file') or
                    not item['location']['file'].endswith('.kt')):
                unavailable.append('PRODUCTION_DECLARATION_IDENTITY_UNPROVEN')
    elif workload == 'dense-references':
        if len(items) <= COMPARISON_BUDGET['maxResults']:
            unavailable.append('PRODUCTION_REFERENCE_DENSITY_UNPROVEN')
        witnessed = False
        for item in items:
            occurrence = item.get('occurrence', {})
            site = occurrence.get('occurrence', {})
            target = occurrence.get('target', {})
            compiler = target.get('compilerEvidence', {})
            bounds = site.get('range', {})
            if (not compiler.get('signature') or not compiler.get('identity') or
                    type(bounds.get('startInclusive')) is not int or type(bounds.get('endExclusive')) is not int or
                    not 0 <= bounds['startInclusive'] < bounds['endExclusive']):
                unavailable.append('COMPILER_OCCURRENCE_EVIDENCE_UNPROVEN')
            if (item.get('type') != 'reference-occurrence' or
                    target.get('qualifiedIdentity') != KAST_PACKAGE + '.QueryStepSyntax' or
                    occurrence.get('coverage') != 'exact-compiler-confirmed' or
                    occurrence.get('provenance') != 'k2-authored-source'):
                unavailable.append('COMPILER_OCCURRENCE_EVIDENCE_UNPROVEN')
            # The production plan carries QueryStepSyntax as its steps type in QueryPlan.kt.
            if site.get('file', '').endswith('/query/contract/QueryPlan.kt') and occurrence.get('context', '').upper() == 'TYPE':
                witnessed = True
        if not witnessed: unavailable.append('PRODUCTION_REFERENCE_WITNESS_MISSING')
    return unavailable

def continuation(response):
    progress = response.get('qualification', {}).get('progress', {})
    if progress.get('type') == 'resumable' and progress.get('next_action') == 'resume':
        return progress.get('checkpoint', {}).get('token')
    return None


def drain_workload(request, invoke, max_calls):
    initial = request
    calls, seen, live = [], set(), None
    for _ in range(max_calls):
        call = invoke(request)
        calls.append(call)
        response = call.response
        if response is None or call.process.get('outcome') != 'completed':
            break
        basis = response.get('live')
        if live is not None and basis != live:
            break
        live = basis
        token = continuation(response)
        grant = replay_wire.ExecutionBudget(**initial['request']['executionBudget'])
        if token is not None:
            if token in seen: break
            seen.add(token)
            request = asdict(replay_wire.Query(replay_wire.Resume(token, grant)))
        else:
            progress = presentation(calls)
            if not isinstance(progress, PresentationNext): break
            selected = initial['request']['output']
            if selected['type'] == 'SYMBOLS':
                output = replay_wire.Symbols([replay_wire.SymbolField(f) for f in selected['fields']])
            elif selected['type'] == 'OCCURRENCES':
                output = replay_wire.Occurrences()
            else: raise ValueError('UNSUPPORTED_WORKLOAD_OUTPUT')
            request = asdict(replay_wire.Query(replay_wire.ReadResult(
                progress.result.value, progress.cursor.value, output, grant, progress.evidence_cursor.value)))
    return calls


def stable_semantics(responses):
    """Only opaque handles with companion semantic identity and bound host/epoch are normalized.

    No path, source text, compiler digest, range, qualification, failure or omission is erased.
    Pagination and discovery work observations are separated from the terminal semantic answer;
    every original page is retained in ReplayCall, including qualified prefixes.
    """
    handles = {}
    def collect(value):
        if isinstance(value, dict):
            signature = value.get('signature') or value.get('compilerEvidence', {}).get('signature')
            location = value.get('location') or (dict(file=value['file'], range=value['range'])
                                                 if 'file' in value and 'range' in value else None)
            if signature and location:
                for key in ('ref', 'selector'):
                    if isinstance(value.get(key), str):
                        identity = dict(signature=signature, location=location)
                        if value[key] in handles and handles[value[key]] != identity:
                            raise ValueError('HANDLE_IDENTITY_CHANGED')
                        handles[value[key]] = identity
            # A candidate occurrence handle is proven by its own exact file/range.
            if 'candidateSelector' in value and 'file' in value and 'range' in value:
                identity = dict(file=value['file'], range=value['range'])
                token = value['candidateSelector']
                if token in handles and handles[token] != identity:
                    raise ValueError('HANDLE_IDENTITY_CHANGED')
                handles[token] = identity
            # Reference-occurrence rows carry their confirmed target separately.
            if value.get('type') == 'reference-occurrence' and 'ref' in value:
                target = value.get('occurrence', {}).get('target', {})
                collect(target)
                selector = target.get('selector')
                if selector in handles: handles[value['ref']] = handles[selector]
            for child in value.values(): collect(child)
        elif isinstance(value, list):
            for child in value: collect(child)
    for response in responses: collect(response)
    def normalize(value):
        if isinstance(value, list): return [normalize(x) for x in value]
        if not isinstance(value, dict): return value
        result = {}
        for key, child in value.items():
            if key in ('ref', 'selector', 'candidateSelector') and isinstance(child, str):
                if child not in handles: raise ValueError('UNPROVEN_HANDLE_IDENTITY')
                result[key] = handles[child]
            elif key == 'live':
                result[key] = {k: v for k, v in child.items() if k not in ('host', 'epoch')}
            else: result[key] = normalize(child)
        return result
    terminal = responses[-1]
    required = {'status', 'items', 'failures', 'omissions', 'coverage', 'live'}
    if not required <= terminal.keys(): raise ValueError('MISSING_SEMANTIC_FIELDS')
    # Prefix qualifications are superseded only after the entire chain reaches exhaustive completion.
    terminal_fields = {k: terminal[k] for k in ('status', 'coverage', 'live', 'qualification', 'execution_budget', 'retention', 'next_cursor') if k in terminal}
    items, failures, omissions, walks, references = [], [], [], [], []
    universes, discovery_progress = [], []
    for response in responses:
        if not required <= response.keys(): raise ValueError('MISSING_SEMANTIC_FIELDS')
        items.extend(normalize(response['items']))
        failures.extend(normalize(response['failures']))
        omissions.extend(normalize(response['omissions']))
        walks.extend(normalize(response.get('walk_observations', [])))
        references.extend(normalize(response.get('reference_observations', [])))
        for observed in response.get('discovery_observations', []):
            # Quantities/durations measure execution; universe, ordering and terminal progress are evidence.
            universe = {k: observed[k] for k in ('universe', 'ordering')}
            if universe not in universes: universes.append(universe)
            if observed.get('progress', {}).get('kind') == 'exhausted':
                discovery_progress.append(observed['progress'])
    terminal_fields.update(failures=failures, omissions=omissions)
    return dict(items=items, terminal=normalize(terminal_fields), walkObservations=walks,
                referenceObservations=references, discoveryUniverses=universes, discoveryCompletion=discovery_progress)


class NativeCallMeasurementFailure(str, Enum):
    MISSING = 'NATIVE_CALL_COUNTS_UNAVAILABLE'
    INVALID = 'NATIVE_CALL_COUNTS_INVALID'
    SATURATED = 'NATIVE_CALL_COUNTS_SATURATED'
    UNFINISHED = 'NATIVE_CALL_COUNTS_UNFINISHED'
    INTERRUPTED = 'NATIVE_CALL_COUNTS_INTERRUPTED'


@dataclass(frozen=True)
class ObservedNativeCallCounts:
    counts: dict[str, int]


@dataclass(frozen=True)
class UnavailableNativeCallCounts:
    failure: NativeCallMeasurementFailure


def native_call_counts(receipt):
    """Exact invocations at declared boundaries; no estimate of opaque executor work or time weights."""
    vocabulary, rows = receipt.get('nativeCallVocabulary'), receipt.get('nativeCalls')
    def rejected(reason=NativeCallMeasurementFailure.INVALID):
        return UnavailableNativeCallCounts(reason)
    if vocabulary is None or rows is None: return rejected(NativeCallMeasurementFailure.MISSING)
    if (not isinstance(vocabulary, list) or not vocabulary or len(vocabulary) > 128 or
            any(not isinstance(name, str) or not name or len(name) > 80 for name in vocabulary) or
            len(set(vocabulary)) != len(vocabulary) or not isinstance(rows, list) or
            len(rows) > len(vocabulary) * (len(vocabulary) + 1)): return rejected()
    declared, roots, seen, totals = set(vocabulary), set(), set(), {name: 0 for name in vocabulary}
    qualities = set()
    required = {'call', 'parent', 'entered', 'returned', 'cancelled', 'failed', 'unfinished',
                'qualification', 'firstEntry', 'durationNanos'}
    for row in rows:
        if not isinstance(row, dict) or set(row) != required: return rejected()
        kind, parent = row['call'], row['parent']
        if not isinstance(kind, str) or kind not in declared or not isinstance(parent, dict): return rejected()
        if parent == {'type': 'root'}:
            parent_key = 'ROOT'
            roots.add(kind)
        elif set(parent) == {'type', 'call'} and parent['type'] == 'call' and isinstance(parent['call'], str) and parent['call'] in declared:
            parent_key = parent['call']
        else: return rejected()
        key = (kind, parent_key)
        if key in seen: return rejected()
        seen.add(key)
        if any(type(row[field]) is not int or row[field] < 0 for field in
               ('entered', 'returned', 'cancelled', 'failed', 'unfinished', 'durationNanos')): return rejected()
        if row['qualification'] not in ('EXACT', 'SATURATED'): return rejected()
        entry = row['firstEntry']
        if row['entered'] == 0:
            if entry != {'type': 'not-entered'}: return rejected()
        elif (not isinstance(entry, dict) or set(entry) != {'type', 'nanos'} or entry['type'] != 'entered' or
              type(entry['nanos']) is not int or entry['nanos'] < 0): return rejected()
        if row['qualification'] == 'SATURATED': qualities.add(NativeCallMeasurementFailure.SATURATED)
        elif row['entered'] != sum(row[field] for field in ('returned', 'cancelled', 'failed', 'unfinished')):
            return rejected()
        if row['unfinished']: qualities.add(NativeCallMeasurementFailure.UNFINISHED)
        if row['cancelled'] or row['failed']: qualities.add(NativeCallMeasurementFailure.INTERRUPTED)
        totals[kind] += row['entered']
    if roots != declared: return rejected()
    for reason in (NativeCallMeasurementFailure.SATURATED, NativeCallMeasurementFailure.UNFINISHED,
                   NativeCallMeasurementFailure.INTERRUPTED):
        if reason in qualities: return rejected(reason)
    return ObservedNativeCallCounts(totals)


class ExactNegativeFailure(str, Enum):
    UNAVAILABLE = 'EXACT_NEGATIVE_EVIDENCE_UNAVAILABLE'
    NOT_COMPLETE = 'EXACT_NEGATIVE_NOT_COMPLETE'
    NOT_EXHAUSTIVE = 'EXACT_NEGATIVE_NOT_EXHAUSTIVE'
    RETURNED_ITEMS = 'EXACT_NEGATIVE_RETURNED_ITEMS'
    FAILURES = 'EXACT_NEGATIVE_SEMANTIC_FAILURES'
    OMISSIONS = 'EXACT_NEGATIVE_OMISSIONS'


@dataclass(frozen=True)
class CompleteExactNegative:
    pass


@dataclass(frozen=True)
class RejectedExactNegative:
    failure: ExactNegativeFailure


def admit_exact_negative(items, terminal):
    if (not isinstance(items, list) or not isinstance(terminal, dict) or
        not isinstance(terminal.get('coverage'), dict) or
        not isinstance(terminal.get('failures'), list) or not isinstance(terminal.get('omissions'), list)):
        return RejectedExactNegative(ExactNegativeFailure.UNAVAILABLE)
    if terminal.get('status') != 'complete': return RejectedExactNegative(ExactNegativeFailure.NOT_COMPLETE)
    if terminal['coverage'].get('exhaustive') is not True:
        return RejectedExactNegative(ExactNegativeFailure.NOT_EXHAUSTIVE)
    if items: return RejectedExactNegative(ExactNegativeFailure.RETURNED_ITEMS)
    if terminal['failures']: return RejectedExactNegative(ExactNegativeFailure.FAILURES)
    if terminal['omissions']: return RejectedExactNegative(ExactNegativeFailure.OMISSIONS)
    return CompleteExactNegative()


def finish_trial(workload, repetition, warmup, calls, total_nanos, first_usable, profile=WorkloadProfile.RELIABILITY_FIXTURE):
    unavailable, responses = [], [c.response for c in calls if c.response is not None]
    semantic = None
    state = 'UNAVAILABLE'
    if any(c.process.get('outcome') == 'canceled' for c in calls): state = 'CANCELED'
    elif len(responses) == len(calls) and calls:
        last = responses[-1]
        state = 'REJECTED' if last.get('status') == 'rejected' or last.get('failure') else 'INCOMPLETE'
        if all(c.process.get('outcome') == 'completed' and c.process.get('exitCode') == 0 and
               c.response.get('status') in ('complete', 'qualified') for c in calls):
            if last.get('status') == 'complete' and last.get('coverage', {}).get('exhaustive') is True:
                state = 'COMPLETE'
        if len({json.dumps(x.get('live'), sort_keys=True) for x in responses}) != 1:
            state = 'INCOMPLETE'
            unavailable.append('HOST_OR_EPOCH_MOVED')
        presented = presentation(calls)
        if isinstance(presented, (PresentationNext, PresentationRejected)):
            if state == 'COMPLETE': state = 'INCOMPLETE'
            unavailable.append(presented.failure.value if isinstance(presented, PresentationRejected)
                               else PresentationFailure.UNREAD.value)
        try: semantic = stable_semantics(responses)
        except ValueError as error:
            unavailable.append(str(error))
        except (KeyError, TypeError):
            unavailable.append('MISSING_SEMANTIC_FIELDS')
    if semantic is None: unavailable.append('SEMANTIC_EVIDENCE_UNAVAILABLE')
    elif state == 'COMPLETE' and workload == Workload.EXACT_NEGATIVE:
        admitted = admit_exact_negative(semantic['items'], semantic['terminal'])
        if isinstance(admitted, RejectedExactNegative): unavailable.append(admitted.failure.value)
    elif state == 'COMPLETE' and profile == WorkloadProfile.KAST_SOURCE:
        unavailable.extend(kast_source_evidence(workload, semantic))
    elif state == 'COMPLETE':
        items = semantic['items']
        if workload == 'exact-source' and (len(items) != 1 or not items[0].get('source') or
                items[0].get('signature', {}).get('qualifiedIdentity') != 'repro.logging.FixtureLogger'):
            unavailable.append('REQUESTED_DECLARATION_OR_SOURCE_UNPROVEN')
        if workload == 'scoped-all':
            expected = [f'reliability.source.pageItem{i:02d}' for i in range(64)]
            expected += [f'repro.logging.FixtureLogger.{name}' for name in ('trace', 'debug', 'info', 'warn', 'error')]
            expected += [f'repro.logging.Identity{name}.sharedOperation' for name in ('One', 'Two', 'Three', 'Four', 'Five')]
            if Counter(i.get('signature', {}).get('qualifiedIdentity') for i in items) != Counter(expected):
                unavailable.append('AUTHORED_DECLARATION_ORACLE_MISMATCH')
        if workload == 'dense-references':
            target_identity = 'fixture.reference.dense.DenseReferenceTarget'
            actual = []
            for item in items:
                occurrence = item.get('occurrence', {})
                site = occurrence.get('occurrence', {})
                target = occurrence.get('target', {})
                if (item.get('type') != 'reference-occurrence' or target.get('qualifiedIdentity') != target_identity or
                        occurrence.get('coverage') != 'exact-compiler-confirmed' or
                        occurrence.get('provenance') != 'k2-authored-source'):
                    unavailable.append('COMPILER_OCCURRENCE_EVIDENCE_UNPROVEN')
                bounds = site.get('range', {})
                actual.append((site.get('file', '').split('/')[-1], bounds.get('startInclusive'),
                               bounds.get('endExclusive'), occurrence.get('context', '').upper()))
            wanted = [('ReadDenseReferences.kt', 69, 89, 'IMPORT')] + [
                ('ReadDenseReferences.kt', 138 + 52 * i, 158 + 52 * i, 'TYPE') for i in range(1000)]
            if Counter(actual) != Counter(wanted): unavailable.append('AUTHORED_OCCURRENCE_ORACLE_MISMATCH')
    diagnostics_ok = bool(calls) and all(c.correlation == 'MATCHED' and len(c.diagnostics) == 1 and
        {'counters', 'nativePhaseDurations', 'stages'} <= c.diagnostics[0].keys() for c in calls)
    counters, phases, stages, pages = None, None, None, None
    if diagnostics_ok:
        counters, phases, stages = {}, [], []
        page_keys = {'NATIVE_DISCOVERY_PAGES/NONE', 'NATIVE_RELATION_PAGES/NONE'}
        pages_observed = True
        for call in calls:
            receipt = call.diagnostics[0]
            observed_keys = {c['counter'] + '/' + c['contributor'] for c in receipt['counters']}
            pages_observed = pages_observed and page_keys <= observed_keys
            if profile == WorkloadProfile.KAST_SOURCE and not LOCATOR_OUTCOME_COUNTERS <= observed_keys:
                unavailable.append('LOCATOR_OUTCOME_COUNTERS_UNAVAILABLE')
            ceiling = next((x['value'] for x in receipt.get('limits', []) if x['parameter'] == 'DIAGNOSTIC_COUNT'), None)
            for count in receipt['counters']:
                if ceiling is not None and count['count'] >= ceiling: unavailable.append('COUNTER_SATURATED')
                key = count['counter'] + '/' + count['contributor']
                counters[key] = counters.get(key, 0) + count['count']
            if receipt.get('schemaVersion', 0) >= 7:
                native = native_call_counts(receipt)
                if isinstance(native, UnavailableNativeCallCounts): unavailable.append(native.failure.value)
                else:
                    for kind, count in native.counts.items():
                        key = 'NATIVE_CALLS/' + kind
                        counters[key] = counters.get(key, 0) + count
            # Per-call vectors are kept separate. Stage and phase clocks overlap; never add them together.
            phases.append(receipt['nativePhaseDurations'])
            stages.append(receipt['stages'])
        # Only explicit native page counters establish page quantities; public calls never stand in for pages.
        if pages_observed: pages = {k: counters[k] for k in sorted(page_keys)}
        else: unavailable.append('NATIVE_PAGE_COUNTERS_UNAVAILABLE')
    else: unavailable.append('NATIVE_DIAGNOSTICS_UNAVAILABLE_OR_UNCORRELATED')
    encoded = (sum(len(c.process['stdout'].encode('utf-8')) for c in calls)
               if calls and all(c.response is not None and c.process.get('outcome') == 'completed' for c in calls) else None)
    measurements = dict(publicCalls=len(calls) if all(c.process.get('outcome') != 'launch-rejected' for c in calls) else None, encodedBytes=encoded, firstUsableNanos=first_usable,
                        totalNanos=total_nanos, counters=counters, nativePages=pages,
                        nativePhaseDurations=phases, stageDurations=stages)
    return ReplayTrial(TrialState(state), workload, repetition, warmup, calls, semantic, measurements, unavailable)


def incompatible_runs(baseline, candidate):
    keys = ('fixture', 'environment', 'limits', 'requests', 'warmups', 'repetitions',
            'concurrency', 'maxCalls', 'timeoutSeconds', 'cachePolicy', 'evidenceLevel', 'transport')
    return [key for key in keys if key not in baseline or key not in candidate or baseline[key] != candidate[key]]


def work_counter_deltas(counters):
    """exactIssued records exactly one outcome per completed locator-retention pipeline attempt.

    Preserve both raw outcomes separately; their sum measures attempts, not unique stored locators,
    store calls, allocation, or per-token availability. An outcome shift alone proves no work reduction.
    """
    observed = LOCATOR_OUTCOME_COUNTERS & counters.keys()
    if observed and observed != LOCATOR_OUTCOME_COUNTERS: raise ValueError('LOCATOR_OUTCOME_COUNTERS_UNAVAILABLE')
    work = {key: value for key, value in counters.items() if key not in LOCATOR_OUTCOME_COUNTERS}
    if observed:
        work['LOCATOR_RETENTION_ATTEMPTS/NONE'] = sum(counters[key] for key in LOCATOR_OUTCOME_COUNTERS)
    return work


def work_does_not_increase(counters):
    if counters is None: return False
    observed = LOCATOR_OUTCOME_COUNTERS & counters.keys()
    if observed:
        if observed != LOCATOR_OUTCOME_COUNTERS: return False
        # Fewer successes or additional rejections cannot qualify, even with fewer other operations.
        if counters[LOCATOR_RETAINED_COUNTER] < 0 or counters[LOCATOR_REJECTED_COUNTER] > 0: return False
    return all(delta <= 0 for delta in work_counter_deltas(counters).values())


def compare_trials(baseline, candidate, same_artifact):
    deltas = {}
    missing = sorted(set(baseline.unavailable + candidate.unavailable))
    for key in MEASUREMENT_KEYS:
        a, b = baseline.measurements[key], candidate.measurements[key]
        if a is None or b is None:
            deltas[key] = None
            missing.append(key)
        elif key in ('counters', 'nativePages'):
            # Absent counters are unobserved, never zero. Only compare the intersection.
            deltas[key] = {k: b[k] - a[k] for k in sorted(a.keys() & b.keys())}
            if a.keys() != b.keys(): missing.append(key + ':counter-set-changed')
        elif key in ('nativePhaseDurations', 'stageDurations'):
            deltas[key] = dict(baseline=a, candidate=b)
        else: deltas[key] = b - a
    a, b = baseline.measurements['counters'], candidate.measurements['counters']
    if a is not None and b is not None and LOCATOR_OUTCOME_COUNTERS & (a.keys() | b.keys()):
        if LOCATOR_OUTCOME_COUNTERS <= a.keys() and LOCATOR_OUTCOME_COUNTERS <= b.keys():
            attempts_a, attempts_b = (sum(counts[k] for k in LOCATOR_OUTCOME_COUNTERS) for counts in (a, b))
            deltas['locatorRetentionAttempts'] = dict(baseline=attempts_a, candidate=attempts_b,
                                                    delta=attempts_b - attempts_a)
        else:
            deltas['locatorRetentionAttempts'] = None
            missing.append('LOCATOR_OUTCOME_COUNTERS_UNAVAILABLE')
    state = 'EQUIVALENT'
    if baseline.type != 'COMPLETE' or candidate.type != 'COMPLETE': state = 'INCOMPLETE'
    elif baseline.semantic is None or candidate.semantic is None: state = 'MISSING_EVIDENCE'
    elif baseline.semantic != candidate.semantic: state = 'SEMANTIC_REGRESSION'
    elif {json.dumps(c.response.get('execution_budget'), sort_keys=True) for c in baseline.calls} != {json.dumps(c.response.get('execution_budget'), sort_keys=True) for c in candidate.calls}:
        state = 'MISSING_EVIDENCE'
        missing.append('EFFECTIVE_BUDGETS_DIFFER')
    elif missing: state = 'MISSING_EVIDENCE'
    counters = deltas.get('counters')
    less_work = (state == 'EQUIVALENT' and not same_artifact and work_does_not_increase(counters) and
                 any(x < 0 for x in work_counter_deltas(counters).values()))
    return TrialComparison(ComparisonState(state), less_work, deltas, sorted(set(missing)),
                           compare_hosted_timings(baseline.calls, candidate.calls))


def load_trial(path, profile=WorkloadProfile.RELIABILITY_FIXTURE):
    """Closed local receipt parser. Public response/diagnostic fields are retained opaque contracts."""
    from dataclasses import fields
    value = json.loads(path.read_text())
    if set(value) != {f.name for f in fields(ReplayTrial)}: raise ValueError('INVALID_TRIAL_FIELDS')
    if type(value['schemaVersion']) is not int or value['schemaVersion'] != 2:
        raise ValueError('INVALID_TRIAL_VERSION')
    if value['type'] not in {'COMPLETE', 'REJECTED', 'INCOMPLETE', 'CANCELED', 'UNAVAILABLE'}:
        raise ValueError('INVALID_TRIAL_TYPE')
    if value['workload'] not in comparison_requests(): raise ValueError('INVALID_WORKLOAD')
    if type(value['repetition']) is not int or value['repetition'] < 0 or type(value['warmup']) is not bool:
        raise ValueError('INVALID_TRIAL_IDENTITY')
    if set(value['measurements']) != MEASUREMENT_KEYS: raise ValueError('INVALID_MEASUREMENT_FIELDS')
    def quantities(value):
        if isinstance(value, dict):
            for v in value.values(): quantities(v)
        elif isinstance(value, list):
            for v in value: quantities(v)
        elif value is not None and not isinstance(value, str) and (type(value) is not int or value < 0):
            raise ValueError('INVALID_MEASUREMENT')
    quantities(value['measurements'])
    for key in ('publicCalls', 'encodedBytes', 'firstUsableNanos', 'totalNanos'):
        measured = value['measurements'][key]
        if measured is not None and (type(measured) is not int or measured < 0):
            raise ValueError('INVALID_MEASUREMENT')
    first, total = value['measurements']['firstUsableNanos'], value['measurements']['totalNanos']
    if first is not None and (total is None or first > total): raise ValueError('INVALID_MEASUREMENT_ORDER')
    calls = []
    for call in value['calls']:
        if set(call) != {f.name for f in fields(ReplayCall)}: raise ValueError('INVALID_CALL_FIELDS')
        if call['correlation'] not in {'MATCHED', 'UNAVAILABLE', 'AMBIGUOUS', 'MISMATCHED'}:
            raise ValueError('INVALID_CORRELATION')
        if type(call['observationCaptureNanos']) is not int or call['observationCaptureNanos'] < 0:
            raise ValueError('INVALID_OBSERVATION_CAPTURE_DURATION')
        calls.append(ReplayCall(**{**call, 'hostedObservations': load_window(call['hostedObservations'])}))
    parsed = ReplayTrial(**{**value, 'type': TrialState(value['type']), 'calls': calls})
    derived = finish_trial(parsed.workload, parsed.repetition, parsed.warmup, calls,
                           parsed.measurements['totalNanos'], parsed.measurements['firstUsableNanos'], profile)
    if parsed != derived: raise ValueError('RECEIPT_DERIVATION_MISMATCH')
    return parsed


def artifact_identity(pinned):
    return dict(executable=pinned['cli']['sha256'], cliJars=sorted(pinned['cli']['jars'].values()),
                pluginJars=sorted(pinned['plugin']['jars'].values()),
                loadedClasses={name: value['sha256'] for name, value in pinned['plugin']['native']['classResources'].items()},
                schema=pinned['installedSchemaSha256'], catalogExecutable=pinned['cli'].get('catalogExecutableSha256', pinned['cli']['sha256']))


@dataclass(frozen=True)
class McpReplayRequest:
    id: int
    method: str
    params: dict
    jsonrpc: str = '2.0'


class McpWireFailure(str, Enum):
    INVALID_FRAME = 'MCP_FRAME_INVALID'
    CORRELATION_REJECTED = 'MCP_CORRELATION_REJECTED'
    PROCESS_ENDED = 'MCP_PROCESS_ENDED'
    FRAME_TOO_LARGE = 'MCP_FRAME_TOO_LARGE'
    IO_UNAVAILABLE = 'MCP_IO_UNAVAILABLE'


@dataclass(frozen=True)
class McpWireAccepted:
    envelope: dict


@dataclass(frozen=True)
class McpWireRejected:
    failure: McpWireFailure


def admit_mcp_wire(stdout, sequence):
    def unique_fields(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError('DUPLICATE_MCP_FIELD')
            result[key] = value
        return result
    def invalid_constant(value):
        raise ValueError('NON_JSON_CONSTANT')
    try: response = json.loads(stdout, object_pairs_hook=unique_fields, parse_constant=invalid_constant)
    except (ValueError, TypeError): return McpWireRejected(McpWireFailure.INVALID_FRAME)
    if (not isinstance(response, dict) or set(response) - {'jsonrpc', 'id', 'result', 'error'} or
            ('result' in response) == ('error' in response) or
            not isinstance(response.get('result', response.get('error')), dict)):
        return McpWireRejected(McpWireFailure.INVALID_FRAME)
    if type(response.get('id')) is not int or response['id'] != sequence or response.get('jsonrpc') != '2.0':
        return McpWireRejected(McpWireFailure.CORRELATION_REJECTED)
    return McpWireAccepted(response)


class McpReplaySession:
    """One installed MCP stdio owner; its existing session prepares the workspace once.

    Request arguments and result documents are opaque public schema contracts.
    Control replies and exact wire frames stay in the existing reproduction output.
    """
    def __init__(self, cli, root, output, timeout):
        self.output, self.timeout, self.sequence, self.pending = output, timeout, 0, b''
        self.error_path = output / 'mcp-session.stderr'
        self.errors = self.error_path.open('wb')
        try:
            self.process = subprocess.Popen([str(cli)], cwd=root, stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=self.errors)
        except OSError:
            self.errors.close()
            raise
        self.command, self.root = [str(cli)], root
        self.control('initialize', dict(protocolVersion='2025-06-18', capabilities={},
                     clientInfo=dict(name='kast-semantic-reproduction', version='1')))

    def exchange(self, method, params):
        started, before = time.monotonic_ns(), self.error_path.stat().st_size
        stdout, failure = '', None
        self.sequence += 1
        wire = json.dumps(asdict(McpReplayRequest(self.sequence, method, params))) + '\n'
        try:
            self.process.stdin.write(wire.encode()); self.process.stdin.flush()
            deadline = time.monotonic() + self.timeout
            while b'\n' not in self.pending:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not select.select([self.process.stdout], [], [], remaining)[0]:
                    raise subprocess.TimeoutExpired(self.command, self.timeout)
                block = os.read(self.process.stdout.fileno(), 65536)
                if not block:
                    failure = McpWireFailure.PROCESS_ENDED
                    break
                self.pending += block
                if len(self.pending) > 64 * 1024 * 1024:
                    failure = McpWireFailure.FRAME_TOO_LARGE
                    break
            if failure is None:
                line, self.pending = self.pending.split(b'\n', 1)
                try: stdout = (line + b'\n').decode('utf-8')
                except UnicodeDecodeError: failure = McpWireFailure.INVALID_FRAME
                if failure is None:
                    admitted = admit_mcp_wire(stdout, self.sequence)
                    if isinstance(admitted, McpWireRejected): failure = admitted.failure
            outcome = 'completed' if failure is None else 'exchange-rejected'
        except subprocess.TimeoutExpired:
            outcome, failure = 'harness-timeout', 'HARNESS_TIMEOUT'
        except KeyboardInterrupt:
            outcome, failure = 'canceled', 'CANCELED'
        except OSError:
            outcome, failure = 'launch-rejected', McpWireFailure.IO_UNAVAILABLE
        elapsed = time.monotonic_ns() - started
        with self.error_path.open('rb') as stream:
            stream.seek(before); stderr = stream.read(2 * 1024 * 1024).decode(errors='replace')
        result = dict(command=self.command, cwd=str(self.root), stdin=wire, stdout=stdout,
                      stderr=stderr, elapsedNanos=elapsed, outcome=outcome)
        if outcome == 'completed': result['exitCode'] = 0
        else: result['failure'] = failure
        return result

    def control(self, method, params):
        receipt = self.exchange(method, params)
        write(self.output / ('mcp-' + method.replace('/', '-') + '.json'), receipt)
        if receipt['outcome'] != 'completed':
            self.close(); raise ValueError('MCP_CONTROL_UNAVAILABLE')
        reply = json.loads(receipt['stdout'])
        if 'error' in reply:
            self.close(); raise ValueError('MCP_CONTROL_REJECTED')
        return reply['result']

    def catalog(self):
        return self.control('tools/list', {})

    def call(self, arguments):
        return self.exchange('tools/call', dict(name='query_symbols', arguments=arguments))

    def close(self):
        if not self.process.stdin.closed: self.process.stdin.close()
        try: self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            try: self.process.wait(timeout=5)
            except subprocess.TimeoutExpired: self.process.kill(); self.process.wait()
        self.process.stdout.close(); self.errors.close()


def replay_workloads(args):
    from dataclasses import asdict
    import platform
    import jsonschema
    output = fresh(args.output)
    root, cli = args.fixture.resolve(strict=True), args.cli.resolve(strict=True)
    pinned = json.loads(args.pin.read_text())
    # Reuse native pin at both boundaries; validates the loaded plugin, not merely an on-disk CLI label.
    def repin(label):
        pin_args = argparse.Namespace(output=output / label, fixture=root, cli=args.cli,
            idea_contents=args.idea_contents, source_tree=None, public_rpc=True,
            host_pid=pinned['host']['pid'],
            qualification_slice=pinned['qualificationSlice'], public_mcp=pinned["cli"].get("transport") == "MCP_SESSION")
        if pin(pin_args) != 0: raise ValueError('NATIVE_PIN_UNAVAILABLE')
        current = json.loads((pin_args.output / 'pin.json').read_text())
        host = admit_same_host(pinned['host'], current['host'])
        if isinstance(host, RejectedNativeHost): raise ValueError(host.failure.value)
        if artifact_identity(current) != artifact_identity(pinned): raise ValueError('PIN_CHANGED:artifact')
        for key in ('fixture', 'limits'):
            if current[key] != pinned[key]: raise ValueError('PIN_CHANGED:' + key)
        return current
    profile = WorkloadProfile(getattr(args, 'workload_profile', WorkloadProfile.RELIABILITY_FIXTURE))
    fixture = {**pinned['fixture'], 'type': 'SYNTHETIC'}
    if profile == WorkloadProfile.KAST_SOURCE:
        archive = args.source_archive.resolve(strict=True)
        fixture = {**pinned['fixture'], 'type': 'REPRESENTATIVE', 'sourceArchive':
                   dict(path=str(archive), sha256=digest(archive), commit=args.source_commit, repository='amichne/kast')}
        admit_kast_source_fixture(fixture)
    else:
        for name in ('ReadDenseReferenceTarget.kt', 'ReadDenseReferences.kt', 'ReadPageBudget.kt'):
            fixture_source = root / 'logging/src/main/kotlin/reliability' / name
            if not fixture_source.is_file() or digest(fixture_source) != digest(FIXTURE / 'read-reliability' / name):
                raise ValueError('COMPARISON_FIXTURE_NOT_PREPARED')
    try:
        current = repin('start-pin')
    except (ValueError, RuntimeError, OSError, AssertionError):
        write(output / 'unavailable.json', asdict(TrialComparison(
            ComparisonState.MISSING_EVIDENCE, False, {k: None for k in MEASUREMENT_KEYS}, ['NATIVE_PIN_UNAVAILABLE'])))
        return 2
    requests = comparison_requests(profile)
    schema = json.loads((args.pin.parent / 'installed-schema.json').read_text())
    tools = schema['catalog']['tools']
    ToolSurface.admit(tools)
    query_schema = next(t['inputSchema'] for t in tools if t['name'] == 'query_symbols')
    manifest = asdict(ReplayRun(type='SEMANTIC_REPLAY', schemaVersion=1, artifact=artifact_identity(pinned), fixture=fixture,
        environment=dict(ideaBuild=current['host']['ideaBuild'], jbr=current['host']['jbr'],
            kotlinPlugin=current['host']['kotlinPlugin'], os=platform.platform(), machine=platform.machine(),
            javaHome=current['host']['javaHome'], cliJavaHome=os.environ.get('JAVA_HOME')),
        limits={**pinned['limits'], 'observationCapture': OBSERVATION_POLICY}, requests=requests, warmups=args.warmups, repetitions=args.repeats,
        concurrency=1, maxCalls=args.max_calls, timeoutSeconds=args.timeout,
        cachePolicy='existing host caches; no invalidation; warmups fully drained and retained', evidenceLevel='NATIVE', trials=[],
        transport=pinned['cli'].get('transport', 'TOOL_RPC')))
    write(output / 'run.json', manifest)
    session = None
    try:
        session = McpReplaySession(cli, root, output, args.timeout) if manifest['transport'] == 'MCP_SESSION' else None
        if session:
            observed = session.catalog()
            if {t['name']: t['inputSchema'] for t in observed['tools']} != {t['name']: t['inputSchema'] for t in tools}:
                session.close()
                raise ValueError('MCP_CATALOG_CHANGED')
    except (OSError, ValueError, KeyError, TypeError):
        if session: session.close()
        manifest['type'] = 'NATIVE_UNAVAILABLE'
        write(output / 'run.json', manifest)
        write(output / 'unavailable.json', asdict(TrialComparison(
            ComparisonState.MISSING_EVIDENCE, False, {k: None for k in MEASUREMENT_KEYS}, ['MCP_SESSION_UNAVAILABLE'])))
        return 2
    for warmup, repeats in ((True, args.warmups), (False, args.repeats)):
        for repetition in range(repeats):
            for workload, request in requests.items():
                directory = fresh(output / f'{workload}-{"warmup" if warmup else "trial"}-{repetition}')
                calls, first_usable, completion_nanos = [], None, None
                started = time.monotonic_ns()
                def invoke(request):
                    nonlocal first_usable, completion_nanos
                    jsonschema.Draft202012Validator(query_schema).validate(request)
                    call_dir = fresh(directory / f'call-{len(calls):04d}')
                    try: before = args.idea_log.stat() if args.idea_log else None
                    except OSError: before = None
                    process = session.call(request) if session else capture([cli, 'call', 'query_symbols'], root, json.dumps(request), args.timeout)
                    completion_nanos = workload_wall_nanos(time.monotonic_ns() - started, calls)
                    try:
                        envelope = json.loads(process['stdout'])
                        response = envelope.get('result', {}).get('structuredContent') if session else envelope.get('document') if envelope.get('type') in ('complete', 'qualified', 'rejected_document') else envelope if envelope.get('type') == 'rejected' else None
                        if not isinstance(response, dict): response = None
                    except (json.JSONDecodeError, AttributeError): response = None
                    observation_started = time.monotonic_ns()
                    observations = (collect_observations(args.idea_log, before,
                        ready=lambda window: response_read_released(response, window)) if before else
                        ObservationWindow(failures=(TimingFailure.LOG_UNAVAILABLE,)))
                    observation_capture_nanos = time.monotonic_ns() - observation_started
                    diagnostics, phases = list(observations.diagnostics), list(observations.phases)
                    correlated = correlate_diagnostic(response, diagnostics)
                    if isinstance(correlated, MatchedDiagnostic):
                        diagnostics, correlation = [correlated.document], 'MATCHED'
                    else: correlation = correlated.failure.value
                    call = ReplayCall(request['request']['type'], request, process, response, diagnostics, phases,
                                      correlation, observations, observation_capture_nanos)
                    calls.append(call)
                    if first_usable is None and process['outcome'] == 'completed' and usable_result(workload, response, profile):
                        first_usable = completion_nanos
                    write(call_dir / 'request.json', request)
                    write(call_dir / 'process.json', process)
                    write(call_dir / 'native-diagnostics.json', diagnostics)
                    write(call_dir / 'native-phases.json', phases)
                    write(call_dir / 'hosted-observations.json', asdict(observations))
                    write(call_dir / 'hosted-timing.json', asdict(call_timing(call)))
                    if response is not None: write(call_dir / 'response.json', response)
                    # Persist after every call, including interrupted and incomplete executions.
                    trial = finish_trial(workload, repetition, warmup, calls, completion_nanos, first_usable, profile)
                    write(directory / 'trial.json', asdict(trial))
                    return call
                drain_workload(request, invoke, args.max_calls)
                trial = finish_trial(workload, repetition, warmup, calls, completion_nanos, first_usable, profile)
                write(directory / 'trial.json', asdict(trial))
                manifest['trials'].append(str((directory / 'trial.json').relative_to(output)))
                write(output / 'run.json', manifest)
                print(f'{workload} {repetition} warmup={warmup}: {trial.type}', flush=True)
                if any(c.process['outcome'] in ('canceled', 'harness-timeout') for c in calls):
                    if session: session.close()
                    manifest['type'] = 'DRAIN_UNVERIFIED'
                    write(output / 'run.json', manifest)
                    return 2
    if session: session.close()
    try:
        ending = repin('end-pin')
        for key in ('ideaBuild', 'jbr', 'kotlinPlugin', 'javaHome', 'pid', 'processStart', 'model'):
            if ending['host'][key] != current['host'][key]: raise ValueError('HOST_CHANGED')
    except ValueError:
        manifest['type'] = 'PIN_CHANGED'
        write(output / 'run.json', manifest)
        raise
    return 0 if all(load_trial(output / p, profile).type == 'COMPLETE' for p in manifest['trials']) else 2


def load_run(path):
    value = json.loads(path.read_text())
    from dataclasses import fields
    names = {field.name for field in fields(ReplayRun)}
    if set(value) != names or value['schemaVersion'] != 1: raise ValueError('INVALID_RUN_FIELDS')
    if value['transport'] not in ('TOOL_RPC', 'MCP_SESSION'): raise ValueError('INVALID_TRANSPORT')
    if value['type'] not in {'SEMANTIC_REPLAY', 'PIN_CHANGED', 'NATIVE_UNAVAILABLE', 'DRAIN_UNVERIFIED'}:
        raise ValueError('INVALID_RUN_TYPE')
    for key, low, high in [('warmups', 0, 5), ('repetitions', 1, 5), ('concurrency', 1, 1),
                           ('maxCalls', 1, 1024), ('timeoutSeconds', 1, 300)]:
        if type(value[key]) is not int or not low <= value[key] <= high: raise ValueError('INVALID_RUN_POLICY')
    if value['evidenceLevel'] not in {'NATIVE', 'SCRIPTED'}: raise ValueError('INVALID_EVIDENCE_LEVEL')
    profile = workload_profile(value['requests'])
    if profile == WorkloadProfile.KAST_SOURCE:
        admit_kast_source_fixture(value['fixture'])
    for key in ('artifact', 'fixture', 'environment', 'limits'):
        if not isinstance(value[key], dict) or not value[key]: raise ValueError('MISSING_RUN_EVIDENCE')
    if value['evidenceLevel'] == 'NATIVE':
        environment = value['environment']
        required_environment = {'ideaBuild', 'jbr', 'kotlinPlugin', 'os', 'machine', 'javaHome', 'cliJavaHome'}
        if set(environment) != required_environment:
            raise ValueError('INVALID_NATIVE_ENVIRONMENT_FIELDS')
        for key in required_environment - {'cliJavaHome'}:
            if not isinstance(environment[key], str) or not environment[key].strip() or len(environment[key]) > 4096:
                raise ValueError('NATIVE_ENVIRONMENT_EVIDENCE_UNAVAILABLE')
        cli_home = environment['cliJavaHome']
        if cli_home is not None and (not isinstance(cli_home, str) or not cli_home.strip() or len(cli_home) > 4096):
            raise ValueError('INVALID_CLI_JAVA_HOME')
        artifact = value['artifact']
        if set(artifact) != {'executable', 'cliJars', 'pluginJars', 'loadedClasses', 'schema', 'catalogExecutable'}:
            raise ValueError('INVALID_ARTIFACT_FIELDS')
        for key in ('cliJars', 'pluginJars'):
            if not isinstance(artifact[key], list) or not artifact[key]:
                raise ValueError('NATIVE_ARTIFACT_EVIDENCE_UNAVAILABLE')
        if (not isinstance(artifact['loadedClasses'], dict) or not artifact['loadedClasses'] or
                any(not isinstance(name, str) or not name.strip() for name in artifact['loadedClasses'])):
            raise ValueError('NATIVE_ARTIFACT_EVIDENCE_UNAVAILABLE')
        hashes = [artifact['executable'], artifact['schema'], artifact['catalogExecutable'], *artifact['cliJars'],
                  *artifact['pluginJars'], *artifact['loadedClasses'].values()]
        if any(not isinstance(h, str) or len(h) != 64 or any(c not in '0123456789abcdef' for c in h) for h in hashes):
            raise ValueError('INVALID_ARTIFACT_HASH')
        root = Path(value['fixture']['root'])
        if not root.is_dir() or inventory(root) != value['fixture']['hashes']:
            raise ValueError('FIXTURE_EVIDENCE_UNAVAILABLE')
    for p in value['trials']:
        if not isinstance(p, str) or Path(p).is_absolute() or '..' in Path(p).parts:
            raise ValueError('INVALID_TRIAL_PATH')
    return asdict(ReplayRun(**value))


def usable_result(workload, response, profile=WorkloadProfile.RELIABILITY_FIXTURE):
    if workload == Workload.EXACT_NEGATIVE:
        if not isinstance(response, dict): return False
        return isinstance(admit_exact_negative(response.get('items'), response), CompleteExactNegative)
    if not response or response.get('status') not in ('complete', 'qualified'): return False
    for item in response.get('items', []):
        if workload == 'exact-source':
            if item.get('signature', {}).get('qualifiedIdentity') == exact_identity(profile) and item.get('source', {}).get('text'):
                return True
        elif workload == 'scoped-all':
            if item.get('signature', {}).get('qualifiedIdentity') and item.get('location'): return True
        else:
            occurrence = item.get('occurrence', {})
            if occurrence.get('coverage') == 'exact-compiler-confirmed' and occurrence.get('provenance') == 'k2-authored-source':
                return True
    return False


def observation_only(baseline, candidate):
    return (baseline['artifact'] == candidate['artifact'] or
            baseline['evidenceLevel'] != 'NATIVE' or candidate['evidenceLevel'] != 'NATIVE' or
            baseline['fixture'].get('type') != 'REPRESENTATIVE' or candidate['fixture'].get('type') != 'REPRESENTATIVE' or
            workload_profile(baseline['requests']) != WorkloadProfile.KAST_SOURCE or
            workload_profile(candidate['requests']) != WorkloadProfile.KAST_SOURCE)


def suite_reduces_work(comparisons):
    return (bool(comparisons) and
            all(c.type == ComparisonState.EQUIVALENT and work_does_not_increase(c.deltas.get('counters'))
                for c in comparisons) and
            any(c.lessWork for c in comparisons))


def compare_admitted_replays(args):
    from dataclasses import asdict
    baseline, candidate = load_run(args.baseline), load_run(args.candidate)
    mismatch = incompatible_runs(baseline, candidate)
    pairs, comparisons = [], []
    if not mismatch and baseline.get('type') == candidate.get('type') == 'SEMANTIC_REPLAY':
        def trials(path, manifest):
            result, warmups = {}, set()
            for p in manifest['trials']:
                profile = workload_profile(manifest['requests'])
                trial = load_trial(path.parent / p, profile)
                if manifest['evidenceLevel'] == 'NATIVE' and trial.workload == 'exact-source' and trial.type == 'COMPLETE':
                    for call in trial.calls:
                        for item in call.response['items']:
                            source = item['source']
                            file = Path(manifest['fixture']['root']) / exact_source_file(profile)
                            if item['location']['file'] != str(file): raise ValueError('SOURCE_IDENTITY_MISMATCH')
                            text = file.read_text()
                            start, end = source['startLine'], source['endLine']
                            lines = text.splitlines(keepends=True)
                            if not 1 <= start <= end <= len(lines): raise ValueError('SOURCE_RANGE_MISMATCH')
                            window = ''.join(lines[start - 1:end])
                            if source['text'] not in (window, window.removesuffix('\n')):
                                raise ValueError('SOURCE_TEXT_MISMATCH')
                            bounds = item['location']['range']
                            declaration = text[bounds['startInclusive']:bounds['endExclusive']]
                            if not declaration or declaration not in source['text']: raise ValueError('SOURCE_DECLARATION_WITHHELD')
                if not trial.calls or trial.calls[0].request != manifest['requests'][trial.workload]:
                    raise ValueError('REQUEST_RECEIPT_MISMATCH')
                for previous, resumed in zip(trial.calls, trial.calls[1:]):
                    expected = dict(verbose=True, request=dict(type='RESUME', continuation=continuation(previous.response),
                                    executionBudget=manifest['requests'][trial.workload]['request']['executionBudget']))
                    if resumed.request != expected: raise ValueError('CONTINUATION_RECEIPT_MISMATCH')
                if trial.warmup:
                    if trial.type != 'COMPLETE': raise ValueError('WARMUP_INCOMPLETE')
                    key = (trial.workload, trial.repetition)
                    if key in warmups: raise ValueError('DUPLICATE_WARMUP')
                    warmups.add(key)
                else:
                    key = (trial.workload, trial.repetition)
                    if key in result: raise ValueError('DUPLICATE_TRIAL')
                    result[key] = trial
            expected = {(w, n) for w in comparison_requests() for n in range(manifest['repetitions'])}
            if set(result) != expected: raise ValueError('MISSING_TRIALS')
            if warmups != {(w, n) for w in comparison_requests() for n in range(manifest['warmups'])}:
                raise ValueError('MISSING_WARMUPS')
            return result
        a, b = trials(args.baseline, baseline), trials(args.candidate, candidate)
        for key in sorted(a):
            comparison = compare_trials(a[key], b[key], observation_only(baseline, candidate))
            comparisons.append(comparison)
            pairs.append(dict(workload=key[0], repetition=key[1], **asdict(comparison)))
    else: mismatch.append('RUN_NOT_ADMITTED')
    report = asdict(ReplayComparisonReport(type='INCOMPATIBLE' if mismatch else 'COMPARISON', schemaVersion=1,
                  baseline=str(args.baseline), candidate=str(args.candidate),
                  baselineArtifact=baseline.get('artifact'), candidateArtifact=candidate.get('artifact'),
                  repeatability=baseline.get('artifact') == candidate.get('artifact'), evidenceLevel=baseline['evidenceLevel'],
                  incompatible=mismatch, trials=pairs,
                  lessWork=suite_reduces_work(comparisons)))
    write(args.output, report)
    print(args.output)
    return 0 if not mismatch and all(p['type'] == 'EQUIVALENT' for p in pairs) else 2


def compare_replays(args):
    try:
        return compare_admitted_replays(args)
    except (ValueError, KeyError, TypeError, OSError):
        write(args.output, dict(type='INVALID_EVIDENCE', schemaVersion=1, lessWork=False,
                               reason='RECEIPT_ADMISSION_FAILED', baseline=str(args.baseline),
                               candidate=str(args.candidate)))
        return 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("setup", help="Create and compile a fresh synthetic fixture; never imports it")
    create.add_argument("--fixture", type=Path, required=True)
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--noise-modules", type=int, default=1)
    create.add_argument("--noise-names", type=int, default=1)
    create.add_argument("--noise-kind", choices=("class", "function"), default="class")
    create.add_argument("--callee", choices=("kotlin", "java", "outside"), default="kotlin")
    create.add_argument("--java-references", action="store_true")
    create.add_argument("--comparison-workloads", action="store_true", help="Include the existing dense-reference and page fixtures")
    create.set_defaults(run=setup)
    identify = commands.add_parser("pin", help="Read loaded plugin/model facts from an existing IDEA process")
    identify.add_argument("--idea-contents", type=Path, required=True)
    identify.add_argument("--host-pid", type=int, help="Exact existing host PID when several IDEA profiles use this executable")
    identify.add_argument("--source-tree", type=Path)
    identify.add_argument("--public-mcp", action="store_true", help="Pin the installed persistent MCP query path")
    identify.add_argument("--public-rpc", action="store_true", help="Pin the installed Tool RPC catalog and executable")
    identify.add_argument("--catalog-rpc", type=Path, help="Exact sibling catalog launcher for a candidate Gradle distribution")
    identify.add_argument('--qualification-slice', choices=tuple(value.value for value in QualificationSlice), required=True)
    identify.set_defaults(run=pin)
    play = commands.add_parser("replay", help="Read-only replay through the public CLI or production provider")
    play.add_argument("--pin", type=Path, required=True)
    play.add_argument("--surface", choices=("cli", "provider"), required=True)
    play.add_argument("--repeats", type=int, choices=range(1, 6), default=2)
    play.add_argument("--idea-log", type=Path, help="Collect bounded appended semantic and correlated hosted timing records")
    play.add_argument("--cases", help="Optional comma-separated case-name prefixes for causal experiments")
    play.add_argument("--verify-corrections", action="store_true", help="Require complete, independently expected results for the demonstrated fix cases")
    play.set_defaults(run=replay)
    for command in (identify, play):
        command.add_argument("--fixture", type=Path, required=True)
        command.add_argument("--cli", type=Path, required=True)
        command.add_argument("--output", type=Path, required=True)
    workload = commands.add_parser("replay-workloads", help="Drain four pinned workloads through the installed public CLI")
    workload.add_argument("--pin", type=Path, required=True)
    workload.add_argument("--fixture", type=Path, required=True)
    workload.add_argument("--cli", type=Path, required=True)
    workload.add_argument("--idea-contents", type=Path, required=True)
    workload.add_argument("--idea-log", type=Path)
    workload.add_argument("--output", type=Path, required=True)
    workload.add_argument("--warmups", type=int, choices=range(0, 6), default=1)
    workload.add_argument("--repeats", type=int, choices=range(1, 6), default=2)
    workload.add_argument("--max-calls", type=int, choices=range(1, 1025), default=512)
    workload.add_argument("--timeout", type=int, choices=range(1, 301), default=60)
    workload.add_argument('--workload-profile', choices=[p.value for p in WorkloadProfile], default=WorkloadProfile.RELIABILITY_FIXTURE.value)
    workload.add_argument('--source-archive', type=Path, help='Immutable git archive required for KAST_SOURCE')
    workload.add_argument('--source-commit', help='Exact local Git commit corresponding to source archive')
    workload.set_defaults(run=replay_workloads)
    compare = commands.add_parser("compare", help="Compare two complete workload runs; retain rejected trials")
    compare.add_argument("--baseline", type=Path, required=True)
    compare.add_argument("--candidate", type=Path, required=True)
    compare.add_argument("--output", type=Path, required=True)
    compare.set_defaults(run=compare_replays)
    args = parser.parse_args()
    if args.command == "setup" and (not 1 <= args.noise_modules <= 1500 or not 0 <= args.noise_names <= 100000):
        parser.error("noise-modules must be 1..1500; noise-names must be 0..100000")
    if args.command == 'replay-workloads' and args.workload_profile == WorkloadProfile.KAST_SOURCE.value:
        if args.source_archive is None or args.source_commit is None:
            parser.error('KAST_SOURCE requires --source-archive and --source-commit')
    return args.run(args)


if __name__ == "__main__":
    raise SystemExit(main())
