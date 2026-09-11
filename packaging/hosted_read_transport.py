"""Owned, bounded result transport for the native read regression; payloads never become log files."""
from contextlib import contextmanager
from enum import Enum
import json
import os
import re
import selectors
import subprocess
import time


class ReadTransportFailure(str, Enum):
    CLI_SCHEMA = 'READ_CLI_SCHEMA_REJECTED'
    CLI_TOOL = 'READ_CLI_TOOL_REJECTED'
    CLI_OUTPUT = 'READ_CLI_OUTPUT_REJECTED'
    SURFACE = 'READ_SURFACE_REJECTED'
    PROVIDER = 'READ_PROVIDER_REJECTED'
    PROVIDER_PROTOCOL = 'READ_PROVIDER_PROTOCOL_REJECTED'
    DISCONNECTED = 'READ_PROVIDER_DISCONNECTED'
    OUTPUT_BOUND = 'READ_PROVIDER_OUTPUT_BOUND'
    EXTRA_OUTPUT = 'READ_PROVIDER_EXTRA_OUTPUT'
    TIMEOUT = 'READ_PROVIDER_TIMEOUT'


class ReadProviderFailure(str, Enum):
    INVALID_ARGUMENTS = 'INVALID_ARGUMENTS'
    UNKNOWN_NAMESPACE = 'UNKNOWN_NAMESPACE'
    UNKNOWN_TOOL = 'UNKNOWN_TOOL'
    OUTPUT_CONTRACT = 'OUTPUT_CONTRACT_REJECTED'
    CANCELLED = 'INVOCATION_CANCELLED'
    OVERLOADED = 'OVERLOADED'
    UNEXPECTED = 'UNEXPECTED_FAILURE'
    TIMED_OUT = 'TIMED_OUT'
    IO = 'IO_REJECTED'
    OUTPUT_LIMIT = 'OUTPUT_LIMIT'
    SPAWN_FAILED = 'SPAWN_FAILED'
    TERMINATED = 'TERMINATED'
    QUALIFICATION = 'KAST_QUALIFICATION_FAILED'
    CONTRACT_CHANGED = 'KAST_CONTRACT_CHANGED'
    ARGUMENT = 'KAST_ARGUMENT_NOT_SCALAR'
    MALFORMED_OUTPUT = 'MALFORMED_KAST_OUTPUT'
    APPROVAL_REQUIRED = 'APPROVAL_REQUIRED'
    APPROVAL_BINDING = 'APPROVAL_BINDING_REJECTED'
    GRADLE_WRAPPER = 'GRADLE_WRAPPER_UNAVAILABLE'


class ReadViolationKeyword(str, Enum):
    TYPE = 'TYPE'
    REQUIRED = 'REQUIRED'
    ENUM = 'ENUM'
    CONST = 'CONST'
    PATTERN = 'PATTERN'
    ADDITIONAL_PROPERTIES = 'ADDITIONAL_PROPERTIES'
    ONE_OF = 'ONE_OF'
    ANY_OF = 'ANY_OF'
    MINIMUM = 'MINIMUM'
    MAXIMUM = 'MAXIMUM'
    MIN_LENGTH = 'MIN_LENGTH'
    MAX_LENGTH = 'MAX_LENGTH'
    MIN_ITEMS = 'MIN_ITEMS'
    MAX_ITEMS = 'MAX_ITEMS'
    UNKNOWN = 'UNKNOWN'


class ReadViolationField(str, Enum):
    DOCUMENT = 'DOCUMENT'
    STATUS = 'STATUS'
    OPERATION = 'OPERATION'
    LIVE = 'LIVE'
    ROOT = 'ROOT'
    HOST = 'HOST'
    EPOCH = 'EPOCH'
    CONTENT_VIEW = 'CONTENT_VIEW'
    VERSION = 'VERSION'
    GRAPH = 'GRAPH'
    SNAPSHOT = 'SNAPSHOT'
    CANONICAL_ROOT = 'CANONICAL_ROOT'
    GENERATION = 'GENERATION'
    NODES = 'NODES'
    EDGES = 'EDGES'
    PROOFS = 'PROOFS'
    ID = 'ID'
    SELECTOR = 'SELECTOR'
    KIND = 'KIND'
    NAME = 'NAME'
    QUALIFIED_IDENTITY = 'QUALIFIED_IDENTITY'
    FILE = 'FILE'
    RANGE = 'RANGE'
    START_INCLUSIVE = 'START_INCLUSIVE'
    END_EXCLUSIVE = 'END_EXCLUSIVE'
    PROOF = 'PROOF'
    DEPTH = 'DEPTH'
    MEANING = 'MEANING'
    SOURCE = 'SOURCE'
    TARGET = 'TARGET'
    OCCURRENCE = 'OCCURRENCE'
    CANDIDATE_SELECTOR = 'CANDIDATE_SELECTOR'
    PROVENANCE = 'PROVENANCE'
    COVERAGE = 'COVERAGE'
    IDENTITY = 'IDENTITY'
    QUALIFICATION = 'QUALIFICATION'
    LIMITATIONS = 'LIMITATIONS'
    RELATION_LIMITATIONS = 'RELATION_LIMITATIONS'
    CONTINUATION = 'CONTINUATION'
    REASON = 'REASON'
    DIAGNOSTIC = 'DIAGNOSTIC'
    UNKNOWN = 'UNKNOWN'


