# Tekmor Clean-Break Rename Design

**Status:** Approved

**Date:** 2026-07-12

## Reader contract

This specification is the normative input for a fresh implementation task. The
reader is an agent starting from remote `main` after this document has landed.
The reader must be able to plan and execute the rename without access to the
conversation that produced it.

The implementation task is:

> Rename the complete current Kast product identity to Tekmor, preserve product
> behavior, reject compatibility aliases, validate every owned distribution
> lane, merge the repository cutover, and finish the external GitHub,
> documentation-domain, Homebrew, action, and release cutover.

A suitable fresh-task prompt is:

> Implement `.agents/superpowers/specs/2026-07-12-tekmor-clean-break-rename-design.md`
> from the current `origin/main`. Treat it as the normative rename contract,
> write the implementation plan first, preserve unrelated work, and carry the
> repository and external cutover through the specification's terminal
> acceptance criteria.

Read the nearest `AGENTS.md` before editing any subtree. Read the current public
surface ADRs before writing an implementation plan. Use the repository's
compiler-backed semantic tooling for Kotlin identity changes after the selected
worktree has been prepared by the IntelliJ plugin.

## Decision

The project name is **Tekmor**, pronounced **TEK-mor**. The command, package,
repository, configuration, and distribution stem is `tekmor`.

The rename is a clean break. Tekmor is not a display-name alias for Kast and
does not ship a transition period in which both identities are supported. The
implementation preserves capabilities and behavior while replacing the product
identity at every current, source-owned boundary.

The approved product description is:

> Tekmor is an agent-first, compiler-backed Kotlin and Gradle semantic control
> plane.

The approved short positioning line is:

> Compiler-backed code intelligence for agents.

The approved supporting line is:

> Know what the code means before you change it.

These lines replace current Kast positioning where that positioning is public
or current. They do not require adding marketing copy to internal contracts.

## Considered approaches

### Approved: atomic repository cutover followed by external cutover

All repository-owned current identity changes land in one PR so `main` never
contains a knowingly mixed Kast/Tekmor runtime. After that PR is green and
merged, the operator performs the GitHub repository, hosted documentation,
Homebrew, sibling action, and first-release cutover in the order defined below.

This approach keeps compiler, protocol, installer, docs, and packaging
contracts reviewable as one transition while separating source changes from
external mutations that cannot be represented in Git.

### Rejected: cosmetic rebrand

Changing only prose, the logo, or the GitHub repository name would leave the
`kast` binary, Kotlin namespaces, state paths, plugin identity, managed skill,
and release artifacts authoritative. That would create two product identities
and fail the clean-break requirement.

### Rejected: dual-name compatibility bridge

Shipping `kast` and `tekmor` binaries, reading `KAST_*` and `TEKMOR_*`
environment variables, accepting both managed fences, or migrating old state
inside Tekmor would make the old identity part of Tekmor's supported contract.
No compatibility window, alias binary, fallback environment variable, dual
plugin ID, dual protocol namespace, or automatic state migration is allowed.

## Starting state and thread bootstrap

The specification was written immediately after PR #329 merged to `main`. The
observed baseline commit was:

```text
d56b67bf460281176c66317eccd2b8679dc13674
```

That SHA is provenance, not a pin. A fresh task must use the current remote
`main` containing this specification:

```console
git fetch origin main
git status --short --branch
git worktree add .worktrees/tekmor-rename -b feature/tekmor-rename origin/main
```

If the branch or worktree already exists, inspect and reuse it only when it is
clean and its starting point is the current `origin/main`. Do not reset,
overwrite, or stash unrelated work.

Before edits, run:

```console
./gradlew test
cargo test --manifest-path cli-rs/Cargo.toml --locked
```

On macOS, do not run `kast setup`. Open the selected worktree in IntelliJ IDEA
or Android Studio with the currently installed Kast plugin enabled, then run:

```console
kast agent verify --workspace-root "$PWD"
```

