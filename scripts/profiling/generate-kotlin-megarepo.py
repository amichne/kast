#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
from pathlib import Path


MARKER = ".kast-synthetic-profile-repo"
GRADLE_VERSION = "8.14.3"
KOTLIN_VERSION = "2.2.0"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate a large synthetic Kotlin/Gradle workspace for kast profiling.")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--modules", type=int, default=240)
    parser.add_argument("--fanout", type=int, default=3)
    parser.add_argument("--classes-per-module", type=int, default=3)
    parser.add_argument("--force", action="store_true")
    return parser.parse_args()


def module_name(index: int) -> str:
    return f"module-{index:03d}"


def package_name(index: int) -> str:
    return f"com.example.enterprise.module{index:03d}"


def service_name(index: int, class_index: int = 0) -> str:
    suffix = "" if class_index == 0 else f"Variant{class_index}"
    return f"Service{index:03d}{suffix}"


def dependency_indexes(index: int, fanout: int) -> list[int]:
    start = max(1, index - fanout)
    return list(range(start, index))


def write_settings(root: Path, modules: int) -> None:
    include_lines = ",\n".join(f'    ":{module_name(index)}"' for index in range(1, modules + 1))
    (root / "settings.gradle.kts").write_text(
        f"""pluginManagement {{
    repositories {{
        gradlePluginPortal()
        mavenCentral()
    }}
}}

dependencyResolutionManagement {{
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {{
        mavenCentral()
    }}
}}

rootProject.name = "kast-synthetic-kotlin-megarepo"

include(
{include_lines}
)
""",
        encoding="utf-8",
    )


def write_root_build(root: Path) -> None:
    (root / "build.gradle.kts").write_text(
        f"""plugins {{
    kotlin("jvm") version "{KOTLIN_VERSION}" apply false
}}

subprojects {{
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<org.gradle.api.plugins.JavaPluginExtension>("java") {{
        toolchain {{
            languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
        }}
    }}
}}
""",
        encoding="utf-8",
    )


def write_gradle_properties(root: Path) -> None:
    (root / "gradle.properties").write_text(
        """org.gradle.caching=true
org.gradle.parallel=true
org.gradle.configuration-cache=true
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
kotlin.stdlib.default.dependency=true
""",
        encoding="utf-8",
    )


def write_wrapper_properties(root: Path) -> None:
    wrapper_dir = root / "gradle" / "wrapper"
    wrapper_dir.mkdir(parents=True, exist_ok=True)
    (wrapper_dir / "gradle-wrapper.properties").write_text(
        f"""distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
""",
        encoding="utf-8",
    )


def write_module_build(module_dir: Path, index: int, fanout: int) -> None:
    deps = dependency_indexes(index, fanout)
    dependencies = "\n".join(f'    implementation(project(":{module_name(dep)}"))' for dep in deps)
    body = "dependencies {\n" + dependencies + "\n}\n" if dependencies else ""
    (module_dir / "build.gradle.kts").write_text(body, encoding="utf-8")


def write_source_file(module_dir: Path, index: int, fanout: int, classes_per_module: int) -> None:
    src_dir = module_dir / "src" / "main" / "kotlin" / "com" / "example" / "enterprise" / f"module{index:03d}"
    src_dir.mkdir(parents=True, exist_ok=True)
    deps = dependency_indexes(index, fanout)
    imports = "\n".join(f"import {package_name(dep)}.{service_name(dep)}" for dep in deps)
    constructor_args = ", ".join(
        f"private val upstream{dep}: {service_name(dep)} = {service_name(dep)}()" for dep in deps
    )
    upstream_sum = " + ".join(f"upstream{dep}.compute(input / 2)" for dep in deps)
    upstream_expr = f" + {upstream_sum}" if upstream_sum else ""
    variants = "\n\n".join(
        f"""class {service_name(index, class_index)} {{
    fun fold(value: Int): Int = value + {index} + {class_index}
}}"""
        for class_index in range(1, classes_per_module)
    )
    variants_block = f"\n\n{variants}" if variants else ""
    source = f"""package {package_name(index)}

{imports}

class {service_name(index)}({constructor_args}) {{
    fun compute(input: Int): Int = input + {index}{upstream_expr}
}}

fun module{index:03d}Entry(value: Int): Int = {service_name(index)}().compute(value){variants_block}
"""
    (src_dir / f"{service_name(index)}.kt").write_text(source, encoding="utf-8")


def prepare_output(root: Path, force: bool) -> None:
    if not root.exists():
        root.mkdir(parents=True)
        return
    marker = root / MARKER
    if not marker.exists():
        raise SystemExit(f"refusing to overwrite non-synthetic directory: {root}")
    if force:
        shutil.rmtree(root)
        root.mkdir(parents=True)


def main() -> int:
    args = parse_args()
    if args.modules < 1:
        raise SystemExit("--modules must be at least 1")
    if args.fanout < 0:
        raise SystemExit("--fanout must be non-negative")
    if args.classes_per_module < 1:
        raise SystemExit("--classes-per-module must be at least 1")

    root = args.output.resolve()
    prepare_output(root, args.force)
    (root / MARKER).write_text("owned by scripts/profiling/generate-kotlin-megarepo.py\n", encoding="utf-8")

    write_settings(root, args.modules)
    write_root_build(root)
    write_gradle_properties(root)
    write_wrapper_properties(root)

    for index in range(1, args.modules + 1):
        module_dir = root / module_name(index)
        module_dir.mkdir(parents=True, exist_ok=True)
        write_module_build(module_dir, index, args.fanout)
        write_source_file(module_dir, index, args.fanout, args.classes_per_module)

    print(root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
