#!/usr/bin/env python3
"""Collect normalized GitHub evidence for PR 633 gates without third-party packages."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


class EvidenceFailure(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceFailure(message)


def text(value: Any, label: str) -> str:
    require(isinstance(value, str) and bool(value), f"{label} is missing")
    return value


class GitHub:
    def __init__(self, repository: str, token: str, api_url: str) -> None:
        owner, separator, name = repository.partition("/")
        require(bool(separator and owner and name), "repository must be owner/name")
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")

    def get(self, path: str, query: dict[str, str] | None = None) -> Any:
        suffix = "?" + urllib.parse.urlencode(query) if query else ""
        request = urllib.request.Request(
            self.api_url + path + suffix,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "kast-pr633-evidence",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            raise EvidenceFailure(f"GitHub request failed {error.code}: {path}: {body}") from error

    def pull_request(self, number: int) -> dict[str, Any]:
        value = self.get(f"/repos/{self.repository}/pulls/{number}")
        require(isinstance(value, dict), "pull request response is not an object")
        return value

    def check_runs(self, head_sha: str) -> list[dict[str, Any]]:
        value = self.get(
            f"/repos/{self.repository}/commits/{head_sha}/check-runs",
            {"per_page": "100", "filter": "latest"},
        )
        runs = value.get("check_runs") if isinstance(value, dict) else None
        require(isinstance(runs, list), "check-runs response has no check_runs list")
        return [run for run in runs if isinstance(run, dict)]

    def pull_request_files(self, number: int) -> list[str]:
        paths: list[str] = []
        page = 1
        while True:
            value = self.get(
                f"/repos/{self.repository}/pulls/{number}/files",
                {"per_page": "100", "page": str(page)},
            )
            require(isinstance(value, list), "pull request files response is not a list")
            paths.extend(
                text(item.get("filename"), "changed filename")
                for item in value
                if isinstance(item, dict)
            )
            if len(value) < 100:
                return paths
            page += 1


def pull_refs(client: GitHub, number: int, expected_base: str, expected_head: str | None) -> tuple[dict[str, Any], str]:
    pull_request = client.pull_request(number)
    base = pull_request.get("base")
    head = pull_request.get("head")
    require(isinstance(base, dict) and isinstance(head, dict), "pull request refs are missing")
    require(text(base.get("ref"), "base ref") == expected_base, f"PR does not target {expected_base}")
    head_sha = text(head.get("sha"), "head sha")
    if expected_head:
        require(head_sha == expected_head, f"PR head is {head_sha}, not {expected_head}")
    return pull_request, head_sha


def exact_head_ci(client: GitHub, args: argparse.Namespace) -> dict[str, Any]:
    _, head_sha = pull_refs(client, args.pull_request, args.expected_base, args.expected_head)
    matches = [run for run in client.check_runs(head_sha) if run.get("name") == args.check_name]
    require(len(matches) == 1, f"expected one check named {args.check_name}, found {len(matches)}")
    run = matches[0]
    require(run.get("status") == "completed", "check is not completed")
    require(run.get("conclusion") == "success", "check is not successful")
    require(run.get("head_sha", head_sha) == head_sha, "check belongs to another head")
    return evidence(
        "exact-head-ci",
        head_sha,
        client.repository,
        args.pull_request,
        args.expected_base,
        {
            "checkName": args.check_name,
            "checkRunId": str(run.get("id")),
            "checkConclusion": "success",
        },
    )


def merged_pull_request(client: GitHub, args: argparse.Namespace) -> dict[str, Any]:
    pull_request, head_sha = pull_refs(
        client,
        args.pull_request,
        args.expected_base,
        args.expected_head,
    )
    require(pull_request.get("merged") is True, "pull request is not merged")
    changed_paths = client.pull_request_files(args.pull_request)
    policy_bytes = args.path_policy.read_bytes()
    policy = json.loads(policy_bytes.decode("utf-8"))
    forbidden = [re.compile(value) for value in policy.get("forbiddenRegex", [])]
    violations = sorted(path for path in changed_paths if any(rule.search(path) for rule in forbidden))
    require(not violations, f"pull request contains forbidden paths: {violations}")
    fingerprint = hashlib.sha256(("\n".join(sorted(changed_paths)) + "\n").encode()).hexdigest()
    return evidence(
        "merged-pull-request",
        head_sha,
        client.repository,
        args.pull_request,
        args.expected_base,
        {
            "mergedAt": text(pull_request.get("merged_at"), "merged_at"),
            "mergeCommitSha": text(pull_request.get("merge_commit_sha"), "merge commit"),
            "changedPathCount": str(len(changed_paths)),
            "changedPathsSha256": fingerprint,
            "pathPolicySha256": "sha256:" + hashlib.sha256(policy_bytes).hexdigest(),
        },
    )


def evidence(kind: str, head: str, repository: str, number: int, base: str, facts: dict[str, str]) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "kind": kind,
        "status": "passed",
        "headSha": head,
        "facts": {
            "repository": repository,
            "pullRequest": str(number),
            "baseRef": base,
            **facts,
        },
    }


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    root.add_argument("--repository", required=True)
    root.add_argument("--api-url", default=os.environ.get("GITHUB_API_URL", "https://api.github.com"))
    root.add_argument("--output", type=Path, required=True)
    commands = root.add_subparsers(dest="command", required=True)
    exact = commands.add_parser("exact-head-ci")
    exact.add_argument("--pull-request", type=int, required=True)
    exact.add_argument("--expected-base", default="main")
    exact.add_argument("--expected-head")
    exact.add_argument("--check-name", required=True)
    merged = commands.add_parser("merged-pull-request")
    merged.add_argument("--pull-request", type=int, required=True)
    merged.add_argument("--expected-base", default="main")
    merged.add_argument("--expected-head")
    merged.add_argument("--path-policy", type=Path, required=True)
    return root


def main() -> int:
    args = parser().parse_args()
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        print("missing GITHUB_TOKEN", file=sys.stderr)
        return 1
    try:
        client = GitHub(args.repository, token, args.api_url)
        result = exact_head_ci(client, args) if args.command == "exact-head-ci" else merged_pull_request(client, args)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(result, sort_keys=True))
        return 0
    except (EvidenceFailure, OSError, json.JSONDecodeError) as error:
        print(f"GitHub delivery evidence failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