The implementation may use the currently installed `kast` command as the
semantic tool that performs its own source rename. That tool is build-time
machinery for this migration; it is not a compatibility surface in the Tekmor
result.

## Hard requirements

1. The finished current product identity is Tekmor/`tekmor` everywhere it is
   source-owned.
2. Product behavior, supported workflows, plan-first mutation rules, AXI
   output, and runtime boundaries remain unchanged unless this specification
   explicitly names an identity-shaped change.
3. Repository-owned changes land atomically in one PR. Intermediate commits may
   be temporarily inconsistent, but every commit must be intentional and the
   PR head must be self-consistent.
4. No runtime compatibility is provided for `kast`, `KAST_*`, `.kast`, the old
   plugin ID, the old managed fence, or old artifact names.
5. Existing Kast configuration, install state, workspace metadata, caches, and
   plugin state are not read or translated by Tekmor.
6. Users uninstall Kast and install Tekmor as separate products. Documentation
   may give explicit uninstall and cleanup commands, but Tekmor code must not
   silently adopt Kast state.
7. Historical records remain historically accurate. Do not rewrite Git
   history, release tags, accepted historical ADRs, completed specs/plans, or
   captured records merely to remove the old name.
8. Current source-of-truth files are edited first. Generated artifacts are
   regenerated through their owning generators and contract checks.
9. Kotlin packages and types move through compiler-checked slices. Do not use
   casts, suppressions, compatibility type aliases, or reflection to bypass
   compiler failures.
10. The first Tekmor release is `v0.13.0`. Release lineage continues from Kast;
    versions do not restart.

## Canonical identity mapping

The following mappings are mandatory unless a later section explicitly marks a
surface as historical:

| Kast identity | Tekmor identity |
| --- | --- |
| `Kast` | `Tekmor` |
| `kast` | `tekmor` |
| `KAST_` environment or constant prefix | `TEKMOR_` |
| `io.github.amichne.kast` | `io.github.amichne.tekmor` |
| `Kast*` product-prefixed Kotlin/Rust types | `Tekmor*` |
| `kast*` product-prefixed functions, fields, and modules | `tekmor*` |
| `amichne/kast` | `amichne/tekmor` |
| `https://kast.michne.com` | `https://tekmor.michne.com` |
| `kast` CLI and Homebrew formula | `tekmor` |
| `kast-plugin` Homebrew cask | `tekmor-plugin` |
| `kast-action` / `amichne/kast-action` | `tekmor-action` / `amichne/tekmor-action` |
| `.kast` workspace state | `.tekmor` |
| `.agents/skills/kast` | `.agents/skills/tekmor` |
| `cli-rs/resources/kast-skill` | `cli-rs/resources/tekmor-skill` |
| `<kast>...</kast>` | `<tekmor>...</tekmor>` |
| `kastMethods` LSP capability | `tekmorMethods` |
| `kast/*` custom LSP methods | `tekmor/*` |
| `x-kast-*` OpenAPI extensions | `x-tekmor-*` |
| `kast-*.sock` | `tekmor-*.sock` |
| `.gradle/kast` product cache | `.gradle/tekmor` |
| `.config/kast` | `.config/tekmor` |
| `.cache/kast` | `.cache/tekmor` |
| `.local/share/kast` | `.local/share/tekmor` |
| `.local/state/kast` | `.local/state/tekmor` |
| `KastSettings` / `kast.xml` IDEA state | `TekmorSettings` / `tekmor.xml` |
| `KastHeadless` IDEA selector | `TekmorHeadless` |
| `kast.svg` | `tekmor.svg` |

Apply the mapping semantically. Do not replace unrelated words merely because
they contain the byte sequence `kast`, and do not alter arbitrary user fixture
repository names when they are not product identities.

## Repository-owned scope

### Governance and current guidance

The implementation starts by adding
`.agents/adr/0015-tekmor-clean-break-product-identity.md`. ADR 0015 supersedes
the product-identity portions of ADR 0006 and every later current ADR that names
Kast as the forward product. It must preserve the existing public surface,
system boundaries, AXI rules, source owners, and validation gates under the
Tekmor identity.

