"""Read-only saved-selector and inherited-tool checks in the owned native fixture."""
from dataclasses import asdict, dataclass
from enum import Enum
import hashlib
import json
import subprocess

from released_acceptance_product import product_executable


class ConfigurationContinuityOutcome(str, Enum):
    PASSED = 'passed'
    REJECTED = 'rejected'


@dataclass(frozen=True)
class ConfigurationContinuityReceipt:
    outcome: ConfigurationContinuityOutcome
    inheritedAliasesAdmitted: bool
    explicitSelectorRetained: bool
    processProvenanceRetained: bool
    savedFileUnchanged: bool
    cleanStderr: bool


def inspect_configuration_continuity(isolation, fixture, product):
    directory = isolation.root / 'configuration-continuity'
    directory.mkdir(mode=0o700)
    saved = directory / 'alternate.environment'
    # The canonical environment format permits a comment-only saved source; the owner supplies every default.
    saved.write_text('# Private native fixture saved configuration.\n')
    saved.chmod(0o600)
    before = hashlib.sha256(saved.read_bytes()).digest()
    environment = dict(fixture.environment)
    environment['KAST_CONFIGURATION_FILE'] = str(saved)
    environment['KAST_APP_SERVER_TOOLS'] = 'semantic_query,impact_analyze'
    # Preserve private JVM paths through the wrapper without VM injection notices on stderr.
    options = environment.pop('_JAVA_OPTIONS', None)
    tool_options = environment.pop('JAVA_TOOL_OPTIONS', None)
    if options is not None and options == tool_options:
        environment['JAVA_OPTS'] = options
    executable = str(product_executable(product, isolation.root))
    validated = subprocess.run([executable, 'config', 'validate', '--file', str(saved), '--json'],
        cwd=fixture.workspace, env=environment, capture_output=True, timeout=30)
    shown = subprocess.run([executable, 'config', 'show', '--json'],
        cwd=fixture.workspace, env=environment, capture_output=True, timeout=30)
    admitted = selector = provenance = False
    if max(len(validated.stdout), len(shown.stdout), len(validated.stderr), len(shown.stderr)) <= 262144:
        try:
            validation, document = json.loads(validated.stdout), json.loads(shown.stdout)
            admitted = (validated.returncode == 0 and validation.get('operation') == 'config-validate'
                        and validation.get('status') == 'complete')
            values = {value['key']: value for value in document.get('resolvedNextLaunch', [])}
            selector = (shown.returncode == 0 and document.get('desiredSavedConfiguration') == 'LOADED'
                        and values['KAST_CONFIGURATION_FILE']['value'] == str(saved))
            provenance = (values['KAST_APP_SERVER_TOOLS']['source'] == 'PROCESS_ENVIRONMENT'
                          and values['KAST_APP_SERVER_TOOLS']['value'] == 'semantic_query,impact_analyze')
        except (ValueError, TypeError, KeyError):
            pass
    unchanged = hashlib.sha256(saved.read_bytes()).digest() == before
    quiet = not validated.stderr and not shown.stderr
    passed = all((admitted, selector, provenance, unchanged, quiet))
    return asdict(ConfigurationContinuityReceipt(
        ConfigurationContinuityOutcome.PASSED if passed else ConfigurationContinuityOutcome.REJECTED,
        admitted, selector, provenance, unchanged, quiet))
