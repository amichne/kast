#!/usr/bin/env python3
"""Retired file-backed helper for the removed `kast agent call` surface."""

from __future__ import annotations

import json
import sys


def main() -> int:
    result = {
        "type": "KAST_AGENT_CALL_HELPER_REMOVED",
        "ok": False,
        "issue": {
            "code": "KAST_AGENT_CALL_HELPER_REMOVED",
            "message": "`kast agent call` is not part of the v1 public agent surface.",
        },
        "replacements": [
            "kast agent symbol --query <name> --workspace-root <repo>",
            "kast agent diagnostics --file-path <path> --workspace-root <repo>",
            "kast agent impact --symbol <fq-name> --workspace-root <repo>",
            "kast agent rename --symbol <fq-name> --new-name <name> --workspace-root <repo>",
            "kast help agent",
        ],
        "schemaVersion": 1,
    }
    json.dump(result, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