Update current guidance, including root and scoped `AGENTS.md` files and
`.agents/docs/documentation-journeys.md`, to point to Tekmor paths and commands.
If source ownership or validation gates change as a consequence of moving
files, update the nearest scoped guidance in the same change.

Do not rewrite older accepted ADRs or completed `.agents/superpowers/specs` and
`.agents/superpowers/plans`. They are historical records. The new ADR and the
implementation plan are the forward records.

### Kotlin and Gradle identity

Rename the complete Kotlin namespace from `io.github.amichne.kast` to
`io.github.amichne.tekmor` in production, test, test-fixture, Java, resources,
reflection strings, service declarations, plugin XML, build scripts, generated
protocol models, and classpath assertions.

Move source directories to match their package declarations. Rename
product-prefixed types such as `KastConfig`, `KastFileOperations`, and IDEA
`Kast*` services to `Tekmor*` using semantic rename operations where available.
Every renamed non-private top-level Kotlin type retains a same-named file in
accordance with ADR 0014.

Keep generic module directory names such as `analysis-api`, `analysis-server`,
`index-store`, `backend-shared`, `backend-headless`, and `backend-idea`. Their
names describe responsibilities rather than the product brand.

Update at least these Gradle and JVM boundaries:

- `rootProject.name`;
- Maven artifact IDs, POM names, URLs, and SCM coordinates;
- build-logic plugin IDs, implementation classes, extension names, fixtures,
  and tests where they are brand-prefixed;
- generated backend-version resource names;
- headless main-class names and classpath assertions;
- IDEA paths selectors, persistent state names, tool-window IDs, settings IDs,
  notification groups, icon paths, and plugin service classes; and
- IDEA plugin ID, name, description, vendor URL, and distribution filename.

The new IDEA plugin ID is `io.github.amichne.tekmor`. The old plugin is not an
alternate ID. Installing Tekmor therefore creates a distinct plugin identity.

The Maven group remains `io.github.amichne`. Rename the root `kast` artifact
and every brand-prefixed `kast-*` artifact to the corresponding `tekmor` or
`tekmor-*` artifact. Do not publish new Tekmor code under old Kast artifact IDs.

### Rust CLI and runtime identity

Rename the Cargo package, default binary, Clap command name, development
binary, metrics benchmark binary, shell completions, help/version output,
installer constants, manifest tool name, runtime descriptors, socket names,
and typed product-prefixed Rust types/functions.

The command is only `tekmor`. Do not install a `kast` shim or command alias.
The development command is `tekmor-dev`.

Rename every supported `KAST_*` environment variable to `TEKMOR_*`, including
build, runtime, install, release, test, eval, Homebrew, tracing, and Linux-bundle
variables. Do not read the old variable as a fallback. Shell scripts, workflow
YAML, tests, examples, and documentation must use the new names.

Rename default state and cache locations to the mappings above. This includes
the install manifest, active version tree, logs, workspace data, source index,
descriptors, sockets, shell integration, backups, and macOS plugin-prepared
workspace metadata.

Tekmor readiness must reject missing Tekmor state honestly. It must not report
Kast state as a partial Tekmor installation.

### Agent resources and managed repository guidance

Rename the packaged skill source to `cli-rs/resources/tekmor-skill` and the
installed skill name/path to `tekmor`. Update its entrypoint, references,
scripts, fixtures, schemas, evals, command catalog descriptions, and package
verification tests.

The only managed guidance fence is `<tekmor>...</tekmor>`. Do not retain
`<kast>`, legacy comment fences, or attribute-style Kast fences as recognized
Tekmor inputs. Tekmor may document that old Kast-managed guidance must be
removed before setup; it must not edit that region as if Tekmor owned it.

Rename the authored package extension under
`cli-rs/resources/plugin/extensions/` and update `plugin.json`,
`primitive-manifest.json`, LSP launch commands, injected runtime guidance, and
package tests. Generated `.github` package copies remain outputs and must be
regenerated from this authored source.

