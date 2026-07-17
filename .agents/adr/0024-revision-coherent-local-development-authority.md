# ADR 0024: Revision-coherent local development authority

Status: Accepted

Date: 2026-07-15

## Context

Kast needs one release-free path that exercises a checkout through the same
CLI, backend, skill, guidance, and configuration boundary consumed by an
agent. The existing `installDevelopmentLocal` task copies `kast-dev`, patches
machine configuration, and replaces files in a user JetBrains profile as
independent mutations. It cannot prove that the effective surfaces share one
source snapshot, and a failure can expose a partial installation.

ADR 0023 makes Homebrew authoritative for the release CLI and JetBrains
authoritative for signed plugin installation and updates. A developer refresh
must not weaken those release authorities or recover the removed profile-write
path under another name.

## Decision

Kast has an explicit `local-development` authority that is independent of the
release authorities. The supported entrypoint is:

```console
./gradlew refreshDevelopmentLocal
```

The task builds the current checkout and asks the typed Rust CLI to stage and
activate one immutable local generation. It does not publish or consume a new
GitHub, Homebrew, tap, or JetBrains plugin release.

Each generation contains exactly these revision-coupled surfaces:

- the `kast-dev` implementation binary;
- the portable headless backend, including its IDEA runtime;
- the packaged Kast skill;
- rendered managed agent guidance; and
- isolated runtime configuration plus a strict authority receipt.

The CLI and backend are each attested independently after their producing
build task. Their strict provenance records carry the captured source snapshot,
typed artifact kind, canonical producer path, implementation version, and a
length-framed SHA-256 of the exact file or tree. The source digest is also
compiled into the local CLI bytes and packaged inside the exact local headless
plugin JAR. That JAR additionally embeds a strict producer manifest naming and
hashing the exact seven repo-built runtime JAR roles in the portable backend.
The installer rejects missing, duplicate, unexpected, relabeled, or stale
sibling JARs. Attestation reads those artifact-internal facts; an external
label cannot turn ordinary Cargo output or an old backend archive into current
local output. Refresh requires both provenance records to name the same source
and implementation version and recomputes both artifact hashes before it
creates the prefix. Runtime launch validates the full installed receipt and
backend tree again before consuming the selected classpath. Relabeling an old
artifact, swapping one producer output, or changing bytes after attestation is
therefore not a valid generation.

The release-free path uses the headless backend. It does not write a JetBrains
plugin directory, plugin repository, certificate enrollment, or release
receipt. The ordinary `kast` entrypoint and the Homebrew and JetBrains release
authorities remain byte-for-byte outside the local transaction.

### Source identity

Before artifact production, the task captures a canonical source snapshot. A
snapshot consists of the canonical checkout root, Git commit, worktree kind,
and a length-framed SHA-256 over tracked changes plus untracked, non-ignored
source content. Entry count, path, kind, executable state, and content length
are framed so changes in file boundaries cannot produce the same serialized
hash input.
The installer recomputes the snapshot after staging. A changed checkout,
different canonical root, different component identity, or mixed digest fails
before activation.

Dirty checkouts are supported because the source-content digest, rather than
the commit alone, is the local generation identity. A linked worktree never
inherits another checkout's snapshot or prefix implicitly.

### Prepare once, consume without rebuilding

Artifact production and authority activation are separate typed operations.
`prepareDevelopmentLocalGeneration` captures or consumes one strict source
snapshot, builds the CLI and backend once, attests their exact bytes, and
publishes one immutable prepared directory. `activateDevelopmentLocal`
verifies and activates an explicitly selected prepared directory without a
Cargo or Gradle producer dependency. `refreshDevelopmentLocal` remains the
convenience aggregate that orders those two operations for local use.

The prepared directory has one closed layout rooted by `generation.json`. The
ledger denies unknown fields and records the source identity, generation ID,
implementation version, fixed relative path, and SHA-256 of the source
snapshot, CLI, CLI provenance, backend tree, backend provenance, standalone
backend component manifest, skill, guidance inputs, and configuration. The
artifact provenance stored inside the directory is path-independent, so the
whole directory may be relocated without weakening byte identity. Verification
rejects missing or extra files and directories, symlinks, special entries,
renamed component paths, source drift, digest drift, version drift, and any
difference between the standalone backend manifest and the manifest embedded
by its producer.

