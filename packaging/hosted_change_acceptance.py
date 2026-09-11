"""Admission and bounded evidence for the opt-in real hosted change acceptance."""
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
from urllib.parse import quote
import uuid
import zipfile

from acceptance_idea import digest
from native_fixture_probe import COMMANDS, DIGEST


class AcceptanceFailure(Enum):
    INPUT = 'input-rejected'
    DIRTY_SOURCE = 'dirty-source-rejected'
    ARTIFACT_CHANGED = 'artifact-changed'
    HARNESS = 'harness-rejected'
    SCHEMAS = 'schemas-rejected'
    READINESS = 'readiness-rejected'
    READINESS_TIMEOUT = 'readiness-timeout'
    NATIVE_PROCESS = 'native-process-rejected'
    NATIVE_OUTPUT = 'native-output-rejected'
    NATIVE_TIMEOUT = 'native-timeout'
    RECEIPT = 'receipt-rejected'
    RETIREMENT = 'retirement-rejected'


class AcceptanceRejected(ValueError):
    def __init__(self, failure: AcceptanceFailure):
        self.failure = failure
        super().__init__(failure.value)


@dataclass(frozen=True)
class SourceIdentity:
    commit: str
    clean: bool
    changes_digest: str


def source_identity(repo: Path, diagnostic_dirty: bool) -> SourceIdentity:
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    status = subprocess.check_output(['git', 'status', '--porcelain=v1', '--untracked-files=normal'], cwd=repo)
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise AcceptanceRejected(AcceptanceFailure.INPUT)
    if status and not diagnostic_dirty:
        raise AcceptanceRejected(AcceptanceFailure.DIRTY_SOURCE)
    return SourceIdentity(commit, not status, hashlib.sha256(status).hexdigest())


def tree_identity(root: Path) -> dict:
    if not root.is_absolute() or root.is_symlink() or not root.is_dir():
        raise AcceptanceRejected(AcceptanceFailure.INPUT)
    files = sorted(path for path in root.rglob('*') if path.is_file() or path.is_symlink())
    if not files or len(files) > 2048 or any(path.is_symlink() for path in files):
        raise AcceptanceRejected(AcceptanceFailure.INPUT)
    inventory = [{'path': path.relative_to(root).as_posix(), 'sha256': digest(path),
                  'bytes': path.stat().st_size} for path in files]
    encoded = json.dumps(inventory, separators=(',', ':'), sort_keys=True).encode()
    return {'sha256': hashlib.sha256(encoded).hexdigest(), 'fileCount': len(files),
            'bytes': sum(item['bytes'] for item in inventory)}


def admit_harness(jar: Path, commit: str) -> str:
    if not jar.is_absolute() or jar.is_symlink() or not jar.is_file() or jar.stat().st_size > 4 * 1024 * 1024:
        raise AcceptanceRejected(AcceptanceFailure.HARNESS)
    prefix = 'io/github/amichne/kast/appserver/acceptance/hostedchange/'
    try:
        with zipfile.ZipFile(jar) as archive:
            entries = archive.namelist()
            if (not entries or len(entries) > 512 or len(set(entries)) != len(entries)
                    or any(not (name.startswith(prefix) or name in ('META-INF/', 'META-INF/MANIFEST.MF')
                                or (name.endswith('/') and prefix.startswith(name))) for name in entries)
                    or prefix + 'NativeHostedChangeMain.class' not in entries):
                raise AcceptanceRejected(AcceptanceFailure.HARNESS)
            manifest = archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '')
            if f'Kast-Acceptance-Source-Commit: {commit}' not in manifest:
                raise AcceptanceRejected(AcceptanceFailure.HARNESS)
    except (KeyError, UnicodeError, zipfile.BadZipFile):
        raise AcceptanceRejected(AcceptanceFailure.HARNESS) from None
    return digest(jar)


class StartupDiscoveryState(Enum):
    MODEL_PENDING = 'MODEL_PENDING'
    MODEL_INCOMPLETE_PENDING = 'MODEL_INCOMPLETE_PENDING'
    DUMB_PENDING = 'DUMB_PENDING'
    INDEXING_PENDING = 'INDEXING_PENDING'
    READ_PREEMPTED_PENDING = 'READ_PREEMPTED_PENDING'
    FRESHNESS_MOVED_PENDING = 'FRESHNESS_MOVED_PENDING'
    READ_EPOCH_REJECTED = 'READ_EPOCH_REJECTED'
    OTHER_REJECTION = 'OTHER_REJECTION'
    OBSERVED = 'OBSERVED'

    @property
    def pending(self):
        return self in (self.MODEL_PENDING, self.MODEL_INCOMPLETE_PENDING, self.DUMB_PENDING, self.INDEXING_PENDING,
                        self.READ_PREEMPTED_PENDING, self.FRESHNESS_MOVED_PENDING)


