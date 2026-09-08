#!/usr/bin/env python3
"""Installed Gradle startup acceptance with contained links and an explicit sidecar heap."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--product", type=Path, required=True)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--idea-home", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    product, runtime, idea = (p.resolve() for p in (args.product, args.runtime, args.idea_home))
    report = args.report.resolve()
    report.parent.mkdir(parents=True, exist_ok=True)
    # Retain the private fixture on failure for bounded diagnostic inspection.
    fixture = Path(tempfile.mkdtemp(prefix="kast-model-input-acceptance-")).resolve()
    workspace = fixture / "workspace"
    workspace.mkdir()
    def write(relative, text):
        path = workspace / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    write("settings.gradle.kts", 'rootProject.name = "contained-inputs"\nincludeBuild("build-logic")\n')
    write("build.gradle.kts", 'plugins { kotlin("jvm") version "2.3.10" }\nrepositories { mavenCentral() }\n')
    write("src/main/kotlin/Fixture.kt", 'class Fixture(val value: String)\n')
    write("build-logic/settings.gradle.kts", 'rootProject.name = "fixture-build-logic"\n')
    write("build-logic/build.gradle.kts", 'plugins { `java-library` }\n')
    write("build-logic/src/main/java/Convention.java", 'public final class Convention {}\n')
    write("shared/gradle.properties", 'org.gradle.jvmargs=-Xmx1g\n')
    (workspace / "shared/gradle/wrapper").mkdir(parents=True)
    for name in ("gradle-wrapper.jar", "gradle-wrapper.properties"):
        shutil.copy2(repo / "gradle/wrapper" / name, workspace / "shared/gradle/wrapper" / name)
    shutil.copy2(repo / "gradlew", workspace / "shared/gradlew")
    links = {"gradle.properties": "shared/gradle.properties", "gradle": "shared/gradle",
             "gradlew": "shared/gradlew", "build-logic/gradle.properties": "../shared/gradle.properties",
             "build-logic/gradle": "../shared/gradle", "build-logic/gradlew": "../shared/gradlew"}
    for name, target in links.items():
        (workspace / name).symlink_to(target)
    def snapshot():
        return {name: os.readlink(workspace / name) for name in links}
    before = snapshot()
    env = {k: v for k, v in os.environ.items() if not k.startswith("KAST_")}
    env.update(KAST_RUNTIME_ARCHIVE=str(runtime), KAST_RUNTIME_STORE=str(fixture / "store"),
               KAST_RUNTIME_DIRECTORY=str(fixture / "run"), KAST_CACHE_ROOT=str(fixture / "cache"),
               KAST_ENABLE_LAUNCHD="0", KAST_ENABLE_APP_SERVER="0", KAST_INDEXER_MAX_HEAP="8g")
    command = str(product / "bin/kast")
    evidence = {"fixture": str(fixture), "runtimeSha256": hashlib.sha256(runtime.read_bytes()).hexdigest(),
                "linksBefore": before}
    success = False
    try:
        start = subprocess.run([command, "start", "--idea-home", str(idea)], cwd=workspace, env=env,
                               capture_output=True, text=True, timeout=600)
        report.with_suffix(".start.stdout").write_text(start.stdout)
        report.with_suffix(".start.stderr").write_text(start.stderr)
        evidence["startExitCode"] = start.returncode
        assert start.returncode == 0, f"startup rejected; see {report.with_suffix('.start.stderr')}"
        status = subprocess.run([command], cwd=workspace, env=env, capture_output=True, text=True, timeout=30)
        assert status.returncode == 0, status.stderr
        evidence["status"] = json.loads(status.stdout)
        states = [json.loads(p.read_text())["bootstrap"] for p in (fixture / "cache").rglob("bootstrap-state")]
        assert len(states) == 1 and states[0]["state"] == "ready", states
        assert "Selected" in states[0]["gradleJvm"]["report"]["outcome"]["state"], states
        evidence["bootstrap"] = states[0]
        heaps = [json.loads(line.partition("kast-indexer-heap: ")[2])
                 for path in (fixture / "cache").rglob("startup.log") for line in path.read_text().splitlines()
                 if line.startswith("kast-indexer-heap: ")]
        assert len(heaps) == 1 and heaps[0]["requestedMaxHeapMiB"] == 8192, heaps
        assert abs(heaps[0]["observedMaxHeapBytes"] - 8192 * 1024 * 1024) <= 64 * 1024 * 1024, heaps
        evidence["heap"] = heaps[0]
        evidence["linksAfter"] = snapshot()
        assert evidence["linksAfter"] == before
        success = True
    finally:
        stop = subprocess.run([command, "stop"], cwd=workspace, env=env, capture_output=True, text=True, timeout=60)
        evidence["stopExitCode"] = stop.returncode
        evidence["passed"] = success and stop.returncode == 0
        report.write_text(json.dumps(evidence, indent=2) + "\n")
        if evidence["passed"]:
            shutil.rmtree(fixture)
    assert evidence["passed"], evidence
    print(f"model-input-startup: passed; {report}")


if __name__ == "__main__":
    main()
