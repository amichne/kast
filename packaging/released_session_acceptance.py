"""Fresh child-shell checks of installed wrappers; never reads a user's shell startup files."""
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from pathlib import Path
import subprocess

from acceptance_idea import digest
from released_acceptance_product import ReleaseFailure, ReleaseRejected, product_executable


class SessionCommand(str, Enum):
    REGISTER = 'register-owned-workspace'
    RESOLVE = 'resolve-command'
    VERSION = 'version'
    CONFIGURATION = 'saved-configuration'
    EXPLANATION = 'configuration-explanation'
    INSTALLATION = 'installation-inspect'


@dataclass(frozen=True)
class SessionInvocation:
    command: SessionCommand
    arguments: tuple[str, ...]
    productSha256: str
    exitStatus: str
    stdoutSha256: str
    stderrSha256: str
    evidence: str


class SessionRejected(ReleaseRejected):
    def __init__(self, invocation: SessionInvocation):
        self.invocation = invocation
        super().__init__(ReleaseFailure.SESSION)


@dataclass(frozen=True)
class ShellSessionReceipt:
    product: str
    payloadIdentity: str
    resolvedExecutable: str
    configurationSha256: str
    runtimeDirectory: str
    invocations: tuple[SessionInvocation, ...]


def _invoke(isolation, installed, directory, index, command, arguments, environment):
    shell = str(isolation.tools['bash'])
    script = 'command -v kast' if command is SessionCommand.RESOLVE else 'exec kast "$@"'
    invocation = (shell, '--noprofile', '--norc', '-c', script, 'kast-release-session', *arguments)
    output = directory / f'{index}-{command.value}.private.log'
    try:
        result = subprocess.run(invocation, cwd=isolation.root / 'workspace', env=environment,
                                capture_output=True, timeout=30)
    except subprocess.TimeoutExpired as error:
        stdout, stderr, status = error.stdout or b'', error.stderr or b'', 'deadline-exceeded'
    else:
        stdout, stderr, status = result.stdout, result.stderr, str(result.returncode)
    record = SessionInvocation(command, invocation, installed.controlSha256, status,
        hashlib.sha256(stdout).hexdigest(), hashlib.sha256(stderr).hexdigest(), str(output))
    with output.open('xb') as stream:
        output.chmod(0o600)
        stream.write(stdout[:262144] + b'\n--- stderr ---\n' + stderr[:262144])
    if status != '0' or len(stdout) > 262144 or len(stderr) > 262144:
        raise SessionRejected(record)
    try:
        return stdout.decode(), record
    except UnicodeError:
        raise SessionRejected(record) from None


def _shell_environment(isolation):
    environment = dict(isolation.environment)
    environment['PATH'] = str(isolation.root / 'bin') + ':' + environment['PATH']
    # The wrapper must select saved state without a caller-provided runtime/config selector.
    environment.pop('KAST_RUNTIME_DIRECTORY', None)
    environment.pop('KAST_CONFIGURATION_FILE', None)
    return environment


def register_owned_workspace(isolation, installed):
    """Enrollment is handled before service admission and writes only the owned installation registry."""
    product_executable(Path(installed.product), isolation.root)
    directory = isolation.root / 'release-registration'
    directory.mkdir(mode=0o700)
    raw, invocation = _invoke(isolation, installed, directory, 0, SessionCommand.REGISTER,
                              ('app-server', 'register'), _shell_environment(isolation))
    workspace = str(isolation.root / 'workspace')
    try:
        document = json.loads(raw)
        valid = (set(document) == {'operation', 'workspaceId', 'root', 'revision'}
                 and document['operation'] == 'app-server.register' and document['root'] == workspace
                 and document['workspaceId'] == hashlib.sha256(workspace.encode()).hexdigest()
                 and type(document['revision']) is int and document['revision'] > 0)
    except (ValueError, KeyError, TypeError):
        valid = False
    if not valid:
        raise SessionRejected(invocation)
    return invocation


def inspect_shell_sessions(isolation, installed, *, previous=False):
    """Two independently launched shells must select the same immutable installed version/configuration."""
    product = Path(installed.product)
    executable = product_executable(product, isolation.root)
    directory = isolation.root / ('previous-release-sessions' if previous else 'release-sessions')
    directory.mkdir(mode=0o700)
    environment = _shell_environment(isolation)
    if not previous:
        # Preserve the owned JVM home/temp options through the launcher without JVM-injection notices.
        options = environment.pop('_JAVA_OPTIONS', None)
        if not isinstance(options, str) or environment.pop('JAVA_TOOL_OPTIONS', None) != options:
            raise ReleaseRejected(ReleaseFailure.SESSION)
        environment['JAVA_OPTS'] = options
    configuration = product / 'config/environment'
    configuration_digest = digest(configuration)
    receipts = []
    for index in range(2):
        observations = []
        resolved, call = _invoke(isolation, installed, directory, index, SessionCommand.RESOLVE, (), environment)
        observations.append(call)
        if resolved.strip() != str(isolation.root / 'bin/kast') or Path(resolved.strip()).resolve() != executable:
            raise SessionRejected(call)
        version, call = _invoke(isolation, installed, directory, index, SessionCommand.VERSION, ('--version',), environment)
        observations.append(call)
        if version.strip() != f'kast {installed.version} (IntelliJ plugin)':
            raise SessionRejected(call)
        raw, call = _invoke(isolation, installed, directory, index, SessionCommand.CONFIGURATION,
                            ('config', 'show', '--json'), environment)
        observations.append(call)
        try:
            document = json.loads(raw)
            assignments = document['resolvedNextLaunch']
            runtime = [entry for entry in assignments if entry['key'] == 'KAST_RUNTIME_DIRECTORY']
            valid = (document['operation'] == 'config-show' and document['status'] == 'complete'
                     and document['desiredSavedConfiguration'] == 'LOADED' and len(runtime) == 1
                     and runtime[0]['source'] == 'SAVED_INSTALLATION'
                     and runtime[0]['value'] == str(product / 'state/run'))
        except (ValueError, KeyError, TypeError):
            valid = False
        if not valid:
            raise SessionRejected(call)
        if not previous:
            # Previous releases retain their original diagnostics as evidence; the current target must be quiet.
            if call.stderrSha256 != hashlib.sha256(b'').hexdigest():
                raise SessionRejected(call)
            raw, call = _invoke(isolation, installed, directory, index, SessionCommand.EXPLANATION,
                                ('config', 'explain', 'KAST_APP_SERVER_TOOLS'), environment)
            observations.append(call)
            try:
                explanation = json.loads(raw)
                explained = (explanation['operation'] == 'config-explain' and explanation['status'] == 'complete'
                             and explanation['key'] == 'KAST_APP_SERVER_TOOLS')
            except (ValueError, KeyError, TypeError):
                explained = False
            if not explained or call.stderrSha256 != hashlib.sha256(b'').hexdigest():
                raise SessionRejected(call)
        raw, call = _invoke(isolation, installed, directory, index, SessionCommand.INSTALLATION,
                            ('installation', 'inspect', '--json'), environment)
        observations.append(call)
        try:
            document = json.loads(raw)
            valid = (document['operation'] == 'installation.inspect' and document['status'] == 'inspected'
                     and document['installation'] == str(product) and document['state'] == str(product / 'state'))
        except (ValueError, KeyError, TypeError):
            valid = False
        if not valid or digest(configuration) != configuration_digest:
            raise SessionRejected(call)
        receipts.append(ShellSessionReceipt(str(product), installed.payloadIdentity, str(executable),
                        configuration_digest, str(product / 'state/run'), tuple(observations)))
    return tuple(receipts)
