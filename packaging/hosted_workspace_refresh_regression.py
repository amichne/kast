"""Owned native lifecycle fixture over the production request/status control path."""
from dataclasses import asdict, dataclass, field
from enum import Enum
import json
import shutil
import struct
import time
import uuid

from hosted_peer_probe import admit_peer_endpoint, connected_peer, receive_terminal_reply
from hosted_read_transport import HostedReadTransport, ReadTransportRejected, ReadTransportFailure, ReadProviderFailure
from hosted_repair_budget_regression import SemanticOutcome


class RefreshStage(str, Enum):
    PROVIDER = 'provider'
    HOSTED_RULE = 'hosted_rule'
    FILE_EFFECT = 'file_effect'
    FILE_VISIBILITY = 'file_visibility'
    MODEL_EFFECT = 'model_effect'
    MODEL_VISIBILITY = 'model_visibility'
    FAILED_IMPORT = 'failed_import'


class RefreshBoundaryFailure(str, Enum):
    IO = 'io_rejected'
    VALUE = 'value_rejected'
    TYPE = 'type_rejected'
    KEY = 'key_rejected'
    HOSTED_RULE_TIMEOUT = 'HOSTED_RULE_TIMEOUT'
    HOSTED_RULE_INSPECTION_REJECTED = 'HOSTED_RULE_INSPECTION_REJECTED'
    HOSTED_RULE_HOST_UNAVAILABLE = 'HOSTED_RULE_HOST_UNAVAILABLE'
    HOSTED_RULE_PLUGIN_UNAVAILABLE = 'HOSTED_RULE_PLUGIN_UNAVAILABLE'
    HOSTED_RULE_SELECTED_IDE_UNAVAILABLE = 'HOSTED_RULE_SELECTED_IDE_UNAVAILABLE'
    HOSTED_RULE_CAPABILITY_MISMATCH = 'HOSTED_RULE_CAPABILITY_MISMATCH'
    HOSTED_RULE_HOST_IDENTITY_MISMATCH = 'HOSTED_RULE_HOST_IDENTITY_MISMATCH'
    HOSTED_RULE_UNSUPPORTED_PLATFORM_LINE = 'HOSTED_RULE_UNSUPPORTED_PLATFORM_LINE'
    HOSTED_RULE_TARGET_REJECTED = 'HOSTED_RULE_TARGET_REJECTED'
    HOSTED_RULE_RESTORATION_REJECTED = 'HOSTED_RULE_RESTORATION_REJECTED'
    HOSTED_RULE_RELEASE_REJECTED = 'HOSTED_RULE_RELEASE_REJECTED'


@dataclass(frozen=True)
class RefreshBoundaryRejection:
    stage: RefreshStage
    cause: RefreshBoundaryFailure | ReadTransportFailure
    providerFailure: ReadProviderFailure | None = None


def refresh_rejection(stage, error):
    if isinstance(error, ReadTransportRejected):
        return RefreshBoundaryRejection(stage, error.reason, error.provider_failure)
    if stage is RefreshStage.HOSTED_RULE and isinstance(error, ValueError):
        known = next((reason for reason in RefreshBoundaryFailure if reason.value == str(error)), None)
        if known is not None:
            return RefreshBoundaryRejection(stage, known)
    cause = next(reason for kind, reason in ((OSError, RefreshBoundaryFailure.IO),
        (ValueError, RefreshBoundaryFailure.VALUE), (TypeError, RefreshBoundaryFailure.TYPE),
        (KeyError, RefreshBoundaryFailure.KEY)) if isinstance(error, kind))
    return RefreshBoundaryRejection(stage, cause)


class RefreshEffect(str, Enum):
    FILE_REFRESH = 'FILE_REFRESH'
    GRADLE_MODEL_RELOAD = 'GRADLE_MODEL_RELOAD'


class RefreshFixtureFailure(str, Enum):
    REJECTED = 'NATIVE_REFRESH_REJECTED'
    RESTORATION_REJECTED = 'NATIVE_REFRESH_RESTORATION_REJECTED'


class RefreshEffectFailure(str, Enum):
    INVALID_REQUEST = 'INVALID_REQUEST'
    UNLINKED_BUILD = 'UNLINKED_BUILD'
    UNSAVED_DOCUMENTS = 'UNSAVED_DOCUMENTS'
    DISPOSED = 'DISPOSED'
    CAPACITY = 'CAPACITY'
    UNKNOWN_REQUEST = 'UNKNOWN_REQUEST'
    REQUEST_CONFLICT = 'REQUEST_CONFLICT'
    EFFECT_FAILED = 'EFFECT_FAILED'
    CANCELLED = 'CANCELLED'
    DEADLINE_EXCEEDED = 'DEADLINE_EXCEEDED'
    ADMISSION_REJECTED = 'ADMISSION_REJECTED'
    NEWER_CHANGE = 'NEWER_CHANGE'


