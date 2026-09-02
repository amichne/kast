#!/usr/bin/env python3
"""Check the Mintlify documentation structure, claims, and deployment contract."""

from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "docs/public"
CONFIG = PUBLIC / "docs.json"
MINTIGNORE = PUBLIC / ".mintignore"
README = ROOT / "README.md"
DOCS_WORKFLOW = ROOT / ".github/workflows/docs.yml"
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
INSTALL_COMMAND = (
    "curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh | bash"
)
APPROVED_LUCIDE_ICONS = {
    "activity",
    "badge-check",
    "boxes",
    "braces",
    "circle-check",
    "circle-dashed",
    "code-xml",
    "compass",
    "copy-check",
    "file-pen",
    "folder-key",
    "folder-tree",
    "gauge",
    "git-pull-request-create",
    "monitor-check",
    "network",
    "package-check",
    "power",
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
        "The compiler sees more than text",
        "Start from the repository root",
        "Thirteen sidecar operations",
        "What is Kast ready to inspect?",
        "What declaration is this?",
        "How is this code connected?",
        "How can I add a declaration safely?",
        "Evidence keeps its boundary",
    ],
    "start.mdx": [
        "Java 25 or newer",
        "IntelliJ IDEA build 262.9437.185",
        "https://raw.githubusercontent.com/amichne/kast/main/install.sh",
        "rejects unsafe archive paths",
        "does not edit your shell profile",
        "kast start --seed-from-idea",
        "kast --schema",
        "thirteen operations",
        "kast index sync",
        "OpenTelemetry traces",
        "relation reads",
        "diagnostics",
        "kast start",
        "kast status",
        "<Steps>",
        "<AccordionGroup>",
    ],
    "questions/workspace-readiness.mdx": [
        "kast workspace inspect",
        "exact root",
        "<Columns cols={3}>",
    ],
    "questions/declaration-identity.mdx": [
        "kast symbol discover",
        "kast symbol resolve",
        "kast symbol describe",
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
    ],
    "questions/safe-change.mdx": [
        "add-declaration",
        "kast change plan",
        "kast change apply",
        "kast change verify",
        "kast change recover",
        "without restarting",
    ],
    "concepts/evidence-boundaries.mdx": [
        "Complete",
        "Qualified",
        "Rejected",
        "generation",
        "thirteen sidecar operations",
        "<Columns cols={3}>",
    ],
    "explanation/how-kast-works.mdx": [
        "exact thirteen-operation public capability set",
        "Kotlin control executable",
        "SymbolDescribeRequestDocument",
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
        "Topology",
        "Diagnostics",
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
    "reference/cli.mdx": [
        "Generated by docs/generate_cli_reference.py",
        "documentation check fails",
        "Sidecar endpoint operations",
        "Kast owns the isolated sidecar lifecycle",
        "relation.read",
        "diagnostic.check",
        "Process-local commands",
        "product inspect",
        "broker serve",
        "Default local traces",
        "index.sync",
        "workspace.inspect",
        "change.recover",
    ],
}

EXPECTED_NAVIGATION = {
    "Get started": ["index", "start"],
    "Guides": [
        "questions/workspace-readiness",
        "questions/declaration-identity",
        "questions/code-connections",
        "questions/semantic-validity",
        "questions/safe-change",
    ],
    "Concepts": [
        "concepts/evidence-boundaries",
        "explanation/how-kast-works",
    ],
    "Technical specification": [
        "technical-specification/index",
        "technical-specification/runtime-boundary",
        "technical-specification/protocol-and-dispatch",
        "technical-specification/semantic-services",
        "technical-specification/change-and-evidence",
        "technical-specification/module-architecture",
        "technical-specification/verification-and-contribution",
    ],
    "Reference": ["reference/cli"],
}

TECHNICAL_SPECIFICATION_AUTHORITIES = {
    "technical-specification/index.mdx": [
        "AGENTS.md",
        "docs/public/architecture/model.c4",
        "runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/bootstrap/InstalledRuntimeAssembly.kt",
    ],
    "technical-specification/runtime-boundary.mdx": [
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
]

DEFERRED_PAGES: set[str] = set()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def page_route(relative: str) -> str:
    return relative.removesuffix(".mdx")


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
    require(config.get("colors", {}).get("primary") == "#4F46E5", "Mintlify primary color changed")

    groups = config.get("navigation", {}).get("groups")
    require(isinstance(groups, list), "Mintlify navigation groups are missing")
    navigation = {group["group"]: group["pages"] for group in groups}
    require(
        "Technical specification" in navigation,
        "technical specification navigation is missing",
    )
    require(navigation == EXPECTED_NAVIGATION, f"unexpected Mintlify navigation: {navigation}")
    destinations = [path for pages in navigation.values() for path in pages]
    expected_destinations = [
        page_route(relative) for relative in PAGES if relative not in DEFERRED_PAGES
    ]
    require(
        destinations == expected_destinations,
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
        == {"type": "button", "label": "Get started", "href": "/start"},
        "Mintlify primary navigation action changed",
    )
    require(
        config.get("footer", {}).get("socials", {}).get("github")
        == "https://github.com/amichne/kast",
        "Mintlify footer GitHub link changed",
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

    index = (PUBLIC / "index.mdx").read_text()
    metadata = frontmatter(index, "index.mdx")
    require('mode: "wide"' in metadata, "homepage does not use Mintlify wide mode")
    require(
        index.count('href="/questions/') == 4,
        "homepage does not expose the four supported question guides",
    )
    order = [index.index(phrase) for phrase in PAGES["index.mdx"]]
    require(order == sorted(order), "homepage reader journey is out of order")

    check_readme()
    check_install_command()
    check_installed_capability_contract()
    check_authored_cli_commands()
    check_technical_specification()


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
        "https://raw.githubusercontent.com/amichne/kast/main/install.sh",
        "Kast supports macOS on Apple silicon, Java 25 or newer",
        "matched private sidecar",
        "thirteen public semantic operations",
        "kast start",
        "kast workspace inspect",
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
    }
    for path, text in sources.items():
        require(INSTALL_COMMAND in text, f"{path} is missing the one-command installer")


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
            "workspace.inspect",
            "index.sync",
            "topology.build",
            "symbol.discover",
            "symbol.resolve",
            "symbol.describe",
            "relation.read",
            "traversal.run",
            "diagnostic.check",
            "change.plan",
            "change.apply",
            "change.verify",
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
    require(len(canonical) == 13, f"canonical operation surface changed: {canonical}")
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


def main() -> None:
    check_config()
    check_deployment()
    check_pages()
    check_architecture_sources()
    print("public-docs: Mintlify content and deployment contract passed")


if __name__ == "__main__":
    main()
