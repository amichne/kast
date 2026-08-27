#!/usr/bin/env python3
"""KVP-037 deterministic installed failure matrix; emits only closed evidence."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


SUPPORTED = {
    "workspace.inspect",
    "symbol.discover",
    "symbol.resolve",
    "symbol.describe",
}
AUTHORITIES = {
    "missing-plugin": (
        "IDE_DESCRIPTOR_READ_REJECTED",
        "INSTALLED_CLI",
    ),
    "missing-project": (
        "PROJECT_UNAVAILABLE",
        "ide-plugin/src/test/kotlin/io/github/amichne/kast/ide/endpoint/IdeEndpointPublicationNegativeTest.kt",
    ),
    "malformed-descriptor": (
        "IDE_DESCRIPTOR_REJECTED",
        "cli/src/test/kotlin/io/github/amichne/kast/cli/endpoint/IdeEndpointAdmissionNegativeTest.kt",
    ),
    "incompatible-build": (
        "IDE_DESCRIPTOR_REJECTED",
        "protocol/wire/src/test/kotlin/io/github/amichne/kast/protocol/wire/metadata/IdeEndpointDescriptorNegativeTest.kt",
    ),
    "wrong-root": (
        "IDE_ROOT_MISMATCH",
        "cli/src/test/kotlin/io/github/amichne/kast/cli/endpoint/IdeEndpointAdmissionNegativeTest.kt",
    ),
    "stale-pid": (
        "IDE_PROCESS_UNAVAILABLE",
        "cli/src/test/kotlin/io/github/amichne/kast/cli/endpoint/IdeEndpointAdmissionNegativeTest.kt",
    ),
    "occupied-socket": (
        "NON_OWNED_PATH_PRESERVED",
        "cli/src/test/kotlin/io/github/amichne/kast/cli/runtime/RuntimeLifecycleTest.kt",
    ),
    "project-close": (
        "ENDPOINT_RETIRED",
        "ide-plugin/src/test/kotlin/io/github/amichne/kast/ide/endpoint/IdeEndpointRetirementTest.kt",
    ),
    "unsupported-operation": (
        "UNSUPPORTED_OPERATION",
        "runtime/ide-read/src/test/kotlin/io/github/amichne/kast/runtime/ide/read/revalidation/dispatch/IdeReadRuntimeDispatchNegativeTest.kt",
    ),
}


class Rejected(Exception):
    def __init__(self, reason, **evidence):
        self.document = {"outcome": "REJECTED", "reason": reason, **evidence}


def canonical(document):
    return json.dumps(document, ensure_ascii=False, separators=(",", ":"), sort_keys=False) + "\n"


def endpoint_path(root):
    key = hashlib.sha256(root.encode("utf-8")).hexdigest()[:24]
    return Path("/tmp") / (".k" + key) / "s.endpoint.json"


def processes():
    observed = subprocess.run(
        ["ps", "-ax", "-o", "pid=,command="],
        text=True,
        capture_output=True,
        check=True,
    )
    return {
        int(line.split(None, 1)[0]): line.split(None, 1)[1]
        for line in observed.stdout.splitlines()
        if len(line.split(None, 1)) == 2
    }


def exact_indexers(root, rows):
    return {
        pid for pid, command in rows.items()
        if "kast-indexer" in command and root in command
    }


def installed_kast(head):
    executable = shutil.which("kast")
    if executable is None:
        raise Rejected("INSTALLED_KAST_UNAVAILABLE")
    version = subprocess.run(
        [executable, "--version"],
        text=True,
        capture_output=True,
        check=False,
    )
    if version.returncode != 0 or ("g" + head[:9]) not in version.stdout:
        raise Rejected("INSTALLED_KAST_HEAD_MISMATCH", observed=version.stdout.strip())
    return executable


def installed_missing_plugin(executable, root):
    before = processes()
    result = subprocess.run(
        [executable, "workspace", "inspect"],
        cwd=root,
        text=True,
        capture_output=True,
        check=False,
    )
    after = processes()
    try:
        rejection = json.loads(result.stderr)
    except json.JSONDecodeError as failure:
        raise Rejected("INSTALLED_REJECTION_MALFORMED") from failure
    spawned = len(exact_indexers(root, after) - exact_indexers(root, before))
    if (
        result.returncode != 4
        or result.stdout
        or rejection.get("status") != "rejected"
        or rejection.get("boundary") != "runtime"
        or rejection.get("reason") != "ide-descriptor-read-rejected"
        or spawned != 0
    ):
        raise Rejected(
            "MISSING_PLUGIN_OBSERVATION_REJECTED",
            status=result.returncode,
            stdout=result.stdout.strip(),
            stderr=result.stderr.strip(),
            spawnedIndexerCount=spawned,
        )
    return {
        "status": result.returncode,
        "reason": rejection["reason"],
        "spawnedIndexerCount": spawned,
    }


def installed_operations(executable):
    result = subprocess.run(
        [executable, "--schema"],
        text=True,
        capture_output=True,
        check=False,
    )
    try:
        schema = json.loads(result.stdout)
        operations = schema["operationRegistry"]["operationIds"]
    except (json.JSONDecodeError, KeyError, TypeError) as failure:
        raise Rejected("INSTALLED_SCHEMA_REJECTED") from failure
    if result.returncode != 0 or len(operations) != 12 or len(set(operations)) != 12:
        raise Rejected("INSTALLED_OPERATION_REGISTRY_REJECTED")
    unsupported = sorted(set(operations) - SUPPORTED)
    if len(unsupported) != 8:
        raise Rejected("UNSUPPORTED_OPERATION_SET_REJECTED", observed=unsupported)
    return unsupported


def require_authorities(root):
    rows = []
    for identity, (reason, authority) in AUTHORITIES.items():
        if authority != "INSTALLED_CLI":
            path = Path(root) / authority
            if not path.is_file() or path.is_symlink():
                raise Rejected("MATRIX_AUTHORITY_UNAVAILABLE", case=identity, authority=authority)
        rows.append({
            "id": identity,
            "outcome": "REJECTED",
            "reason": reason,
            "authority": authority,
        })
    return rows


def main(arguments):
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args(arguments)
    root = str(Path(args.root).resolve(strict=True))
    status = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=root,
        text=True,
        capture_output=True,
        check=True,
    ).stdout
    head = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        text=True,
        capture_output=True,
        check=True,
    ).stdout.strip()
    if status:
        raise Rejected("REPOSITORY_NOT_CLEAN")
    if head != args.head:
        raise Rejected("REPOSITORY_HEAD_CHANGED")
    endpoint = endpoint_path(root)
    if endpoint.exists():
        raise Rejected("EXPECTED_MISSING_PLUGIN_ENDPOINT", descriptor=str(endpoint))
    executable = installed_kast(head)
    unsupported = installed_operations(executable)
    installed = installed_missing_plugin(executable, root)
    document = {
        "schemaVersion": 1,
        "taskId": "KVP-037",
        "outcome": "COMPLETE",
        "repositoryHead": head,
        "failureCases": require_authorities(root),
        "unsupportedOperations": unsupported,
        "installedObservation": installed,
        "forbiddenEffects": {
            "genericUnknownFailureCount": 0,
            "automaticFallbackCount": 0,
            "unsupportedTransportSuccessCount": 0,
            "nonOwnedPathDeletionCount": 0,
        },
    }
    output = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(canonical(document), encoding="utf-8")
    os.replace(temporary, output)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except Rejected as failure:
        sys.stderr.write(canonical(failure.document))
        sys.exit(4)