class RefreshEffectRejected(ValueError):
    def __init__(self, reason):
        self.reason = RefreshEffectFailure(reason)
        super().__init__('REFRESH_EFFECT_INCOMPLETE')


@dataclass(frozen=True)
class RefreshRequest:
    requestId: str
    effect: RefreshEffect
    type: str = field(default='request', init=False)


@dataclass(frozen=True)
class RefreshStatus:
    requestId: str
    type: str = field(default='status', init=False)


@dataclass(frozen=True)
class RefreshTransportRequest:
    root: str
    document: str
    type: str = field(default='WORKSPACE_REFRESH', init=False)


@dataclass(frozen=True)
class RefreshClassSearch:
    class_name: str
    name_match: str = 'exact'
    scope: None = None


class VisibilityReason(str, Enum):
    WORKSPACE_NOT_READY = 'workspace-not-ready'
    QUERY_REJECTED = 'query-rejected'
    RESULT_LIMIT = 'result-limit'
    BYTE_LIMIT = 'byte-limit'
    WORK_LIMIT = 'work-limit'
    TIME_LIMIT = 'time-limit'
    DUMB_MODE_TRANSITION = 'dumb-mode-transition'
    PROVIDER_FAILURE = 'provider-failure'
    UNSCOPED_PROVIDER = 'unscoped-provider'
    UNSUPPORTED_ITEM = 'unsupported-item'
    EXACT_DEFINITION_UNAVAILABLE = 'exact-definition-unavailable'


@dataclass(frozen=True)
class VisibilityReceipt:
    status: SemanticOutcome
    reasons: tuple[VisibilityReason, ...]
    resultCount: int
    exactNameMatches: int
    effectiveMillis: int | None
    roundTripNanos: int

    @property
    def visible(self):
        return self.status == SemanticOutcome.COMPLETE and self.resultCount == self.exactNameMatches == 1


@dataclass(frozen=True)
class RefreshReceipt:
    outcome: str
    fileVisible: bool
    importedModuleVisible: bool
    duplicateRetained: bool
    conflictingRequestRejected: bool
    failedImportRejected: bool
    restored: bool
    configuredRule: bool
    invalidRuleRejected: bool
    pendingObservations: int
    failure: RefreshFixtureFailure | None = None
    effectFailure: RefreshEffectFailure | None = None
    restorationFailure: RefreshEffectFailure | None = None
    fileObservation: VisibilityReceipt | None = None
    moduleObservation: VisibilityReceipt | None = None
    rejection: RefreshBoundaryRejection | None = None


def exchange_refresh(endpoint, command, schema):
    request = RefreshTransportRequest(endpoint.root, json.dumps(asdict(command), separators=(',', ':')))
    body = json.dumps(asdict(request), separators=(',', ':')).encode()
    with connected_peer(endpoint) as peer:
        peer.sendall(struct.pack('>I', len(body)) + body)
        reply, _ = receive_terminal_reply(peer)
    if (not isinstance(reply, dict) or reply.get('root') != endpoint.root
            or reply.get('host') != endpoint.host or not isinstance(reply.get('result'), dict)):
        raise ValueError('REFRESH_RESPONSE_REJECTED')
    if not schema.admits(reply):
        raise ValueError('REFRESH_SCHEMA_REJECTED')
    result = reply['result']
    if result.get('type') in ('pending', 'complete', 'failed') and result.get('requestId') != command.requestId:
        raise ValueError('REFRESH_CORRELATION_REJECTED')
    return result


def wait_refresh(endpoint, request, first, schema, *, timeout=300):
    deadline = time.monotonic() + timeout
    result, observations = first, 0
    while result.get('type') == 'pending':
        observations += 1
        if result.get('stage') not in ('QUEUED', 'EFFECT', 'ADMISSION'):
            raise ValueError('REFRESH_STAGE_REJECTED')
        if time.monotonic() >= deadline:
            raise ValueError('REFRESH_FIXTURE_TIMEOUT')
        time.sleep(0.2)
        result = exchange_refresh(endpoint, RefreshStatus(request.requestId), schema)
    return result, observations


def await_refresh(endpoint, request, first, schema, *, timeout=300):
    result, observations = wait_refresh(endpoint, request, first, schema, timeout=timeout)
    if result.get('type') in ('failed', 'rejected'):
        raise RefreshEffectRejected(result.get('reason'))
    if result.get('type') != 'complete':
        raise ValueError('REFRESH_EFFECT_INCOMPLETE')
    return observations