def startup_discovery_state(document: dict) -> StartupDiscoveryState:
    failure, detail = document.get('failure'), document.get('detail')
    if failure == 'PROJECT_ADMISSION_REJECTED' and detail == 'GRADLE_MODEL_UNAVAILABLE':
        return StartupDiscoveryState.MODEL_PENDING
    if failure == 'PROJECT_ADMISSION_REJECTED' and detail == 'GRADLE_MODEL_INCOMPLETE':
        return StartupDiscoveryState.MODEL_INCOMPLETE_PENDING
    if failure == 'PROJECT_ADMISSION_REJECTED' and detail == 'DUMB_MODE':
        return StartupDiscoveryState.DUMB_PENDING
    if failure == 'INDEXING':
        return StartupDiscoveryState.INDEXING_PENDING
    if failure == 'FRESHNESS_REJECTED' and detail == 'MOVED':
        return StartupDiscoveryState.FRESHNESS_MOVED_PENDING
    if failure == 'READ_EPOCH_REJECTED':
        return (StartupDiscoveryState.READ_PREEMPTED_PENDING if detail == 'READ_PREEMPTED'
                else StartupDiscoveryState.READ_EPOCH_REJECTED)
    return StartupDiscoveryState.OTHER_REJECTION


def pending_readiness(document: dict) -> bool:
    return startup_discovery_state(document).pending


def admitted_live(value: object, workspace: Path) -> dict:
    if not isinstance(value, dict) or set(value) != {'root', 'host', 'epoch', 'contentView', 'version'}:
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    try:
        owner = uuid.UUID(value['host'])
    except (ValueError, TypeError, AttributeError):
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT) from None
    if (value['root'] != str(workspace) or str(owner) != value['host'] or type(value['epoch']) is not int
            or value['epoch'] < 0 or value['contentView'] != 'SAVED_PSI_COMMITTED' or value['version'] != 1):
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    return dict(value)


CASE_NAMES = frozenset(('indexing-policy-refusal', 'foreign-root-refusal', 'generated-target-refusal', 'model-movement-refusal',
    'unsupported-intents', 'ambiguous-name-is-not-authority', 'invalid-reference', 'approval-decline', 'approval-cancel', 'approval-malformed',
    'edited-preimage', 'old-epoch-reference', 'approval-wait-releases-workspace', 'plan-has-no-source-effect',
    'broker-process-restart-no-replay', 'plugin-unload-retires-pending-approval', 'historical-receipt-after-owner-restart', 'concurrent-same-plan-single-effect', 'post-save-interruption-no-replay',
    'dirty-document-refusal', 'recovery-preserves-divergent-document', 'psi-structure', 'production-undo',
    'complete-workflow', 'repeat-apply-reuses-receipt', 'owner-retirement-invalidates-reference-and-plan',
    'recovery-preserves-divergent-content', 'fresh-owner-exact-image-recovery', 'lost-response-no-replay', 'provider-routing'))
DIGEST_FIELDS = frozenset(('referenceSha256', 'planIdentitySha256', 'receiptIdentitySha256',
                         'sourcePreimageSha256', 'sourcePostimageSha256', 'sourceSha256', 'savedSha256', 'documentSha256',
                         'invocationJournalSha256', 'threadStoreSha256'))
COUNT_FIELDS = frozenset(('rejectedIntentCount', 'candidateCount', 'declarationCount', 'processCount', 'isolatedStartupCommandCount',
                        'approvalPrepareCount', 'approvedInvocationCount'))


