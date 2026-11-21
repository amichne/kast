"""Drive one installed native MCP session and drain one public query workload.

Request arguments and result documents are opaque public schema contracts.
The installed MCP executable owns the workspace and its Host; this module never
starts, scripts or restarts an IDE.
"""
from dataclasses import asdict, dataclass, field
from enum import Enum
import hashlib
import json
import os
from pathlib import Path
import re
import select
import subprocess
import time

from retained_pages import presentation, PresentationNext
from hosted_timing import (ObservationWindow, UnavailableTiming, TimingFailure, join_timing,
    release_observed)

HERE = Path(__file__).resolve().parent


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def fresh(path):
    path.mkdir(mode=0o700, parents=True, exist_ok=False)
    return path.resolve(strict=True)


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
    rejection = response.get('rejection') if isinstance(response, dict) else None
    detail = rejection.get('detail') if isinstance(rejection, dict) else None
    if (isinstance(response, dict) and response.get('status') == 'rejected' and
        isinstance(rejection, dict) and rejection.get('type') == 'COMPLETION_UNPROVEN' and
        isinstance(detail, dict) and 'diagnosticReadId' in detail):
        identity = detail['diagnosticReadId']
        if not isinstance(identity, str) or not re.fullmatch(
            r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', identity):
            return RejectedDiagnosticCorrelation(DiagnosticCorrelationFailure.MISMATCHED)
        matching = [doc for doc in diagnostics if doc.get('readId') == identity]
        if len(matching) == 1: return MatchedDiagnostic(matching[0])
        return RejectedDiagnosticCorrelation(DiagnosticCorrelationFailure.AMBIGUOUS if matching else
                                             DiagnosticCorrelationFailure.MISMATCHED)
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
        grant = initial['request'].get('executionBudget')
        if token is not None:
            if token in seen: break
            seen.add(token)
            request = dict(verbose=True, request=dict(type='RESUME', continuation=token,
                                                     executionBudget=grant))
        else:
            progress = presentation(calls)
            if not isinstance(progress, PresentationNext): break
            selected = initial['request']['terminalStep'].get('output')
            request = dict(verbose=True, request=dict(type='READ_RESULT', result=progress.result.value,
                cursor=progress.cursor.value, output=selected, executionBudget=grant,
                evidence_cursor=progress.evidence_cursor.value))
    return calls


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

