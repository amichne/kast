"""Bounded source ratchet for Kast-owned environment/property ingress, including launch scripts.

This verifies concrete input references and named ambient-read owners. It is not a
replacement for compiler effect checks or the canonical runtime value parsers.
"""
from enum import Enum
import argparse
import ast
import json
from pathlib import Path
import re

class Failure(Enum):
    UNDECLARED_KEY = 'UNDECLARED_KEY'
    UNOWNED_INGRESS = 'UNOWNED_INGRESS'
    DUPLICATE_DECLARATION = 'DUPLICATE_DECLARATION'
    GENERATED_SNAPSHOT_MISMATCH = 'GENERATED_SNAPSHOT_MISMATCH'
    GENERATED_SNAPSHOT_UNAVAILABLE = 'GENERATED_SNAPSHOT_UNAVAILABLE'
    OPERATIONAL_PROJECTION_MISMATCH = 'OPERATIONAL_PROJECTION_MISMATCH'

# Explicit cross-language projection identities; values belong to the generated owner catalogue.
_INSTALLATION_PROJECTIONS = {
    'install.sh': {
        'INSTALL_DOWNLOAD_RETRIES': 'installation.download.retries',
        'INSTALL_DOWNLOAD_RETRY_DELAY_MILLIS': 'installation.download.retry_delay',
    },
    'packaging/installation-lifecycle.py': {
        'RETIREMENT_CHILD_TIMEOUT_MILLIS': 'installation.retirement.child_timeout',
        'STATE_MAXIMUM_ENTRIES': 'installation.state.maximum_entries',
    },
}


def operational_projection_findings(root, schema):
    entries = schema.get('operationalLimits', [])
    # Small ingress-only unit fixtures contain neither production script nor installation declarations.
    if not any((root / name).exists() for name in _INSTALLATION_PROJECTIONS) and not any(
            item.get('key', '').startswith('installation.') for item in entries):
        return []
    findings = []
    for name, symbols in _INSTALLATION_PROJECTIONS.items():
        observed = {symbol: [] for symbol in symbols}
        try:
            source = (root / name).read_text()
            if name.endswith('.py'):
                for node in ast.walk(ast.parse(source)):
                    if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store) and node.id in symbols:
                        observed[node.id].append(None)
                for node in ast.parse(source).body:
                    if (isinstance(node, ast.Assign) and len(node.targets) == 1
                            and isinstance(node.targets[0], ast.Name) and node.targets[0].id in symbols
                            and isinstance(node.value, ast.Constant) and type(node.value.value) is int):
                        symbol = node.targets[0].id
                        if observed[symbol] == [None]:
                            observed[symbol] = [node.value.value]
            else:
                for line in source.splitlines():
                    for symbol in symbols:
                        if re.match(r'\s*(?:readonly\s+)?' + re.escape(symbol) + r'=', line):
                            literal = re.fullmatch(r'readonly ' + re.escape(symbol) + r'=([0-9]+)', line)
                            observed[symbol].append(int(literal[1]) if literal else None)
        except (OSError, SyntaxError, UnicodeError):
            pass
        for symbol, key in symbols.items():
            declared = [item.get('value') for item in entries if item.get('key') == key]
            if (len(declared) != 1 or type(declared[0]) is not int or observed[symbol] != declared):
                findings.append({'file': name, 'condition': Failure.OPERATIONAL_PROJECTION_MISMATCH.value,
                                 'identity': key})
    return findings