def _admit_output_violation_evidence(raw):
    """Mirror the closed JsonSchemaViolationEvidence boundary, never validator text."""
    if (not isinstance(raw, dict) or set(raw) != {'observations'}
            or not isinstance(raw['observations'], list) or not 1 <= len(raw['observations']) <= 4096):
        raise ReadTransportRejected('READ_PROVIDER_PROTOCOL_REJECTED')
    observed = set()
    admitted = []
    for row in raw['observations']:
        if not isinstance(row, dict) or set(row) != {'keyword', 'field'}:
            raise ReadTransportRejected('READ_PROVIDER_PROTOCOL_REJECTED')
        try:
            pair = (ReadViolationKeyword(row['keyword']), ReadViolationField(row['field']))
        except (ValueError, TypeError):
            raise ReadTransportRejected('READ_PROVIDER_PROTOCOL_REJECTED') from None
        if pair in observed:
            raise ReadTransportRejected('READ_PROVIDER_PROTOCOL_REJECTED')
        observed.add(pair)
        admitted.append({'keyword': pair[0].value, 'field': pair[1].value})
    return {'observations': admitted}


class ReadTransportRejected(ValueError):
    def __init__(self, reason, provider_failure=None, output_violation_evidence=None):
        self.reason = ReadTransportFailure(reason)
        self.provider_failure = provider_failure
        self.output_violation_evidence = output_violation_evidence
        self.invocation = None
        super().__init__(self.reason.value)

    def evidence(self):
        result = {'reason': self.reason.value}
        if self.provider_failure is not None:
            result['providerFailure'] = self.provider_failure.value
        if self.output_violation_evidence is not None:
            result['outputViolationEvidence'] = self.output_violation_evidence
        if self.invocation is not None:
            result['surface'], result['tool'] = self.invocation
        return result


def _provider_result(response):
    if response.get('kind') == 'completed':
        return response['envelope']['document']
    if response.get('kind') != 'rejected':
        raise ReadTransportRejected('READ_PROVIDER_PROTOCOL_REJECTED')
    try:
        failure = ReadProviderFailure(response.get('failure'))
    except (ValueError, TypeError):
        raise ReadTransportRejected('READ_PROVIDER_PROTOCOL_REJECTED') from None
    evidence = None
    if 'outputViolationEvidence' in response:
        if failure is not ReadProviderFailure.OUTPUT_CONTRACT:
            raise ReadTransportRejected('READ_PROVIDER_PROTOCOL_REJECTED')
        evidence = _admit_output_violation_evidence(response['outputViolationEvidence'])
    if failure is ReadProviderFailure.INVALID_ARGUMENTS:
        return {'failure': failure.value}
    raise ReadTransportRejected('READ_PROVIDER_REJECTED', failure, evidence)


MAXIMUM_RESPONSE_BYTES = 4 * 1024 * 1024