def admit_event(value: object) -> dict:
    if not isinstance(value, dict):
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    event = value.get('event')
    if event == 'case' and set(value) == {'event', 'case', 'outcome'} and value['case'] in CASE_NAMES and value['outcome'] in ('passed', 'rejected', 'unqualified'):
        return value
    if value in ({'event': 'completed'}, {'event': 'control', 'action': 'restart-ide'}):
        return value
    if event == 'control' and value.get('action') == 'probe':
        allowed = {'event', 'action', 'command', 'preimageSha256', 'postimageSha256'}
        required = allowed - {'postimageSha256'}
        if (set(value) <= allowed and required <= set(value) and value['command'] in COMMANDS
                and isinstance(value['preimageSha256'], str) and DIGEST.fullmatch(value['preimageSha256'])
                and ('postimageSha256' not in value or isinstance(value['postimageSha256'], str)
                     and DIGEST.fullmatch(value['postimageSha256']))):
            return value
    if event == 'control' and value.get('action') == 'wait-save-barrier-restart':
        if (set(value) == {'event', 'action', 'barrierId', 'preimageSha256', 'postimageSha256'}
                and isinstance(value['barrierId'], str) and str(uuid.UUID(value['barrierId'])) == value['barrierId']
                and isinstance(value['preimageSha256'], str) and DIGEST.fullmatch(value['preimageSha256'])
                and isinstance(value['postimageSha256'], str) and DIGEST.fullmatch(value['postimageSha256'])):
            return value
    if event == 'control' and value.get('action') == 'amend-generated-provenance':
        if (set(value) == {'event', 'action', 'sourceSha256'}
                and isinstance(value['sourceSha256'], str) and DIGEST.fullmatch(value['sourceSha256'])):
            return value
    if event == 'control' and value.get('action') == 'replace-broker':
        if (set(value) == {'event', 'action', 'planIdentity', 'sourceSha256'}
                and isinstance(value['planIdentity'], str) and re.fullmatch(r'plan:[0-9a-f]{64}', value['planIdentity'])
                and isinstance(value['sourceSha256'], str) and DIGEST.fullmatch(value['sourceSha256'])):
            return value
    if event == 'rejected' and set(value) == {'event', 'failure'} and isinstance(value['failure'], str) and re.fullmatch('[A-Z_]{1,80}', value['failure']):
        return value
    raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)


def event_observation(event: dict) -> dict:
    if event.get('action') == 'replace-broker':
        return {key: value for key, value in event.items() if key != 'planIdentity'} | {
            'planIdentitySha256': hashlib.sha256(event['planIdentity'].encode()).hexdigest()}
    return dict(event)


CONTROLLER_SCHEMAS = frozenset((
    'DYNAMIC_TOOL_CALL_PARAMS',
    'DYNAMIC_TOOL_CALL_RESPONSE',
    'FILE_CHANGE_REQUEST_APPROVAL_PARAMS',
    'FILE_CHANGE_REQUEST_APPROVAL_RESPONSE',
    'SERVER_REQUEST_RESOLVED_NOTIFICATION',
    'INITIALIZE_PARAMS',
    'ITEM_COMPLETED_NOTIFICATION',
    'ITEM_STARTED_NOTIFICATION',
    'REVIEW_START_PARAMS',
    'REVIEW_START_RESPONSE',
    'THREAD_FORK_PARAMS',
    'THREAD_FORK_RESPONSE',
    'THREAD_ITEMS_LIST_PARAMS',
    'THREAD_ITEMS_LIST_RESPONSE',
    'THREAD_LIST_PARAMS',
    'THREAD_LIST_RESPONSE',
    'THREAD_METADATA_UPDATE_PARAMS',
    'THREAD_METADATA_UPDATE_RESPONSE',
    'THREAD_QUEUE_START_PARAMS',
    'THREAD_QUEUE_START_RESPONSE',
    'THREAD_READ_PARAMS',
    'THREAD_READ_RESPONSE',
    'THREAD_RESUME_PARAMS',
    'THREAD_RESUME_RESPONSE',
    'THREAD_REVERT_PARAMS',
    'THREAD_REVERT_RESPONSE',
    'THREAD_ROLLBACK_PARAMS',
    'THREAD_ROLLBACK_RESPONSE',
    'THREAD_SEARCH_PARAMS',
    'THREAD_SEARCH_RESPONSE',
    'THREAD_START_PARAMS',
    'THREAD_START_RESPONSE',
    'THREAD_STARTED_NOTIFICATION',
    'THREAD_TIMELINE_LIST_PARAMS',
    'THREAD_TIMELINE_LIST_RESPONSE',
    'THREAD_TURNS_LIST_PARAMS',
    'THREAD_TURNS_LIST_RESPONSE',
    'THREAD_UNARCHIVE_PARAMS',
    'THREAD_UNARCHIVE_RESPONSE',
    'TURN_COMPLETED_NOTIFICATION',
    'TURN_INTERRUPT_PARAMS',
    'TURN_STARTED_NOTIFICATION',
    'TURN_START_PARAMS',
    'TURN_START_RESPONSE',
))

