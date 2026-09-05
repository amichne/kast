#!/usr/bin/env python3
"""Installed Gradle import matrix. Any missing proof exits nonzero without a success receipt."""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import urllib.request
from typing import Any

MATRIX_FILE = Path(__file__).resolve().parents[1] / "benchmarks/gradle-import-acceptance.json"
INPUT_NAME = "GRADLE_IMPORT_PROOF_INPUT"
INPUT_VALUE = "explicit-import-proof"
SECRET_NAME = "AMBIENT_SECRET_LIKE_TOKEN"
SECRET_VALUE = "unselected-import-proof-secret"
START_TIMEOUT_SECONDS = 240
OPERATION_TIMEOUT_SECONDS = 60


class AcceptanceFailure(Exception):
    """Bounded harness failure; never includes raw command output or environment values."""


@dataclass(frozen=True)
class MatrixCase:
    gradle: str
    java: int
    expected_outcome: str


def load_matrix(path: Path = MATRIX_FILE) -> list[MatrixCase]:
    if path.stat().st_size > 8192:
        raise AcceptanceFailure("matrix authority exceeds its bound")
    document = json.loads(path.read_text())
    if not isinstance(document, dict) or type(document.get("schemaVersion")) is not int or document["schemaVersion"] != 1:
        raise AcceptanceFailure("matrix authority has an unsupported schema")
    if set(document) != {"schemaVersion", "cases"}:
        raise AcceptanceFailure("matrix authority has unknown fields")
    records = document.get("cases")
    if not isinstance(records, list) or not 4 <= len(records) <= 8:
        raise AcceptanceFailure("matrix authority has an invalid case count")
    cases: list[MatrixCase] = []
    for record in records:
        if not isinstance(record, dict) or set(record) != {"gradle", "java", "expectedOutcome"}:
            raise AcceptanceFailure("matrix case has an unsupported shape")
        version, feature, outcome = record["gradle"], record["java"], record["expectedOutcome"]
        if not isinstance(version, str) or re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,2}", version) is None or len(version) > 24:
            raise AcceptanceFailure("matrix wrapper version is invalid")
        if type(feature) is not int or feature not in {17, 21, 25} or not isinstance(outcome, str) or outcome not in {"ready", "jvm-rejected"}:
            raise AcceptanceFailure("matrix JVM or expected outcome is unsupported")
        cases.append(MatrixCase(version, feature, outcome))
    if len({(case.gradle, case.java) for case in cases}) != len(cases):
        raise AcceptanceFailure("matrix authority has duplicate pairs")
    ready = [case for case in cases if case.expected_outcome == "ready"]
    if len(ready) != 3 or {case.java for case in ready} != {17, 21, 25}:
        raise AcceptanceFailure("matrix must prove one successful import each for Java 17, 21, and 25")
    if not any(case.expected_outcome == "jvm-rejected" for case in cases):
        raise AcceptanceFailure("matrix omits an incompatible project JVM")
    return cases


@dataclass(frozen=True)
class Jdk:
    feature: int
    home: Path
    release_sha256: str

    @classmethod
    def parse(cls, raw: str) -> Jdk:
        feature, separator, home = raw.partition(":")
        if not separator or not feature.isdecimal():
            raise AcceptanceFailure("JDK input must be FEATURE:/absolute/home")
        candidate = Path(home)
        if not candidate.is_absolute():
            raise AcceptanceFailure("JDK home must be absolute")
        try:
            canonical = candidate.resolve(strict=True)
            release_file = canonical / "release"
            if release_file.stat().st_size > 65536:
                raise AcceptanceFailure("JDK release identity exceeds its bound")
            release_bytes = release_file.read_bytes()
            release = release_bytes.decode()
        except OSError as error:
            raise AcceptanceFailure("JDK release identity is unavailable") from error
        match = re.search(r'^JAVA_VERSION="(\d+)', release, re.MULTILINE)
        if match is None or int(match.group(1)) != int(feature):
            raise AcceptanceFailure("JDK release does not match its declared Java feature")
        if not os.access(canonical / "bin/java", os.X_OK):
            raise AcceptanceFailure("JDK Java executable is unavailable")
        return cls(int(feature), canonical, hashlib.sha256(release_bytes).hexdigest())


