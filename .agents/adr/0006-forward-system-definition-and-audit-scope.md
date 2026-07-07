# ADR 0006: Forward system definition and audit scope

Status: Accepted

Date: 2026-07-06

This ADR defines Kast as it should be evaluated going forward. It is an audit
charter, not a migration log. Historical surfaces, compatibility aliases, and
deprecation candidates are evidence only when they prove that the forward
contract is enforced or that stale paths fail loudly.

## Definition

Kast is an agent-first, compiler-backed Kotlin and Gradle semantic control
plane. It gives agents, scripts, developers, CI runners, hosted agents, and
server images one typed command surface for answering questions that text
search cannot answer safely: which declaration a name resolves to, where a
symbol is referenced, which callers or callees are real, whether diagnostics
are clean, which source-index impact a symbol has, and whether an identity-first
rename is safe to apply.

Kast is not a general search tool, a generic code editor, a public JSON-RPC
playground, a Copilot-only extension, or an IDE-only plugin. Those surfaces may
exist as internal implementation details, retained adapters, package source, or
release contracts, but they are not the system to evaluate unless this ADR or a
newer ADR names them as public.

## Exclusive Public Product Surface

The current public system is evaluated only through these surfaces:

| Surface | Forward role | Source of truth |
| --- | --- | --- |
| Root AXI CLI | Content-first context, setup, readiness, repair, status, and developer operations | `cli-rs/src/cli/root.rs`, `cli-rs/src/main.rs`, `docs/commands/` |
| Typed agent CLI | Compiler-backed semantic work through `kast agent verify`, `symbol`, `diagnostics`, `impact`, `rename`, and `lsp` | `cli-rs/src/cli/agent.rs`, `cli-rs/src/agent/`, `docs/commands/agent.md` |
| Repository agent assets | One packaged `SKILL.md` and one managed `<kast>...</kast>` guidance region | `cli-rs/resources/kast-skill/SKILL.md`, `cli-rs/src/install/agent_guidance.rs` |
| Unified API layer | Host-agnostic request/response models, capabilities, descriptors, JSON-RPC wire models, and edit-plan semantics | `analysis-api/` |
| Transport and dispatch layer | Line-delimited JSON-RPC dispatch, socket/stdio servers, descriptor lifecycle, timeout and error mapping | `analysis-server/` |
| Runtime backends | IDEA or Android Studio plugin backend for developer machines; packaged headless backend for Linux CI, hosted agents, servers, and images | `backend-idea/`, `backend-headless/`, `backend-shared/` |
| Source index | SQLite-backed declaration, reference, metrics, and impact data shared by runtimes and Rust CLI queries | `index-store/`, `cli-rs/src/metrics_database/`, `cli-rs/src/symbol_query/` |
| Release and distribution | Homebrew developer install, version-coupled IDEA plugin cask, Linux headless bundle, runtime manifests, and validation receipts | `docs/getting-started/`, `docs/distribution/`, `cli-rs/src/package.rs` |

Hidden, generated, or retained surfaces such as `kast agent tools`,
`kast agent call`, `kast agent workflow`, raw offset aliases, Copilot package
installs, portable Markdown instruction installs, generated catalog exports,
session hooks, and raw `kast rpc` are not public evaluation targets. A forward
audit may inspect them only to confirm that they are hidden, internal,
replacement-guided, or source-of-truth inputs for generated artifacts.

## System Boundaries

The Rust CLI is the user-facing and agent-facing control plane. It owns
argument parsing, AXI output rendering, install-state repair, manifest-backed
path resolution, runtime lifecycle orchestration, source-index CLI queries,
release packaging, and repository agent-resource installation.

The Kotlin API layer is the stable semantic contract. `analysis-api` must stay
host-agnostic and owns serializable model types, capability enums, descriptor
models, transport interfaces, shared config helpers, and deterministic edit
validation semantics.

The Kotlin server layer is the transport adapter around the API. It owns
line-delimited JSON-RPC 2.0, local socket and stdio serving, descriptor
lifecycle, method dispatch, error mapping, capability checks, truncation, and
request-limit enforcement.

