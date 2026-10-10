#!/usr/bin/env python3
"""Installed callback qualification using the existing schema admission and replay drainer.

The source oracle is independent of the returned graph. Every public page and issued
continuation is retained. This runner neither installs nor restarts the user's IDE.
"""
import argparse
from collections import Counter
from contextlib import closing
from dataclasses import asdict, dataclass, field
from enum import Enum
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import re
import time
from zipfile import ZipFile

import jsonschema
import reproduce_semantic_queries as replay
import static_callback_oracle as static


STATIC_COUNTERS = (
    'CALLBACK_BODY_SCANS', 'CALLBACK_BODY_SCANS_COMPLETED', 'CALLBACK_BODY_SCANS_INCOMPLETE',
    'CALLBACK_SUMMARY_HITS', 'CALLBACK_SUMMARY_MISSES', 'CALLBACK_SUMMARY_REJECTIONS',
    'CALLBACK_SUMMARIES_RETAINED', 'CALLBACK_SUMMARY_RETENTION_REJECTIONS',
    'CALLBACK_FORWARDING_FORMALS', 'CALLBACK_FORWARDING_EDGES',
    'CALLBACK_FIXED_POINTS_COMPLETED', 'CALLBACK_FIXED_POINTS_REJECTED',
)

SEMANTIC_FACT_COUNTERS = (
    'SEMANTIC_FACT_PARTITIONS_EXTRACTED', 'SEMANTIC_FACT_PARTITIONS_REUSED',
    'SEMANTIC_FACT_PARTITIONS_INVALIDATED', 'SEMANTIC_FACT_DEPENDENCY_REVALIDATIONS',
    'SEMANTIC_FACT_DEPENDENCY_REJECTIONS', 'SEMANTIC_FACT_GENERATIONS_PUBLISHED',
    'SEMANTIC_FACT_GENERATIONS_REJECTED',
    'SEMANTIC_FACT_SUPPLIER_INVENTORIES_EXTRACTED',
    'SEMANTIC_FACT_SUPPLIER_INVENTORIES_REUSED',
    'SEMANTIC_FACT_SUPPLIER_INVENTORIES_INVALIDATED',
    'SEMANTIC_FACT_NAMED_PARTITIONS_EXTRACTED',
    'SEMANTIC_FACT_NAMED_PARTITIONS_REUSED',
    'SEMANTIC_FACT_NAMED_PARTITIONS_INVALIDATED',
    'SEMANTIC_FACT_NAMED_PARTITIONS_INELIGIBLE',
)

POLICY_EVIDENCE_COUNTERS = (
    'QUERY_POLICY_EVIDENCE_PUBLICATIONS_COMMITTED',
    'QUERY_POLICY_EVIDENCE_COMMIT_REJECTIONS',
    'QUERY_POLICY_EVIDENCE_PUBLICATIONS_DISCARDED',
)


class NativeDiagnosticCounterVersion(Enum):
    """Versions with the same bounded counter contract; additional fields are not qualified here."""
    NAMED_FACTS = 6
    NATIVE_CALLS = 7
    FIRST_CALLBACK = 8
    READ_ACTIONS = 9
    SCOPE_API_CALLS = 10
    FILE_ENUMERATION = 11
    SDK_FILE_PLAN = 12
    CHECKPOINT_STORAGE = 13


@dataclass(frozen=True)
class SourceFingerprint:
    commit: str
    patchSha256: str
    untrackedHashes: dict[str, str]


@dataclass(frozen=True)
class CandidateCompositionReceipt:
    markerSource: str
    markerSourceSha256: str
    markerDestination: str
    markerDestinationSha256: str
    type: str = field(default='STATIC_CALLBACK_CANDIDATE_COMPOSITION', init=False)


@dataclass(frozen=True)
class CandidateBuildReceipt:
    sourceTree: str
    sourceBefore: SourceFingerprint
    sourceAfter: SourceFingerprint
    candidatePluginSha256: str
    candidateCliHashes: dict[str, str]
    composition: CandidateCompositionReceipt
    process: dict
    type: str = field(default='STATIC_CALLBACK_CANDIDATE_BUILD', init=False)


def source_fingerprint(source):
    """Reuse the pin's source inventory; a checkout name is never runtime proof."""
    def checked(arguments):
        result = replay.capture(arguments, source)
        assert result['outcome'] == 'completed' and result['exitCode'] == 0, result
        return result['stdout']
    commit = checked(['git', 'rev-parse', 'HEAD']).strip()
    patch = checked(['git', 'diff', '--binary', 'HEAD'])
    untracked = checked(['git', 'ls-files', '--others', '--exclude-standard']).splitlines()
    return SourceFingerprint(commit, sha256(patch.encode()).hexdigest(),
        {name: replay.digest(source / name) for name in untracked if (source / name).is_file()})


def candidate_build_receipt(source, before, process, cli, plugin):
    """Call immediately after the retained successful build process, before native setup."""
    after = source_fingerprint(source)
    assert before == after, 'SOURCE_CHANGED_DURING_CANDIDATE_BUILD'
    assert process.get('outcome') == 'completed' and process.get('exitCode') == 0, 'CANDIDATE_BUILD_FAILED'
    assert Path(process['cwd']).resolve() == source.resolve(), 'CANDIDATE_BUILD_WRONG_SOURCE'
    assert {':runtime:hosted:hostedPlugin', ':cli:installDist'} <= set(process['command']), 'CANDIDATE_BUILD_TASKS_MISSING'
    composition = candidate_composition_receipt(source, cli)
    return CandidateBuildReceipt(str(source.resolve()), before, after, replay.digest(plugin), replay.inventory(cli), composition, process)


def candidate_composition_receipt(source, cli):
    """Admit a staged source-owned marker; this records no managed installation claim."""
    origin = source.resolve() / 'distribution/cli/one-shot-observation-v1'
    destination = cli.resolve() / 'share/kast/one-shot-observation-v1'
    assert origin.is_file() and not origin.is_symlink(), 'CANONICAL_CANDIDATE_MARKER_UNAVAILABLE'
    assert destination.is_file() and not destination.is_symlink(), 'CANDIDATE_MARKER_UNAVAILABLE'
    assert origin.read_bytes() == b'1\n', 'CANONICAL_CANDIDATE_MARKER_REJECTED'
    assert destination.read_bytes() == origin.read_bytes(), 'CANDIDATE_MARKER_MISMATCH'
    return CandidateCompositionReceipt(str(origin), replay.digest(origin), str(destination), replay.digest(destination))


def admit_candidate(args, pinned):
    """Match selected artifacts, loaded classes and source to the retained build boundary."""
    receipt = json.loads(args.build_receipt.read_text())
    assert set(receipt) == set(CandidateBuildReceipt.__dataclass_fields__), 'INVALID_CANDIDATE_BUILD_RECEIPT'
    assert receipt['type'] == 'STATIC_CALLBACK_CANDIDATE_BUILD', 'INVALID_CANDIDATE_BUILD_RECEIPT'
    source = Path(receipt['sourceTree']).resolve(strict=True)
    source_identity = source_fingerprint(source)
    fingerprint = asdict(source_identity)
    assert receipt['sourceBefore'] == receipt['sourceAfter'] == fingerprint, 'CANDIDATE_SOURCE_CHANGED'
    process = receipt['process']
    assert process.get('outcome') == 'completed' and process.get('exitCode') == 0, 'CANDIDATE_BUILD_FAILED'
    assert Path(process['cwd']).resolve() == source, 'CANDIDATE_BUILD_WRONG_SOURCE'
    assert {':runtime:hosted:hostedPlugin', ':cli:installDist'} <= set(process['command']), 'CANDIDATE_BUILD_TASKS_MISSING'
    assert {key: pinned['source'][key] for key in fingerprint} == fingerprint, 'PIN_SOURCE_MISMATCH'
    assert replay.digest(args.candidate_plugin) == receipt['candidatePluginSha256'], 'CANDIDATE_PLUGIN_CHANGED'
    cli_inventory = replay.inventory(args.candidate_cli)
    assert cli_inventory == receipt['candidateCliHashes'], 'CANDIDATE_CLI_CHANGED'
    composition = candidate_composition_receipt(source, args.candidate_cli)
    assert receipt['composition'] == asdict(composition), 'CANDIDATE_COMPOSITION_CHANGED'
    assert receipt['candidateCliHashes']['share/kast/one-shot-observation-v1'] == receipt['composition']['markerDestinationSha256'], 'CANDIDATE_MARKER_INVENTORY_MISMATCH'
    assert replay.digest(args.rpc) == pinned['cli']['sha256'], 'PIN_EXECUTABLE_CHANGED'
    jars = {p.name: replay.digest(p) for p in (args.candidate_cli / 'lib').glob('*.jar')}
    assert jars and sorted(jars.values()) == sorted(pinned['cli']['jars'].values()), 'PIN_CLI_JARS_MISMATCH'
    plugin_jars, classes = {}, {}
    assert pinned['plugin']['native']['classResources'], 'PIN_LOADED_CLASSES_MISSING'
    with ZipFile(args.candidate_plugin) as archive:
        for name in archive.namelist():
            if '/lib/' not in name or not name.endswith('.jar'):
                continue
            data = archive.read(name)
            plugin_jars[Path(name).name] = sha256(data).hexdigest()
            with ZipFile(BytesIO(data)) as jar:
                for owner in pinned['plugin']['native']['classResources']:
                    resource = owner.replace('.', '/') + '.class'
                    if resource in jar.namelist():
                        assert owner not in classes, 'CANDIDATE_CLASS_AMBIGUOUS'
                        classes[owner] = sha256(jar.read(resource)).hexdigest()
    assert plugin_jars and sorted(plugin_jars.values()) == sorted(pinned['plugin']['jars'].values()), 'PIN_PLUGIN_JARS_MISMATCH'
    assert classes == {name: value['sha256'] for name, value in pinned['plugin']['native']['classResources'].items()}, 'PIN_LOADED_CLASSES_MISMATCH'
    assert pinned['fixture']['root'] == str(args.root.resolve()), 'PIN_FIXTURE_ROOT_MISMATCH'
    assert pinned['fixture']['hashes'] == replay.inventory(args.root), 'PIN_FIXTURE_CHANGED'
    assert pinned['cli']['transport'] == 'MCP_SESSION', 'STATIC_ACCEPTANCE_REQUIRES_MCP'
    return CandidateBuildReceipt(str(source), source_identity, source_identity,
        receipt['candidatePluginSha256'], cli_inventory, composition, process)


@dataclass(frozen=True)
class Budget:
    maxElapsedMs: int = 20000
    maxWorkUnits: int = 20000
    maxResults: int = 1
    maxReturnedBytes: int = 524288


@dataclass(frozen=True)
class StaticGrants:
    complete: tuple[Budget, Budget]
    presentation: Budget


def static_grants(max_returned_bytes, max_elapsed_ms=20000, max_work_units=20000):
    if min(max_returned_bytes, max_elapsed_ms, max_work_units) <= 0:
        raise ValueError("static qualification grants must be positive")
    # Positive grants retain both lexical callback evidence and the atomic
    # selected-supply inventory. The former eight-slot premise is a separate
    # finite resource rejection, not an admissible complete result.
    return StaticGrants(tuple(Budget(maxElapsedMs=max_elapsed_ms, maxWorkUnits=max_work_units,
        maxResults=results, maxReturnedBytes=max_returned_bytes) for results in (32, 128)),
        Budget(maxElapsedMs=max_elapsed_ms, maxWorkUnits=max_work_units,
               maxResults=1, maxReturnedBytes=max_returned_bytes))


@dataclass(frozen=True)
class RelationStep:
    relation: str
    type: str = 'EXPAND_RELATION'
    expansionScope: dict | None = None


@dataclass(frozen=True)
class WalkStep:
    relation: str
    type: str = 'WALK'
    maximumDepth: int = 1
    expansionScope: dict | None = None


class StaticRelation(str, Enum):
    CALLEES = 'CALLEES'
    CALLERS = 'CALLERS'


class StaticRetention(str, Enum):
    RETAIN = 'RETAIN'
    DISCARD = 'DISCARD'


@dataclass(frozen=True)
class WorkspaceDomain:
    type: str = field(default='WORKSPACE', init=False)


@dataclass(frozen=True)
class AtLocation:
    file: str
    offset: int
    type: str = field(default='AT_LOCATION', init=False)


@dataclass(frozen=True)
class OccurrencesOutput:
    type: str = field(default='OCCURRENCES', init=False)


@dataclass(frozen=True)
class SymbolsOutput:
    fields: tuple[str, ...] = ('NAME', 'LOCATION', 'SIGNATURE')
    type: str = field(default='SYMBOLS', init=False)


@dataclass(frozen=True)
class StaticRelationStep:
    relation: StaticRelation
    expansionScope: WorkspaceDomain = field(default_factory=WorkspaceDomain)
    type: str = field(default='EXPAND_RELATION', init=False)