def admit_contract_failure(value: dict):
    if (not isinstance(value, dict) or set(value) != {'stage', 'observations'}
            or value['stage'] not in ('SCHEMA_INVENTORY', 'CONTRACT_DEFINITION', 'INITIALIZE', 'THREAD_START', 'THREAD_STARTED')
            or not isinstance(value['observations'], list) or not 1 <= len(value['observations']) <= 64):
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    for observation in value['observations']:
        if (not isinstance(observation, dict) or set(observation) != {'contract', 'schema'}
                or observation['contract'] not in ('SCHEMA_FILE_REJECTED', 'MISSING', 'INVALID',
                    'INITIALIZE_MUTATION_INCOMPATIBLE', 'TOOL_CALL_PROJECTION_INCOMPATIBLE',
                    'PLAN_APPROVAL_INCOMPATIBLE', 'PAYLOAD_REJECTED')
                or observation['schema'] not in CONTROLLER_SCHEMAS):
            raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)


def bounded_native_report(path: Path, workspace: Path) -> dict:
    if path.stat().st_size > 256 * 1024:
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    value = json.loads(path.read_text())
    if set(value) != {'schemaVersion', 'metadata', 'cases'} or value['schemaVersion'] != 1:
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    metadata = value['metadata']
    if set(metadata) == {'upstream', 'provider', 'stockCodexUi', 'workspaceRoot'}:
        metadata = dict(metadata, status='incomplete', failure=None)
        value = dict(value, metadata=metadata)
    if set(metadata) - {'contractFailure'} != {'upstream', 'provider', 'stockCodexUi', 'workspaceRoot', 'status', 'failure'}:
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    if metadata['workspaceRoot'] != str(workspace) or metadata['status'] not in ('observed', 'rejected', 'incomplete'):
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    if metadata['status'] == 'incomplete' and metadata['failure'] is not None:
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    if metadata['failure'] is not None and not re.fullmatch('[A-Z_]{1,80}', metadata['failure']):
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    if metadata['upstream'] != 'scripted-native-protocol-controller' or metadata['provider'] != 'staged-production-broker-cli-plugin' or metadata['stockCodexUi'] != 'unqualified':
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    if 'contractFailure' in metadata:
        admit_contract_failure(metadata['contractFailure'])
        if metadata['status'] != 'rejected' or metadata['failure'] != 'CONTRACT_REJECTED':
            raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    cases = value['cases']
    if not isinstance(cases, dict) or not set(cases) <= CASE_NAMES:
        raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    for name, case in cases.items():
        if set(case) != {'outcome', 'evidence'} or case['outcome'] not in ('passed', 'rejected', 'unqualified'):
            raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
        for field, item in case['evidence'].items():
            valid = ((field in DIGEST_FIELDS and isinstance(item, str) and re.fullmatch('[0-9a-f]{64}', item))
                     or (field in COUNT_FIELDS and type(item) is int and 0 <= item <= 1000)
                     or (field == 'documentState' and item in ('SAVED_COMMITTED', 'SAVED_UNCOMMITTED', 'DIRTY_COMMITTED', 'DIRTY_UNCOMMITTED'))
                     or (field == 'syntax' and item in ('CLEAN', 'ERRORS', 'UNCOMMITTED'))
                     or (field == 'undo' and item in ('PRODUCTION_CHANGE', 'OTHER', 'UNAVAILABLE'))
                     or (field == 'referencePassedUnchanged' and item is True)
                     or (field in ('indexingState', 'afterIndexingState') and item in ('DUMB', 'SMART'))
                     or (field in ('expectedRejection', 'observedRejection') and item in (
                         'APPROVAL_DECLINED', 'APPROVAL_CANCELLED', 'APPROVAL_CONTROLLER_REJECTED',
                         'BROKER_INVALID_ARGUMENTS', 'EXACT_SYMBOL_REQUIRED', 'WORKSPACE_NOT_READY',
                         'CONTENT_CHANGED', 'OTHER_REJECTION', 'NOT_REJECTED', 'RESPONSE_LOST'))
                     or (field in ('expectedRecovery', 'observedRecovery') and item in ('MANUAL_RECOVERY_REQUIRED', 'OTHER_RESULT'))
                     or (field == 'postPlanAdmission' and item == 'SAVED_PSI_COMMITTED')
                     or (field == 'brokerFailure' and item == 'WORKSPACE_RECOVERY_REQUIRED')
                     or (field == 'retryInvocationCount' and type(item) is int and item == 0)
                     or (field == 'brokerReplacement' and item == 'STORES_RETAINED')
                     or (field == 'recoveryState' and item == 'ROLLED_BACK')
                     or (field == 'retrievedState' and item in ('verified', 'applied_unverified', 'recovery_required')))
            if field in ('before', 'after'):
                admitted_live(item, workspace)
            elif not valid:
                raise AcceptanceRejected(AcceptanceFailure.NATIVE_OUTPUT)
    return value