Runtime hosts are interchangeable behind the same API and CLI command surface:

- Developer-machine mode uses the Homebrew-installed `kast` binary plus the
  version-coupled IDEA or Android Studio backend plugin.
- Headless mode uses the Linux headless tarball that packages the Rust CLI,
  install manifest, Java/runtime expectations, and headless backend runtime as
  one server or hosted-agent artifact.

Repository setup is separate from machine/runtime installation. It installs or
repairs only the thin skill and one managed guidance region; it does not install
public hooks, Copilot package files, portable instruction packages, workflow
helper assets, or generated catalog copies.

## Workflows To Evaluate

An audit must cover the variety of workflows Kast intentionally supports:

| Workflow | Required behavior |
| --- | --- |
| Context/home | Running `kast` with no command prints compact workspace context, executable path, output defaults, and actionable command hints. |
| Repository setup | `kast setup` plans or installs the skill and one managed `<kast>` region, preserves user-authored context outside the region, records manifest-backed resources, and supports explicit `--context-file` targets. |
| Readiness | `kast ready --for agent|kotlin|release|machine` is read-only and reports whether the requested task surface is usable. |
| Repair | `kast repair` plans by default and mutates only with `--apply`. |
| Runtime lifecycle | `kast status` and `kast developer runtime up|status|restart|stop|capabilities` inspect or manage the selected backend without changing the semantic command dialect. |
| Semantic verification | `kast agent verify` proves backend health, runtime state, and capabilities for semantic work. |
| Symbol identity | `kast agent symbol --query <name>` is the public lookup path; optional `--kind`, `--file-hint`, `--containing-type`, `--references`, and `--callers` refine compiler-backed evidence. |
| Diagnostics | `kast agent diagnostics --file-path <path>` refreshes as needed and reports Kotlin diagnostics for touched files. |
| Impact | `kast agent impact --symbol <fq-name>` reads source-index impact for a compiler identity. |
| Rename | `kast agent rename --symbol <fq-name> --new-name <name>` plans by default and applies only with `--apply`; local-variable rename remains out of scope until a typed non-offset selector exists. |
| LSP | `kast agent lsp --stdio` remains available for editor integration; agent automation still prefers typed `kast agent` commands. |
| Developer-machine install | Homebrew installs the global binary and matching IDEA plugin; `kast developer machine ...` repairs plugin links, defaults, and shell integration. |
| Headless install | The Linux bundle installs binary, manifest, and backend runtime together; mirrors and images verify exact artifacts before activation. |
| Release engineering | `kast developer release ...` packages, activates, generates, and validates release artifacts without redefining the public agent dialect. |

## AXI Contract

Kast's public CLI is an AXI surface. The contract is:

- Captured or agent-run commands default to compact TOON. Interactive human
  terminals may use readable human output. `--output json|toon|human` is the
  explicit override.
- Structured errors are machine-readable, carry stable error codes, and map
  usage errors to exit code 2 and execution failures to exit code 1.
- Unknown commands, unknown flags, missing required flags, and removed public
  surfaces fail loudly through Clap or structured replacement guidance.
- Every public mutation has an explicit flag gate: `--apply` for repair and
  rename; setup mutation is previewable with `--dry-run`; forceful replacement
  requires `--force`.
- Backend and workspace selection are explicit standard flags:
  `--workspace-root` and `--backend idea|headless`.
- Public agent commands are typed noun/verb operations with shallow flags. New
  public behavior must not require arbitrary JSON method names, byte offsets,
  raw request files, generated catalog lookup, or implementation class names.
- Large or detailed internal payloads belong behind explicit flags, generated
  contract checks, or developer commands; public default output must stay small
  enough for agents to decide the next action.
- The no-args context view, command help, and managed guidance must direct
  agents to `kast ready`, `kast repair`, and typed `kast agent` commands.

These AXI rules are not optional polish. They are standardization gates. A new
extension point is acceptable only when it is represented as a typed command or
flag, has structured output, rejects unknown input, and has a source-backed
validation path.

## Extension Points

Forward-compatible extension points are intentionally narrow:

