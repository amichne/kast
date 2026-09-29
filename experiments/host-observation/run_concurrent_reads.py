#!/usr/bin/env python3
"""Opt-in native concurrency qualification in an exact already-open IDEA project."""
import argparse
import base64
from dataclasses import asdict, dataclass
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
import zipfile


@dataclass(frozen=True)
class Input:
    project: str
    hostPid: int
    file: str
    offset: int
    jars: list[str]
    capacities: list[int]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--idea-contents', type=Path, required=True)
    parser.add_argument('--artifact', type=Path, required=True)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--file', required=True)
    parser.add_argument('--offset', type=int, required=True)
    parser.add_argument('--capacities', type=int, nargs='+', default=[1, 2], choices=[1, 2, 4, 8])
    args = parser.parse_args()
    launcher = args.idea_contents / 'MacOS/idea'
    rows = subprocess.check_output(['ps', '-ww', '-axo', 'pid=,command='], text=True).splitlines()
    hosts = [int(row.split(maxsplit=1)[0]) for row in rows if row.split(maxsplit=1)[1:] == [str(launcher)]]
    if len(hosts) != 1:
        raise RuntimeError('EXACT_RUNNING_HOST_UNAVAILABLE')
    project = args.project.resolve(strict=True)
    source = (project / args.file).resolve(strict=True)
    source.relative_to(project)
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    evidence = Path(tempfile.mkdtemp(prefix='kast-concurrent-reads-')).resolve()
    print(evidence, flush=True)
    with zipfile.ZipFile(args.artifact) as archive:
        for entry in archive.infolist():
            (evidence / entry.filename).resolve().relative_to(evidence)
        archive.extractall(evidence)
    jars = sorted(str(p) for p in evidence.glob('*/lib/*.jar'))
    if not jars:
        raise RuntimeError('PLUGIN_JARS_UNAVAILABLE')
    data = Input(str(project), hosts[0], args.file, args.offset, jars, args.capacities)
    path = evidence / 'input.json'
    path.write_text(json.dumps(asdict(data)))
    (evidence / 'artifact.sha256').write_text(hashlib.sha256(args.artifact.read_bytes()).hexdigest())
    template = Path(__file__).with_name('concurrent-reads.kts.template').read_text()
    script = evidence / 'qualify.kts'
    script.write_text(template.replace('@INPUT_BASE64@', base64.b64encode(str(path).encode()).decode()))
    with (evidence / 'carrier.log').open('w') as log:
        subprocess.run([str(launcher), 'ideScript', str(script)], stdout=log, stderr=log, timeout=240, check=True)
    deadline = time.monotonic() + 30
    while not any((evidence / name).exists() for name in ('report.json', 'failure.json')) and time.monotonic() < deadline:
        time.sleep(0.2)
    if hashlib.sha256(source.read_bytes()).hexdigest() != before:
        raise RuntimeError(f'SOURCE_RESTORATION_FAILED: {evidence}')
    if not (evidence / 'report.json').exists():
        raise RuntimeError(f'QUALIFICATION_UNCONFIRMED: {evidence}')
    print(f'Qualified: {evidence / "report.json"}')


if __name__ == '__main__':
    main()