def durable_receipt_scopes(home: Path, workspace: Path) -> list[dict]:
    root_digest = hashlib.sha256(str(workspace).encode()).hexdigest()
    database = home / '.kast/state/workspaces' / root_digest / 'mutation.sqlite'
    if database.is_symlink() or not database.is_file():
        raise AcceptanceRejected(AcceptanceFailure.RECEIPT)
    with sqlite3.connect('file:' + quote(str(database)) + '?mode=ro', uri=True) as connection:
        rows = connection.execute('SELECT document, document_sha256 FROM live_change_receipt LIMIT 17').fetchall()
    if not rows or len(rows) > 16:
        raise AcceptanceRejected(AcceptanceFailure.RECEIPT)
    result = []
    for raw, expected in rows:
        if len(raw.encode()) > 4 * 1024 * 1024 or hashlib.sha256(raw.encode()).hexdigest() != expected:
            raise AcceptanceRejected(AcceptanceFailure.RECEIPT)
        document = json.loads(raw)
        if (set(document) != {'identity', 'content'} or not re.fullmatch(r'receipt:[0-9a-f]{64}', document['identity'])
                or document['content'].get('version') != 1 or document['content'].get('kind') != 'LIVE_ADD_DECLARATION_RECEIPT'):
            raise AcceptanceRejected(AcceptanceFailure.RECEIPT)
        result.append(receipt_scope_observation(document['content']['body'], expected, workspace))
    return result


SEMANTIC_OBLIGATIONS = (
    'TARGET_PREIMAGE_UNCHANGED', 'OWNER_AND_PROVENANCE_UNCHANGED', 'DECLARED_WRITE_SET_CLOSED',
    'EXPECTED_POSTIMAGE_OBSERVED', 'DECLARATION_IDENTITY_OBSERVED', 'COMPILER_COLLISION_REMAINS_ABSENT',
    'OUTBOUND_BINDINGS_PRESERVED', 'EXISTING_BINDINGS_PRESERVED', 'COMPILER_DIAGNOSTICS_CLEAR',
)
LIVE_OBLIGATIONS = ('ORIGINAL_OWNER_EPOCH_MODEL_UNCHANGED_BEFORE_WRITE', 'RESULT_SAVED_COMMITTED_LIVE_STATE_OBSERVED')


def receipt_scope_observation(body: dict, document_digest: str, workspace: Path) -> dict:
    plan, after = body['plan'], body['after']
    before_live = {'root': plan['workspaceRoot'], 'host': plan['owner'], 'epoch': plan['epoch'],
                   'contentView': plan['contentView'], 'version': plan['referenceVersion']}
    after_live = {'root': after['root'], 'host': after['owner'], 'epoch': after['epoch'],
                  'contentView': after['contentView'], 'version': after['version']}
    admitted_live(before_live, workspace)
    admitted_live(after_live, workspace)
    if (after['owner'] != plan['owner'] or after['epoch'] <= plan['epoch']
            or tuple(body['semanticObligations']) != SEMANTIC_OBLIGATIONS
            or tuple(body['liveObligations']) != LIVE_OBLIGATIONS
            or body['semanticObligations'] != plan['semanticObligations']
            or body['liveObligations'] != plan['liveObligations']):
        raise AcceptanceRejected(AcceptanceFailure.RECEIPT)
    scope = plan['verificationScope']
    if set(scope) != {'relations', 'traversals', 'diagnostics'}:
        raise AcceptanceRejected(AcceptanceFailure.RECEIPT)
    if any(not isinstance(scope[key], list) or not 1 <= len(scope[key]) <= 64 for key in scope):
        raise AcceptanceRejected(AcceptanceFailure.RECEIPT)
    for files in scope['diagnostics']:
        if (not isinstance(files, list) or not 1 <= len(files) <= 256
                or any(not isinstance(name, str) or not Path(name).is_relative_to(workspace)
                       or '..' in Path(name).parts for name in files)):
            raise AcceptanceRejected(AcceptanceFailure.RECEIPT)
    return {'receiptDocumentSha256': document_digest,
        'verificationScopeSha256': hashlib.sha256(json.dumps(scope, separators=(',', ':'), sort_keys=True).encode()).hexdigest(),
        'relationScopeCount': len(scope['relations']), 'traversalScopeCount': len(scope['traversals']),
        'diagnosticFileCounts': [len(files) for files in scope['diagnostics']],
        'dischargedObligations': list(SEMANTIC_OBLIGATIONS + LIVE_OBLIGATIONS),
        'beforeOwner': plan['owner'], 'beforeEpoch': plan['epoch'],
        'afterOwner': after['owner'], 'afterEpoch': after['epoch']}