def wrapper_checksum(version: str) -> str:
    with urllib.request.urlopen(
        f"https://services.gradle.org/distributions/gradle-{version}-bin.zip.sha256", timeout=60,
    ) as response:
        checksum = response.read(256).decode().strip()
    if re.fullmatch(r"[0-9a-f]{64}", checksum) is None:
        raise AcceptanceFailure("published Gradle checksum was malformed")
    return checksum


def prepare_fixture(source: Path, repository: Path, destination: Path, version: str, jdk: Jdk) -> None:
    shutil.copytree(source, destination, ignore=shutil.ignore_patterns(".git", ".idea", ".gradle", "build"))
    wrapper = destination / "gradle/wrapper"
    wrapper.mkdir(parents=True, exist_ok=True)
    shutil.copy2(repository / "gradle/wrapper/gradle-wrapper.jar", wrapper / "gradle-wrapper.jar")
    shutil.copy2(repository / "gradlew", destination / "gradlew")
    (wrapper / "gradle-wrapper.properties").write_text(
        "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n"
        f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{version}-bin.zip\n"
        f"distributionSha256Sum={wrapper_checksum(version)}\n"
        "zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n",
    )
    criteria = destination / "gradle/gradle-daemon-jvm.properties"
    if criteria.exists():
        criteria.unlink()
    (destination / "gradle.properties").write_text(
        f"org.gradle.java.home={jdk.home}\norg.gradle.jvmargs=-Xmx1g\norg.gradle.workers.max=2\n",
    )
    with (destination / "build.gradle.kts").open("a") as build:
        build.write(f'''
apply(plugin = "org.jetbrains.kotlin.jvm")
check(System.getenv("{INPUT_NAME}") == "{INPUT_VALUE}") {{ "explicit import input missing" }}
check(System.getenv("{SECRET_NAME}") == null) {{ "unselected environment input leaked" }}
check(System.getProperty("java.specification.version") == "{jdk.feature}") {{ "unexpected Gradle JVM" }}
val admittedGradleHome = File(System.getenv("GRADLE_USER_HOME") ?: File(System.getenv("HOME"), ".gradle").path).canonicalFile
check(gradle.gradleUserHomeDir.canonicalFile == admittedGradleHome) {{ "Gradle user home escaped admitted authority" }}
val importProbe = ProcessBuilder("kast-gradle-import-probe").start()
check(importProbe.inputStream.bufferedReader().readText().trim() == "admitted") {{ "explicit executable missing" }}
check(importProbe.waitFor() == 0) {{ "explicit executable failed" }}
''')
    probe = destination / "src/main/kotlin/GradleImportProbe.kt"
    probe.parent.mkdir(parents=True, exist_ok=True)
    probe.write_text("package importproof\nclass GradleImportProbe\n")
    result = subprocess.run(["git", "init", "--quiet", str(destination)], capture_output=True, check=False)
    if result.returncode != 0:
        raise AcceptanceFailure("fixture Git root creation failed")