### Protocol, LSP, and generated contracts

Preserve generic internal method families such as `symbol/resolve` and
`workspace/files`. Rename brand-bearing type names, schema descriptions,
capability keys, OpenAPI extensions, and custom LSP method namespaces.

Specifically:

- `capabilities.experimental.kastMethods` becomes
  `capabilities.experimental.tekmorMethods`;
- custom `kast/...` LSP methods become `tekmor/...`;
- `x-kast-required-capability` and related extensions become `x-tekmor-*`;
- `Kast*Response` and other brand-prefixed wire-model names become
  `Tekmor*Response`; and
- result discriminator values that encode the product name, such as
  `KAST_AGENT_COMMAND`, become `TEKMOR_*`.

These are clean-break protocol changes. Do not accept both namespaces or emit
both discriminator families.

Edit the owning Kotlin serialization models and
`cli-rs/resources/tekmor-skill/references/commands.json` first. Regenerate YAML,
request schemas, samples, OpenAPI, Markdown, LSP route metadata, and any release
copies through the existing generators. Do not independently patch generated
copies to make checks pass.

### Installation, packaging, and release artifacts

Rewrite the root `install.sh` identity and rename the brand-bearing shell
wrapper `kast.sh` to `tekmor.sh`. Rename the Homebrew formula, plugin cask,
packaging scripts, release workflow inputs, runtime manifests, schema IDs,
bundle kinds, archive roots, checksums, and artifact filenames.

The target Homebrew commands are:

```console
brew install amichne/tekmor/tekmor
tekmor developer machine plugin
```

The target cask is `amichne/tekmor/tekmor-plugin`.

Release assets use the Tekmor stem, including the macOS CLI zip, IDEA plugin
zip, Linux headless bundle, manifest, and checksums. Runtime manifest `tool`,
`kind`, URL, schema ID, binary path, and backend paths must all agree on Tekmor.
Old Kast artifacts are not valid Tekmor inputs and bundle activation must fail
closed when an artifact identifies itself as Kast.

Keep the existing source-index schema version unless its data schema changes.
Changing a path or product name is not by itself permission to change semantic
index schema behavior.

### Public documentation, site, and demo assets

Update all current README and `docs/` content, `zensical.toml`, navigation
contracts, schemas, examples, command transcripts, image alt text, repository
links, install instructions, troubleshooting paths, and distribution guidance.

The public site is `https://tekmor.michne.com`. Current pages must not link to
the Kast domain after cutover. The root installer URL is under
`raw.githubusercontent.com/amichne/tekmor/main/`.

Rename and regenerate the repository demo recording source and GIF so the
capture shows Tekmor commands, namespaces, paths, and UI. Do not binary-edit the
GIF. Re-record it from the updated source-built product using the existing demo
asset workflow.

`site/` remains generated output. Build it to validate the source docs; do not
hand-edit it or treat it as an independent source of truth.

Historical agent-only records may continue to say Kast. Public current docs may
mention Kast only in a short migration notice that states it is a prior project
name and that no compatibility is provided.

### CI, scripts, evals, and contract names

Rename brand-bearing workflow steps, scripts, test files, plugin-eval packs,
metric IDs, fixture IDs, environment variables, and generated-output paths.
Update every contract that asserts command text, package paths, formula/cask
names, repository URLs, docs links, protocol names, or artifact names.

Semantic eval cases may preserve their underlying questions and expected
behavior, but their current project identity, paths, commands, IDs, and metric
names become Tekmor. Do not lower or delete gates merely to reduce rename work.

## External cutover scope

The repository PR cannot itself perform these operations, but the rename is not
complete until all of them are verified.

### GitHub repository

After the repository PR is green and merged:

1. Rename `amichne/kast` to `amichne/tekmor` in GitHub settings.
2. Update local remotes to the new canonical URL.
3. Verify the default branch is `main`, branch rules still apply, Pages remains
   enabled, Actions permissions remain intact, and release workflows resolve
   repository-relative references.