def remaining_matrix_gates(native: dict | None = None, read_regression: dict | None = None,
                           events: list[dict] | None = None) -> list[dict]:
    cases = (native or {}).get('cases', {})
    def passed(*names):
        return all(cases.get(name, {}).get('outcome') == 'passed' for name in names)
    stages = {event.get('stage') for event in events or []
              if event.get('event') == 'stage' and event.get('outcome') == 'completed'}
    checks = (
        ('complete-workflow-and-exact-approval',
         passed('complete-workflow', 'plan-has-no-source-effect', 'approval-decline', 'approval-cancel',
                'approval-malformed', 'edited-preimage', 'approval-wait-releases-workspace'),
         'Requires actual exact-plan controller approval, unchanged planning state and a verified independent read.'),
        ('dirty-uncommitted-and-unready-project',
         passed('dirty-document-refusal', 'indexing-policy-refusal'),
         'Requires observed native document and indexing policy refusals.'),
        ('post-effect-interruption-boundaries',
         passed('post-save-interruption-no-replay') and 'post-save-interrupted' in stages,
         'Native post-save interruption is missing; deterministic protocol boundaries remain separate test evidence.'),
        ('psi-structure-and-undo', passed('psi-structure', 'production-undo'),
         'Requires native direct-container PSI structure and actual production-command undo.'),
        ('plugin-unload-and-request-drain',
         passed('plugin-unload-retires-pending-approval', 'owner-retirement-invalidates-reference-and-plan')
         and 'plugin-owner-retired' in stages,
         'Requires platform unload, service retirement completion, endpoint absence and retired authority refusal.'),
        ('complete-hosted-read-regression',
         (read_regression or {}).get('outcome') == 'passed' and (read_regression or {}).get('sourceUnchanged') is True,
         'Requires the full authored semantic matrix and all eight default read tools through staged CLI/provider.'),
        ('foreign-root-generated-ambiguous-and-model-movement',
         passed('foreign-root-refusal', 'generated-target-refusal', 'ambiguous-name-is-not-authority',
                'model-movement-refusal', 'unsupported-intents', 'old-epoch-reference'),
         'Requires exact wrong-authority, provenance, ambiguity, unsupported-intent and model-movement refusals.'),
        ('retry-process-replacement-and-recovery',
         passed('concurrent-same-plan-single-effect', 'repeat-apply-reuses-receipt', 'lost-response-no-replay',
                'historical-receipt-after-owner-restart', 'broker-process-restart-no-replay',
                'recovery-preserves-divergent-content', 'recovery-preserves-divergent-document',
                'fresh-owner-exact-image-recovery') and 'fixture-broker-process-replaced' in stages,
         'Requires concurrent/repeated application, process replacement and exact-image versus divergent recovery.'),
    )
    return [dict(scenario=name, outcome='unqualified', reason=reason)
            for name, observed, reason in checks if not observed]


def native_workflow_qualified(evidence: dict) -> bool:
    return (evidence.get('status') != 'rejected' and evidence.get('source', {}).get('clean') is True
            and evidence.get('native', {}).get('metadata', {}).get('status') == 'observed'
            and not remaining_matrix_gates(evidence.get('native'), evidence.get('readRegression'),
                                           evidence.get('events', [])))
