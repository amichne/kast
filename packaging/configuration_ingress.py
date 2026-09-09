"""Bounded source ratchet for Kast-owned environment/property ingress, including launch scripts.

This verifies concrete input references and named ambient-read owners. It is not a
replacement for compiler effect checks or the canonical runtime value parsers.
"""
from enum import Enum
import argparse
import json
from pathlib import Path
import re

class Failure(Enum):
    UNDECLARED_KEY = 'UNDECLARED_KEY'
    UNOWNED_INGRESS = 'UNOWNED_INGRESS'
    DUPLICATE_DECLARATION = 'DUPLICATE_DECLARATION'
    GENERATED_SNAPSHOT_MISMATCH = 'GENERATED_SNAPSHOT_MISMATCH'
    GENERATED_SNAPSHOT_UNAVAILABLE = 'GENERATED_SNAPSHOT_UNAVAILABLE'

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
    findings = []
    for name, path in source_files(args.root):
        findings.extend({'file': name, 'condition': failure.value, 'identity': identity}
                        for failure, identity in violations(name, path.read_text(), set(keys), owners))
    print(json.dumps({'status': 'rejected' if findings else 'complete', 'findings': findings}, sort_keys=True))
    return bool(findings)

if __name__ == '__main__':
    raise SystemExit(main())