def _admit_cli_invocations(document):
    """Keep the staged product's canonical tool/operation/CLI association intact."""
    projection = document['serverProjection']
    cli, bootstrap = projection['cliInvocations'], projection['hostedBootstrap']
    if (projection['schemaVersion'] != 10 or projection['namespace'] != 'kast'
            or cli['schemaVersion'] != 3 or bootstrap['schemaVersion'] != 1
            or not 1 <= len(cli['operations']) <= 64 or not 1 <= len(bootstrap['tools']) <= 64):
        raise ReadTransportRejected('READ_CLI_SCHEMA_REJECTED')
    tools = {tool['name']: tool for tool in bootstrap['tools']}
    operations = {operation['toolName']: operation for operation in cli['operations']}
    if (len(tools) != len(bootstrap['tools']) or len(operations) != len(cli['operations'])
            or tools.keys() != operations.keys()):
        raise ReadTransportRejected('READ_CLI_SCHEMA_REJECTED')
    commands = {}
    for name, operation in operations.items():
        invocation, tool = operation['invocation'], tools[name]
        command = invocation['command']
        if (operation['operationId'] != tool['operationId'] or invocation['type'] != 'CLI'
                or not isinstance(command, list) or not 1 <= len(command) <= 4
                or any(not isinstance(part, str) or not re.fullmatch(r'[a-z][a-z0-9_-]{0,63}', part)
                       for part in command)):
            raise ReadTransportRejected('READ_CLI_SCHEMA_REJECTED')
        if tool['effect'] in ('none', 'intellij_read') and tool['approvalPolicy'] == 'none':
            commands[name] = tuple(command)
    return commands


class HostedReadTransport:
    def __init__(self, isolation, fixture, product, java, harness):
        self.isolation, self.fixture, self.product = isolation, fixture, product
        self.java, self.harness = java, harness
        self.provider = None
        self.cli_commands = {}

    @contextmanager
    def open(self):
        schema = subprocess.run([str(self.product / 'bin/kast'), '--schema'],
            cwd=self.fixture.workspace, env=self.fixture.environment,
            capture_output=True, timeout=30)
        if schema.returncode != 0 or not 0 < len(schema.stdout) <= MAXIMUM_RESPONSE_BYTES:
            raise ReadTransportRejected('READ_CLI_SCHEMA_REJECTED')
        self.cli_commands = _admit_cli_invocations(json.loads(schema.stdout))
        command = [str(self.java), '-cp', str(self.product / 'lib/*') + os.pathsep + str(self.harness),
            'io.github.amichne.kast.appserver.acceptance.hostedchange.NativeHostedReadMain',
            str(self.product), str(self.fixture.workspace)]
        self.provider = self.isolation.spawn(command, cwd=self.fixture.workspace,
            env=self.fixture.environment, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL)
        try:
            yield self
        finally:
            self.provider.stdin.close()
            try:
                self.provider.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.provider.terminate()
                try:
                    self.provider.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.provider.kill()
                    self.provider.wait(timeout=5)
            self.provider.stdout.close()

    def invoke(self, surface, tool, arguments):
        try:
            return self._invoke(surface, tool, arguments)
        except ReadTransportRejected as error:
            if surface in ('cli', 'provider') and tool in self.cli_commands:
                error.invocation = (surface, tool)
            raise

    def _invoke(self, surface, tool, arguments):
        if surface == 'cli':
            if tool not in self.cli_commands:
                raise ReadTransportRejected('READ_CLI_TOOL_REJECTED')
            result = subprocess.run([str(self.product / 'bin/kast'), *self.cli_commands[tool]],
                cwd=self.fixture.workspace, env=self.fixture.environment,
                input=json.dumps(arguments).encode(), capture_output=True, timeout=60)
            if len(result.stdout) > MAXIMUM_RESPONSE_BYTES or not result.stdout:
                raise ReadTransportRejected('READ_CLI_OUTPUT_REJECTED')
            return json.loads(result.stdout)
        if surface != 'provider' or self.provider is None:
            raise ReadTransportRejected('READ_SURFACE_REJECTED')
        payload = json.dumps({'tool': tool, 'arguments': arguments}).encode() + b'\n'
        self.provider.stdin.write(payload)
        self.provider.stdin.flush()
        return _provider_result(json.loads(self._response()))

    def _response(self):
        deadline, response = time.monotonic() + 90, b''
        with selectors.DefaultSelector() as selector:
            selector.register(self.provider.stdout, selectors.EVENT_READ)
            while time.monotonic() < deadline:
                for key, _ in selector.select(timeout=0.5):
                    part = os.read(key.fileobj.fileno(), 4096)
                    if not part:
                        raise ReadTransportRejected('READ_PROVIDER_DISCONNECTED')
                    response += part
                    if len(response) > MAXIMUM_RESPONSE_BYTES:
                        raise ReadTransportRejected('READ_PROVIDER_OUTPUT_BOUND')
                    if b'\n' in response:
                        line, trailing = response.split(b'\n', 1)
                        if trailing:
                            raise ReadTransportRejected('READ_PROVIDER_EXTRA_OUTPUT')
                        return line
        raise ReadTransportRejected('READ_PROVIDER_TIMEOUT')
