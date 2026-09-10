#!/usr/bin/env python3
"""Compile exact carrier declarations and synchronous checks against an explicit installed IDEA."""
import argparse
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-contents", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    destination = here.parents[1] / "build/host-observation/carrier-checks"
    destination.mkdir(parents=True, exist_ok=True)
    # Only the file extension changes; tests execute the carrier's actual definitions.
    source = destination / "Observer.kt"
    source.write_bytes((here / "observer.kts").read_bytes())
    boundary = destination / "BootstrapBoundary.kt"
    boundary.write_text((here / "console.kts.template").read_text().split("// Evaluation boundary.", 1)[0])
    libraries = ":".join(str(p) for p in sorted((args.idea_contents / "lib").glob("*.jar")))
    compiler = args.idea_contents / "plugins/Kotlin/kotlinc/lib/*"
    java = args.jdk / "bin/java"
    command = [str(java), "-Xmx2g", "-cp", str(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
               "-no-stdlib", "-no-reflect", "-classpath", libraries, "-d", str(destination / "classes"),
               str(source), str(boundary), str(here / "CarrierChecks.kt")]
    compiled = subprocess.run(command, check=False)
    if compiled.returncode:
        return compiled.returncode
    return subprocess.run([str(java), "-cp", str(destination / "classes") + ":" + libraries,
                           "CarrierChecksKt"], check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