Pull-request automation applies the same separation across jobs. The static
fanout gate captures the source snapshot without installing Java, Gradle, or
Rust. The existing Rust job compiles that digest into its one release binary,
and the existing Linux Gradle job embeds the same snapshot into its one
source-bound headless backend. A single `prepared-generation` job verifies the
outer CI ledgers, re-attests the extracted component bytes, prepares and
verifies the immutable generation with its exact CLI, and derives the Linux
bundle, headless runtime, runtime manifest, and Gradle read-only cache once.
Each published file receives an outer CI artifact ledger. Container and action
jobs consume those files and ledgers as validation-only jobs; they do not rerun
Rust, Kotlin, Gradle, installation packaging, or release packaging.

### Activation and effective paths

The local prefix has immutable generations plus stable indirection:

```text
<prefix>/
  generations/<source-snapshot-id>/
  current -> generations/<source-snapshot-id>
  previous -> generations/<previous-source-snapshot-id>
  authority.json -> current/authority.json
  install.json -> current/install.json
  bin/kast-dev -> ../current/entrypoint/kast-dev
  state/<source-snapshot-id>/{data,cache,runtime,logs,locks,...}
```

All effective paths used by `kast-dev` resolve through `current`. The installer
stages and validates a complete generation, receipt, wrapper, and repository
resource transaction before atomically replacing `current`. Every operation
after the pointer replacement must be non-fallible or recoverable from the
already-written transaction journal. A failed pre-activation refresh leaves
the prior generation effective.

The receipt denies unknown fields and records the source snapshot, authority,
backend identity, effective and physical targets, SHA-256 for every component,
workspace root, and prior generation. Machine-readable readiness validates the
actual bytes at those targets; it does not trust the receipt by existence.
Runtime descriptors, source-index data, cache, logs, and locks are
generation-scoped. The stable launcher exports the generation data root for
the Kotlin backend as well as selecting the same root from the receipt-backed
Rust manifest. Activating a new source snapshot cannot discover or reuse a
backend descriptor or source index emitted by an older local generation, even
when both builds report the same semantic version.

The first headless start is a correctness barrier rather than a process-spawn
acknowledgement. It adopts an already-running automatic Gradle sync instead of
scheduling a competing import, waits for IDEA smart mode, and requires every
module that actually owns Kotlin source to have a resolved SDK, valid order
entries, the JDK and Kotlin runtime symbols, PSI, and compiler diagnostics
before reporting the runtime ready. The runtime-status endpoint and semantic
indexing share one typed compiler-admission state. Public status remains
`INDEXING` while admission is pending and becomes `DEGRADED` when admission
fails, so smart mode alone cannot create a false `READY` claim. Java-only
modules neither weaken nor block that Kotlin readiness proof. Only a newly
spawned local-development headless runtime receives a five-minute wait budget
so a healthy cold import is not killed by the shorter ordinary startup
timeout. Reuse of an existing runtime honors the caller's ordinary timeout;
release, demo, and normal semantic request budgets remain unchanged. The
headless JVM disables the signed IDEA plugin's unrelated project-open profile
hook before application startup; local execution therefore cannot consult
Homebrew authority or rewrite workspace metadata while a source snapshot is
being exercised. The installed skill and guidance teach this explicit
receipt-owned startup before the reuse-only `agent verify` command.

Runtime startup participates in the same canonical prefix authority lock as
refresh, rollback, and removal. It revalidates the active receipt and all
components under the lock, spawns the exact headless child, and retains the
lock until that workspace, backend, and process identifier appears in the
generation-scoped descriptor ledger. Registration time consumes the existing
cold-start budget, but waiting to acquire the lock does not. A concurrent
startup re-inspects under the lock and reuses the process that registered
first. A child that exits before registration is observed through its retained
process handle, reaped, and reported without spending the full cold budget. If
a transition wins the lock first, the stale start fails revalidation before
spawning; if startup wins first, the transition observes the registered live
process and refuses to switch authority.