def command(kast: Path, workspace: Path, environment: dict[str, str], args: list[str], timeout: int) -> tuple[int, dict[str, Any]]:
    try:
        result = subprocess.run([str(kast), *args], cwd=workspace, env=environment,
                                capture_output=True, text=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        raise AcceptanceFailure(f"{args[0]} exceeded its declared budget") from error
    if SECRET_VALUE in result.stdout or SECRET_VALUE in result.stderr:
        raise AcceptanceFailure("an unselected environment value appeared in command output")
    if len(result.stdout) + len(result.stderr) > 1_048_576:
        raise AcceptanceFailure("command output exceeded the evidence bound")
    raw = result.stdout.strip() or result.stderr.strip()
    try:
        document = json.loads(raw)
    except json.JSONDecodeError as error:
        raise AcceptanceFailure(f"{args[0]} omitted its final JSON document") from error
    if not isinstance(document, dict):
        raise AcceptanceFailure("command returned a non-object document")
    return result.returncode, document


def selection_report(document: dict[str, Any]) -> dict[str, Any]:
    bootstrap = document.get("bootstrap")
    if not isinstance(bootstrap, dict):
        raise AcceptanceFailure("lifecycle omitted bootstrap evidence")
    observed = bootstrap.get("gradleJvm")
    if not isinstance(observed, dict) or not isinstance(observed.get("report"), dict):
        raise AcceptanceFailure("lifecycle omitted observed Gradle JVM selection")
    report = observed["report"]
    if not report.get("requiredJava") or not report.get("candidates"):
        raise AcceptanceFailure("JVM selection omitted its required range or available candidates")
    return report


def assert_selection(report: dict[str, Any], version: str, feature: int, rejected: bool) -> None:
    if report.get("distribution", {}).get("version") != version:
        raise AcceptanceFailure("JVM evidence reported the wrong wrapper version")
    outcome = report.get("outcome", {})
    if rejected:
        if outcome.get("failure") != "NO_COMPATIBLE_RUNTIME":
            raise AcceptanceFailure("incompatible project JVM lacked a finite selection failure")
    else:
        candidate = outcome.get("candidate", {})
        if candidate.get("java") != feature or candidate.get("authority") != "REPOSITORY_GRADLE_PROPERTY":
            raise AcceptanceFailure("explicit project JVM authority was not retained")


def completed_import_observation(host: Path, version: str, feature: int) -> dict[str, Any]:
    logs = list((host / "caches").glob("*/log/startup.log"))
    if len(logs) != 1:
        raise AcceptanceFailure("import event has no exact runtime log authority")
    with logs[0].open("rb") as stream:
        stream.seek(max(0, logs[0].stat().st_size - 262144))
        lines = stream.read(262144).decode(errors="replace").splitlines()
    observed: list[dict[str, Any]] = []
    prefix = "kast-indexer: Gradle import: "
    for line in lines:
        if not line.startswith(prefix):
            continue
        event = json.loads(line[len(prefix):])
        if not isinstance(event, dict) or set(event) != {
            "stage", "outcome", "distribution", "clientJava", "clientHomeIdentity", "projectJava", "projectHomeIdentity",
        }:
            raise AcceptanceFailure("completed import event has an unsupported shape")
        if event["stage"] != "model-import" or event["outcome"] != "completed" or event["distribution"] != version:
            raise AcceptanceFailure("completed import event does not match its exact wrapper")
        if type(event["clientJava"]) is not int or event["clientJava"] != 25 or type(event["projectJava"]) is not int or event["projectJava"] != feature:
            raise AcceptanceFailure("import event did not retain separate client and project JVM authority")
        if any(not isinstance(event[key], str) or re.fullmatch(r"[0-9a-f]{64}", event[key]) is None
               for key in ("clientHomeIdentity", "projectHomeIdentity")):
            raise AcceptanceFailure("import event has invalid runtime home identities")
        observed.append(event)
    if len(observed) != 1:
        raise AcceptanceFailure("import did not emit exactly one complete terminal event")
    return observed[0]


def run_case(args: argparse.Namespace, version: str, jdk: Jdk, rejected: bool = False) -> dict[str, Any]:
    host = Path(tempfile.mkdtemp(prefix="kg-import-", dir="/tmp")).resolve()
    workspace = host / "workspace"
    prepare_fixture(args.fixture, args.repository, workspace, version, jdk)
    home = host / "home"
    home.mkdir()
    tools = host / "tools"
    tools.mkdir()
    executable = tools / "kast-gradle-import-probe"
    executable.write_text("#!/bin/sh\nprintf 'admitted\\n'\n")
    executable.chmod(0o700)
    sidecar_java = args.idea_home / "jbr/Contents/Home"
    environment = {
        "HOME": str(home), "JAVA_HOME": str(sidecar_java),
        "PATH": f"{sidecar_java}/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        "JAVA_OPTS": f"-Duser.home={home}",
        "KAST_RUNTIME_DIRECTORY": str(host / "endpoints"),
        "KAST_RUNTIME_STORE": str(host / "store"),
        "KAST_CACHE_ROOT": str(host / "caches"),
        "KAST_GRADLE_IMPORT_VARIABLES": INPUT_NAME,
        "KAST_GRADLE_IMPORT_PATH": str(tools),
        INPUT_NAME: INPUT_VALUE, SECRET_NAME: SECRET_VALUE,
    }
    if args.runtime_archive:
        environment["KAST_RUNTIME_ARCHIVE"] = str(args.runtime_archive)
    stopped = False
    completed = False
    try:
        exit_code, started = command(args.kast, workspace, environment,
                                     ["start", f"--idea-home={args.idea_home}"], START_TIMEOUT_SECONDS)
        # Preserve the bounded lifecycle projection before retiring a failed case.
        # Command output has already passed the size and secret-leak checks.
        observation = {"gradle": version, "java": jdk.feature, "expectedRejection": rejected,
                       "exitCode": exit_code,
                       "lifecycle": {key: started[key] for key in
                                     ("status", "reason", "bootstrap", "runtime") if key in started}}
        observation_path = args.state_root / f"gradle-{version}-java-{jdk.feature}.json"
        observation_path.write_text(json.dumps(observation, indent=2, sort_keys=True) + "\n")
        if (exit_code != 0) != rejected:
            raise AcceptanceFailure("installed startup did not match the matrix expectation")
        # Passive status must retain the same report after either startup outcome.
        _, status = command(args.kast, workspace, environment, ["status"], OPERATION_TIMEOUT_SECONDS)
        report = selection_report(status)
        assert_selection(report, version, jdk.feature, rejected)
        if rejected:
            action = status.get("bootstrap", {}).get("correctiveAction", "")
            if "org.gradle.java.home" not in action:
                raise AcceptanceFailure("rejected JVM selection omitted corrective action")
        else:
            code, symbols = command(args.kast, workspace, environment,
                ["symbol", "discover", "--query=GradleImportProbe", "--match=exact-name", "--limit=10"],
                OPERATION_TIMEOUT_SECONDS)
            if code != 0 or not any(item.get("name") == "GradleImportProbe" for item in symbols.get("items", [])):
                raise AcceptanceFailure("installed semantic discovery did not observe the imported fixture")
        receipt = {"gradle": version, "java": jdk.feature, "expectedRejection": rejected,
                   "selection": report, "explicitInputAdmitted": not rejected,
                   "ambientSecretAbsent": not rejected, "explicitExecutableAdmitted": not rejected}
        receipt["gradleUserHomeIsolated"] = not rejected
        if not rejected:
            receipt["importObservation"] = completed_import_observation(host, version, jdk.feature)
        completed = True
        return receipt
    finally:
        try:
            code, _ = command(args.kast, workspace, environment, ["stop"], OPERATION_TIMEOUT_SECONDS)
            stopped = code == 0
        finally:
            if stopped and completed:
                shutil.rmtree(host)
            elif stopped:
                # Retire the process but retain this isolated fixture's diagnostic
                # files locally. They are never copied into the published receipt.
                print(f"gradle-import-acceptance: retired failed case; diagnostic state retained at {host}")
            else:
                raise AcceptanceFailure(f"runtime retirement unproven; retained owned state at {host}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kast", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--idea-home", type=Path, required=True)
    parser.add_argument("--state-root", type=Path, required=True)
    parser.add_argument("--runtime-archive", type=Path)
    parser.add_argument("--jdk", action="append", default=[], metavar="FEATURE:/HOME")
    args = parser.parse_args()
    try:
        args.kast = args.kast.resolve(strict=True)
        args.fixture = args.fixture.resolve(strict=True)
        args.idea_home = args.idea_home.resolve(strict=True)
        args.repository = args.repository.resolve(strict=True)
        if args.runtime_archive:
            args.runtime_archive = args.runtime_archive.resolve(strict=True)
        admitted = [Jdk.parse(raw) for raw in args.jdk]
        jdks = {jdk.feature: jdk for jdk in admitted}
        if set(jdks) != {17, 21, 25} or len(admitted) != 3:
            raise AcceptanceFailure("installed matrix requires exactly one JDK each for Java 17, 21, and 25")
        args.state_root.mkdir(parents=True, exist_ok=False)
        matrix_sha256 = hashlib.sha256(MATRIX_FILE.read_bytes()).hexdigest()
        command_sha256 = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        matrix = load_matrix()
        cases = [run_case(args, case.gradle, jdks[case.java], rejected=case.expected_outcome == "jvm-rejected")
                 for case in matrix]
        if matrix_sha256 != hashlib.sha256(MATRIX_FILE.read_bytes()).hexdigest() or command_sha256 != hashlib.sha256(Path(__file__).read_bytes()).hexdigest():
            raise AcceptanceFailure("matrix or harness authority changed during installed acceptance")
        receipt = {"schemaVersion": 1, "status": "passed", "cases": cases,
                   "matrixSha256": matrix_sha256, "commandSha256": command_sha256,
                   "javaRuntimeReleaseSha256": {str(feature): jdks[feature].release_sha256 for feature in sorted(jdks)}}
        (args.state_root / "gradle-import-receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        print("Gradle import installed matrix passed (three JVM pairs and one explicit rejection).")
        return 0
    except (AcceptanceFailure, OSError, json.JSONDecodeError) as error:
        print(f"gradle-import-acceptance: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