def run_workspace_refresh_regression(isolation, fixture, product, java, harness, live, selected_idea_home):
    """Effects are limited to new owned files and restored fixture settings."""
    from hosted_wire_schema import load_hosted_wire_schema
    endpoint = admit_peer_endpoint(isolation, fixture.workspace, live)
    schema = load_hosted_wire_schema(product, 'ide-hosted/hosted-workspace-refresh.schema.json')
    settings = fixture.workspace / 'settings.gradle.kts'
    baseline = settings.read_bytes()
    external = fixture.workspace / 'src/main/kotlin/NativeRefreshExternal.kt'
    module = fixture.workspace / 'native-refresh-module'
    if external.exists() or module.exists() or fixture.workspace != isolation.root / 'workspace':
        raise ValueError('REFRESH_FIXTURE_OWNERSHIP_REJECTED')
    file_visible = model_visible = duplicate = conflict = failed_import = restored = False
    configured_rule = invalid_rule_rejected = False
    observations, failure, effect_failure, restoration_failure = 0, None, None, None
    file_observation = module_observation = None
    rejection = None
    stage = RefreshStage.PROVIDER
    try:
        with HostedReadTransport(isolation, fixture, product, java, harness, selected_idea_home).open() as transport:
            stage = RefreshStage.HOSTED_RULE
            configured_rule, invalid_rule_rejected = prove_hosted_rule(transport, fixture.workspace)
            stage = RefreshStage.FILE_EFFECT
            external.write_text('package fixture\nclass NativeRefreshExternal\n')
            request = RefreshRequest(str(uuid.uuid4()), RefreshEffect.FILE_REFRESH)
            first = exchange_refresh(endpoint, request, schema)
            repeated = exchange_refresh(endpoint, request, schema)
            duplicate = repeated.get('requestId') == request.requestId and repeated.get('type') in ('pending', 'complete')
            conflicting = exchange_refresh(endpoint, RefreshRequest(request.requestId, RefreshEffect.GRADLE_MODEL_RELOAD), schema)
            conflict = conflicting == {'type': 'rejected', 'reason': 'REQUEST_CONFLICT'}
            observations += await_refresh(endpoint, request, first, schema)
            stage = RefreshStage.FILE_VISIBILITY
            file_observation = observe_visibility(transport, 'NativeRefreshExternal')
            file_visible = file_observation.visible
            stage = RefreshStage.MODEL_EFFECT
            (module / 'src/main/kotlin').mkdir(parents=True)
            (module / 'build.gradle.kts').write_text('plugins { kotlin("jvm") }\nrepositories { mavenCentral() }\n')
            (module / 'src/main/kotlin/NativeRefreshModule.kt').write_text('package refresh.module\nclass NativeRefreshModule\n')
            settings.write_bytes(baseline + b'\ninclude(":native-refresh-module")\n')
            request = RefreshRequest(str(uuid.uuid4()), RefreshEffect.GRADLE_MODEL_RELOAD)
            observations += await_refresh(endpoint, request, exchange_refresh(endpoint, request, schema), schema)
            stage = RefreshStage.MODEL_VISIBILITY
            module_observation = observe_visibility(transport, 'NativeRefreshModule')
            model_visible = module_observation.visible
            stage = RefreshStage.FAILED_IMPORT
            (module / 'build.gradle.kts').write_text('this is deliberately invalid Gradle Kotlin fixture syntax !\n')
            request = RefreshRequest(str(uuid.uuid4()), RefreshEffect.GRADLE_MODEL_RELOAD)
            rejected, pending = wait_refresh(endpoint, request, exchange_refresh(endpoint, request, schema), schema)
            observations += pending
            failed_import = rejected.get('type') == 'failed' and rejected.get('reason') == 'EFFECT_FAILED'
    except RefreshEffectRejected as error:
        failure, effect_failure = RefreshFixtureFailure.REJECTED, error.reason
    except (OSError, ValueError, TypeError, KeyError) as error:
        rejection = refresh_rejection(stage, error)
        failure = RefreshFixtureFailure.REJECTED
    finally:
        settings.write_bytes(baseline)
        external.unlink(missing_ok=True)
        if module.exists():
            shutil.rmtree(module)
        try:
            request = RefreshRequest(str(uuid.uuid4()), RefreshEffect.GRADLE_MODEL_RELOAD)
            observations += await_refresh(endpoint, request, exchange_refresh(endpoint, request, schema), schema)
            restored = settings.read_bytes() == baseline and not external.exists() and not module.exists()
        except RefreshEffectRejected as error:
            failure, restoration_failure = RefreshFixtureFailure.RESTORATION_REJECTED, error.reason
        except (OSError, ValueError, TypeError, KeyError):
            failure = RefreshFixtureFailure.RESTORATION_REJECTED
    passed = all((file_visible, model_visible, duplicate, conflict, failed_import, restored,
                  configured_rule, invalid_rule_rejected)) and failure is None
    return asdict(RefreshReceipt('passed' if passed else 'rejected', file_visible, model_visible,
                               duplicate, conflict, failed_import, restored, configured_rule,
                               invalid_rule_rejected, observations, failure, effect_failure, restoration_failure,
                               file_observation, module_observation, rejection))