4. Inventory repository and environment Actions variables or secrets whose
   names or values encode Kast. Create `TEKMOR_*` replacements and update
   workflow references. GitHub does not expose existing secret values; if a
   required value cannot be supplied, report that exact external blocker rather
   than retaining an old secret name or weakening a workflow.
5. Treat GitHub's automatic old-URL redirect as platform behavior, not a
   supported Kast compatibility contract.

### Hosted documentation

Configure GitHub Pages and DNS for `tekmor.michne.com`, including the custom
domain and HTTPS enforcement. Verify representative Start, Install, Learn, Use,
Reference, Troubleshoot, and Distribute pages at the new domain.

Do not leave current docs intentionally dependent on redirects from
`kast.michne.com`.

### Sibling action

Rename the sibling `kast-action` repository and its public identity to
`tekmor-action`. Update its action metadata, inputs, cache keys, artifact names,
documentation, test fixtures, setup commands, and runtime contract against the
Tekmor `v0.13.0` bundle.

The monorepo and sibling action must agree before the first Tekmor release is
declared ready. If the action cannot be cut over in the same launch window,
remove it from current Tekmor documentation and release assertions until it is
available; do not document `kast-action` as a Tekmor installation path.

### First Tekmor release and Homebrew

Cut `v0.13.0` only after the repository, domain, and sibling-action identities
are canonical. The release must publish Tekmor-named assets and update the
Tekmor formula/cask with matching versions and checksums.

Verify from a disposable environment that has no Kast state:

```console
brew tap amichne/tekmor
brew install amichne/tekmor/tekmor
tekmor version
tekmor ready --for machine
```

Then install the matching plugin through the supported developer-machine flow,
open a disposable Gradle/Kotlin workspace, and verify `tekmor agent verify`.

## Cutover order

The required order is:

1. Add the superseding ADR and implementation plan.
2. Implement the repository-owned rename in compiler-checked slices.
3. Regenerate all derived artifacts and recordings.
4. Pass the complete local validation matrix and residual-name audit.
5. Push one focused branch, open one rename PR, and babysit every check.
6. Merge the rename PR to `main`.
7. Rename the GitHub repository to `amichne/tekmor` and verify branch/ruleset
   state.
8. Cut over Pages and DNS to `tekmor.michne.com`.
9. Cut over or temporarily remove the sibling action integration as defined
   above.
10. Cut and verify `v0.13.0`.
11. Verify Homebrew, plugin preparation, headless installation, action use, and
    public docs from disposable environments.

Do not release Tekmor assets from a repository that still presents itself as
Kast. Do not rename the remote repository before the repository-owned PR is
ready to merge; current CI and source links must remain resolvable during
implementation.

## Historical retention and residual-name policy

The old name is expected in:

- Git history and pre-Tekmor tags/releases;
- historical ADRs that accurately record Kast-era decisions;
- completed historical specs and plans;
- an explicit migration note; and
- tests that intentionally prove a Kast artifact or identifier is rejected,
  provided the test names the rejection purpose.

The old name is not allowed in:

- production package/type/function names;
- current CLI, help, output, errors, environment variables, or default paths;
- current plugin metadata or UI;
- current agent guidance or installed resource paths;
- current protocol/LSP/OpenAPI identity;
- current package, bundle, formula, cask, installer, or release names;
- current public docs, schemas, demo assets, or live links; or
- current CI/eval identifiers and scripts, except a narrowly named rejection
  fixture.

The implementation plan must define and run a residual audit over both file
contents and filenames. Any surviving match outside historical or explicit
rejection scope must be reviewed individually; a broad ignore for `.agents`,
tests, or fixtures is not acceptable.

## Validation matrix

Run the narrowest checks during each slice, then run the complete matrix against
the final PR head.

### Kotlin, Gradle, and IDEA

