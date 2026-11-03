#!/usr/bin/env python3
"""Admit main CI and release jobs before a local runner starts repository code."""

from __future__ import annotations

import os
from collections.abc import Mapping
from enum import Enum


class Admission(str, Enum):
    ALLOWED = "ALLOWED"
    WRONG_REPOSITORY = "WRONG_REPOSITORY"
    WRONG_REF = "WRONG_REF"
    UNSUPPORTED_EVENT = "UNSUPPORTED_EVENT"
    WRONG_WORKFLOW = "WRONG_WORKFLOW"
    WRONG_JOB = "WRONG_JOB"


def admit(environment: Mapping[str, str]) -> Admission:
    if environment.get("GITHUB_REPOSITORY") != "amichne/kast":
        return Admission.WRONG_REPOSITORY
    if environment.get("GITHUB_REF") != "refs/heads/main":
        return Admission.WRONG_REF
    if environment.get("GITHUB_EVENT_NAME") not in {"push", "workflow_dispatch"}:
        return Admission.UNSUPPORTED_EVENT
    workflow = environment.get("GITHUB_WORKFLOW_REF")
    if workflow == "amichne/kast/.github/workflows/ci.yml@refs/heads/main":
        jobs = {"native", "kotlin"}
    elif workflow in {
        "amichne/kast/.github/workflows/release.yml@refs/heads/main",
        "amichne/kast/.github/workflows/release-component.yml@refs/heads/main",
    }:
        if environment.get("GITHUB_EVENT_NAME") != "workflow_dispatch":
            return Admission.UNSUPPORTED_EVENT
        jobs = {"release"}
    else:
        return Admission.WRONG_WORKFLOW
    if environment.get("GITHUB_JOB") not in jobs:
        return Admission.WRONG_JOB
    return Admission.ALLOWED


def main() -> int:
    decision = admit(os.environ)
    print(f"self-hosted-guard: {decision.value}", flush=True)
    return 0 if decision is Admission.ALLOWED else 1


if __name__ == "__main__":
    raise SystemExit(main())