def lifecycle_terminal(transport, request, host):
    deadline = time.monotonic() + 60
    document, _ = transport.invoke_observed('provider', 'workspace_lifecycle', request)
    while document.get('status') == 'completed' and document.get('document', {}).get('type') == 'pending':
        if time.monotonic() >= deadline:
            raise ValueError('HOSTED_RULE_TIMEOUT')
        time.sleep(0.2)
        document, _ = transport.invoke_observed('provider', 'workspace_lifecycle',
            {'type': 'status', 'host': host, 'requestId': request['requestId']})
    return document


def prove_hosted_rule(transport, workspace):
    inspected, _ = transport.invoke_observed('provider', 'workspace_lifecycle', {'type': 'inspect'})
    document = inspected.get('document', {})
    if inspected.get('status') != 'completed' or document.get('type') != 'inspected':
        reason = inspected.get('diagnostic', {}).get('reason')
        cause = 'HOSTED_RULE_' + reason if isinstance(reason, str) else ''
        if any(known.value == cause for known in RefreshBoundaryFailure):
            raise ValueError(cause)
        raise ValueError('HOSTED_RULE_INSPECTION_REJECTED')
    host = document.get('host')
    targets = [project.get('target') for project in document.get('projects', [])
               if project.get('target', {}).get('root') == str(workspace)]
    if len(targets) != 1 or targets[0].get('host') != host:
        raise ValueError('HOSTED_RULE_TARGET_REJECTED')
    target = targets[0]
    rule = {'type': 'task_success', 'task': ':nativeHostedRule', 'effect': 'FILE_REFRESH'}
    try:
        configured = lifecycle_terminal(transport,
            {'type': 'configure_sync', 'target': target, 'requestId': str(uuid.uuid4()), 'rule': rule}, host)
        valid = (configured.get('status') == 'completed' and
                 configured.get('document') == {'type': 'configured', 'target': target, 'rule': rule})
        invalid = lifecycle_terminal(transport,
            {'type': 'configure_sync', 'target': target, 'requestId': str(uuid.uuid4()),
             'rule': {'type': 'task_success', 'task': '?', 'effect': 'FILE_REFRESH'}}, host)
        rejected = (invalid.get('status') == 'rejected' and
                    invalid.get('diagnostic') == {'type': 'blocked', 'reason': 'INVALID_REQUEST'})
        return valid, rejected
    finally:
        cleared = lifecycle_terminal(transport,
            {'type': 'configure_sync', 'target': target, 'requestId': str(uuid.uuid4()),
             'rule': {'type': 'off'}}, host)
        if cleared.get('status') != 'completed' or cleared.get('document', {}).get('type') != 'configured':
            raise ValueError('HOSTED_RULE_RESTORATION_REJECTED')
        released = lifecycle_terminal(transport,
            {'type': 'release', 'target': target, 'requestId': str(uuid.uuid4())}, host)
        if released.get('status') != 'completed' or released.get('document') != {
                'type': 'released', 'target': target}:
            raise ValueError('HOSTED_RULE_RELEASE_REJECTED')


def observe_visibility(transport, name):
    started = time.monotonic_ns()
    response, _ = transport.invoke_observed('cli', 'search_classes', asdict(RefreshClassSearch(name)))
    elapsed = time.monotonic_ns() - started
    items = response.get('items', [])
    qualification = response.get('qualification')
    if qualification is None:
        reasons = ()
    elif isinstance(qualification, str) and qualification.startswith('[') and qualification.endswith(']'):
        reasons = tuple(VisibilityReason(value.strip()) for value in qualification[1:-1].split(',') if value.strip())
    else:
        raise ValueError('VISIBILITY_QUALIFICATION_REJECTED')
    rejection = response.get('reason')
    if rejection is not None:
        reasons += (VisibilityReason(rejection),)
    grant = response.get('execution_budget', {}).get('max_elapsed_ms', {}).get('effective')
    return VisibilityReceipt(SemanticOutcome(response['status']), reasons, len(items),
        sum(item.get('name') == name for item in items), grant, elapsed)