Reference lookup may use a matching generation-scoped source index, but an
unavailable or empty first index page falls back to compiler/PSI evidence.
File-scoped private and local declarations begin traversal at their exact
source file, rather than spending the request budget walking unrelated Gradle
roots. A reference result is admitted as available only when its search scope
is complete; partial evidence remains typed as degraded.

A plan-only rename crosses the same installed semantic boundary. The CLI first
resolves the requested identity to a compiler anchor, then asks the backend for
a dry-run rename and validates a nonempty typed preview containing edits,
affected files, and matching pre-edit file hashes. It preserves source bytes;
the operation type distinguishes this source-read-only preview from an applied
mutation, so only `--apply` requires applied-mutation authority. A static
request-shaped plan without backend evidence is rejected.

### Repository resources

The packaged skill comes from the captured checkout rather than the controller
binary. The local renderer replaces every taught `kast` command with the
receipt-owned absolute `kast-dev` entrypoint. Managed guidance uses the same
entrypoint and is projected only into the explicitly selected exact workspace.
The local transaction owns only its immutable generation surfaces and exact
guidance symlink. Prefix locking uses canonical path authority, and refresh
rejects a final prefix symlink so aliases cannot bypass serialization. The
source-owned root `.gitignore` declares
`/AGENTS.local.md`; refresh verifies that Git ignores the projection and never
mutates the shared `.git/info/exclude` file. It preserves unrelated repository
bytes and removes only owned projections during failure recovery or removal.
Guidance is accepted only when each bare command path exists and every runnable
example parses as a complete effective-generation CLI invocation, including
flags, required arguments, values, and a closed set of normalized documentation
placeholders. Only a closed set of explicitly denied command-path references
is exempt as negative guidance; other code spans on the same line remain
subject to complete invocation parsing.

### Rollback and removal

Rollback requires an explicit generation identifier, selects only that
validated previous generation, and is idempotent when retried against the now
current generation. Refresh and rollback refuse any non-idempotent generation
switch while a generation-owned runtime PID is live. Removal deletes only a
wholly receipt-owned prefix and its guidance projection, including an owned
dangling guidance symlink when the prefix is already absent; it likewise
refuses while a generation-owned runtime PID is live. All three lifecycle
paths serialize through the canonical prefix namespace lock, including when
the prefix is missing. The Gradle removal task prefers the installed stable
controller, then a source-built checkout recovery controller, so missing-prefix
guidance cleanup remains reachable without rebuilding the checkout.
Neither operation changes the Homebrew receipt, release CLI, JetBrains
plugin state, or unrelated user configuration. After removal, release
authority is again the effective ordinary `kast` authority; it is never copied
into or inferred by the local prefix.

## Source ownership

| Contract | Authored owner | Validation |
| --- | --- | --- |
| Local command and receipt types | `cli-rs/src/local_development/` and `cli-rs/src/cli/local_development.rs` | focused Rust unit and smoke tests |
| Generation staging, validation, activation, rollback, removal | `cli-rs/src/local_development/` | failure-injection and idempotence tests |
| Checkout build orchestration | root `build.gradle.kts` and typed tasks under `build-logic/` | `.github/scripts/test-local-development-refresh-contract.sh` |
| Immutable prepared generation and activation | `cli-rs/src/local_development/prepared_generation.rs`, root `build.gradle.kts`, and `scripts/package-prepared-local-generation.sh` | focused Rust tests, local-development source contract, and CI artifact ledgers |
| Pull-request generation assembly and derived Linux packages | `.github/workflows/ci.yml` and `scripts/assemble-prepared-local-generation.sh` | release workflow contract plus exact proof-output graph model |
| Headless development backend | `backend-headless/` portable distribution | layout verification plus semantic probes |
| Installed semantic boundary | `.github/scripts/test-local-development-semantic-e2e.sh` | integrated main/nightly/manual/release canary for refresh/reuse, readiness, exact semantic reads, plan-only mutation, stop, and removal |
| Skill source | `cli-rs/resources/kast-skill/SKILL.md` | command/help lockstep contract |
| Managed local guidance renderer | `cli-rs/src/local_development/` | receipt, command-lockstep, and workspace-preservation tests |
| Machine-readable readiness | `cli-rs/src/self_mgmt.rs` and `cli-rs/src/output/ready.rs` | local authority smoke tests |

