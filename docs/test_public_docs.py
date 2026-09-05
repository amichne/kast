#!/usr/bin/env python3
"""Check the Mintlify documentation structure, claims, and deployment contract."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "docs/public"
CONFIG = PUBLIC / "docs.json"
MINTIGNORE = PUBLIC / ".mintignore"
README = ROOT / "README.md"
DOCS_WORKFLOW = ROOT / ".github/workflows/docs.yml"
VERSION_CATALOG = ROOT / "gradle/libs.versions.toml"
INSTALLER = ROOT / "install.sh"
IDE_RUNTIME_COMPATIBILITY_AUTHORITY = ROOT / (
    "cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/IndexSeedProtocol.kt"
)
IDE_RUNTIME_DISCOVERY_PROOF = ROOT / (
    "cli/src/test/kotlin/io/github/amichne/kast/cli/runtime/InstalledIdeRuntimeDiscoveryTest.kt"
)
LIFECYCLE_COMMAND_AUTHORITY = ROOT / (
    "cli/src/main/kotlin/io/github/amichne/kast/cli/command/lifecycle/LifecycleCommands.kt"
)
CLI_REFERENCE_GENERATOR = ROOT / "docs/generate_cli_reference.py"
GENERATED_OPERATION_REGISTRY = ROOT / (
    "protocol/wire/build/generated/operation-registry/operation-registry.json"
)
CANONICAL_OPERATION_AUTHORITY = ROOT / (
    "protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/"
    "CanonicalOperation.kt"
)
SIDECAR_LIFECYCLE_AUTHORITY = ROOT / (
    "cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/RuntimeLifecycle.kt"
)
INSTALL_COMMAND = 'release_url="$(curl -fsSL -o /dev/null -w \'%{url_effective}\' https://github.com/amichne/kast/releases/latest)"\nrelease_tag="${release_url##*/}"\ncurl -fsSL "https://raw.githubusercontent.com/amichne/kast/${release_tag}/install.sh" | bash -s -- --version "$release_tag"'
APPROVED_LUCIDE_ICONS = {
    "activity",
    "badge-check",
    "boxes",
    "braces",
    "code-xml",
    "compass",
    "copy-check",
    "file-pen",
    "folder-tree",
    "gauge",
    "git-pull-request-create",
    "network",
    "package-check",
    "rocket",
    "rotate-ccw",
    "route",
    "scan-search",
    "server",
    "share-2",
    "shield-check",
    "stethoscope",
    "terminal",
    "trash-2",
    "workflow",
}

PAGES = {
    "index.mdx": [
        "Choose the depth that matches the question",
        "Install, admit one repository, and verify the first result",
        "Read exact command and implementation contracts",
        "The compiler sees more than text",
        "Start from the repository root",
        "Eleven sidecar operations",
        "What is Kast ready to inspect?",
        "What declaration is this?",
        "How is this code connected?",
        "How can I add a declaration safely?",
        "Evidence keeps its boundary",
    ],
    "start.mdx": [
        "Host contract",
        "Java 25 or newer",
        "Reference pair:",
        "Compatible patch builds are accepted",
        "JetBrains platform release line 262",
        "https://raw.githubusercontent.com/amichne/kast/${release_tag}/install.sh",
        "rejects unsafe archive paths",
        "does not edit your shell profile",
        "kast start --cache seed",
        "kast --schema",
        "eleven operations",
        "kast index sync",
        "OpenTelemetry traces",
        "relation reads",
        "diagnostics",
        "kast start",
        "kast status",
        "kast stop",
        "Operate the lifecycle",
        "<Steps>",
        "<Tabs>",
        "<AccordionGroup>",
    ],
    "questions/workspace-readiness.mdx": [
        "kast start",
        "exact root",
        '"runtime": "running"',
        "lifecycle document does not include a semantic `generation` field",
    ],
    "questions/declaration-identity.mdx": [
        "kast symbol discover",
        "kast symbol inspect --candidate",
        "kast symbol inspect --selector",
        "<Steps>",
    ],
    "questions/code-connections.mdx": [
        "kast topology build",
        "kast traversal run",
        "kast relation read",
        "generation-bound",
    ],
    "questions/semantic-validity.mdx": [
        "kast diagnostic check",
        "explicit scope",
        "Complete",
        "| Outcome | How to read it |",
    ],
    "questions/safe-change.mdx": [
        "add-declaration",
        "kast change plan",
        "kast change apply",
        "verified receipt",
        "kast change recover",
        "without restarting",
    ],
    "concepts/evidence-boundaries.mdx": [
        "Complete",
        "Qualified",
        "Rejected",
        "generation",
        "eleven sidecar operations",
        "| Outcome | What it proves |",
    ],
    "explanation/how-kast-works.mdx": [
        "release-line-compatible local IDEA installation",
        "exact eleven-operation public capability set",
        "Kotlin control executable",
        "SymbolInspectRequestDocument",
        "UnixDomainWireClient",
        "InstalledSidecarRootRuntimeDemander",
        "IndexSeedFilesystemService",
        "KastIndexerApplicationStarter",
        "InstalledIndexerTransport",
        "InstalledKastRuntime",
        "InstalledIntellijWorkspace",
        "InstalledIntellijSymbolPorts",
        "PSI and K2 objects never cross the wire",
        "```mermaid placement=\"top-right\" actions={true}",
        "sequenceDiagram",
        "<Frame",
        "<AccordionGroup>",
    ],
    "technical-specification/index.mdx": [
        "Backing specification",
        "How to read this specification",
        "One request, seven trust transitions",
        "descriptive map, not an alternative authority",
        'data-kast-view="runtime-flow"',
    ],
    "technical-specification/runtime-boundary.mdx": [
        "Exact-root admission",
        "observed patch build identities",
        "Private process and private state",
        "Workspace bootstrap and publication",
        "Request-local compiler state",
    ],
    "technical-specification/protocol-and-dispatch.mdx": [
        "One operation, four synchronized surfaces",
        "CanonicalOperation",
        "TypedOperationBinding",
        "Bounded wire transport",
        "Server projection and broker",
    ],
    "technical-specification/semantic-services.mdx": [
        "Workspace and index",
        "Symbol identity",
        "Relations and traversal",
        "eligible SQLite topology snapshot",
        "Topology",
        "Diagnostics",
        "installedIntellijTopologyExtractor",
    ],
    "technical-specification/change-and-evidence.mdx": [
        "Plan: compile intent into proof obligations",
        "Apply: cross the physical effect boundary",
        "Verify: discharge obligations against a resulting generation",
        "Recover: resolve durable uncertainty",
        "SQLite is an adapter, not a domain model",
    ],
    "technical-specification/module-architecture.mdx": [
        "Dependency direction is part of correctness",
        "Effect ownership",
        "Architecture enforcement",
        "The diagrams are generated proof artifacts",
        'data-kast-view="module-ownership"',
    ],
    "technical-specification/verification-and-contribution.mdx": [
        "Verification rings",
        "Installed product proof",
        "Enterprise acceptance",
        "Keep this specification authenticated",
    ],
    "reference/gradle-import.mdx": [
        "KAST_GRADLE_IMPORT_VARIABLES",
        "KAST_GRADLE_IMPORT_PATH",
        "org.gradle.java.home",
        "GradleRuntimeCompatibilityPolicy",
        "semantic workspace identity",
    ],
    "reference/compatibility.mdx": [
        "Stable in 1.x",
        "Versioned JSON shapes",
        "Preview and supported hosts",
        "Mechanical release comparison",
        "Upgrade and recovery",
        "--allow-next-major",
        "pre-stable",
        "first-stable",
        "add-declaration",
    ],
    "reference/cli.mdx": [
        "Generated by docs/generate_cli_reference.py",
        "documentation check fails",
        "Sidecar endpoint operations",
        "Kast owns the isolated sidecar lifecycle",
        "release-line-compatible local IDEA build",
        "relation.read",
        "diagnostic.check",
        "Process-local commands",
        "product inspect",
        "broker serve",
        "Default local traces",
        "index.sync",
        "source.read",
        "change.recover",
    ],
}

EXPECTED_NAVIGATION = {
    "Setup": {"Start here": ["index", "start"]},
    "Guides": {
        "Answer a code question": [
            "questions/workspace-readiness",
            "questions/declaration-identity",
            "questions/code-connections",
            "questions/semantic-validity",
            "questions/safe-change",
        ],
    },
    "Concepts": {
        "Evidence model": [
            "concepts/evidence-boundaries",
            "explanation/how-kast-works",
        ],
    },
    "Reference": {
        "Command surface": ["reference/cli", "reference/gradle-import", "reference/compatibility"],
        "Technical specification": [
            "technical-specification/index",
            "technical-specification/runtime-boundary",
            "technical-specification/protocol-and-dispatch",
            "technical-specification/semantic-services",
            "technical-specification/change-and-evidence",
            "technical-specification/module-architecture",
            "technical-specification/verification-and-contribution",
        ],
    },
}

TECHNICAL_SPECIFICATION_AUTHORITIES = {
    "technical-specification/index.mdx": [
        "AGENTS.md",
        "docs/public/architecture/model.c4",
        "runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/bootstrap/InstalledRuntimeAssembly.kt",
    ],
    "technical-specification/runtime-boundary.mdx": [
        "cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/IndexSeedProtocol.kt",
        "cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/InstalledIdeRuntimeDiscovery.kt",
        "cli/src/main/kotlin/io/github/amichne/kast/cli/runtime/InstalledSidecarRuntimeDemand.kt",
        "indexer/src/main/kotlin/io/github/amichne/kast/indexer/KastIndexerApplicationStarter.kt",
        "workspace/intellij/src/main/kotlin/io/github/amichne/kast/workspace/intellij/InstalledIntellijWorkspace.kt",
        "runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/bootstrap/InstalledKastRuntime.kt",
    ],
    "technical-specification/protocol-and-dispatch.mdx": [
        "protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt",
        "protocol/registry/src/main/kotlin/io/github/amichne/kast/protocol/registry/OperationRegistry.kt",
        "protocol/wire/src/main/kotlin/io/github/amichne/kast/protocol/wire/OperationWireTable.kt",
        "runtime/server/src/main/kotlin/io/github/amichne/kast/runtime/server/RuntimeServer.kt",
    ],
    "technical-specification/semantic-services.mdx": [
        "symbol/service/src/main/kotlin/io/github/amichne/kast/symbol/service/SymbolDiscoveryService.kt",
        "relation/service/src/main/kotlin/io/github/amichne/kast/relation/service/RelationService.kt",
        "traversal/service/src/main/kotlin/io/github/amichne/kast/traversal/service/TraversalService.kt",
        "runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/protocol/graph/TopologyBackedTraversalOperations.kt",
        "topology/intellij/src/main/kotlin/io/github/amichne/kast/topology/intellij/InstalledIntellijTopologyExtractor.kt",
        "diagnostic/service/src/main/kotlin/io/github/amichne/kast/diagnostic/service/DiagnosticService.kt",
    ],
    "technical-specification/change-and-evidence.mdx": [
        "change/contract/src/main/kotlin/io/github/amichne/kast/change/contract/AddDeclarationIntent.kt",
        "change/plan/src/main/kotlin/io/github/amichne/kast/change/plan/PureAddDeclarationPlanningService.kt",
        "change/apply/src/main/kotlin/io/github/amichne/kast/change/apply/AddDeclarationApplyService.kt",
        "change/verify/src/main/kotlin/io/github/amichne/kast/change/verify/VerifiedMutationService.kt",
        "evidence/sqlite/src/main/kotlin/io/github/amichne/kast/evidence/sqlite/SqliteMutationRecoveryJournal.kt",
    ],
    "technical-specification/module-architecture.mdx": [
        "settings.gradle.kts",
        "build-logic/src/main/kotlin/support/architecture/ArchitectureModel.kt",
        "build-logic/src/main/kotlin/support/architecture/validation/ArchitecturePolicyValidator.kt",
        "docs/public/architecture/model.c4",
        "docs/public/architecture/views.c4",
    ],
    "technical-specification/verification-and-contribution.mdx": [
        "build.gradle.kts",
        "packaging/test-installed-product.sh",
        "integration-tests/enterprise_acceptance.py",
        "docs/test_public_docs.py",
        ".github/workflows/docs.yml",
    ],
}

ARCHITECTURE_EMBEDS = {
    "technical-specification/index.mdx": (
        '<div className="kast-architecture-view" data-kast-view="runtime-flow" '
        'data-kast-browser="true" data-kast-dynamic-variant="diagram" '
        'aria-label="Interactive runtime request architecture view"></div>'
    ),
    "technical-specification/module-architecture.mdx": (
        '<div className="kast-architecture-view kast-architecture-view-modules" '
        'data-kast-view="module-ownership" data-kast-browser="true" '
        'data-kast-dynamic-variant="diagram" '
        'aria-label="Interactive module ownership architecture view"></div>'
    ),
}

FORBIDDEN_PROSE = [
    "why should you care",
    "revolutionary",
    "game-changing",
    "seamless",
    "unlock the power",
    "—",
]

STALE_CLAIMS = [
    "Android Studio 2026.1.2",
    "foreground IDE",
    "published set of twelve canonical operations",
    "four hosted operations",
    "exact four-operation capability set",
    "`workspace.inspect`",
    "`symbol.resolve`",
    "`status`, `stop`, and `clean` remain passive",
    "Start, stop, clean, reindex",
]

DEFERRED_PAGES: set[str] = set()
MAX_PROSE_WORDS = 55
MAX_PROSE_CHARACTERS = 360
LIST_ITEM = re.compile(r"^\s*(?:[-*+]|[0-9]+[.)])\s+(?P<text>.+)$")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def page_route(relative: str) -> str:
    return relative.removesuffix(".mdx")


def public_url_route(relative: str) -> str:
    route = page_route(relative)
    if route == "index":
        return "/"
    if route.endswith("/index"):
        return f"/{route.removesuffix('/index')}"
    return f"/{route}"


def frontmatter(text: str, relative: str) -> str:
    match = re.match(r"\A---\n(?P<frontmatter>.*?)\n---\n", text, re.DOTALL)
    require(match is not None, f"{relative} has no YAML frontmatter")
    assert match is not None
    metadata = match.group("frontmatter")
    require(re.search(r'^title: "[^"]+"$', metadata, re.MULTILINE) is not None, f"{relative} has no title")
    require(
        re.search(r'^description: "[^"]+"$', metadata, re.MULTILINE) is not None,
        f"{relative} has no description",
    )
    return metadata


def prose_blocks(text: str, has_frontmatter: bool) -> list[tuple[int, str]]:
    lines = text.splitlines()
    if has_frontmatter:
        closing = next(
            (index for index, line in enumerate(lines[1:], start=1) if line == "---"),
            None,
        )
        require(closing is not None, "prose scan could not find closing frontmatter")
        assert closing is not None
        lines = [""] * (closing + 1) + lines[closing + 1 :]

    blocks: list[tuple[int, str]] = []
    current: list[str] = []
    start_line = 0
    in_fence = False

    def finish() -> None:
        nonlocal current, start_line
        if current:
            blocks.append((start_line, " ".join(current)))
        current = []
        start_line = 0

    for line_number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if stripped.startswith(("```", "~~~")):
            finish()
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if not stripped:
            finish()
            continue

        item = LIST_ITEM.match(line)
        if item is not None:
            finish()
            start_line = line_number
            current = [item.group("text")]
            continue

        if stripped.startswith(("#", "|", ">", "<")) or stripped == "---":
            finish()
            continue

        if not current:
            start_line = line_number
        current.append(stripped)

    finish()
    return blocks


def check_scan_friendly_prose(relative: str, text: str, has_frontmatter: bool) -> None:
    for line_number, block in prose_blocks(text, has_frontmatter):
        word_count = len(re.findall(r"\b[\w'-]+\b", block))
        character_count = len(block)
        require(
            word_count <= MAX_PROSE_WORDS and character_count <= MAX_PROSE_CHARACTERS,
            f"{relative}:{line_number} has an oversized prose block "
            f"({word_count} words, {character_count} characters); split or structure it",
        )


def quoted_frontmatter_value(metadata: str, key: str, relative: str) -> str:
    match = re.search(rf'^{re.escape(key)}: "([^"]+)"$', metadata, re.MULTILINE)
    require(match is not None, f"{relative} has no quoted OKF {key}")
    assert match is not None
    return match.group(1)


def okf_code_sources(metadata: str, relative: str) -> list[tuple[str, int, int, list[str]]]:
    lines = metadata.splitlines()
    try:
        source_index = lines.index("code_sources:")
    except ValueError:
        require(False, f"{relative} has no OKF code_sources")
        raise AssertionError("unreachable")

    block: list[str] = []
    for line in lines[source_index + 1 :]:
        if line and not line.startswith(" "):
            break
        if line:
            block.append(line)

    sources: list[tuple[str, int, int, list[str]]] = []
    cursor = 0
    while cursor < len(block):
        path_match = re.fullmatch(r'  - path: "([^"]+)"', block[cursor])
        require(path_match is not None, f"{relative} has malformed OKF source path")
        assert path_match is not None
        cursor += 1

        require(cursor < len(block), f"{relative} source {path_match.group(1)} has no line range")
        range_match = re.fullmatch(r'    lines: "([1-9][0-9]*)-([1-9][0-9]*)"', block[cursor])
        require(range_match is not None, f"{relative} source {path_match.group(1)} has malformed lines")
        assert range_match is not None
        cursor += 1

        symbols: list[str] = []
        if cursor < len(block) and block[cursor].startswith("    symbols:"):
            symbols_match = re.fullmatch(r"    symbols: (\[.*\])", block[cursor])
            require(symbols_match is not None, f"{relative} has malformed OKF symbols")
            assert symbols_match is not None
            parsed_symbols = json.loads(symbols_match.group(1))
            require(
                isinstance(parsed_symbols, list)
                and parsed_symbols
                and all(isinstance(symbol, str) and symbol for symbol in parsed_symbols),
                f"{relative} has invalid OKF symbols",
            )
            symbols = parsed_symbols
            cursor += 1

        sources.append(
            (
                path_match.group(1),
                int(range_match.group(1)),
                int(range_match.group(2)),
                symbols,
            )
        )

    require(sources, f"{relative} has no OKF code source entries")
    return sources


def check_okf_frontmatter(metadata: str, relative: str) -> None:
    quoted_frontmatter_value(metadata, "type", relative)
    resource = quoted_frontmatter_value(metadata, "resource", relative)
    require(resource == f"file://docs/public/{relative}", f"{relative} has the wrong OKF resource")

    tags_match = re.search(r"^tags: (\[.*\])$", metadata, re.MULTILINE)
    require(tags_match is not None, f"{relative} has no OKF tags")
    assert tags_match is not None
    tags = json.loads(tags_match.group(1))
    require(
        isinstance(tags, list) and tags and all(isinstance(tag, str) and tag for tag in tags),
        f"{relative} has invalid OKF tags",
    )

    timestamp = quoted_frontmatter_value(metadata, "timestamp", relative)
    try:
        parsed_timestamp = datetime.fromisoformat(timestamp)
    except ValueError as error:
        raise AssertionError(f"{relative} has invalid OKF timestamp") from error
    require(parsed_timestamp.tzinfo is not None, f"{relative} OKF timestamp has no timezone")

    for source_path, start, end, symbols in okf_code_sources(metadata, relative):
        source = Path(source_path)
        require(
            not source.is_absolute() and ".." not in source.parts,
            f"{relative} has non-repository OKF source {source_path}",
        )
        authority = ROOT / source
        require(authority.is_file(), f"{relative} cites missing OKF source {source_path}")
        authority_lines = authority.read_text().splitlines()
        require(start <= end <= len(authority_lines), f"{relative} has invalid range {source_path}:{start}-{end}")
        selected_source = "\n".join(authority_lines[start - 1 : end])
        for symbol in symbols:
            require(
                re.search(rf"\b{re.escape(symbol)}\b", selected_source) is not None,
                f"{relative} cites missing symbol {symbol!r} in {source_path}:{start}-{end}",
            )


def report_okf_impact(raw_paths: list[str]) -> None:
    require(raw_paths, "usage: python3 docs/test_public_docs.py --impact SOURCE_PATH...")
    requested: list[str] = []
    for raw_path in raw_paths:
        candidate = Path(raw_path)
        if candidate.is_absolute():
            try:
                candidate = candidate.relative_to(ROOT)
            except ValueError as error:
                raise AssertionError(f"impact path is outside the repository: {raw_path}") from error
        require(".." not in candidate.parts, f"impact path leaves the repository: {raw_path}")
        requested.append(candidate.as_posix())

    pages_by_source: dict[str, set[str]] = {}
    for relative in PAGES:
        metadata = frontmatter((PUBLIC / relative).read_text(), relative)
        check_okf_frontmatter(metadata, relative)
        for source_path, _start, _end, _symbols in okf_code_sources(metadata, relative):
            pages_by_source.setdefault(source_path, set()).add(relative)

    for source_path in requested:
        pages = sorted(pages_by_source.get(source_path, []))
        if pages:
            print(f"{source_path}: {', '.join(pages)}")
        else:
            print(f"{source_path}: no mapped public page")


def check_icon_system(config: dict[str, object], groups: list[dict[str, object]]) -> None:
    require(
        config.get("icons") == {"library": "lucide"},
        "Mintlify icon library must be Lucide",
    )

    referenced_icons: set[str] = set()
    for group in groups:
        icon = group.get("icon")
        require(isinstance(icon, str), f"{group.get('group')} navigation group has no icon")
        referenced_icons.add(icon)

    for relative in PAGES:
        text = (PUBLIC / relative).read_text()
        metadata = frontmatter(text, relative)
        page_icon = re.search(r'^icon: "([^"]+)"$', metadata, re.MULTILINE)
        require(page_icon is not None, f"{relative} has no sidebar icon")
        assert page_icon is not None
        referenced_icons.add(page_icon.group(1))
        referenced_icons.update(re.findall(r'\bicon="([^"]+)"', text))

    require(
        referenced_icons == APPROVED_LUCIDE_ICONS,
        "Mintlify Lucide icon contract changed: "
        f"missing={sorted(APPROVED_LUCIDE_ICONS - referenced_icons)}, "
        f"unapproved={sorted(referenced_icons - APPROVED_LUCIDE_ICONS)}",
    )


def check_config() -> None:
    require(CONFIG.is_file(), "Mintlify docs.json is missing")
    config = json.loads(CONFIG.read_text())
    require(config.get("$schema") == "https://mintlify.com/docs.json", "Mintlify schema authority changed")
    require(config.get("theme") == "maple", "Mintlify theme changed")
    require(config.get("name") == "Kast", "Mintlify project name changed")
    require(
        config.get("description") == "Compiler-grounded evidence for exact Kotlin repositories.",
        "Mintlify site description changed",
    )
    require(
        config.get("colors")
        == {"primary": "#3154C7", "light": "#27439F", "dark": "#8FA7FF"},
        "Mintlify color contract changed",
    )
    require(
        config.get("background")
        == {
            "color": {"light": "#FBFBF9", "dark": "#171816"},
        },
        "Mintlify background contract changed",
    )
    require(config.get("fonts") == {"family": "SUSE"}, "Mintlify typography contract changed")
    require(
        config.get("appearance") == {"default": "light", "strict": False},
        "Mintlify appearance contract changed",
    )
    require("gradient" not in CONFIG.read_text().lower(), "Mintlify background uses a gradient")

    tabs = config.get("navigation", {}).get("tabs")
    require(isinstance(tabs, list), "Mintlify navigation tabs are missing")
    navigation: dict[str, dict[str, list[str]]] = {}
    groups: list[dict[str, object]] = []
    for tab in tabs:
        tab_name = tab.get("tab")
        tab_groups = tab.get("groups")
        require(isinstance(tab_name, str), "Mintlify navigation tab has no name")
        require(isinstance(tab_groups, list), f"{tab_name} navigation groups are missing")
        groups.extend(tab_groups)
        navigation[tab_name] = {group["group"]: group["pages"] for group in tab_groups}
    require(navigation == EXPECTED_NAVIGATION, f"unexpected Mintlify navigation: {navigation}")
    destinations = [
        path
        for tab_groups in navigation.values()
        for pages in tab_groups.values()
        for path in pages
    ]
    expected_destinations = [
        page_route(relative) for relative in PAGES if relative not in DEFERRED_PAGES
    ]
    require(
        len(destinations) == len(set(destinations))
        and set(destinations) == set(expected_destinations),
        f"navigation and page contract differ: {destinations}",
    )
    check_icon_system(config, groups)

    navbar = config.get("navbar", {})
    require(
        navbar.get("links") == [{"label": "GitHub", "href": "https://github.com/amichne/kast"}],
        "Mintlify GitHub navigation changed",
    )
    require(
        navbar.get("primary")
        == {"type": "button", "label": "Install Kast", "href": "/start"},
        "Mintlify primary navigation action changed",
    )
    require(
        config.get("footer", {}).get("socials", {}).get("github")
        == "https://github.com/amichne/kast",
        "Mintlify footer GitHub link changed",
    )
    require(
        config.get("footer", {}).get("links")
        == [
            {
                "header": "Use Kast",
                "items": [
                    {"label": "Quickstart", "href": "/start"},
                    {"label": "CLI reference", "href": "/reference/cli"},
                ],
            },
            {
                "header": "Understand Kast",
                "items": [
                    {"label": "Trust the evidence", "href": "/concepts/evidence-boundaries"},
                    {"label": "Technical specification", "href": "/technical-specification"},
                ],
            },
            {
                "header": "Source",
                "items": [
                    {"label": "GitHub", "href": "https://github.com/amichne/kast"},
                    {
                        "label": "MIT license",
                        "href": "https://github.com/amichne/kast/blob/main/LICENSE",
                    },
                ],
            },
        ],
        "Mintlify footer navigation changed",
    )
    require(
        config.get("contextual")
        == {
            "options": ["copy", "view", "chatgpt", "claude", "cursor"],
            "display": "header",
        },
        "Mintlify contextual actions changed",
    )
    require(
        config.get("search", {}).get("prompt")
        == "Search Kast documentation",
        "Mintlify search prompt changed",
    )
    require(config.get("metadata", {}).get("timestamp") is True, "page timestamps are disabled")

    require(MINTIGNORE.is_file(), "Mintlify publication exclusions are missing")
    ignored = {
        line
        for line in MINTIGNORE.read_text().splitlines()
        if line and not line.startswith("#")
    }
    require(
        ignored
        == {
            "architecture/likec4.config.json",
            "architecture/model.c4",
            "architecture/specification.c4",
            "architecture/views.c4",
        },
        f"unexpected Mintlify architecture exclusions: {sorted(ignored)}",
    )

    obsolete = [
        ROOT / "zensical.toml",
        ROOT / "requirements-docs.txt",
        ROOT / "docs/build_public_site.py",
    ]
    require(not any(path.exists() for path in obsolete), "obsolete Zensical authority remains")


def check_deployment() -> None:
    require(DOCS_WORKFLOW.is_file(), "documentation workflow is missing")
    workflow = DOCS_WORKFLOW.read_text()
    required = (
        "name: Documentation",
        "pull_request:",
        "push:",
        "workflow_dispatch:",
        "python3 docs/test_public_docs.py",
        "python3 docs/tooling/likec4/generate_bundle.py --check",
        "npm install --global mint@4.2.841",
        "mint validate",
        "working-directory: docs/public",
    )
    for token in required:
        require(token in workflow, f"documentation workflow is missing {token}")
    for obsolete in ("upload-pages-artifact", "deploy-pages", "pages: write", "id-token: write"):
        require(obsolete not in workflow, f"GitHub Pages deployment remains: {obsolete}")


def check_pages() -> None:
    reader_pages = {
        page.relative_to(PUBLIC).as_posix()
        for page in PUBLIC.rglob("*.mdx")
        if page.name != "AGENTS.md"
    }
    require(reader_pages == set(PAGES), f"unexpected public MDX surface: {sorted(reader_pages)}")

    for relative, phrases in PAGES.items():
        page = PUBLIC / relative
        require(page.is_file(), f"missing page: {relative}")
        text = page.read_text()
        metadata = frontmatter(text, relative)
        check_okf_frontmatter(metadata, relative)
        if relative in DEFERRED_PAGES:
            require("hidden: true" in metadata, f"{relative} must stay out of navigation")
            require("noindex: true" in metadata, f"{relative} must not be indexed")
        for phrase in phrases:
            require(phrase in text, f"{relative} is missing {phrase!r}")
        lowered = text.lower()
        for phrase in FORBIDDEN_PROSE:
            require(phrase not in lowered, f"{relative} contains forbidden prose {phrase!r}")
        for raw_html in ("<span", "<script"):
            require(raw_html not in text, f"{relative} retains legacy site markup {raw_html}")
        for claim in STALE_CLAIMS:
            require(claim not in text, f"{relative} contains stale claim {claim!r}")
        check_scan_friendly_prose(relative, text, has_frontmatter=True)

    index = (PUBLIC / "index.mdx").read_text()
    metadata = frontmatter(index, "index.mdx")
    require('mode: "wide"' in metadata, "homepage does not use Mintlify wide mode")
    require(
        index.count("](/questions/") == 4,
        "homepage does not expose the four supported question guides",
    )
    order = [index.index(phrase) for phrase in PAGES["index.mdx"]]
    require(order == sorted(order), "homepage reader journey is out of order")

    check_readme()
    check_install_command()
    check_documented_host_contract()
    check_installed_capability_contract()
    check_authored_cli_commands()
    check_generated_cli_reference()
    check_internal_links()
    check_source_links()
    check_workspace_examples()
    check_technical_specification()


def catalog_version(catalog: str, key: str) -> str:
    match = re.search(rf'^{re.escape(key)} = "([^"]+)"$', catalog, re.MULTILINE)
    require(match is not None, f"version catalog has no {key!r}")
    assert match is not None
    return match.group(1)


def check_documented_host_contract() -> None:
    require(VERSION_CATALOG.is_file(), "Gradle version catalog is missing")
    require(INSTALLER.is_file(), "installer authority is missing")
    require(IDE_RUNTIME_COMPATIBILITY_AUTHORITY.is_file(), "IDE compatibility authority is missing")
    require(IDE_RUNTIME_DISCOVERY_PROOF.is_file(), "IDE compatibility proof is missing")
    require(LIFECYCLE_COMMAND_AUTHORITY.is_file(), "lifecycle command authority is missing")
    catalog = VERSION_CATALOG.read_text()
    installer = INSTALLER.read_text()
    compatibility = IDE_RUNTIME_COMPATIBILITY_AUTHORITY.read_text()
    discovery_proof = IDE_RUNTIME_DISCOVERY_PROOF.read_text()
    lifecycle_commands = LIFECYCLE_COMMAND_AUTHORITY.read_text()
    idea_builds = {
        catalog_version(catalog, "idea-indexer"),
        catalog_version(catalog, "idea-platform-build"),
        catalog_version(catalog, "ide-host-build"),
    }
    require(len(idea_builds) == 1, f"IDE build authorities disagree: {sorted(idea_builds)}")
    idea_build = next(iter(idea_builds))
    kotlin_build = catalog_version(catalog, "ide-kotlin-plugin-build")
    idea_release_line = idea_build.partition(".")[0]
    kotlin_release_line = kotlin_build.partition(".")[0]
    require(
        idea_release_line == kotlin_release_line,
        "documented IDEA and Kotlin reference builds use different platform release lines",
    )
    require(
        "idea.releaseLine == observed.idea.releaseLine" in compatibility
        and "kotlinPlugin.releaseLine == observed.kotlinPlugin.releaseLine" in compatibility,
        "IDE compatibility no longer admits by independent platform release line",
    )
    require(
        "compatible patch builds retain their exact installed runtime identity" in discovery_proof,
        "IDE compatible-patch proof is missing",
    )
    require(
        "Release-line-compatible local IntelliJ IDEA home." in lifecycle_commands,
        "--idea-home help overstates patch-build specificity",
    )
    java_match = re.search(r"\(\( java_major >= ([0-9]+) \)\)", installer)
    require(java_match is not None, "installer Java requirement is unreadable")
    assert java_match is not None
    java_major = java_match.group(1)
    require('[[ "$(uname -s)" == "Darwin" ]]' in installer, "installer macOS gate changed")
    require("arm64|aarch64)" in installer, "installer architecture gate changed")

    expected = (
        "macOS on Apple silicon",
        f"Java {java_major} or newer",
        "on-disk Kotlin Gradle repository",
    )
    reference_claim = (
        f"Reference pair: IDEA build {idea_build} and Kotlin plugin build {kotlin_build}."
    )
    compatibility_claim = (
        "Compatible patch builds are accepted when IDEA and Kotlin plugin both remain on "
        f"JetBrains platform release line {idea_release_line}."
    )
    for relative, text in {
        "README.md": README.read_text(),
        "docs/public/start.mdx": (PUBLIC / "start.mdx").read_text(),
    }.items():
        normalized = " ".join(text.split())
        for claim in expected:
            require(claim in normalized, f"{relative} does not match host authority: {claim}")
        require(reference_claim in normalized, f"{relative} does not label the release reference pair")
        require(
            compatibility_claim in normalized,
            f"{relative} does not document release-line-compatible patch builds",
        )


def check_generated_cli_reference() -> None:
    require(CLI_REFERENCE_GENERATOR.is_file(), "CLI reference generator is missing")
    completed = subprocess.run(
        [sys.executable, str(CLI_REFERENCE_GENERATOR), "--check"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    require(
        completed.returncode == 0,
        "generated CLI reference is stale: "
        f"{completed.stdout.strip()} {completed.stderr.strip()}".strip(),
    )


def check_internal_links() -> None:
    known_routes = {public_url_route(relative) for relative in PAGES}
    link_pattern = re.compile(r'\]\((/[^)\s#?]*)(?:[?#][^)]*)?\)|\bhref="(/[^"#?]*)')
    for relative in PAGES:
        text = (PUBLIC / relative).read_text()
        for match in link_pattern.finditer(text):
            destination = (match.group(1) or match.group(2)).rstrip("/") or "/"
            asset = PUBLIC / destination.removeprefix("/")
            require(
                destination in known_routes or asset.is_file(),
                f"{relative} links to unknown internal destination {destination}",
            )


def check_source_links() -> None:
    path_pattern = re.compile(
        r"https://github\.com/amichne/kast/(?:blob|tree)/main/([^)#\s]+)"
    )
    symbol_pattern = re.compile(
        r"\[\`([^`]+)\`\]\(https://github\.com/amichne/kast/blob/main/([^)#]+)\)"
    )
    for relative in PAGES:
        text = (PUBLIC / relative).read_text()
        for path in path_pattern.findall(text):
            require((ROOT / path).exists(), f"{relative} links to missing source {path}")
        for symbol, path in symbol_pattern.findall(text):
            if symbol == Path(path).name:
                continue
            source = (ROOT / path).read_text()
            require(
                re.search(rf"\b{re.escape(symbol)}\b", source) is not None,
                f"{relative} names missing symbol {symbol!r} in {path}",
            )


def check_workspace_examples() -> None:
    text = (PUBLIC / "questions/workspace-readiness.mdx").read_text()
    examples = [json.loads(block) for block in re.findall(r"```json\n(.*?)\n\s*```", text, re.DOTALL)]
    require(
        examples
        == [
            {
                "command": "start",
                "status": "complete",
                "runtime": "running",
                "root": "/path/to/kotlin-repository",
                "runtimeId": "kast-sidecar",
                "removed": [],
            },
        ],
        f"workspace CLI examples changed: {examples}",
    )


def check_technical_specification() -> None:
    technical_pages = set(TECHNICAL_SPECIFICATION_AUTHORITIES)
    require(
        technical_pages == {relative for relative in PAGES if relative.startswith("technical-specification/")},
        "technical specification page and authority contracts differ",
    )

    for relative, authorities in TECHNICAL_SPECIFICATION_AUTHORITIES.items():
        text = (PUBLIC / relative).read_text()
        require("## Primary authorities" in text, f"{relative} has no primary-authority boundary")
        for authority in authorities:
            require((ROOT / authority).is_file(), f"technical authority does not exist: {authority}")
            url = f"https://github.com/amichne/kast/blob/main/{authority}"
            require(url in text, f"{relative} does not cite {authority}")

    for relative in technical_pages:
        text = (PUBLIC / relative).read_text()
        expected = ARCHITECTURE_EMBEDS.get(relative)
        if expected is None:
            require("data-kast-view" not in text, f"{relative} contains an uncontracted architecture view")
            require("<div" not in text, f"{relative} contains uncontracted raw HTML")
        else:
            require(text.count("data-kast-view") == 1, f"{relative} must contain exactly one architecture view")
            require(expected in text, f"{relative} has the wrong architecture view contract")


def check_readme() -> None:
    require(README.is_file(), "root README.md is missing")
    text = README.read_text()
    required = [
        "# Kast",
        "https://kast.michne.com/",
        "https://raw.githubusercontent.com/amichne/kast/${release_tag}/install.sh",
        "bundled Java 25 JBR",
        "matched private sidecar",
        "kast-control-*.tar.gz",
        "kast-semantic-runtime-*.zip",
        "KAST_RUNTIME_STORE",
        "eleven public semantic operations",
        "kast start",
        "kast symbol inspect",
        "kast --schema",
        "```mermaid",
        "| Question | Command path |",
        "Kotlin control executable",
        "Local wire RPC",
        "Complete, qualified, or rejected JSON",
        "./gradlew assembleSidecarRelease",
        "installs the control launcher and matched private sidecar",
        "mint validate",
        "mint dev",
        "[MIT License](LICENSE)",
    ]
    for phrase in required:
        require(phrase in text, f"README.md is missing {phrase!r}")

    for command in (
        "kast relation read",
        "kast diagnostic check",
    ):
        require(command in text, f"README.md omits public command {command!r}")
    for claim in STALE_CLAIMS:
        require(claim not in text, f"README.md contains stale claim {claim!r}")
    check_scan_friendly_prose("README.md", text, has_frontmatter=False)

    headings = re.findall(r"^#\s+.+$", text, re.MULTILINE)
    require(headings == ["# Kast"], f"README.md has unexpected H1 headings: {headings}")
    require(not text.startswith("---\n"), "README.md contains site frontmatter")
    require("<div class=" not in text, "README.md contains site-only HTML components")

    public_routes = {
        "https://kast.michne.com/"
        if relative == "index.mdx"
        else f"https://kast.michne.com/{page_route(relative)}/"
        for relative in PAGES
    }
    readme_routes = set(re.findall(r"https://kast\.michne\.com/[^)\s]*", text))
    unknown_routes = sorted(readme_routes - public_routes)
    require(not unknown_routes, f"README.md links to unknown public routes: {unknown_routes}")

    lowered = text.lower()
    for phrase in FORBIDDEN_PROSE:
        require(phrase not in lowered, f"README.md contains forbidden prose {phrase!r}")


def check_install_command() -> None:
    sources = {
        "README.md": README.read_text(),
        "docs/public/start.mdx": (PUBLIC / "start.mdx").read_text(),
        "docs/public/reference/compatibility.mdx": (PUBLIC / "reference/compatibility.mdx").read_text(),
    }
    for path, text in sources.items():
        require(not re.search(r"kast/v[0-9]+\.[0-9]+\.[0-9]+/install\.sh", text), f"{path} pins a stale installer release")
        require(INSTALL_COMMAND in "\n".join(line.lstrip() for line in text.splitlines()), f"{path} is missing the release-resolving installer")


def check_installed_capability_contract() -> None:
    require(GENERATED_OPERATION_REGISTRY.is_file(), "generated operation registry is missing")
    registry = json.loads(GENERATED_OPERATION_REGISTRY.read_text())
    require(registry.get("schemaVersion") == 2, "operation registry schema changed")
    operations = registry.get("operations")
    require(isinstance(operations, list), "operation registry metadata is unreadable")
    hosted = [
        operation["operationId"]
        for operation in operations
        if operation.get("hostedExposure") == "public"
    ]
    require(
        hosted
        == [
            "index.sync",
            "topology.build",
            "symbol.discover",
            "symbol.inspect",
            "source.read",
            "relation.read",
            "traversal.run",
            "diagnostic.check",
            "change.plan",
            "change.apply",
            "change.recover",
        ],
        f"installed sidecar capability surface changed: {hosted}",
    )
    internal = [
        operation["operationId"]
        for operation in operations
        if operation.get("hostedExposure") == "internal_only"
    ]
    require(
        internal == [],
        f"internal sidecar service surface changed: {internal}",
    )

    canonical_source = CANONICAL_OPERATION_AUTHORITY.read_text()
    canonical = re.findall(r'canonicalOperationId\("([a-z.]+)"\)', canonical_source)
    require(len(canonical) == 11, f"canonical operation surface changed: {canonical}")
    reference = (PUBLIC / "reference/cli.mdx").read_text()
    for operation in hosted:
        require(
            f"`{operation}`" in reference,
            f"sidecar reference section omits {operation}",
        )
    require(
        "## Canonical operations without a direct sidecar route" not in reference,
        "reference retains a no-sidecar-route section for an all-public registry",
    )

    lifecycle_source = SIDECAR_LIFECYCLE_AUTHORITY.read_text()
    require(
        "class ExactRootRuntimeLifecycle" in lifecycle_source,
        "sidecar lifecycle implementation is missing",
    )
    require(
        "RuntimeProcessObservation.Owned" in lifecycle_source,
        "sidecar stop no longer requires exact process ownership",
    )
    require(
        "InactiveRuntimeEndpoint.afterObservedAbsence" in lifecycle_source,
        "sidecar clean no longer requires proven endpoint inactivity",
    )


def cli_command_key(match: re.Match[str]) -> str:
    flag = match.group(1)
    if flag is not None:
        return f"kast {flag}"
    group = match.group(2)
    command = match.group(3)
    return f"kast {group}" if command is None else f"kast {group} {command}"


def check_authored_cli_commands() -> None:
    reference = (PUBLIC / "reference/cli.mdx").read_text()
    command_pattern = re.compile(
        r"\bkast[ \t]+(?:(--[a-z-]+)|([a-z]+)(?:[ \t]+([a-z]+))?)"
    )
    reference_commands = {
        cli_command_key(match) for match in command_pattern.finditer(reference)
    }

    authored_sources = {
        relative: (PUBLIC / relative).read_text()
        for relative in PAGES
        if relative != "reference/cli.mdx"
    }
    authored_sources["README.md"] = README.read_text()

    for relative, text in authored_sources.items():
        authored_commands = {
            cli_command_key(match) for match in command_pattern.finditer(text)
        }
        unknown = sorted(authored_commands - reference_commands)
        require(
            not unknown,
            f"{relative} contains commands absent from generated CLI reference: {unknown}",
        )


def check_architecture_sources() -> None:
    architecture = PUBLIC / "architecture"
    views = (architecture / "views.c4").read_text()
    require("dynamic view runtime-flow" in views, "runtime-flow view is missing")
    require("view module-ownership" in views, "module-ownership view is missing")
    require("navigateTo" not in views, "architecture source still defines dialog navigation")
    bundle = (PUBLIC / "likec4-views.js").read_text()
    require(bundle.startswith("// kast-likec4-lock-sha256:"), "LikeC4 bundle provenance is missing")
    require("customElements.define(" in bundle, "LikeC4 web component bundle is incomplete")
    require(
        not any(path.name.startswith("likec4-views.") for path in architecture.iterdir()),
        "nested LikeC4 bundle remains outside Mintlify's custom-script boundary",
    )

    mount = (PUBLIC / "likec4-mount.js").read_text()
    for token in (
        'customElements.whenDefined("kast-view")',
        'document.querySelectorAll("[data-kast-view]")',
        "MutationObserver",
        'document.createElement("kast-view")',
    ):
        require(token in mount, f"LikeC4 mount bridge is missing {token}")
    stylesheet = (PUBLIC / "kast-architecture.css").read_text()
    require(".kast-architecture-view" in stylesheet, "LikeC4 view sizing is missing")
    require(
        "--likec4-view-max-width: 28rem" in stylesheet,
        "runtime architecture view has no readable desktop width bound",
    )
    require(
        ".kast-architecture-view-modules" in stylesheet
        and "--likec4-view-max-width: 100%" in stylesheet,
        "module architecture view does not preserve its wide intrinsic layout",
    )
    require("min-block-size" not in stylesheet, "architecture view retains forced blank height")


def main() -> None:
    arguments = sys.argv[1:]
    if arguments:
        require(arguments[0] == "--impact", "only --impact accepts command-line arguments")
        report_okf_impact(arguments[1:])
        return
    check_config()
    check_deployment()
    check_pages()
    check_architecture_sources()
    print("public-docs: Mintlify content and deployment contract passed")


if __name__ == "__main__":
    main()