| Extension point | Allowed shape | Audit rule |
| --- | --- | --- |
| Output format | `--output json|toon|human` | All structured result types must serialize without leaking debug logs into stdout. |
| Workspace selection | `--workspace-root <path>` | Commands must resolve and report the actual workspace root they operate on. |
| Backend selection | `--backend idea|headless` | Commands must target the selected backend and report absence or unsupported distribution honestly. |
| Task readiness | `--for agent|kotlin|release|machine` | Readiness and repair must keep task scopes distinct. |
| Repository guidance | `--context-file <path>`, `--skill-target-dir <path>`, `--no-auto-exclude-git`, `--force`, `--dry-run` | Setup may modify only the managed region and manifest-backed skill install. |
| Semantic command flags | `--query`, `--symbol`, `--kind`, `--file-hint`, `--containing-type`, `--references`, `--callers`, `--file-path`, `--depth`, `--limit`, `--new-name`, `--apply` | Flags must preserve typed identity and bounded evidence; mutation requires an explicit apply gate. |
| Developer and release commands | `developer runtime`, `developer inspect`, `developer machine`, `developer release` | Operator/debug/release functionality must not become the default agent path. |
| LSP adapter | `kast agent lsp --stdio` | Editor integration may bridge to the backend but must not define a separate public semantic contract. |

Hook installs, Copilot package installs, generated catalog copies, portable
instruction package installs, and public arbitrary JSON-RPC dispatch are not
current extension points. Adding any of them back to the public surface requires
a superseding ADR and docs/contract updates.

## Source-Backed Audit Assertions

An audit model should evaluate Kast against these assertions:

1. The public product promise is compiler-backed Kotlin semantic evidence for
   agents and developers, not generic search.
2. Every public agent workflow routes through typed `kast agent` commands or
   `kast agent lsp`; arbitrary catalog dispatch is hidden or internal.
3. The CLI, skill, managed guidance, docs, and ADRs agree on setup, readiness,
   repair, output defaults, and typed agent commands.
4. Developer-machine and headless-server support are separate install lanes
   that converge on the same CLI/API/backend contract.
5. `analysis-api` remains host-agnostic; runtime-specific logic stays in
   backend/runtime hosts and CLI process management.
6. `analysis-server` owns JSON-RPC transport and dispatch; it does not absorb
   IDE PSI logic, workspace discovery, or CLI parsing.
7. `index-store` owns SQLite source-index schema and hydration; operational
   source-index reads for agents remain in the Rust CLI.
8. Mutations are plan-first and gated: repair and rename require `--apply`;
   setup supports `--dry-run`; stale resources fail closed.
9. Managed repository guidance modifies only the `<kast>...</kast>` region and
   points at the real installed skill path.
10. Generated protocol, catalog, Copilot package, and docs artifacts are
    regenerated from their source owners and are not edited as independent
    truth.
11. AXI output is structured, compact, predictable, and flag-governed; unknown
    or removed inputs fail loudly with actionable replacement guidance.
12. Deprecated, hidden, or compatibility surfaces are excluded from product
    scoring unless the audit is specifically checking that they are no longer
    public.

## Validation Commands

Use the narrowest validation that matches the changed layer, then broaden when
contracts move:

```console
cargo fmt --manifest-path cli-rs/Cargo.toml --all -- --check
cargo clippy --manifest-path cli-rs/Cargo.toml --locked --all-targets --all-features -- -D warnings
cargo test --manifest-path cli-rs/Cargo.toml --locked
./gradlew :analysis-api:test
./gradlew :analysis-server:test
./gradlew :index-store:test
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
```

Run backend-specific Gradle tests when changes touch `backend-idea`,
`backend-headless`, runtime descriptors, index hydration, or plugin packaging.
Run release/package checks when changes touch distribution artifacts, runtime
manifests, generated protocol artifacts, or bundled agent resources.

## Change Rule

If a future change expands or contracts the public product surface, add a
superseding ADR before rewriting docs or generated assets. The new ADR must
state the public surface, out-of-scope legacy surfaces, source owners, AXI
flags, and validation gates with the same level of precision as this record.
