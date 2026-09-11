"""Owned, bounded result transport for the native read regression; payloads never become log files."""
from contextlib import contextmanager
import json
import os
import re
import selectors
import subprocess
import time


class ReadTransportRejected(ValueError):
    pass


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
        response = json.loads(self._response())
        if response.get('kind') == 'completed':
            return response['envelope']['document']
        if response.get('kind') == 'rejected' and response.get('failure') == 'INVALID_ARGUMENTS':
            return {'failure': 'INVALID_ARGUMENTS'}
        raise ReadTransportRejected('READ_PROVIDER_REJECTED')

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