_OWNED = r'(?:KAST_[A-Z0-9_]+|BROKER_SERVICE_IDENTITY|BROKER_READINESS_FILE|kast\.[A-Za-z0-9_.]+)'
_LITERAL_READ = re.compile(r'''(?:System\s*\.\s*getenv|System\s*\.\s*getProperty|environmentVariable|systemProperty|environ\.get)\s*\(\s*["'](''' + _OWNED + r''')["'](?=\s*(?:\)|,))''')
_MAP_READ = re.compile(r'''(?:environment|ambient|environ)\s*\[\s*["'](''' + _OWNED + r''')["']\s*\]''')
_SHELL_READ = re.compile(r'\$\{?(' + _OWNED + r')\b')
_EMPTY_ENV = re.compile(r'(?:System\s*\.\s*getenv|System\s*::\s*getenv)\s*\(\s*\)')
_SYMBOLIC_PROPERTY = re.compile(r'System\s*\.\s*getProperty\s*\(\s*(?!["\'])[^)]*\)')
_SYMBOLIC_ENV = re.compile(r'System\s*\.\s*getenv\s*\(\s*(?!["\'])[^)]*\)')


def violations(path, source, declared, owners):
    keys = set(_LITERAL_READ.findall(source)) | set(_MAP_READ.findall(source))
    constants = dict(re.findall(r'\b(?:const\s+)?val\s+([A-Z_][A-Z0-9_]*)\s*=\s*"(' + _OWNED + r')"', source))
    for symbol in re.findall(r'System\s*\.\s*(?:getProperty|getenv)\s*\(\s*([A-Z_][A-Z0-9_]*)\s*\)', source):
        if symbol in constants:
            keys.add(constants[symbol])
    if path.endswith('.sh') or '/scripts/' in path:
        keys.update(_SHELL_READ.findall(source))
    findings = [(Failure.UNDECLARED_KEY, key) for key in sorted(keys - declared)]
    if (keys or _EMPTY_ENV.search(source) or _SYMBOLIC_ENV.search(source) or _SYMBOLIC_PROPERTY.search(source)) and path not in owners:
        findings.append((Failure.UNOWNED_INGRESS, path))
    return findings


def source_files(root):
    for path in sorted(root.rglob('*')):
        relative = path.relative_to(root)
        if not path.is_file() or any(part in {'build', '.git', '.gradle', '.idea', '__pycache__'} for part in relative.parts):
            continue
        name = relative.as_posix()
        if '/src/test/' in name or name == 'packaging/test-configuration-ingress.py':
            continue
        if (path.suffix in {'.kt', '.kts'} and ('/src/main/' in name or '/src/gradleTooling/' in name or len(relative.parts) == 1)) or name == 'install.sh' or '/src/main/scripts/' in name or (name.startswith('packaging/') and path.suffix in {'.sh', '.py'}):
            yield name, path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--schema', required=True, type=Path)
    parser.add_argument('--policy', required=True, type=Path)
    parser.add_argument("--snapshot", required=True, type=Path)
    args = parser.parse_args()
    generated = args.schema.read_bytes()
    snapshot_failure = None
    try:
        if args.snapshot.is_symlink():
            snapshot_failure = Failure.GENERATED_SNAPSHOT_UNAVAILABLE
        else:
            with args.snapshot.open("rb") as source:
                snapshot = source.read(len(generated) + 1)
            if snapshot != generated:
                snapshot_failure = Failure.GENERATED_SNAPSHOT_MISMATCH
    except OSError:
        snapshot_failure = Failure.GENERATED_SNAPSHOT_UNAVAILABLE
    if snapshot_failure is not None:
        print(json.dumps({"status": "rejected", "findings": [{"file": str(args.snapshot),
            "condition": snapshot_failure.value, "identity": "configuration-schema"}]}, sort_keys=True))
        return True
    schema = json.loads(generated)
    keys = [entry['key'] for entry in schema['parameters']]
    if len(keys) != len(set(keys)):
        raise SystemExit(Failure.DUPLICATE_DECLARATION.value)
    policy = json.loads(args.policy.read_text())
    owners = set(policy['ingressOwners'])
    findings = operational_projection_findings(args.root, schema)
    for name, path in source_files(args.root):
        findings.extend({'file': name, 'condition': failure.value, 'identity': identity}
                        for failure, identity in violations(name, path.read_text(), set(keys), owners))
    print(json.dumps({'status': 'rejected' if findings else 'complete', 'findings': findings}, sort_keys=True))
    return bool(findings)

if __name__ == '__main__':
    raise SystemExit(main())