@dataclass(frozen=True)
class StaticRunRequest:
    source: AtLocation
    steps: tuple[StaticRelationStep, ...]
    output: OccurrencesOutput | SymbolsOutput
    retention: StaticRetention
    executionBudget: Budget
    type: str = field(default='RUN', init=False)


@dataclass(frozen=True)
class StaticReadResultRequest:
    result: str
    executionBudget: Budget
    cursor: str | None = None
    evidence_cursor: int | None = None
    output: OccurrencesOutput = field(default_factory=OccurrencesOutput)
    type: str = field(default='READ_RESULT', init=False)


@dataclass(frozen=True)
class StaticQueryRequest:
    request: StaticRunRequest | StaticReadResultRequest
    verbose: bool = True


def static_wire(request):
    encoded = asdict(StaticQueryRequest(request))
    if isinstance(request, StaticReadResultRequest):
        for name in ('cursor', 'evidence_cursor'):
            if encoded['request'][name] is None: del encoded['request'][name]
    return json.loads(json.dumps(encoded))


@dataclass(frozen=True)
class StaticTrialQualification:
    case: str
    direction: StaticRelation
    budget: Budget
    presentationBudget: Budget
    rows: int
    callbackObservations: int
    retainedRowPages: int
    retainedEvidencePages: int
    nativeCounters: dict[str, int]
    policyEvidenceCounters: dict[str, int]
    semanticFactCounters: dict[str, int]
    verdict: str
    totalElapsedNanos: int


class StaticQualificationStatus(str, Enum):
    RUNNING = 'NATIVE_STATIC_CALLBACK_RUNNING'
    ANSWER_VERIFIED = 'NATIVE_STATIC_CALLBACK_ANSWER_VERIFIED'
    QUALIFIED = 'NATIVE_STATIC_CALLBACK_QUALIFIED'


class StaticAcceptanceFailureCause(str, Enum):
    REQUIRED_EVIDENCE_REJECTED = 'REQUIRED_EVIDENCE_REJECTED'
    CANCELED = 'CANCELED'


@dataclass(frozen=True)
class StaticAcceptanceFailure:
    cause: StaticAcceptanceFailureCause
    type: str = field(default='NATIVE_STATIC_CALLBACK_FAILED', init=False)
    stage: str = field(default='NATIVE_STATIC_CALLBACK_ACCEPTANCE', init=False)


@dataclass
class StaticQualification:
    oracle: static.SuiteOracle
    artifact: dict
    grants: tuple[Budget, ...]
    presentationBudget: Budget
    source: SourceFingerprint
    buildReceiptSha256: str
    trials: list[StaticTrialQualification] = field(default_factory=list)
    semantics: dict = field(default_factory=dict)
    type: StaticQualificationStatus = StaticQualificationStatus.RUNNING
    scope: str = field(default='ROOTED_RELATION_AND_BOUND_CALLBACK_FLOW', init=False)
    model: str = field(default='COMPILER_RESOLVED_STATIC_V1', init=False)
    evidenceLevel: str = field(default='NATIVE', init=False)


@dataclass(frozen=True)
class Oracle:
    owner: str
    policy: str
    reason: str | None
    binding: str
    invocation: bool
    obligation: str | tuple[str, ...] | None = None
    occurrences: int = 1
    body_expression: str | None = None
    forbidden_obligations: tuple[str, ...] = ()
    scan: str | None = None
    forwarding_receivers: tuple[str, ...] = ()
    exhaustive_budget: bool = False
    default_expression: str | None = None
    direct_anonymous_owner: bool = False
    direct_invocation: str = '({ inlineTarget() })()'
    policy_cause: str | None = None
    binding_cause: str | None = None
    named_edge: bool | None = None
    forwarding_owner_expressions: tuple[str, ...] = ()


@dataclass(frozen=True)
class ParameterOracle:
    definition: str
    parameter: str
    position: int
    invocation: str
    anonymous_owner: bool = False


