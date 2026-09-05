#!/usr/bin/env python3
"""Admit a retained candidate from successful main CI before toolchain setup."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from enum import Enum
import json
import os
from pathlib import Path
import re
import subprocess


class Cause(str, Enum):
    INVALID_INPUT = 'invalid-input'
    SOURCE_MOVED = 'source-moved'
    VERSION_MISMATCH = 'version-mismatch'
    CI_UNPROVEN = 'ci-unproven'
    ARTIFACT_UNAVAILABLE = 'artifact-unavailable'
    RELEASE_EXISTS = 'release-exists'
    OBSERVATION_FAILED = 'observation-failed'


class Rejected(Exception):
    def __init__(self, cause: Cause):
        self.cause = cause
        super().__init__(cause.value)


@dataclass(frozen=True)
class Candidate:
    run_id: int
    artifact_name: str


def trusted_runs(runs: list[dict], repository: str, sha: str) -> list[dict]:
    return [run for run in runs if run.get('head_sha') == sha
            and run.get('head_branch') == 'main' and run.get('event') == 'push'
            and run.get('path') == '.github/workflows/ci.yml'
            and run.get('head_repository', {}).get('full_name') == repository
            and type(run.get('id')) is int and run['id'] > 0]


def latest_run(runs: list[dict], repository: str, sha: str) -> dict:
    trusted = trusted_runs(runs, repository, sha)
    if not trusted:
        raise Rejected(Cause.CI_UNPROVEN)
    latest = max(trusted, key=lambda run: run['id'])
    if latest.get('status') != 'completed' or latest.get('conclusion') != 'success':
        raise Rejected(Cause.CI_UNPROVEN)
    return latest


def admit(runs: list[dict], artifacts: list[dict], repository: str, sha: str) -> Candidate:
    latest = latest_run(runs, repository, sha)
    name = f'release-candidate-{sha}'
    matching = [artifact for artifact in artifacts if artifact.get('name') == name]
    if len(matching) != 1:
        raise Rejected(Cause.ARTIFACT_UNAVAILABLE)
    artifact = matching[0]
    if artifact.get('expired') is not False or artifact.get('workflow_run', {}).get('id') != latest['id'] or artifact.get('workflow_run', {}).get('head_sha') != sha:
        raise Rejected(Cause.ARTIFACT_UNAVAILABLE)
    return Candidate(latest['id'], name)


def api(path: str) -> list:
    result = subprocess.run(['gh', 'api', '--paginate', '--slurp', path], text=True,
                            capture_output=True, timeout=60, check=False)
    if result.returncode:
        raise Rejected(Cause.OBSERVATION_FAILED)
    return json.loads(result.stdout)


def observe(repository: str, sha: str, version: str, root: Path) -> Candidate:
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) or not re.fullmatch(r'[0-9a-f]{40}', sha) or not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise Rejected(Cause.INVALID_INPUT)
    if (root / 'distribution/release/candidate-version.txt').read_text().strip() != version:
        raise Rejected(Cause.VERSION_MISMATCH)
    base = f'repos/{repository}'
    if api(f'{base}/git/ref/heads/main')[0]['object']['sha'] != sha:
        raise Rejected(Cause.SOURCE_MOVED)
    releases = [release for page in api(f'{base}/releases?per_page=100') for release in page]
    tags = [tag for page in api(f'{base}/tags?per_page=100') for tag in page]
    if any(release.get('tag_name') == f'v{version}' for release in releases) or any(tag.get('name') == f'v{version}' for tag in tags):
        raise Rejected(Cause.RELEASE_EXISTS)
    runs = [run for page in api(f'{base}/actions/workflows/ci.yml/runs?branch=main&event=push&head_sha={sha}&per_page=100') for run in page['workflow_runs']]
    run = latest_run(runs, repository, sha)
    artifacts = [artifact for page in api(f'{base}/actions/runs/{run["id"]}/artifacts?per_page=100') for artifact in page['artifacts']]
    return admit(runs, artifacts, repository, sha)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--source-revision', required=True)
    parser.add_argument('--version', required=True)
    args = parser.parse_args()
    try:
        candidate = observe(args.repository, args.source_revision, args.version, Path(__file__).resolve().parents[2])
        if 'GITHUB_OUTPUT' in os.environ:
            with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
                output.write(f'run_id={candidate.run_id}\nartifact_name={candidate.artifact_name}\n')
        print(json.dumps({'stage': 'release-eligibility', 'outcome': 'admitted', 'runId': candidate.run_id, 'sourceRevision': args.source_revision}))
    except (Rejected, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as failure:
        cause = failure.cause if isinstance(failure, Rejected) else Cause.OBSERVATION_FAILED
        print(json.dumps({'stage': 'release-eligibility', 'outcome': 'rejected', 'cause': cause.value}))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()