```console
./gradlew test
./gradlew buildIdeaPlugin
./gradlew :analysis-api:test
./gradlew :analysis-server:test
./gradlew :index-store:test
./gradlew :backend-shared:test
./gradlew :backend-headless:test
./gradlew :backend-idea:test
```

Use `tekmor agent diagnostics` against materially changed Kotlin files once the
new source-built plugin prepares the worktree. Before that bootstrap is
possible, use the current compiler and focused Gradle tests as the exhaustive
gate.

### Rust CLI

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
```

### Generated contracts, docs, and packaging

Run the final Tekmor-named equivalents of every existing gate:

```console
cargo run --manifest-path cli-rs/Cargo.toml --bin tekmor -- developer release generate contract --check
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
.github/scripts/test-macos-installer-contract.sh
.github/scripts/test-lsp-pivot-gates.sh
.github/scripts/test-tekmor-copilot-plugin.sh
python3 packaging/homebrew/scripts/test-formulas.py
zensical build --clean
git diff --check
```

Run release workflow contract checks, Ubuntu/Debian disposable-container
validation for every supported base image, Maven publication metadata checks,
IDEA plugin packaging checks, and the sibling action runtime contract.

### Public smoke tests

At minimum, prove these source-built commands:

```console
tekmor --help
tekmor version
tekmor demo --workspace-root "$PWD"
tekmor ready --for agent --workspace-root "$PWD"
tekmor agent verify --workspace-root "$PWD"
tekmor agent symbol --query TekmorConfig --workspace-root "$PWD"
tekmor agent diagnostics --file-path <changed-kotlin-file> --workspace-root "$PWD"
```

Also prove that the build and installers do not create a `kast` binary, Kast
plugin ID, `.kast` state directory, `KAST_*` configuration path, or old managed
fence.

## Acceptance criteria

The rename is complete only when all of the following are true:

- A superseding ADR defines Tekmor as the forward public product.
- Current source, tests, resources, docs, scripts, and generated artifacts obey
  the canonical mapping.
- Kotlin packages and product-prefixed types compile under the Tekmor identity.
- The only installed CLI is `tekmor`; the only development CLI is
  `tekmor-dev`.
- The IDEA plugin ID is `io.github.amichne.tekmor` and its visible UI says
  Tekmor.
- Tekmor uses only `TEKMOR_*` environment variables and Tekmor state paths.
- The managed skill and fence are `tekmor` and `<tekmor>...</tekmor>`.
- Custom LSP, OpenAPI, result discriminators, and generated protocol identity
  are Tekmor-only.
- Formula, cask, archives, manifests, checksums, installers, and release assets
  are Tekmor-only.
- All local validation and every PR check pass or reach an intentional neutral
  or skipped state.
- The GitHub repository is `amichne/tekmor` with preserved branch protections
  and Actions behavior.
- `https://tekmor.michne.com` serves the current site over HTTPS.
- The sibling action is Tekmor-compatible or absent from current product claims.
- `v0.13.0` is published and verified from clean macOS and Linux environments.
- A reviewed residual audit finds no stale Kast identity outside historical or
  explicit rejection evidence.

## Non-goals

- Rewriting history or retagging Kast releases.
- Renaming generic architectural modules whose names do not encode Kast.
- Adding capabilities, changing semantic behavior, or redesigning the CLI.
- Migrating user configuration, caches, indexes, plugin state, or installed
  repository resources from Kast.
- Shipping deprecation aliases or a dual-brand period.
- Treating generated outputs as independent sources of truth.

## Required handoff evidence

The implementation task must report:

- starting and final `main` SHAs;
- branch and PR URL;
- repository-owned files and generated outputs changed;
- exact validation commands and outcomes;
- the residual audit and every intentional old-name exception;
- GitHub repository rename evidence;
- documentation-domain and HTTPS evidence;
- sibling-action state;
- `v0.13.0` release URL and asset verification;
- Homebrew formula/cask installation evidence; and
- any external state that could not be verified.

Do not call the rename complete at repository merge time. Repository merge is
the gate into the external cutover, not the terminal state.