FIXTURE_ORACLES = (
    Oracle('stdlibInline', 'ADMITTED_INLINE', None, 'UNAVAILABLE', False, ('EXTERNAL_CALLABLE', 'OUTSIDE_DOMAIN')),
    Oracle('callbackInline', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('homonymousInline', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('noinlineBoundary', 'EXCLUDED', 'NOINLINE_ARGUMENT', 'BOUND', True),
    Oracle('crossinlineBoundary', 'EXCLUDED', 'CROSSINLINE_ARGUMENT', 'BOUND', True),
    Oracle('explicitInline', 'ADMITTED_INLINE', None, 'BOUND', True),
    Oracle('nestedInline', 'ADMITTED_INLINE', None, 'BOUND', True, 'NESTED_CALLBACK_EXECUTION'),
    Oracle('repeatedInline', 'ADMITTED_INLINE', None, 'BOUND', True, occurrences=2),
    Oracle('unsupportedOuter', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True, 'NESTED_CALLBACK_EXECUTION'),
    Oracle('storedInline', 'EXCLUDED', 'STORED_CALLBACK', 'UNAVAILABLE', False, 'STORED_CALLBACK'),
    Oracle('returnedInline', 'EXCLUDED', 'RETURNED_CALLBACK', 'UNAVAILABLE', False, 'RETURNED_CALLBACK'),
    Oracle('immediateLiteralCallback', 'ADMITTED_DIRECT', None, 'DIRECT', False,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'), scan='NOT_APPLICABLE'),
    Oracle('explicitLiteralCallback', 'ADMITTED_DIRECT', None, 'DIRECT', False,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'), scan='NOT_APPLICABLE',
           direct_invocation='({ inlineTarget() }).invoke()'),
    Oracle('explicitAnonymousCallback', 'ADMITTED_DIRECT', None, 'DIRECT', False,
           body_expression='fun(): String { return inlineTarget() }',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'), scan='NOT_APPLICABLE',
           direct_invocation='(fun(): String { return inlineTarget() }).invoke()'),
    Oracle('storedParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('explicitParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('mutableParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', False, 'PARAMETER_ESCAPES'),
    Oracle('storedOperationCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', False, 'PARAMETER_ESCAPES'),
    Oracle('selectedParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True),
    Oracle('uninvokedParameterCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', False,
           forbidden_obligations=('NO_INVOCATION_PROVEN',), scan='EXHAUSTIVE'),
    Oracle('unresolvedCallback', 'UNAVAILABLE', None, 'UNAVAILABLE', False, 'UNRESOLVED_ARGUMENT_MAPPING'),
    Oracle('labelledInline', 'ADMITTED_INLINE', None, 'BOUND', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING')),
    Oracle('labelledOrdinary', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING')),
    Oracle('nestedDirectInline', 'ADMITTED_DIRECT', None, 'DIRECT', False, 'NESTED_CALLBACK_EXECUTION',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'),
           scan='NOT_APPLICABLE', exhaustive_budget=True, direct_anonymous_owner=True),
    Oracle('nestedDirectOrdinary', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'DIRECT', False, 'NESTED_CALLBACK_EXECUTION',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'NO_INVOCATION_PROVEN'),
           scan='NOT_APPLICABLE', exhaustive_budget=True, direct_anonymous_owner=True),
    Oracle('anonymousFunInline', 'ADMITTED_INLINE', None, 'BOUND', True,
           body_expression='fun(): String { return inlineTarget() }',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING')),
    Oracle('anonymousFunOrdinary', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True,
           body_expression='fun(): String { return inlineTarget() }',
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING')),
    Oracle('defaultInlineDefinition', 'EXCLUDED', 'DEFAULT_PARAMETER', 'DEFAULT', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ inlineTarget() }'),
    Oracle('defaultOrdinaryDefinition', 'EXCLUDED', 'DEFAULT_PARAMETER', 'DEFAULT', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ inlineTarget() }'),
    Oracle('suppliedDefaultInline', 'ADMITTED_INLINE', None, 'BOUND', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ inlineTarget() }'),
    Oracle('suppliedDefaultOrdinary', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ inlineTarget() }'),
    Oracle('forwardedInline', 'ADMITTED_INLINE', None, 'BOUND', True,
           forbidden_obligations=('PARAMETER_ESCAPES', 'NO_INVOCATION_PROVEN', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', forwarding_receivers=('ordinaryInline(block:',), exhaustive_budget=True),
    Oracle('forwardedInlineTwice', 'ADMITTED_INLINE', None, 'BOUND', True,
           forbidden_obligations=('PARAMETER_ESCAPES', 'NO_INVOCATION_PROVEN', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', forwarding_receivers=('forwardingInlineHelper', 'ordinaryInline(block:'),
           exhaustive_budget=True),
    Oracle('nestedNamedForwardingCallback', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True,
           'NESTED_CALLBACK_EXECUTION',
           forbidden_obligations=('PARAMETER_ESCAPES', 'NO_INVOCATION_PROVEN', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', forwarding_receivers=('ordinaryCallback',), exhaustive_budget=True,
           forwarding_owner_expressions=('fun deferred(): String = ordinaryCallback(block)',)),
    Oracle('nestedCrossinlineCallback', 'EXCLUDED', 'CROSSINLINE_ARGUMENT', 'BOUND', True,
           'NESTED_CALLBACK_EXECUTION', scan='EXHAUSTIVE', exhaustive_budget=True),
)

METHOD_DEFAULT_ORACLES = tuple(
    Oracle(owner, 'EXCLUDED', 'DEFAULT_PARAMETER', 'DEFAULT', True,
           forbidden_obligations=('UNSUPPORTED_CALLBACK_SUPPLY', 'UNRESOLVED_ARGUMENT_MAPPING'),
           scan='EXHAUSTIVE', exhaustive_budget=True, default_expression='{ client.fetch() }')
    for owner in ('defaultMethodInlineDefinition', 'defaultMethodOrdinaryDefinition')
)

IGNORED_INVOKE_ORACLES = tuple(
    Oracle(owner, 'UNAVAILABLE', None, 'UNAVAILABLE', False, 'UNRESOLVED_ARGUMENT_MAPPING',
           scan='INCOMPLETE', exhaustive_budget=True, policy_cause='UNSUPPORTED_BOUNDARY',
           binding_cause='UNRESOLVED_ARGUMENT_MAPPING', named_edge=False)
    for owner in ('ignoredExplicitCallback', 'ignoredImplicitCallback')
)



# Authored receiving callable/parameter/invocation expectations, independent of responses.
RECEIVERS = {
    'callbackInline': ('ordinaryCallback', 'block', 'block()'),
    'homonymousInline': ('ordinaryInline(marker:', 'block', 'block()'),
    'noinlineBoundary': ('noinlineHelper', 'block', 'block()'),
    'crossinlineBoundary': ('crossinlineHelper', 'block', 'block()'),
    'explicitInline': ('ordinaryInline(block:', 'block', 'block()'),
    'nestedInline': ('ordinaryInline(block:', 'block', 'block()'),
    'repeatedInline': ('ordinaryInline(block:', 'block', 'block()'),
    'unsupportedOuter': ('ordinaryInline(block:', 'block', 'block()'),
    'storedParameterCallback': ('storedParameter', 'block', 'saved()'),
    'explicitParameterCallback': ('explicitParameter', 'block', 'block.invoke()'),
    'mutableParameterCallback': ('mutableParameter', 'block', None),
    'storedOperationCallback': ('storeOperation', 'block', None),
    'selectedParameterCallback': ('selectedOperation', 'selected', 'selected()'),
    'uninvokedParameterCallback': ('selectedOperation', 'unused', None),
    'labelledInline': ('ordinaryInline(block:', 'block', 'block()'),
    'labelledOrdinary': ('ordinaryCallback', 'block', 'block()'),
    'anonymousFunInline': ('ordinaryInline(block:', 'block', 'block()'),
    'anonymousFunOrdinary': ('ordinaryCallback', 'block', 'block()'),
    'defaultInlineDefinition': ('defaultInlineDefinition', 'block', 'block()'),
    'defaultOrdinaryDefinition': ('defaultOrdinaryDefinition', 'block', 'block()'),
    'suppliedDefaultInline': ('defaultInlineDefinition', 'block', 'block()'),
    'suppliedDefaultOrdinary': ('defaultOrdinaryDefinition', 'block', 'block()'),
    'forwardedInline': ('forwardingInlineHelper', 'block', 'block()'),
    'forwardedInlineTwice': ('forwardingInlineTwiceHelper', 'block', 'block()'),
    'nestedNamedForwardingCallback': ('nestedNamedForwardingHelper', 'block', 'block()'),
    'nestedCrossinlineCallback': ('nestedCrossinlineHelper', 'block', 'block()'),
    'defaultMethodInlineDefinition': ('defaultMethodInlineDefinition', 'block', 'block()'),
    'defaultMethodOrdinaryDefinition': ('defaultMethodOrdinaryDefinition', 'block', 'block()'),
    'read': ('nativeBoundary', 'operation', 'operation()'),
}

DEFAULT_CALL_SITES = (
    ('omittedDefaultInline', 'defaultInlineDefinition()', 'inlineTarget'),
    ('replacedDefaultInline', 'defaultInlineDefinition { "replacement" }', 'inlineTarget'),
    ('omittedDefaultOrdinary', 'defaultOrdinaryDefinition()', 'inlineTarget'),
    ('replacedDefaultOrdinary', 'defaultOrdinaryDefinition { "replacement" }', 'inlineTarget'),
    ('omittedDefaultMethodInline', 'defaultMethodInlineDefinition(client)', 'fetch'),
    ('replacedDefaultMethodInline', 'defaultMethodInlineDefinition(client) { "replacement" }', 'fetch'),
    ('omittedDefaultMethodOrdinary', 'defaultMethodOrdinaryDefinition(client)', 'fetch'),
    ('replacedDefaultMethodOrdinary', 'defaultMethodOrdinaryDefinition(client) { "replacement" }', 'fetch'),
)

PARAMETER_CALLEE_ORACLES = (
    ParameterOracle('ordinaryInline(block:', 'block: () -> String', 0, 'block()'),
    ParameterOracle('ordinaryInline(marker:', 'block: () -> String', 1, 'block()'),
    ParameterOracle('ordinaryCallback', 'block: () -> String', 0, 'block()'),
    ParameterOracle('noinlineHelper', 'noinline block: () -> String', 0, 'block()'),
    ParameterOracle('crossinlineHelper', 'crossinline block: () -> String', 0, 'block()'),
    ParameterOracle('defaultInlineDefinition', 'block: () -> String = { inlineTarget() }', 0, 'block()'),
    ParameterOracle('defaultOrdinaryDefinition', 'block: () -> String = { inlineTarget() }', 0, 'block()'),
    ParameterOracle('defaultMethodInlineDefinition', 'block: () -> String = { client.fetch() }', 1, 'block()'),
    ParameterOracle('defaultMethodOrdinaryDefinition', 'block: () -> String = { client.fetch() }', 1, 'block()'),
    ParameterOracle('explicitParameter', 'block: () -> String', 0, 'block.invoke()'),
    ParameterOracle('selectedOperation', 'selected: () -> String', 1, 'selected()'),
    ParameterOracle('nestedCrossinlineHelper', 'crossinline block: () -> String', 0, 'block()', True),
)


def next_function(text, start):
    """The fixture oracle owns top-level declarations, including inline helpers."""
    match = re.search(r'\n(?:inline )?fun ', text[start:])
    return len(text) if match is None else start + match.start()


def authored_region(root, owner, production=False):
    relative = ('relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/'
                'IntellijValueFlowCompilerAdapter.kt' if production else
                'logging/src/main/kotlin/fixture/calls/' +
                ('ReadUnresolvedCallbacks.kt' if owner == 'unresolvedCallback' else 'ReadKotlinCalls.kt'))
    file = root / relative
    text = file.read_text()
    marker = 'suspend fun read(' if production else 'fun ' + owner + '('
    start = text.index(marker)
    end = text.find('\n    private fun readPrepared', start) if production else next_function(text, start + len(marker))
    return file, text, start, len(text) if end < 0 else end


def site_key(site):
    return (site['file'], site['range']['startInclusive'], site['range']['endExclusive'])


def expected_occurrences(root, oracle, target, production):
    file, text, start, end = authored_region(root, oracle.owner, production)
    expected = []
    position = start
    while True:
        position = text.find(target + '(', position, end)
        if position < 0:
            break
        utf16 = len(text[:position].encode('utf-16-le')) // 2
        expected.append((str(file), utf16, utf16 + len(target)))
        position += len(target)
    assert len(expected) == oracle.occurrences, (oracle.owner, expected)
    return Counter(expected)


def check_mapping(observation, oracle, invocation=True):
    if oracle.binding not in ('BOUND', 'DEFAULT'):
        return
    receiver, parameter, expression = RECEIVERS[oracle.owner]
    binding = observation['flow']['binding']
    if binding['type'] == 'DEFAULT':
        binding = binding['parameter']
    expected_position = (5 if oracle.owner == 'read' else 1 if oracle.owner in (
        'homonymousInline', 'selectedParameterCallback', 'defaultMethodInlineDefinition',
        'defaultMethodOrdinaryDefinition') else 0)
    assert binding['position'] == expected_position, binding
    assert callable_name(binding['callable']) == receiver.split('(')[0], binding
    declaration = binding['callable']['declaration']
    text = Path(declaration['file']).read_text()
    marker = 'fun <Result> nativeBoundary(' if oracle.owner == 'read' else 'fun ' + receiver
    start = text.index(marker)
    end = next_function(text, start + len(marker))
    parameter_site = binding['parameter']
    bounds = parameter_site['range']
    modifier = {'noinlineHelper': 'noinline ', 'crossinlineHelper': 'crossinline ',
                'nestedCrossinlineHelper': 'crossinline '}.get(receiver, '')
    parameter_text = modifier + parameter + ': () -> ' + ('Result' if oracle.owner == 'read' else 'String')
    if oracle.default_expression:
        parameter_text += ' = ' + oracle.default_expression
    parameter_start = len(text[:text.index(parameter_text, start, end)].encode('utf-16-le')) // 2
    assert bounds == dict(startInclusive=parameter_start, endExclusive=parameter_start + len(parameter_text)), (parameter_site, parameter_text)
    if expression and invocation:
        if oracle.forwarding_receivers:
            receiver = oracle.forwarding_receivers[-1]
            declaration = next(iter(observation['flow']['invocations']))['owner']['callable']['declaration']
            text = Path(declaration['file']).read_text()
            marker = 'fun ' + receiver
            start = text.index(marker)
            end = next_function(text, start + len(marker))
        offset = text.index(expression, start, end)
        utf16 = len(text[:offset].encode('utf-16-le')) // 2
        assert Counter(site_key(i['occurrence']) for i in observation['flow']['invocations']) == Counter([
            (declaration['file'], utf16, utf16 + len(expression))]), observation['flow']


def source_text(site):
    bounds = site['range']
    text = Path(site['file']).read_text().encode('utf-16-le')
    return text[bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le')


def check_forwardings(invocation, observation, oracle):
    forwardings = invocation['forwardings']
    assert len(forwardings) == len(oracle.forwarding_receivers), forwardings
    previous = observation['flow']['binding']
    if oracle.forwarding_owner_expressions:
        assert len(oracle.forwarding_owner_expressions) == len(forwardings), oracle
    for index, (forwarding, receiver) in enumerate(zip(forwardings, oracle.forwarding_receivers)):
        source, argument, target = forwarding['source'], forwarding['argument'], forwarding['target']
        assert source['callable'] == previous['callable'], forwarding
        assert source['position'] == previous['position'], forwarding
        assert source['parameter'] == previous['parameter'], forwarding
        assert source_text(argument) == 'block', forwarding
        assert argument['candidateSelector'], forwarding
        assert callable_name(target['callable']) == receiver.split('(')[0], forwarding
        assert target['position'] == 0, forwarding
        assert source_text(target['parameter']) == 'block: () -> String', forwarding
        assert source_text(target['invocation_occurrence']) == receiver.split('(')[0] + '(block)', forwarding
        assert target['invocation_owner']['type'] == 'NAMED', forwarding
        owner = target['invocation_owner']['callable']
        if oracle.forwarding_owner_expressions:
            assert source_text(owner['declaration']) == oracle.forwarding_owner_expressions[index], forwarding
            assert owner != source['callable'], forwarding
            assert owner['declaration']['file'] == source['callable']['declaration']['file'], forwarding
            assert source['callable']['declaration']['range']['startInclusive'] <= owner['declaration']['range']['startInclusive'], forwarding
            assert owner['declaration']['range']['endExclusive'] <= source['callable']['declaration']['range']['endExclusive'], forwarding
        else:
            assert owner == source['callable'], forwarding
        previous = target
    if forwardings:
        assert invocation['owner']['type'] == 'NAMED', invocation
        assert invocation['owner']['callable'] == previous['callable'], invocation


def request(name, directory, direction, walk, budget, source=None):
    scope = dict(type='DIRECTORY', value=directory, containment='RECURSIVE')
    case = replay.Case(name, replay.search(name, scope), select=())
    _, _, payload = replay.invocation(case, replay.ToolSurface.PUBLIC)
    payload['verbose'] = True
    run = payload['request']
    if source is not None:
        run['source'] = source
    run['steps'] = [asdict((WalkStep if walk else RelationStep)(direction, expansionScope=dict(type='WORKSPACE')))]
    run['output'] = dict(type='TRAVERSAL_RECORDS' if walk else 'OCCURRENCES')
    run['retention'] = 'RETAIN'
    run['executionBudget'] = asdict(budget)
    return payload


def observations(pages):
    for page in pages:
        for relation in page.get('relation_observations', []):
            yield from relation.get('callback_observations', [])
        for walk in page.get('walk_observations', []):
            for item in walk.get('callback_observations', []):
                yield item['observation']


def callable_observations(pages):
    for page in pages:
        for relation in page.get('relation_observations', []):
            yield from relation.get('callable_observations', [])
        for walk in page.get('walk_observations', []):
            for item in walk.get('callable_observations', []):
                yield item['observation']


def named_relations(pages):
    for page in pages:
        for item in page['items']:
            if item['type'] == 'occurrence':
                yield item['relation']
            elif item['type'] == 'traversal_record':
                yield item['record']['relation']


def check_named_edge(pages, oracle, target):
    if oracle.named_edge is None:
        return
    matched = [fact for fact in named_relations(pages)
               if fact['source']['name'] == oracle.owner and fact['target']['name'] == target]
    assert bool(matched) == oracle.named_edge, (oracle.owner, target, matched)


def callable_name(value):
    return value['compiler_target']['name']


def check(observation, oracle, target, budget=None):
    assert callable_name(observation['target']) == target, observation
    assert callable_name(observation['lexical_owner']) == oracle.owner, observation
    policy = observation['named_policy']
    assert policy['type'] == oracle.policy, policy
    if oracle.reason:
        assert policy['reason'] == oracle.reason, policy
    if oracle.policy_cause:
        assert policy['cause'] == oracle.policy_cause, policy
    site = observation['occurrence']
    text = Path(site['file']).read_text()
    bounds = site['range']
    assert text.encode('utf-16-le')[bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le') == target, site
    body = observation['callback_body']
    assert body['file'] == site['file']
    assert body['range']['startInclusive'] <= bounds['startInclusive'] < bounds['endExclusive'] <= body['range']['endExclusive']
    assert site['candidateSelector'] and body['candidateSelector']
    target_offset = len(text.encode('utf-16-le')[:bounds['startInclusive'] * 2].decode('utf-16-le'))
    if oracle.owner == 'read':
        lambda_start = text.index('{\n                val started', text.index('suspend fun read('))
        lambda_end = text.index('\n            .also', lambda_start)
        lambda_end = text.rfind('}', lambda_start, lambda_end) + 1
    elif oracle.body_expression:
        region_start = text.index('fun ' + oracle.owner + '(')
        region_end = next_function(text, region_start + len('fun ' + oracle.owner + '('))
        lambda_start = text.index(oracle.body_expression, region_start, region_end)
        lambda_end = lambda_start + len(oracle.body_expression)
    else:
        lambda_start = text.rfind('{', 0, target_offset)
        lambda_end = text.index('}', target_offset) + 1
    expected_body = dict(startInclusive=len(text[:lambda_start].encode('utf-16-le')) // 2,
                         endExclusive=len(text[:lambda_end].encode('utf-16-le')) // 2)
    assert body['range'] == expected_body, (body, expected_body)
    flow = observation['flow']
    assert flow['type'] == 'OBSERVED', flow
    assert flow['body']['occurrence']['range'] == body['range']
    assert flow['body']['compiler_evidence']['identity'].startswith('canonical-signature-sha256-v1|')
    assert flow['body']['compiler_evidence']['signature']['qualifiedIdentity'] == (
        'anonymous@' + body['file'] + '#' + str(expected_body['startInclusive']) + ':' +
        str(expected_body['endExclusive']))
    assert flow['binding']['type'] == oracle.binding, flow
    if oracle.binding_cause:
        assert flow['binding']['cause'] == oracle.binding_cause, flow
    if oracle.scan:
        assert flow['scan'] == oracle.scan, flow
    if oracle.binding == 'DEFAULT':
        assert site_key(flow['binding']['default_value']) == site_key(body), flow
        assert source_text(flow['binding']['default_value']) == oracle.default_expression, flow
    if oracle.binding == 'DIRECT':
        binding = flow['binding']
        assert source_text(binding['occurrence']) == oracle.direct_invocation, binding
        assert binding['owner']['type'] == ('ANONYMOUS' if oracle.direct_anonymous_owner else 'NAMED'), binding
        if oracle.direct_anonymous_owner:
            assert source_text(binding['owner']['occurrence']) == '{ ({ inlineTarget() })() }', binding
        else:
            assert callable_name(binding['owner']['callable']) == oracle.owner, binding
    if oracle.owner in ('selectedParameterCallback', 'uninvokedParameterCallback'):
        assert flow['binding']['position'] == (1 if oracle.owner == 'selectedParameterCallback' else 0), flow
    # Authored scans retain the outer anonymous owner or immutable alias before
    # their invocation. These two facts share one result allowance.
    one_result = budget is not None and budget.maxResults == 1
    invocation_limited = one_result and oracle.owner in (
        'nestedInline', 'unsupportedOuter', 'storedParameterCallback')
    expected_invocation = oracle.invocation and not invocation_limited
    assert bool(flow['invocations']) == expected_invocation, flow
    check_mapping(observation, oracle, expected_invocation)
    if invocation_limited:
        assert {'RESULT_LIMIT_REACHED', 'NO_INVOCATION_PROVEN'} <= set(flow['obligations']), flow
    if oracle.obligation:
        expected_causes = (oracle.obligation,) if isinstance(oracle.obligation, str) else oracle.obligation
        assert set(expected_causes) & set(flow['obligations']), flow
    assert not set(oracle.forbidden_obligations) & set(flow['obligations']), flow
    if oracle.owner == 'storedParameterCallback' and expected_invocation:
        assert any(i.get('callable_transfers') for i in flow['invocations']), flow
    for invocation in flow['invocations']:
        check_forwardings(invocation, observation, oracle)
        assert invocation['occurrence']['candidateSelector']
        owner = invocation['owner']
        exact = owner['occurrence'] if owner['type'] == 'ANONYMOUS' else owner['callable']['declaration']
        assert exact['file'] == invocation['occurrence']['file']
        assert exact['range']['startInclusive'] <= invocation['occurrence']['range']['startInclusive']
        assert invocation['occurrence']['range']['endExclusive'] <= exact['range']['endExclusive']
    anonymous_owners = [flow['binding']['invocation_owner']] if flow['binding']['type'] == 'BOUND' else (
        [flow['binding']['owner']] if flow['binding']['type'] == 'DIRECT' else [])
    anonymous_owners.extend(i['owner'] for i in flow['invocations'])
    primary_supply = {'storedInline': 'STORED', 'returnedInline': 'RETURNED'}.get(oracle.owner)
    if primary_supply:
        anonymous_owners.append(dict(type='ANONYMOUS', **flow['body']))
    expected_owners = {site_key(owner['occurrence']) for owner in anonymous_owners if owner['type'] == 'ANONYMOUS'}
    # The production read retains its invocation before its anonymous owner.
    if one_result and oracle.owner == 'read':
        expected_owners = set()
        assert 'RESULT_LIMIT_REACHED' in flow['obligations'], flow
    owner_bindings = flow['owner_bindings']
    assert {site_key(owner['body']['occurrence']) for owner in owner_bindings} == expected_owners, flow
    if oracle.owner in ('nestedInline', 'unsupportedOuter', 'nestedDirectInline', 'nestedDirectOrdinary'):
        region_start = text.index('fun ' + oracle.owner + '(')
        region_end = next_function(text, region_start + len('fun ' + oracle.owner + '('))
        outer_start = text.index('{', region_start, region_end)
        outer_end = text.rfind('}', region_start, region_end) + 1
        expected_outer = (site['file'], len(text[:outer_start].encode('utf-16-le')) // 2,
                          len(text[:outer_end].encode('utf-16-le')) // 2)
        assert len(owner_bindings) == 1, owner_bindings
        assert site_key(owner_bindings[0]['body']['occurrence']) == expected_outer, owner_bindings
        outer_binding = owner_bindings[0]['binding']
        receiver = 'ordinaryInline' if oracle.owner in ('nestedInline', 'nestedDirectInline') else 'ordinaryCallback'
        assert outer_binding['type'] == 'BOUND' and outer_binding['position'] == 0, outer_binding
        assert callable_name(outer_binding['callable']) == receiver, outer_binding
        parameter_start = text.index('block: () -> String', text.index('fun ' + receiver + '(block:'))
        parameter_utf16 = len(text[:parameter_start].encode('utf-16-le')) // 2
        assert outer_binding['parameter']['range'] == dict(startInclusive=parameter_utf16,
            endExclusive=parameter_utf16 + len('block: () -> String')), outer_binding
        if oracle.owner == 'nestedDirectOrdinary':
            assert site_key(policy['excluded_boundary']) == expected_outer, policy
    for owner in owner_bindings:
        assert 'NESTED_CALLBACK_EXECUTION' in owner['obligations'], owner
        supply = owner['supply']
        if supply['type'] in ('INVOCATION', 'RETURNED'):
            occurrence = supply['occurrence']
            exact = owner['body']['occurrence']
            assert occurrence['file'] == exact['file']
            assert occurrence['range']['startInclusive'] <= exact['range']['startInclusive']
            assert exact['range']['endExclusive'] <= occurrence['range']['endExclusive']
        if primary_supply:
            assert supply['type'] == primary_supply, owner
            assert owner['binding']['type'] == 'UNAVAILABLE', owner
            if primary_supply == 'INVOCATION':
                occurrence = supply['occurrence']
                source = Path(occurrence['file']).read_text().encode('utf-16-le')
                bounds = occurrence['range']
                assert source[bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le') == '({ inlineTarget() })()'
        if oracle.owner == 'read':
            assert supply['type'] == 'INVOCATION', owner
            assert owner['binding'] == dict(type='UNAVAILABLE', cause='EXTERNAL_CALLABLE'), owner
            assert {'EXTERNAL_CALLABLE', 'OUTSIDE_DOMAIN', 'NESTED_CALLBACK_EXECUTION'} <= set(owner['obligations']), owner
            occurrence = supply['occurrence']
            source = Path(occurrence['file']).read_text().encode('utf-16-le')
            bounds = occurrence['range']
            assert source[bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le') == 'readAction { operation() }'


def qualify_value_bindings(args, budget, invoke, drain):
    """Real VALUE_PATHS requests, with source-authored destinations and immutable replay."""
    relative = 'logging/src/main/kotlin/representation/fixture/RepresentationImpactFixture.kt'
    text = (args.root / relative).read_text()

    def locate(offset):
        pages = drain(dict(verbose=True, request=dict(type='RUN',
            source=dict(type='AT_LOCATION', file=relative, offset=offset), steps=[],
            output=dict(type='SYMBOLS', fields=['NAME', 'LOCATION']), executionBudget=asdict(budget))))
        items = [item for page in pages for item in page['items']]
        assert len(items) == 1 and items[0]['type'] == 'exact-symbol', items
        return items[0]['ref']

    enclosing = locate(text.index('fun investigate') + 4)
    callable_ref = locate(text.index('fun encrypt') + 4)
    seeds = (('first', 'Voltage.encrypt(accountA)'), ('second', 'Voltage.encrypt(accountB)'))
    checked = []
    for name, expression in seeds:
        start = text.index(expression)
        assert text[start:start + len(expression)] == expression
        evaluation_budget = Budget(maxResults=1000, maxReturnedBytes=524288)
        pages = drain(dict(verbose=True, request=dict(type='RUN', source=dict(type='IMPACT',
            seeds=[dict(enclosing=enclosing, callable=callable_ref, anchor=dict(start=start, end=start + len(expression)))],
            declarations=[], models=[], domain=dict(type='WORKSPACE'), flow='KOTLIN_FORWARD_V1'), steps=[],
            output=dict(type='VALUE_PATHS'), retention='RETAIN', executionBudget=asdict(evaluation_budget))))
        paths = [item['path'] for page in pages for item in page['items'] if item['type'] == 'VALUE_PATH']
        assert paths, name
        assert all(p['producer']['range'] == dict(start=start, end=start + len(expression)) for p in paths)
        transfers = [step['transfer'] for path in paths for step in path['steps'] if step['type'] == 'COMPILER']
        assert {'LOCAL_BINDING', 'LOCAL_READ'} <= {t['kind'] for t in transfers}, name
        expected = [('submit(misleadingHipedName, second)', 0)] if name == 'first' else [
            ('submit(misleadingHipedName, second)', 1), ('display(second)', 0), ('submit(wrapped, second)', 1)]
        for destination, slot in expected:
            offset = text.index(destination)
            assert any(t['target']['role']['type'] == 'ARGUMENT'
                and t['target']['role']['index'] == slot
                and t['target']['role']['invocation']['range'] == dict(start=offset, end=offset + len(destination))
                for t in transfers), (name, destination, slot)
        if name == 'first':
            assert 'WRAPPER_RETURN' in {t['kind'] for t in transfers}, name
            assert any(p['terminal']['type'] == 'UNRESOLVED_FLOW'
                       and p['terminal']['cause'] == 'MUTABLE_CONTROL_FLOW' for p in paths), name
        retention = pages[-1]['retention']
        assert retention['kind'] == 'retained', retention
        reference, cursor, seen = retention['reference'], None, set()
        retained_items = []
        for _ in range(512):
            action = dict(type='READ_RESULT', result=reference, output=dict(type='VALUE_PATHS'),
                          executionBudget=asdict(budget))
            if cursor is not None:
                action['cursor'] = cursor
            presented = drain(dict(verbose=True, request=action))
            retained_items.extend(item for page in presented for item in page['items'])
            cursor = presented[-1].get('next_cursor')
            if cursor is None:
                break
            assert cursor not in seen, 'retained cursor repeated'
            seen.add(cursor)
        else:
            raise AssertionError('retained value paths did not drain')
        assert len(paths) <= 1 or len(seen) > 0, 'multiple paths bypassed one-result pagination'
        assert [item['path'] for item in retained_items] == paths, 'retained paths changed or duplicated'
        ids = [item['row_id'] for item in retained_items]
        assert len(ids) == len(set(ids)), 'retained row identity duplicated'
        checked.append(dict(seed=name, valuePaths=len(paths), retainedPages=len(seen) + 1))
    return checked


def static_location(symbol, root):
    """The public syntax owns relative paths; compiler witnesses retain absolute paths."""
    relative = Path(symbol.name_site.file).relative_to(Path(root).resolve()).as_posix()
    return AtLocation(relative, symbol.name_site.range.startInclusive)


def static_request(symbol, budget, root, direction=StaticRelation.CALLEES):
    """One named relation hop; callback flow retains its independent formal route."""
    return static_wire(StaticRunRequest(static_location(symbol, root),
        (StaticRelationStep(direction),), OccurrencesOutput(), StaticRetention.RETAIN, budget))


def assert_span(actual, expected):
    assert site_key(actual) == (expected.file, expected.range.startInclusive, expected.range.endExclusive), (actual, expected)


def assert_callable(actual, expected):
    assert_span(actual['declaration'], expected.declaration)
    target = actual['compiler_target']
    assert target['name'] == expected.name, (target, expected)
    assert_span(target, expected.declaration)
    signature = target['compiler_evidence']['signature']
    assert signature['qualifiedIdentity'] == expected.fqn, (signature, expected)


def assert_formal(actual, expected):
    assert_callable(actual['callable'], expected.callable)
    assert_span(actual['parameter'], expected.parameter)
    assert actual['position'] == expected.position, (actual, expected)


def assert_supply(observation, expected, occurrence):
    assert_callable(observation['lexical_owner'], expected.supplier)
    assert_callable(observation['target'], expected.target)
    assert_span(observation['occurrence'], occurrence)
    assert_span(observation['callback_body'], expected.body)
    assert observation['named_policy']['type'] == 'EXCLUDED', observation['named_policy']
    assert observation['named_policy']['reason'] == 'NON_INLINE_ARGUMENT', observation['named_policy']
    assert_span(observation['named_policy']['excluded_boundary'], expected.body)
    flow = observation['flow']
    assert flow['type'] == 'OBSERVED', flow
    assert_span(flow['body']['occurrence'], expected.body)
    evidence = flow['body']['compiler_evidence']
    assert evidence['identity'].startswith('canonical-signature-sha256-v1|'), evidence
    assert evidence['signature']['qualifiedIdentity'] == ('anonymous@' + expected.body.file + '#' +
        str(expected.body.range.startInclusive) + ':' + str(expected.body.range.endExclusive)), evidence
    binding = flow['binding']
    assert binding['type'] == 'BOUND', binding
    assert_formal(binding, expected.formal)
    assert_span(binding['invocation_occurrence'], expected.call)
    assert binding['invocation_owner']['type'] == 'NAMED', binding
    assert_callable(binding['invocation_owner']['callable'], expected.supplier)
    assert binding['invocation']['range'] == dict(start=expected.call.range.startInclusive,
                                               end=expected.call.range.endExclusive), binding
    assert flow['owner_bindings'] == [], flow
    return flow


def assert_forwarding(actual, expected):
    assert_formal(actual['source'], expected.source)
    assert_span(actual['argument'], expected.argument)
    target = actual['target']
    assert target['type'] == 'BOUND', target
    assert_formal(target, expected.target)
    assert_span(target['invocation_occurrence'], expected.call)
    assert target['invocation_owner']['type'] == 'NAMED', target
    assert_callable(target['invocation_owner']['callable'], expected.source.callable)

    transfers = actual['callable_transfers']
    assert len(transfers) == len(expected.transfers), 'forwarding transfer inventory changed'
    owner = expected.source.callable.declaration
    identity = actual['source']['callable']['compiler_target']['compiler_evidence']['identity']
    for actual_transfer, expected_transfer in zip(transfers, expected.transfers):
        assert actual_transfer['kind'] == expected_transfer.kind, 'forwarding transfer kind changed'
        for endpoint, span, role in (('source', expected_transfer.source, expected_transfer.source_role),
                                     ('target', expected_transfer.target, expected_transfer.target_role)):
            site = actual_transfer[endpoint]
            assert site['range'] == dict(start=span.range.startInclusive, end=span.range.endExclusive), 'forwarding transfer site changed'
            assert site['role'] == dict(type=role), 'forwarding transfer role changed'
            enclosing = site['enclosing']
            assert enclosing['file'] == owner.file, 'forwarding transfer file changed'
            assert enclosing['range'] == dict(start=owner.range.startInclusive, end=owner.range.endExclusive), 'forwarding transfer owner changed'
            assert enclosing['compilerIdentity'] == identity, 'forwarding transfer identity changed'


def case_supplies(case):
    if isinstance(case, (static.CompleteCase, static.CompleteEmptyCase, static.RejectedResourceCase)):
        return case.supplies
    return (case.supply,)


def mapped_callback_observations(pages, case):
    observed = list(observations(pages))
    expected_sites = {(site.file, site.range.startInclusive, site.range.endExclusive): (supply, site)
        for supply in case_supplies(case) for site in supply.target_occurrences}
    expected_count = sum(len(supply.target_occurrences) for supply in case_supplies(case))
    assert len(expected_sites) == expected_count, 'oracle callback occurrence duplicated'
    assert len(observed) == expected_count, (case.name, observed)
    actual_by_site = {site_key(observation['occurrence']): observation for observation in observed}
    assert len(actual_by_site) == len(observed), 'callback occurrence duplicated'
    assert actual_by_site.keys() == expected_sites.keys(), 'callback source inventory changed'
    return [(actual_by_site[key], supply, site) for key, (supply, site) in expected_sites.items()]


def assert_exhausted_graph(actual, expected):
    """Compare finite graph inventory with authored source, including closing edges."""
    assert actual['type'] == 'EXHAUSTED_GRAPH', 'complete formal graph witness missing'
    assert_formal(actual['root'], expected.root)
    expected_formals = {(formal.parameter.file, formal.parameter.range.startInclusive,
                         formal.parameter.range.endExclusive): formal for formal in expected.formals}
    assert len(actual['formals']) == len(expected_formals), 'exhaustive formal inventory changed'
    assert Counter(site_key(formal['parameter']) for formal in actual['formals']) == Counter(expected_formals.keys()), 'exhaustive formal inventory changed'
    for formal in actual['formals']:
        assert_formal(formal, expected_formals[site_key(formal['parameter'])])
    expected_edges = {(edge.argument.file, edge.argument.range.startInclusive,
                      edge.argument.range.endExclusive): edge for edge in expected.forwardings}
    assert len(actual['forwardings']) == len(expected_edges), 'exhaustive forwarding inventory changed'
    assert Counter(site_key(edge['argument']) for edge in actual['forwardings']) == Counter(expected_edges.keys()), 'exhaustive forwarding inventory changed'
    for edge in actual['forwardings']:
        assert_forwarding(edge, expected_edges[site_key(edge['argument'])])


def assert_complete_callback(pages, case):
    for observation, supply, occurrence in mapped_callback_observations(pages, case):
        flow = assert_supply(observation, supply, occurrence)
        assert flow['scan'] == 'EXHAUSTIVE' and flow['obligations'] == [], flow
        assert_exhausted_graph(flow['forwarding'], case.graph)
        if isinstance(case, static.CompleteEmptyCase):
            assert flow['invocations'] == [], 'exhausted empty graph manufactured an invocation'
            continue
        assert len(flow['invocations']) == 1, flow
        invocation = flow['invocations'][0]
        assert_span(invocation['occurrence'], case.invocation.occurrence)
        assert invocation['owner']['type'] == 'NAMED', invocation
        assert_callable(invocation['owner']['callable'], case.invocation.owner)
        assert len(invocation['forwardings']) == len(case.forwardings), invocation
        for actual, expected in zip(invocation['forwardings'], case.forwardings):
            assert_forwarding(actual, expected)
    assert selected_supply_extras(pages, case) == [], 'unexpected unsupported callable evidence'
    names = {callable_name(o['target']) for o in observations(pages)}
    assert not names & {target.name for target in case.forbidden_targets}, 'supplier context crossed'


def assert_named_reference(pages, case):
    assert list(observations(pages)) == [], 'named reference became an anonymous callback'
    values = selected_supply_extras(pages, case)
    assert len(values) == 1, 'reference observation inventory changed'
    observed, = values
    assert_span(observed['occurrence'], case.reference)
    assert_callable(observed['lexical_owner'], case.supplier)
    assert observed['body']['type'] == 'NAMED', observed
    assert_callable(observed['body']['callable'], case.supplier)
    assert observed['target']['type'] == 'NAMED_REFERENCE', observed
    reference = observed['target']['reference']
    assert_callable(reference['target'], case.target)
    for name in ('dispatch_receiver', 'extension_receiver'):
        receiver = getattr(case, name)
        assert reference[name]['type'] == receiver.type, 'reference receiver kind changed'
        if isinstance(receiver, static.BoundReceiver):
            assert_span(reference[name]['occurrence'], receiver.occurrence)
    flow = reference['flow']
    assert flow['type'] == 'SUPPLIED', flow
    binding = flow['binding']
    assert binding['type'] == 'BOUND', binding
    assert_formal(binding, case.graph.root)
    assert_span(binding['invocation_occurrence'], case.call)
    assert binding['invocation_owner']['type'] == 'NAMED', binding
    assert_callable(binding['invocation_owner']['callable'], case.supplier)
    assert_exhausted_graph(flow['forwarding'], case.graph)
    assert len(flow['invocations']) == 1, 'reference terminal inventory changed'
    invocation, = flow['invocations']
    assert_span(invocation['occurrence'], case.invocation.occurrence)
    assert invocation['owner']['type'] == 'NAMED', invocation
    assert_callable(invocation['owner']['callable'], case.invocation.owner)
    assert invocation['callable_transfers'] == [], invocation
    assert len(invocation['forwardings']) == len(case.forwardings), invocation
    for actual, expected in zip(invocation['forwardings'], case.forwardings):
        assert_forwarding(actual, expected)


def selected_supply_extras(pages, case):
    """Additional callee supply inventory must preserve the same authored binding and graph."""
    remaining = []
    seen = set()
    named = isinstance(case, static.ExcludedReferenceCase)
    supplies = (case,) if named else case_supplies(case)
    for observation in callable_observations(pages):
        target = observation['target']
        if target['type'] != 'CALLBACK_SUPPLIES':
            remaining.append(observation)
            continue
        site = site_key(observation['occurrence'])
        assert site not in seen, 'selected supply observation duplicated'
        seen.add(site)
        matched = [supply for supply in supplies if site == (supply.call.file, supply.call.range.startInclusive, supply.call.range.endExclusive)]
        assert len(matched) == 1, 'selected supply call outside authored inventory'
        expected = matched[0]
        assert_callable(observation['lexical_owner'], expected.supplier)
        assert observation['body']['type'] == 'NAMED'
        assert_callable(observation['body']['callable'], expected.supplier)
        assert len(target['formals']) == len(target['supplies']) == 1
        formal = case.graph.root if named else expected.formal
        assert_formal(target['formals'][0], formal)
        use = target['supplies'][0]
        assert 'type' not in use, 'concrete selected supply has an unexpected discriminator'
        supplier = use['supplier']
        binding = supplier['binding']
        assert binding['type'] == 'BOUND'
        assert_formal(binding, formal)
        assert_span(binding['invocation_occurrence'], expected.call)
        assert binding['invocation_owner']['type'] == 'NAMED'
        assert_callable(binding['invocation_owner']['callable'], expected.supplier)
        assert supplier['selection']['type'] == 'EXPLICIT'
        value = supplier['value']
        assert value['factories'] == []
        origin = value['origin']
        source = expected.reference if named else expected.body
        if named:
            assert origin['type'] == 'NAMED'
            assert_callable(origin['target'], expected.target)
            assert_span(origin['occurrence'], source)
            for receiver_name in ('dispatch_receiver', 'extension_receiver'):
                receiver = getattr(expected, receiver_name)
                assert origin[receiver_name]['type'] == receiver.type
                if isinstance(receiver, static.BoundReceiver): assert_span(origin[receiver_name]['occurrence'], receiver.occurrence)
        else:
            assert origin['type'] == 'ANONYMOUS'
            assert_span(origin['body']['occurrence'], source)
        assert value['source']['range'] == dict(start=source.range.startInclusive, end=source.range.endExclusive)
        assert value['source']['role'] == dict(type='EXPRESSION_RESULT')
        assert value['destination'] == supplier['selection']['argument']
        for site in (value['source'], value['destination']):
            assert site['range'] == dict(start=source.range.startInclusive, end=source.range.endExclusive)
            owner = site['enclosing']
            assert owner['file'] == expected.supplier.declaration.file
            assert owner['range'] == dict(start=expected.supplier.declaration.range.startInclusive, end=expected.supplier.declaration.range.endExclusive)
            assert owner['compilerIdentity'] == observation['lexical_owner']['compiler_target']['compiler_evidence']['identity']
        assert value['destination']['role'] == dict(type='ARGUMENT', invocation=binding['invocation'], index=formal.position)
        assert value['transfers'] == [dict(source=value['source'], target=value['destination'], kind='ARGUMENT')]
        assert len(value['invoked_callables']) == 1
        assert_callable(value['invoked_callables'][0], formal.callable)
        assert_exhausted_graph(use['forwarding'], case.graph)
        if isinstance(case, static.CompleteEmptyCase):
            assert use['invocations'] == []
        else:
            assert len(use['invocations']) == 1
            invocation = use['invocations'][0]
            assert_span(invocation['occurrence'], case.invocation.occurrence)
            assert invocation['owner']['type'] == 'NAMED'
            assert_callable(invocation['owner']['callable'], case.invocation.owner)
            assert len(invocation['forwardings']) == len(case.forwardings)
            for actual, expected_forward in zip(invocation['forwardings'], case.forwardings): assert_forwarding(actual, expected_forward)
    return remaining


def completion_rejection(document, expected):
    assert document['status'] == 'rejected', document
    rejection = document['rejection']
    assert rejection['type'] == 'COMPLETION_UNPROVEN', rejection
    detail = rejection['detail']
    assert detail['model'] == 'COMPILER_RESOLVED_STATIC_V1', detail
    assert detail['cause']['type'] == 'CALLBACK_GRAPH_UNPROVEN', detail
    cause = detail['cause']['graphFailure']['cause']
    assert cause == json.loads(json.dumps(asdict(expected))), (cause, expected)
    assert detail['policyProgress'] == dict(type='EVIDENCE_ONLY'), detail
    assert detail['evidence']['type'] == 'RETAINED', detail
    return detail


def assert_cold_selected_capacity(document):
    """A fresh host's eight-slot selected-supply proof cannot be retained."""
    detail = completion_rejection(document, static.UnresolvedRejection((static.FlowObligation.RESULT_LIMIT_REACHED,)))
    assert detail['originalCoverage'] == {'type': 'COMPLETE'}
    failure = detail['cause']['graphFailure']
    assert failure['origin'] == 'RELATION' and type(failure['group']) is int and failure['group'] >= 0
    assert type(failure['observation']) is int and failure['observation'] >= 0
    return detail


def retained_static_result(reference, budget, invoke):
    """Drain row and evidence cursors independently; retain every original response."""
    rows, row_pages, evidence_pages = [], [], []
    live, cursor, seen = None, None, set()
    def present(cursor=None, evidence_cursor=None):
        call = invoke(static_wire(StaticReadResultRequest(reference, budget, cursor, evidence_cursor)))
        document = call.response
        assert document is not None and document['status'] in ('complete', 'qualified'), document
        assert document.get('live') is not None, 'retained basis missing'
        assert not document.get('failures') and not document.get('omissions'), document
        assert replay.continuation(document) is None, 'retained evidence cannot resume the producer'
        return call
    for _ in range(512):
        call = present(cursor)
        page = call.response
        if live is None: live = page['live']
        assert page['live'] == live, 'retained basis moved'
        row_pages.append(call)
        rows.extend(page['items'])
        cursor = page.get('next_cursor')
        if cursor is None: break
        assert cursor not in seen, 'retained row cursor repeated'
        seen.add(cursor)
    else: raise AssertionError('retained row drainage incomplete')
    identities = [item['row_id'] for item in rows]
    assert len(set(identities)) == len(identities), 'retained row identity duplicated'
    evidence_cursor, total = 0, None
    for _ in range(512):
        call = present(evidence_cursor=evidence_cursor)
        page = call.response
        assert page['live'] == live, 'retained evidence basis moved'
        window = page['evidence_window']
        if total is None: total = window['total']
        assert window['total'] == total and window['start'] == evidence_cursor, 'retained evidence cursor moved'
        assert window['start'] <= window['end'] <= total, window
        emitted = sum(len(page.get(key, [])) for key in ('failures', 'omissions', 'walk_observations',
            'reference_observations', 'discovery_observations', 'relation_observations'))
        assert emitted == window['end'] - window['start'], 'evidence window does not match records'
        evidence_pages.append(call)
        if window['type'] == 'FINAL':
            assert window['end'] == total, window
            break
        assert window['type'] == 'MORE' and window['end'] > evidence_cursor, 'retained evidence did not advance'
        evidence_cursor = window['end']
    else: raise AssertionError('retained evidence drainage incomplete')
    return rows, row_pages, evidence_pages, live


def native_scoped_counters(call, live, required):
    assert len(call.diagnostics) == 1, 'native receipt missing or ambiguous'
    receipt = call.diagnostics[0]
    version = receipt['schemaVersion']
    assert type(version) is int and version in {item.value for item in NativeDiagnosticCounterVersion}, 'native diagnostic version unsupported'
    correlation = receipt['correlation']
    assert correlation.get('type') == 'bound' and correlation.get('host') == live['host'] and correlation.get('epoch') == live['epoch'], 'native receipt basis mismatch'
    ceilings = [limit['value'] for limit in receipt['limits'] if limit['parameter'] == 'DIAGNOSTIC_COUNT']
    assert len(ceilings) == 1 and type(ceilings[0]) is int and ceilings[0] > 0, 'native diagnostic ceiling missing'
    selected = {}
    for observation in receipt['counters']:
        if observation['counter'] not in required:
            continue
        assert observation['contributor'] == 'NONE', observation
        assert observation['counter'] not in selected, 'native counter duplicated'
        assert type(observation['count']) is int and 0 <= observation['count'] < ceilings[0], 'native scoped counter invalid or saturated'
        selected[observation['counter']] = observation['count']
    assert set(selected) == set(required), 'native scoped work evidence missing'
    return selected


def native_semantic_fact_counters(call, live):
    """Admit explicit bounded observations; zero alone does not prove store use."""
    return native_scoped_counters(call, live, SEMANTIC_FACT_COUNTERS)


def native_callback_counters(call, live):
    selected = native_scoped_counters(call, live, STATIC_COUNTERS)
    assert selected['CALLBACK_BODY_SCANS'] == selected['CALLBACK_BODY_SCANS_COMPLETED'] + selected['CALLBACK_BODY_SCANS_INCOMPLETE'], selected
    return selected


def assert_policy_evidence_publication(call, live):
    counts = native_scoped_counters(call, live, POLICY_EVIDENCE_COUNTERS)
    expected = {counter: 0 for counter in POLICY_EVIDENCE_COUNTERS}
    document = call.response
    if document['status'] == 'rejected':
        assert call.action == 'RUN', 'policy evidence was not published by the scoped RUN'
        rejection = document['rejection']
        assert rejection['type'] == 'COMPLETION_UNPROVEN' and rejection['detail']['evidence']['type'] == 'RETAINED', 'policy rejection evidence unavailable'
        expected['QUERY_POLICY_EVIDENCE_PUBLICATIONS_COMMITTED'] = 1
    else:
        assert document['status'] in ('complete', 'qualified'), 'policy publication outcome unproven'
    assert counts == expected, 'policy evidence publication counters differ from public outcome'
    return counts


def assert_unavailable_resource_supplies(pages, case):
    observed = list(callable_observations(pages))
    expected = {site_key({'file': supply.call.file, 'range': asdict(supply.call.range)}): supply for supply in case.supplies}
    assert len(observed) == len(expected), 'resource supplier inventory missing or duplicated'
    assert Counter(site_key(item['occurrence']) for item in observed) == Counter(expected.keys()), 'resource supplier sites changed'
    for item in observed:
        supply = expected[site_key(item['occurrence'])]
        assert item['target'] == {'type': 'UNAVAILABLE_SUPPLY', 'causes': list(case.rejection.obligations)}
        assert_callable(item['lexical_owner'], supply.supplier)
        assert item['body']['type'] == 'NAMED'
        assert_callable(item['body']['callable'], supply.supplier)


def assert_rejection_pointer(detail, groups, case):
    failure = detail['cause']['graphFailure']
    assert failure['origin'] == 'RELATION', 'static rejection has wrong observation origin'
    assert 0 <= failure['group'] < len(groups), 'static rejection group unavailable'
    group = groups[failure['group']]
    if isinstance(case, static.RejectedResourceCase):
        values = group['callable_observations']
        assert 0 <= failure['observation'] < len(values), 'resource rejection supply unavailable'
        observed = values[failure['observation']]
        assert_span(observed['occurrence'], case.cause_site)
        assert observed['target'] == {'type': 'UNAVAILABLE_SUPPLY', 'causes': list(case.rejection.obligations)}
    elif isinstance(case, static.RejectedFormalCase):
        values = group['callable_observations']
        assert 0 <= failure['observation'] < len(values), 'static rejection callable unavailable'
        assert_span(values[failure['observation']]['occurrence'], case.invocation.occurrence)
    else:
        values = group['callback_observations']
        assert 0 <= failure['observation'] < len(values), 'static rejection callback unavailable'
        observation = values[failure['observation']]
        assert site_key(observation['occurrence']) in {
            (site.file, site.range.startInclusive, site.range.endExclusive)
            for supply in case_supplies(case) for site in supply.target_occurrences
        }, 'static rejection points at another callback'


def named_call_site(call, receiver):
    # Named relation occurrences identify the callee token, while binding evidence
    # retains the complete call expression (which can have a package qualifier).
    if call.text.count(receiver.name) != 1:
        raise ValueError('oracle named callee must occur exactly once in the supplying call')
    start = call.range.startInclusive + len(call.text[:call.text.index(receiver.name)].encode('utf-16-le')) // 2
    return call.file, start, start + len(receiver.name.encode('utf-16-le')) // 2


def assert_complete_scan_reuse(case, direction, counts, fact_counts=None):
    if direction != StaticRelation.CALLEES: return
    # Selected-supply and lexical inventories each own a summary lookup context.
    # The selected inventory extracts first on a cold query; lexical suppliers
    # then instantiate that exact summary. Each lexical supplier counts one hit.
    extracted = 1 if fact_counts is None else fact_counts['SEMANTIC_FACT_PARTITIONS_EXTRACTED']
    assert extracted in (0, 1), 'fixture extracts at most one formal summary'
    if fact_counts is not None:
        assert fact_counts['SEMANTIC_FACT_PARTITIONS_REUSED'] == 2 - extracted, 'two admitted summary contexts required'
    expected = dict.fromkeys(STATIC_COUNTERS, 0)
    expected['CALLBACK_SUMMARY_HITS'] = len(case.supplies)
    if extracted:
        expected.update(CALLBACK_BODY_SCANS=len(case.graph.formals),
            CALLBACK_BODY_SCANS_COMPLETED=len(case.graph.formals),
            CALLBACK_FORWARDING_FORMALS=len(case.graph.formals),
            CALLBACK_FORWARDING_EDGES=len(case.graph.forwardings),
            CALLBACK_FIXED_POINTS_COMPLETED=1)
    assert counts == expected, case.name + ' formal scan/reuse evidence differs from authored graph'


def assert_relation_direction(groups, direction):
    """CanonicalReadDocuments serializes relation evidence separately from query syntax."""
    expected = {StaticRelation.CALLEES: 'callees', StaticRelation.CALLERS: 'callers'}[direction]
    assert all(group['relation'] == expected for group in groups), 'retained relation direction changed'


def stable_static_semantics(rows, evidence, seeds):
    """Normalize only independently proven handles; keep compiler facts and qualifications."""
    handles = {}
    def collect(value):
        if isinstance(value, list):
            for child in value: collect(child)
        elif isinstance(value, dict):
            signature = value.get('signature') or value.get('compilerEvidence', {}).get('signature')
            location = value.get('location') or (dict(file=value['file'], range=value['range'])
                if 'file' in value and 'range' in value else None)
            if signature and location:
                for key in ('ref', 'selector'):
                    if isinstance(value.get(key), str):
                        identity = dict(signature=signature, location=location)
                        assert value[key] not in handles or handles[value[key]] == identity, 'handle identity changed'
                        handles[value[key]] = identity
            if 'candidateSelector' in value:
                identity = dict(file=value['file'], range=value['range'])
                token = value['candidateSelector']
                assert token not in handles or handles[token] == identity, 'candidate identity changed'
                handles[token] = identity
            for child in value.values(): collect(child)
    collect([rows, evidence, seeds])
    def normalized(value):
        if isinstance(value, list): return [normalized(child) for child in value]
        if not isinstance(value, dict): return value
        result = {}
        for key, child in value.items():
            if key == 'row_id': continue  # Row identity uniqueness was checked before semantic comparison.
            if key in ('ref', 'selector', 'candidateSelector', 'token') and isinstance(child, str):
                assert child in handles, 'unproven handle identity'
                result[key] = handles[child]
            else: result[key] = normalized(child)
        return result
    return normalized(dict(items=rows, evidence=evidence))


def qualify_static(args, output, invoke, pinned):
    suite = static.load_oracle(args.root)
    allowances = static_grants(args.max_returned_bytes, args.static_max_elapsed_ms, args.static_max_work_units)
    grants, presentation = allowances.complete, allowances.presentation
    manifest = StaticQualification(suite, replay.artifact_identity(pinned), grants, presentation,
        SourceFingerprint(**{key: pinned['source'][key] for key in SourceFingerprint.__dataclass_fields__}),
        replay.digest(args.build_receipt))
    replay.write(output / 'qualification.json', asdict(manifest))
    seeds, checks, semantics = [], [], {}
    live = None
    for symbol in suite.symbols:
        payload = static_wire(StaticRunRequest(static_location(symbol, args.root),
            (), SymbolsOutput(), StaticRetention.DISCARD, grants[-1]))
        call = invoke(payload)
        document = call.response
        assert document is not None and document.get('status') == 'complete', document
        assert len(document['items']) == 1, document
        item = document['items'][0]
        assert item['type'] == 'exact-symbol' and item['name'] == symbol.name, item
        assert_span(item['location'], symbol.declaration)
        assert item['signature']['qualifiedIdentity'] == symbol.fqn, item
        if live is None: live = document['live']
        assert document['live'] == live, 'native suite basis moved'
        native_callback_counters(call, live)
        assert_policy_evidence_publication(call, live)
        seeds.append(item)

    def evaluate(case, grant, direction=StaticRelation.CALLEES):
        started = time.monotonic_ns()
        if isinstance(case, static.ExcludedReferenceCase): supplier = case.supplier
        elif isinstance(case, static.RejectedFormalCase): supplier = case.owner
        else: supplier = case.supply.supplier
        target = case.target if isinstance(case, static.ExcludedReferenceCase) else (
            case.supply.target if not isinstance(case, static.RejectedFormalCase) else case.owner)
        seed = target if direction == 'CALLERS' else supplier
        calls = replay.drain_workload(static_request(seed, grant, args.root, direction), invoke, 512)
        assert calls and all(call.response is not None for call in calls), 'native answer missing'
        terminal = calls[-1].response
        assert replay.continuation(terminal) is None, 'automatic execution did not drain'
        if isinstance(case, (static.CompleteCase, static.CompleteEmptyCase, static.ExcludedReferenceCase)):
            assert terminal['status'] == 'complete' and terminal['coverage']['exhaustive'], terminal
            assert not terminal.get('failures') and not terminal.get('omissions'), terminal
            assert terminal['retention']['kind'] == 'retained', terminal
            reference = terminal['retention']['reference']
            rejected = None
        else:
            rejected = completion_rejection(terminal, case.rejection)
            if isinstance(case, static.RejectedResourceCase):
                assert rejected['originalCoverage'] == asdict(case.original_coverage), 'resource rejection erased original coverage'
            reference = rejected['evidence']['result']
        rows, row_pages, evidence_calls, basis = retained_static_result(reference, presentation, invoke)
        assert basis == live, 'native suite basis moved'
        pages = [call.response for call in evidence_calls]
        for page in pages:
            assert page['question']['completion'] == dict(type='COMPLETE_ONLY', model='COMPILER_RESOLVED_STATIC_V1'), page
            if rejected is None:
                assert page.get('interpretation', dict(type='QUERY_RESULT')) == dict(type='QUERY_RESULT'), page
            else:
                interpretation = page['interpretation']
                assert interpretation['type'] == 'POLICY_REJECTED_EVIDENCE', interpretation
                assert interpretation['cause'] == rejected['cause'], interpretation
                assert interpretation['originalCoverage'] == rejected['originalCoverage'], interpretation
        if isinstance(case, (static.CompleteCase, static.CompleteEmptyCase)):
            assert_complete_callback(pages, case)
        elif isinstance(case, (static.RejectedLambdaCase, static.RejectedResourceCase)):
            expected_flow = case.lexical_rejection if isinstance(case, static.RejectedResourceCase) else case.rejection
            if isinstance(case, static.RejectedResourceCase): assert_unavailable_resource_supplies(pages, case)
            for observation, supply, occurrence in mapped_callback_observations(pages, case):
                flow = assert_supply(observation, supply, occurrence)
                assert flow['scan'] == expected_flow.scan and flow['obligations'] == list(expected_flow.obligations), flow
                assert flow['invocations'] == [], flow
        elif isinstance(case, static.RejectedFormalCase):
            assert list(observations(pages)) == [], 'formal invocation became anonymous callback'
            callables = list(callable_observations(pages))
            assert len(callables) == 1, callables
            observed = callables[0]
            assert_span(observed['occurrence'], case.invocation.occurrence)
            assert_callable(observed['lexical_owner'], case.owner)
            assert observed['target']['type'] == 'PARAMETER_INVOCATION', observed
            assert_formal(observed['target']['parameter'], case.formal)
            assert observed['target']['suppliers'] == {'type': 'UNAVAILABLE', 'cause': case.rejection.cause}
            assert_span(observed['target']['invocation']['occurrence'], case.invocation.occurrence)
            assert observed['body']['type'] == 'NAMED', observed
            assert_callable(observed['body']['callable'], case.owner)
        else:
            assert_named_reference(pages, case)
        # Only the named supplier-to-wrapper edge is admitted; callback sinks remain derivation evidence.
        if direction == StaticRelation.CALLERS or isinstance(case, static.RejectedFormalCase):
            expected_calls = []
        elif isinstance(case, static.ExcludedReferenceCase):
            receiver = case.graph.root.callable
            expected_calls = [(case.supplier, receiver, case.call)]
        else:
            expected_calls = [(supply.supplier, supply.formal.callable, supply.call) for supply in case_supplies(case)]
        calls_by_site = {named_call_site(site, receiver):
            (owner, receiver) for owner, receiver, site in expected_calls}
        assert len(calls_by_site) == len(expected_calls), 'oracle named call occurrence duplicated'
        assert len(rows) == len(expected_calls), (case.name, direction, rows)
        assert Counter(site_key(row['relation']['occurrence']) for row in rows) == Counter(calls_by_site.keys()), 'named call inventory changed'
        for row in rows:
            relation = row['relation']
            owner, receiver = calls_by_site[site_key(relation['occurrence'])]
            for endpoint, expected in ((relation['source'], owner), (relation['target'], receiver)):
                assert endpoint['name'] == expected.name and endpoint['qualifiedIdentity'] == expected.fqn, endpoint
                assert_span(endpoint, expected.declaration)
            assert relation['coverage'] == 'exact-compiler-confirmed' and relation['provenance'] == 'k2-authored-source', relation
        totals = {counter: 0 for counter in STATIC_COUNTERS}
        publication_totals = {counter: 0 for counter in POLICY_EVIDENCE_COUNTERS}
        fact_totals = dict.fromkeys(SEMANTIC_FACT_COUNTERS, 0)
        for call in calls:
            for counter, count in native_callback_counters(call, live).items(): totals[counter] += count
            for counter, count in assert_policy_evidence_publication(call, live).items(): publication_totals[counter] += count
            for counter, count in native_semantic_fact_counters(call, live).items(): fact_totals[counter] += count
        if isinstance(case, (static.CompleteCase, static.CompleteEmptyCase)): assert_complete_scan_reuse(case, direction, totals, fact_totals)
        for call in row_pages + evidence_calls:
            counts = native_callback_counters(call, live)
            assert all(count == 0 for count in counts.values()), 'retained presentation reran callback work'
            assert all(count == 0 for count in native_semantic_fact_counters(call, live).values()), 'retained presentation reran fact preparation'
            assert_policy_evidence_publication(call, live)
        groups = [group for page in pages for group in page.get('relation_observations', [])]
        if rejected is not None: assert_rejection_pointer(rejected, groups, case)
        assert groups and groups[-1]['coverage']['type'] == 'EXHAUSTED', 'relation observation inventory unproven'
        assert_relation_direction(groups, direction)
        # Producer page grants may partition one exhausted relation differently. Keep its exact
        # derivation witnesses and domain, while raw page coverage remains in every retained call.
        group_keys = ('subject', 'relation', 'provider', 'requested_domain', 'effective_domain', 'domain_fingerprint')
        domain = {key: groups[0][key] for key in group_keys}
        assert all({key: group[key] for key in group_keys} == domain for group in groups), 'relation domain changed'
        evidence = {**domain, **{key: [item for group in groups for item in group[key]] for key in (
            'callback_observations', 'callable_observations', 'scope_exclusions')}}
        assert not evidence['scope_exclusions'], 'fixture relation scope changed'
        # Raw coverage remains per producer page. Exact graph witnesses, row identities and verdicts must match.
        semantic = stable_static_semantics(rows, evidence, seeds)
        semantic['rejection'] = None if rejected is None else dict(cause=rejected['cause'], originalCoverage=rejected['originalCoverage'])
        key = case.name + '/' + direction
        if key in semantics: assert semantics[key] == semantic, 'semantic evidence changed with admissible grant'
        else: semantics[key] = semantic
        check = StaticTrialQualification(case.name, direction, grant, presentation, len(rows), len(list(observations(pages))),
            len(row_pages), len(evidence_calls), totals, publication_totals, fact_totals, 'REFERENCE_SUPPLY_VERIFIED' if isinstance(case, static.ExcludedReferenceCase)
            else 'COMPLETE' if rejected is None else 'EXPECTED_TYPED_RESOURCE_REJECTION' if isinstance(case, static.RejectedResourceCase)
            else 'EXPECTED_TYPED_REJECTION', time.monotonic_ns() - started)
        checks.append(check)
        manifest.trials = checks
        replay.write(output / 'qualification.json', asdict(manifest))
    # This control is run before any relation query on the freshly restarted
    # candidate. Cached summaries can legitimately fit eight slots; they cannot
    # stand in for this independent cold resource rejection.
    cold = invoke(static_request(suite.complete_cases[0].supply.supplier,
        Budget(args.static_max_elapsed_ms, args.static_max_work_units, 8, args.max_returned_bytes), args.root))
    assert_cold_selected_capacity(cold.response)
    replay.write(output / 'alpha-eight-slot-cold-rejection.json', asdict(cold))
    for grant in grants:
        for case in suite.cases: evaluate(case, grant)
        for case in suite.complete_cases: evaluate(case, grant, StaticRelation.CALLERS)
        for case in suite.excluded_cases: evaluate(case, grant, StaticRelation.CALLERS)
    for case in suite.resource_cases:
        evaluate(case, Budget(maxResults=case.max_results, maxReturnedBytes=args.max_returned_bytes))
    alpha_trials = [trial for trial in checks if trial.case == 'alpha' and trial.direction == StaticRelation.CALLEES]
    assert len(alpha_trials) == len(grants), 'cross-query alpha inventory missing'
    assert alpha_trials[-1].semanticFactCounters['SEMANTIC_FACT_PARTITIONS_REUSED'] == 2, 'cross-query project summary reuse unproven'
    manifest.type = StaticQualificationStatus.ANSWER_VERIFIED
    manifest.semantics = semantics
    replay.write(output / 'qualification.json', asdict(manifest))
    return manifest


def run(args):
    try:
        return run_admitted(args)
    except (AssertionError, ValueError, OSError, jsonschema.ValidationError, KeyError, TypeError):
        if args.static_cross_module and args.output.is_dir():
            replay.write(args.output / 'failure.json', asdict(StaticAcceptanceFailure(
                StaticAcceptanceFailureCause.REQUIRED_EVIDENCE_REJECTED)))
        raise
    except KeyboardInterrupt:
        if args.static_cross_module and args.output.is_dir():
            replay.write(args.output / 'failure.json', asdict(StaticAcceptanceFailure(StaticAcceptanceFailureCause.CANCELED)))
        raise


def run_admitted(args):
    budget = Budget(maxReturnedBytes=args.max_returned_bytes)
    output = replay.fresh(args.output)
    pinned = None
    if args.static_cross_module:
        pinned = json.loads(args.pin.read_text())
        receipt = admit_candidate(args, pinned)
        replay.write(output / 'candidate-build.json', asdict(receipt))
        pin_args = argparse.Namespace(output=output / 'start-pin', fixture=args.root, cli=args.rpc,
            idea_contents=args.idea_contents, source_tree=Path(receipt.sourceTree),
            public_rpc=True, public_mcp=True, catalog_rpc=args.catalog_rpc)
        assert replay.pin(pin_args) == 0, 'NATIVE_START_PIN_UNAVAILABLE'
        current = json.loads((pin_args.output / 'pin.json').read_text())
        assert replay.artifact_identity(current) == replay.artifact_identity(pinned), 'NATIVE_ARTIFACT_CHANGED'
        assert current['fixture'] == pinned['fixture'] and current['limits'] == pinned['limits'], 'NATIVE_PIN_CHANGED'
        pinned = current
    catalog_rpc = args.catalog_rpc or (args.rpc.parent / 'kast-tool-rpc-complete' if args.mcp else args.rpc)
    catalog = replay.capture([catalog_rpc, 'catalog'], args.root, timeout=60)
    replay.write(output / 'catalog-process.json', catalog)
    tools = json.loads(catalog['stdout'])['catalog']['tools']
    validator = jsonschema.Draft202012Validator(next(t['inputSchema'] for t in tools if t['name'] == 'query_symbols'))
    if args.mcp:
        with closing(replay.McpReplaySession(args.rpc, args.root, output, args.timeout)) as session:
            observed = session.catalog()
            assert {t['name']: t['inputSchema'] for t in observed['tools']} == {
                t['name']: t['inputSchema'] for t in tools}, 'session catalog changed'
            result_schema = next(t['outputSchema'] for t in observed['tools'] if t['name'] == 'query_symbols')
            result_validator = jsonschema.Draft202012Validator(result_schema)
            completed = qualify(args, budget, output, validator, session, result_validator, pinned)
    else:
        qualify(args, budget, output, validator)
    if pinned is not None:
        pin_args.output = output / 'end-pin'
        assert replay.pin(pin_args) == 0, 'NATIVE_END_PIN_UNAVAILABLE'
        ending = json.loads((pin_args.output / 'pin.json').read_text())
        assert replay.artifact_identity(ending) == replay.artifact_identity(pinned), 'NATIVE_ARTIFACT_CHANGED'
        for key in ('fixture', 'limits', 'source'):
            assert ending[key] == pinned[key], 'NATIVE_PIN_CHANGED:' + key
        for key in ('ideaBuild', 'jbr', 'kotlinPlugin', 'javaHome', 'pid', 'processStart', 'model'):
            assert ending['host'][key] == pinned['host'][key], 'NATIVE_HOST_CHANGED:' + key
        completed.type = StaticQualificationStatus.QUALIFIED
        replay.write(output / 'qualification.json', asdict(completed))
        print(output / 'qualification.json')


def qualify(args, budget, output, validator, session=None, result_validator=None, pinned=None):
    counter = 0
    terminal_pages = []

    def invoke(payload):
        nonlocal counter
        validator.validate(payload)
        before = args.idea_log.stat() if args.idea_log else None
        process = session.call(payload) if session else replay.capture(
            [args.rpc, 'call', 'query_symbols'], args.root, json.dumps(payload), args.timeout)
        envelope = json.loads(process['stdout']) if process['outcome'] == 'completed' else {}
        document = envelope.get('result', {}).get('structuredContent') if session else envelope.get('document')
        diagnostics, phases = replay.collect_observations(args.idea_log, before) if before else ([], [])
        call = replay.ReplayCall(payload['request']['type'], payload, process, document, diagnostics, phases, 'UNAVAILABLE')
        replay.write(output / f'call-{counter:04d}.json', asdict(call))
        counter += 1
        if document is not None and result_validator is not None:
            result_validator.validate(document)
        if args.static_cross_module:
            assert process['outcome'] == 'completed' and process.get('exitCode') == 0, 'NATIVE_EXCHANGE_UNAVAILABLE'
            assert document is not None, 'PUBLIC_SEMANTIC_DOCUMENT_MISSING'
            grant = payload['request']['executionBudget']
            assert len(document.get('items', [])) <= grant['maxResults'], 'page exceeds requested result grant'
            assert len(json.dumps(document, ensure_ascii=False, separators=(',', ':')).encode()) <= grant['maxReturnedBytes'], 'page exceeds returned-byte grant'
        return call

    if args.static_cross_module:
        return qualify_static(args, output, invoke, pinned)

    def drain(payload):
        calls = replay.drain_workload(payload, invoke, 512)
        assert calls and all(c.response is not None for c in calls), 'missing semantic response'
        pages = [c.response for c in calls]
        for call in calls:
            limit = call.request['request'].get('executionBudget', asdict(budget))['maxResults']
            assert len(call.response.get('items', [])) <= limit, 'page exceeds requested result grant'
            byte_limit = call.request['request'].get('executionBudget', asdict(budget))['maxReturnedBytes']
            effective_bytes = call.response.get('execution_budget', {}).get('max_returned_bytes', {}).get('effective', byte_limit)
            assert len(json.dumps(call.response, ensure_ascii=False, separators=(',', ':')).encode()) <= min(byte_limit, effective_bytes), 'page exceeds returned-byte grant'
        assert all(not page.get('failures') for page in pages), pages[-1]
        assert pages[-1].get('status') in ('complete', 'qualified'), pages[-1]
        assert replay.continuation(pages[-1]) is None, 'drain stopped with continuation'
        assert pages[-1]['status'] != 'rejected', pages[-1]
        assert len({json.dumps(p.get('live'), sort_keys=True) for p in pages}) == 1, 'basis moved'
        terminal_pages.append(dict(status=pages[-1]['status'], coverage=pages[-1].get('coverage'),
                                   terminalReason=pages[-1].get('terminal_reason'),
                                   qualification=pages[-1].get('qualification')))
        return pages

    inspected = set()

    def inspect_source(site, basis=None, live=None):
        candidate = site['candidateSelector']
        if candidate in inspected:
            return
        call = invoke(dict(verbose=True, request=dict(type='READ_SOURCE', candidateRef=candidate,
                                                     executionBudget=asdict(budget))))
        document = call.response
        assert document and document['operation'] == 'source.read', document
        assert document['status'] == 'complete', document
        assert document['snapshot']['file'] == site['file'], document
        if basis is not None:
            assert basis['type'] == 'LIVE', basis
            live = dict(root=basis['root'], host=basis['host'], epoch=basis['epoch'],
                        contentView=basis['contentView'], version=basis['referenceVersion'])
        assert live and 'host' in live and 'epoch' in live and 'version' in live, live
        assert document['snapshot']['live'] == live, document
        assert document['region']['selection']['range'] == site['range'], document
        assert document['text']['type'] == 'returned', document
        bounds = site['range']
        expected = Path(site['file']).read_text().encode('utf-16-le')[
            bounds['startInclusive'] * 2:bounds['endExclusive'] * 2].decode('utf-16-le')
        assert document['text']['text'] == expected, document
        inspected.add(candidate)

    def inspect_observation(observation):
        basis = observation['flow']['basis']
        for site in (observation['occurrence'], observation['callback_body'],
                     observation['target']['declaration'], observation['lexical_owner']['declaration']):
            inspect_source(site, basis)
        binding = observation['flow']['binding']
        if binding['type'] == 'BOUND':
            for site in (binding['parameter'], binding['invocation_occurrence'], binding['callable']['declaration']):
                inspect_source(site, basis)
            owner = binding['invocation_owner']
            inspect_source(owner['occurrence'] if owner['type'] == 'ANONYMOUS' else owner['callable']['declaration'], basis)
        if binding['type'] == 'DEFAULT':
            for site in (binding['parameter']['parameter'], binding['parameter']['callable']['declaration'],
                         binding['default_value']):
                inspect_source(site, basis)
        if binding['type'] == 'DIRECT':
            inspect_source(binding['occurrence'], basis)
            owner = binding['owner']
            inspect_source(owner['occurrence'] if owner['type'] == 'ANONYMOUS' else owner['callable']['declaration'], basis)
        for invocation in observation['flow']['invocations']:
            inspect_source(invocation['occurrence'], basis)
            owner = invocation['owner']
            inspect_source(owner['occurrence'] if owner['type'] == 'ANONYMOUS' else owner['callable']['declaration'], basis)
            for forwarding in invocation['forwardings']:
                source, argument, target = forwarding['source'], forwarding['argument'], forwarding['target']
                for site in (source['parameter'], source['callable']['declaration'], argument,
                             target['parameter'], target['callable']['declaration'], target['invocation_occurrence']):
                    inspect_source(site, basis)
        for owner in observation['flow']['owner_bindings']:
            inspect_source(owner['body']['occurrence'], basis)
            if owner['supply']['type'] in ('INVOCATION', 'RETURNED'):
                inspect_source(owner['supply']['occurrence'], basis)
            binding = owner['binding']
            if binding['type'] == 'BOUND':
                for site in (binding['parameter'], binding['invocation_occurrence'], binding['callable']['declaration']):
                    inspect_source(site, basis)
            if binding['type'] == 'DEFAULT':
                for site in (binding['parameter']['parameter'], binding['parameter']['callable']['declaration'],
                             binding['default_value']):
                    inspect_source(site, basis)
            if binding['type'] == 'DIRECT':
                inspect_source(binding['occurrence'], basis)

    if args.value_flow:
        checks = qualify_value_bindings(args, budget, invoke, drain)
        replay.write(output / 'qualification.json', dict(type='INSTALLED_VALUE_BINDINGS_QUALIFIED', calls=counter,
            root=str(args.root), rpc=str(args.rpc), rpcSha256=replay.digest(args.rpc),
            transport='MCP_SESSION' if session else 'TOOL_RPC', budget=asdict(budget), checks=checks, terminalPages=terminal_pages))
        print(output / 'qualification.json')
        return

    if args.production:
        target = 'readPrepared'
        directory = 'relation/intellij/src/main/kotlin'
        oracle = Oracle('read', 'EXCLUDED', 'NON_INLINE_ARGUMENT', 'BOUND', True, 'NESTED_CALLBACK_EXECUTION')
        cases = (oracle,)
    else:
        target, directory, cases = 'inlineTarget', 'logging/src/main/kotlin', FIXTURE_ORACLES
    checks = []
    matrix_budget = Budget(maxResults=1000, maxReturnedBytes=budget.maxReturnedBytes)
    groups = [(target, cases, None)]
    if not args.production:
        file, text, _, _ = authored_region(args.root, 'defaultMethodInlineDefinition')
        offset = text.index('fetch()', text.index('interface BaseClient'))
        method_source = dict(type='AT_LOCATION', file=str(file.relative_to(args.root)),
                             offset=len(text[:offset].encode('utf-16-le')) // 2)
        groups.append(('fetch', METHOD_DEFAULT_ORACLES, method_source))
        groups.append(('ignoredCallbackTarget', IGNORED_INVOKE_ORACLES, None))
    for target, cases, caller_source in groups:
        for walk in (False, True):
            caller_observations = (list(observations(drain(request(target, directory, 'CALLERS', walk, budget, caller_source))))
                                   if any(not o.exhaustive_budget for o in cases) else [])
            exhaustive_pages = (drain(request(target, directory, 'CALLERS', walk, matrix_budget, caller_source))
                                if any(o.exhaustive_budget for o in cases) else [])
            exhaustive_observations = list(observations(exhaustive_pages))
            for oracle in cases:
                case_budget = matrix_budget if oracle.exhaustive_budget else budget
                available = exhaustive_observations if oracle.exhaustive_budget else caller_observations
                matched = [o for o in available if callable_name(o['lexical_owner']) == oracle.owner]
                expected = expected_occurrences(args.root, oracle, target, args.production)
                assert Counter(site_key(o['occurrence']) for o in matched) == expected, (oracle.owner, 'CALLERS', matched, expected)
                for observation in matched:
                    check(observation, oracle, target, case_budget)
                    inspect_observation(observation)
                check_named_edge(exhaustive_pages, oracle, target)
                declaration = matched[0]['lexical_owner']['declaration']
                source = dict(type='AT_LOCATION', file=str(Path(declaration['file']).relative_to(args.root)),
                              offset=declaration['range']['startInclusive'])
                pages = drain(request(oracle.owner, directory, 'CALLEES', walk, case_budget, source))
                check_named_edge(pages, oracle, target)
                callee_observations = [o for o in observations(pages) if callable_name(o['target']) == target]
                assert Counter(site_key(o['occurrence']) for o in callee_observations) == expected, (oracle.owner, 'CALLEES', callee_observations, expected)
                for observation in callee_observations:
                    check(observation, oracle, target, case_budget)
                checks.append(dict(owner=oracle.owner, target=target, walk=walk, matchedOccurrences=len(matched),
                                   bindingCauses=[o['flow']['binding'].get('cause') for o in matched],
                                   obligations=[o['flow']['obligations'] for o in matched],
                                   budget=asdict(case_budget)))
            if not args.production:
                # Declaration-bound defaults are possible inputs to the formal parameter.
                # A direct callee read at an omitted or replaced call must not attach every
                # default body in the receiving declaration to the lexical caller.
                for owner, expression, call_target in DEFAULT_CALL_SITES:
                    if call_target != target:
                        continue
                    file, text, start, end = authored_region(args.root, owner)
                    assert text.index(expression, start, end) >= start
                    source = dict(type='AT_LOCATION', file=str(file.relative_to(args.root)),
                                  offset=len(text[:start].encode('utf-16-le')) // 2)
                    pages = drain(request(owner, directory, 'CALLEES', walk, matrix_budget, source))
                    callbacks = [o for o in observations(pages) if callable_name(o['target']) == target]
                    assert not callbacks, (owner, callbacks)
                    checks.append(dict(owner=owner, target=target, walk=walk, matchedOccurrences=0,
                                       defaultAttribution='DECLARATION_CONTEXT_REQUIRED', budget=asdict(matrix_budget)))
    if not args.production:
        file, text, _, _ = authored_region(args.root, 'ordinaryCallback')
        for walk in (False, True):
            for oracle in PARAMETER_CALLEE_ORACLES:
                marker = 'fun ' + oracle.definition
                start = text.index(marker)
                end = next_function(text, start + len(marker))
                name = oracle.definition.split('(')[0]
                source = dict(type='AT_LOCATION', file=str(file.relative_to(args.root)),
                              offset=len(text[:start].encode('utf-16-le')) // 2)
                pages = drain(request(name, directory, 'CALLEES', walk, matrix_budget, source))
                assert pages[-1]['status'] == 'complete', (oracle.definition, pages[-1])
                parameter_calls = [o for o in callable_observations(pages)
                                   if o['target']['type'] == 'PARAMETER_INVOCATION']
                assert len(parameter_calls) == 1, (oracle.definition, parameter_calls)
                observation = parameter_calls[0]
                parameter = observation['target']['parameter']
                assert callable_name(observation['lexical_owner']) == name, observation
                assert parameter['callable'] == observation['lexical_owner'], observation
                assert parameter['position'] == oracle.position, parameter
                parameter_offset = len(text[:text.index(oracle.parameter, start, end)].encode('utf-16-le')) // 2
                assert site_key(parameter['parameter']) == (str(file), parameter_offset,
                    parameter_offset + len(oracle.parameter)), parameter
                invocation_offset = len(text[:text.index(oracle.invocation, start, end)].encode('utf-16-le')) // 2
                assert site_key(observation['occurrence']) == (str(file), invocation_offset,
                    invocation_offset + len(oracle.invocation)), observation
                body = observation['body']
                assert body['type'] == ('ANONYMOUS' if oracle.anonymous_owner else 'NAMED'), body
                if oracle.anonymous_owner:
                    assert source_text(body['occurrence']) == '{ block() }', body
                else:
                    assert body['callable'] == parameter['callable'], body
                for site in (observation['occurrence'], parameter['parameter'], parameter['callable']['declaration'],
                             body['occurrence'] if oracle.anonymous_owner else body['callable']['declaration']):
                    inspect_source(site, live=pages[-1]['live'])
                checks.append(dict(owner=name, walk=walk, target='PARAMETER_INVOCATION',
                                   parameterPosition=oracle.position, matchedOccurrences=1,
                                   body=body['type'], budget=asdict(matrix_budget)))
    replay.write(output / 'qualification.json', dict(type='INSTALLED_CALLBACK_QUALIFIED', calls=counter,
        root=str(args.root), rpc=str(args.rpc), rpcSha256=replay.digest(args.rpc),
        transport='MCP_SESSION' if session else 'TOOL_RPC',
        budget=asdict(budget), matrixBudget=asdict(matrix_budget),
        inspectedSourceRanges=len(inspected), checks=checks, terminalPages=terminal_pages))
    print(output / 'qualification.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--rpc', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--production', action='store_true')
    parser.add_argument('--value-flow', action='store_true')
    parser.add_argument('--mcp', action='store_true', help='Reuse one installed public MCP session')
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--max-returned-bytes', type=int, default=524288)
    parser.add_argument('--static-max-elapsed-ms', type=int, default=20000, help='Declared time grant for each static native invocation')
    parser.add_argument('--static-max-work-units', type=int, default=20000, help='Declared work grant for each static native invocation')
    parser.add_argument('--static-cross-module', action='store_true', help='Opt-in exact candidate native static callback suite')
    parser.add_argument('--pin', type=Path)
    parser.add_argument('--candidate-plugin', type=Path)
    parser.add_argument('--candidate-cli', type=Path)
    parser.add_argument('--build-receipt', type=Path)
    parser.add_argument('--catalog-rpc', type=Path)
    parser.add_argument('--idea-contents', type=Path)
    parser.add_argument('--idea-log', type=Path)
    args = parser.parse_args()
    if args.static_cross_module:
        if not args.mcp or args.production or args.value_flow:
            parser.error('--static-cross-module requires --mcp and cannot combine other suites')
        for name in ('pin', 'candidate_plugin', 'candidate_cli', 'build_receipt', 'idea_contents', 'idea_log'):
            if getattr(args, name) is None: parser.error('--static-cross-module requires --' + name.replace('_', '-'))
    run(args)
