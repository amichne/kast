#!/usr/bin/env python3
"""Select the trusted local Mac when GitHub reports it idle; otherwise the hosted image."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import cast

LOCAL_LABELS = ("self-hosted", "macOS", "ARM64", "kast-main")
HOSTED_LABEL = "macos-15"
TOKEN_VARIABLE = "KAST_RUNNER_STATUS_TOKEN"


class Reason(str, Enum):
    HOSTED_DEFAULT = "HOSTED_DEFAULT"
    IDLE_LOCAL_RUNNER = "IDLE_LOCAL_RUNNER"
    NO_IDLE_LOCAL_RUNNER = "NO_IDLE_LOCAL_RUNNER"
    NO_STATUS_TOKEN = "NO_STATUS_TOKEN"
    STATUS_UNAVAILABLE = "STATUS_UNAVAILABLE"
    INVALID_STATUS = "INVALID_STATUS"


@dataclass(frozen=True)
class Selection:
    reason: Reason

    @property
    def labels(self) -> str:
        """JSON for `runs-on: ${{ fromJSON(...) }}`."""
        if self.reason is Reason.IDLE_LOCAL_RUNNER:
            return json.dumps(list(LOCAL_LABELS))
        return json.dumps(HOSTED_LABEL)


def idle(runner: object) -> bool | None:
    """Return None when the runner record lacks the fields this decision needs."""
    if not isinstance(runner, dict):
        return None
    record = cast(dict[str, object], runner)
    status, busy, labels = record.get("status"), record.get("busy"), record.get("labels")
    if not isinstance(status, str) or not isinstance(busy, bool) or not isinstance(labels, list):
        return None
    names: set[str] = set()
    for label in cast(list[object], labels):
        name = cast(dict[str, object], label).get("name") if isinstance(label, dict) else None
        if not isinstance(name, str):
            return None
        names.add(name)
    return status == "online" and not busy and set(LOCAL_LABELS) <= names


def select(document: object) -> Selection:
    """Pure decision over the `GET /repos/{repo}/actions/runners` response."""
    if not isinstance(document, dict):
        return Selection(Reason.INVALID_STATUS)
    runners = cast(dict[str, object], document).get("runners")
    if not isinstance(runners, list):
        return Selection(Reason.INVALID_STATUS)
    observed = [idle(runner) for runner in cast(list[object], runners)]
    if None in observed:
        return Selection(Reason.INVALID_STATUS)
    return Selection(Reason.IDLE_LOCAL_RUNNER if any(observed) else Reason.NO_IDLE_LOCAL_RUNNER)


def observe(repository: str, token: str) -> Selection:
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/actions/runners?per_page=100",
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            document: object = json.load(response)
    except (urllib.error.URLError, TimeoutError):
        return Selection(Reason.STATUS_UNAVAILABLE)
    except json.JSONDecodeError:
        return Selection(Reason.INVALID_STATUS)
    return select(document)


def decide(environment: Mapping[str, str]) -> Selection:
    token = environment.get(TOKEN_VARIABLE, "")
    if not token:
        return Selection(Reason.NO_STATUS_TOKEN)
    if environment.get("KAST_PREFER_LOCAL_MAC") != "true":
        return Selection(Reason.HOSTED_DEFAULT)
    return observe(environment["GITHUB_REPOSITORY"], token)


def main() -> int:
    selection = decide(os.environ)
    line = f"mac-runner: {selection.reason.value} -> {selection.labels}"
    print(line, flush=True)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        _ = output.write(f"labels={selection.labels}\n")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            _ = handle.write(f"{line}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
