# IDEA lifecycle verification

This change adds explicit lifecycle control to the existing hosted plugin for
platform line `262.*`. The application service owns project use, operation status,
and native lifecycle coordination. Semantic services and compiler state remain
project-scoped. No exact runtime minor or patch version is required.

## Implemented boundaries

- T1: installer selection retains the canonical IDEA home and derives the bundle
  and executable from product metadata. Upgrade selection and existing precedence
  are preserved; launch revalidates the selected installation.
- T2: the application startup listener exposes a live lifecycle handshake with
  zero projects. Exact host/project incarnations, bounded retained operations,
  duplicate-open joining, and per-thread use records remain application-scoped.
- T3: explicit open attaches first, otherwise requests native background launch of
  the selected bundle. Existing roots are reused without import or activation;
  missing roots open in a separate frame. Explicit presentation protects the target.
- T4: first linking and reload share the existing refresh/readiness owner. The
  import specification disables start/failure tool-window activation and error
  navigation. Temporary project-scoped auto-import coordination covers first open.
- T5: release removes caller use without closure. Normal native closure requires
  eligible ownership, no other users, no active work and no unsaved documents
  anywhere in the application. Borrowed/presented closure requires exact controller
  acceptance and an enrolled signature. Veto restores admission; success waits for
  disposal and retirement of that incarnation's semantic endpoint.
- T6: the canonical operation generates CLI/agent projections and output schemas.
  Source-change approval remains separate. The public workspaces page documents
  actions, best-effort background behavior and finite recovery conditions.

## Evidence and limits

The bounded `packaging/run-hosted-lifecycle-smoke.py` runner reuses the existing
private graphical IDEA acceptance fixture. It checks zero-project inspection,
opening an existing-settings fixture and a sibling without `.idea`, distinct
project identities, reuse, reload, release, normal closure, retained closure
status, and inspection after the last project closes. Only fixture-owned processes
and files are retired by the existing isolation owner.

The 2026-09-16 native smoke passed every listed stage and the fixture owner
reported successful cleanup. It used the staged development product based on
`v0.42.4`; the tested plugin archive SHA-256 was
`59803c39637f64f564bd576e45a3da162cc72f7dc3c7b4fb3da15a5d3c934dd6`.
This was a working-tree verification, not a published-release qualification.

The native run uses IDEA `IU-262.10315.125` on macOS ARM64. This is a functional
observation on one installation, not a compatibility matrix or performance claim.
Deterministic tests cover launcher ambiguity and supported-line updates, encoded
closed outcomes, quiet spec flags, refresh failure mapping, ownership and shared
use, exact signed authorization, veto-state restoration and result retention.
Installed Codex experimental schemas qualify the actual command-approval documents.

The native smoke does not verify transient focus behavior, normal-profile cold
launch, interactive unsaved-editor blocking, native close-veto UI, or stock Codex
Desktop rendering of the exact-target approval. Tests of those policies and schemas
must not be presented as desktop observations. Background opening remains best
effort, and the normal user IDEA process was not restarted or modified.

## Unrelated observation

The private native fixture logged an unavailable mise Node shim under its isolated
home. Gradle import and lifecycle completion succeeded. This belongs to fixture
startup/tool discovery and was not changed by this patch.

## Repository checks

`productBuildGate` passed, including all module checks, architecture enforcement,
packaging checks, `verifyJsonContracts` and configuration ingress. The JSON guard
reported 1,039 fingerprints and zero violations; one migrated fixture allowance
was removed. `knowledgeImpact` was reviewed and `verifyKnowledgeBase` passed.
Pinned `mint@4.2.841 validate` and `git diff --check` passed. Native verification
remains limited to the functional observations above.