## Required gates

The local authority is not complete until executable checks prove:

- one non-interactive refresh from a primary checkout and an explicitly
  selected linked worktree or isolated prefix;
- component and source-digest lockstep, including same-commit dirty drift;
- framed digest collision resistance and foreign-generation symlink rejection;
- independent source-bound CLI and backend provenance, per-JAR producer
  manifests, runtime revalidation, and mixed-artifact rejection;
- idempotence and failure preservation at each activation boundary;
- refresh, rollback, and removal refusal in the presence of live
  generation-owned runtimes, plus missing-prefix removal serialized against
  concurrent refresh without release or unrelated-content mutation;
- runtime-start and generation-transition barrier tests in both lock orderings,
  proving revalidation-before-spawn and registration-before-transition;
- concurrent-start reuse, prompt failed-child reaping, and post-spawn-only
  cold-budget accounting;
- generation-scoped runtime descriptors, source indexes, and caches;
- cold Gradle import and shared compiler-admission completion before a ready
  claim, with the extended timeout scoped to local headless startup;
- installed skill and guidance bare paths exist and every taught runnable
  invocation parses completely against the effective CLI; and
- exact symbol resolution, a known nonzero reference query, clean-file
  diagnostics, and a backend-produced nonzero plan-only mutation preview
  through the refreshed headless generation.

Validation is layered by authored owner. Source and ownership contracts may
assert that a focused owner exists and is wired, but they do not invoke that
owner again. Rust unit and integration tests execute in the Rust job, Kotlin
and IDEA tests execute in their Gradle jobs, documentation renders in the
documentation workflow, and installer, runtime-compatibility, release,
provenance, and asset contracts execute once in their named owners. The
selector-handle integration test binds directly to Cargo's exact built binary,
so the Rust owner cannot report success by silently skipping it.

The pre-existing `installDevelopmentLocal` compatibility task is not the
revision-coherent local authority defined by this ADR. Its profile-writing
contract remains executable on integrated `main` pushes, but it is quarantined
from the universal pull-request gate. This changes validation frequency, not
the documented compatibility surface; removing the task or its supported
profile behavior requires a separate product decision. Pull requests retain
the source/task-graph assertions and the release-free local-authority proof.

The complete Kast-on-Kast installed semantic boundary is an integration
canary, not an ordinary pull-request root. One reusable workflow runs it on
integrated `main`, nightly, manually, and against the exact prepared release
tag. A failed canary is a failed release prerequisite, is never
`continue-on-error`, and retains runtime logs for diagnosis. The deterministic
workflow model keeps this canary's proof output in the exact integrated output
inventory while excluding its duration from the pull-request critical path.

The focused pull-request source gate is:

```console
.github/scripts/test-local-development-refresh-contract.sh
```

The integrated installed canary is:

```console
.github/scripts/test-local-development-semantic-e2e.sh
```

Rust formatting, Clippy, focused tests, headless layout verification, and the
affected documentation contracts remain required in proportion to each slice.

## Consequences

Local refresh becomes larger than copying a debug executable, but every agent
test can now identify the exact bytes and source snapshot it exercised. The
release CLI and signed plugin retain their independent trust models. A running
release IDE is not silently replaced, and local semantic evidence no longer
depends on whichever plugin or CLI happened to be active on the machine.

This ADR supersedes only the direct user-profile mutation and independent
side-effect model of the pre-existing development Gradle tasks. It complements,
and does not relax, ADR 0023's release authority and typed compatibility rules.
