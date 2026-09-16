"""Owned native lifecycle fixture over the production request/status control path."""
from dataclasses import asdict, dataclass, field
from enum import Enum
import json
import shutil
import struct
import time
import uuid

from hosted_peer_probe import admit_peer_endpoint, connected_peer, receive_terminal_reply
from hosted_read_transport import HostedReadTransport


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


@dataclass(frozen=True)
class RefreshReceipt:
    outcome: str
    fileVisible: bool
    importedModuleVisible: bool
    duplicateRetained: bool
    conflictingRequestRejected: bool
    failedImportRejected: bool
    restored: bool
    pendingObservations: int
    failure: RefreshFixtureFailure | None = None
    effectFailure: RefreshEffectFailure | None = None
    restorationFailure: RefreshEffectFailure | None = None


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


def run_workspace_refresh_regression(isolation, fixture, product, java, harness, live):
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
    observations, failure, effect_failure, restoration_failure = 0, None, None, None
    try:
        with HostedReadTransport(isolation, fixture, product, java, harness).open() as transport:
            external.write_text('package fixture\nclass NativeRefreshExternal\n')
            request = RefreshRequest(str(uuid.uuid4()), RefreshEffect.FILE_REFRESH)
            first = exchange_refresh(endpoint, request, schema)
            repeated = exchange_refresh(endpoint, request, schema)
            duplicate = repeated.get('requestId') == request.requestId and repeated.get('type') in ('pending', 'complete')
            conflicting = exchange_refresh(endpoint, RefreshRequest(request.requestId, RefreshEffect.GRADLE_MODEL_RELOAD), schema)
            conflict = conflicting == {'type': 'rejected', 'reason': 'REQUEST_CONFLICT'}
            observations += await_refresh(endpoint, request, first, schema)
            file_visible = visible(transport, 'NativeRefreshExternal')
            (module / 'src/main/kotlin').mkdir(parents=True)
            (module / 'build.gradle.kts').write_text('plugins { kotlin("jvm") }\nrepositories { mavenCentral() }\n')
            (module / 'src/main/kotlin/NativeRefreshModule.kt').write_text('package refresh.module\nclass NativeRefreshModule\n')
            settings.write_bytes(baseline + b'\ninclude(":native-refresh-module")\n')
            request = RefreshRequest(str(uuid.uuid4()), RefreshEffect.GRADLE_MODEL_RELOAD)
            observations += await_refresh(endpoint, request, exchange_refresh(endpoint, request, schema), schema)
            model_visible = visible(transport, 'NativeRefreshModule')
            (module / 'build.gradle.kts').write_text('this is deliberately invalid Gradle Kotlin fixture syntax !\n')
            request = RefreshRequest(str(uuid.uuid4()), RefreshEffect.GRADLE_MODEL_RELOAD)
            rejected, pending = wait_refresh(endpoint, request, exchange_refresh(endpoint, request, schema), schema)
            observations += pending
            failed_import = rejected.get('type') == 'failed' and rejected.get('reason') == 'EFFECT_FAILED'
    except RefreshEffectRejected as error:
        failure, effect_failure = RefreshFixtureFailure.REJECTED, error.reason
    except (OSError, ValueError, TypeError, KeyError):
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
    passed = all((file_visible, model_visible, duplicate, conflict, failed_import, restored)) and failure is None
    return asdict(RefreshReceipt('passed' if passed else 'rejected', file_visible, model_visible,
                               duplicate, conflict, failed_import, restored, observations, failure, effect_failure, restoration_failure))


def visible(transport, name):
    response = transport.invoke('cli', 'search_classes', asdict(RefreshClassSearch(name)))
    return (response.get('status') == 'complete' and len(response.get('items', [])) == 1
            and response['items'][0].get('name') == name)
