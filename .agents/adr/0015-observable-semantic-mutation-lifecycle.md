# ADR 0015: Observable semantic mutation lifecycle

Status: Accepted

Date: 2026-07-13

This ADR supersedes ADR 0006 and ADR 0009 only for execution of public typed
semantic mutations. Their plan-first command boundary, hidden raw transport,
and remaining product rules stay authoritative.

## Decision

Every public `kast agent` semantic mutation that crosses the `--apply` gate is
an asynchronous, server-owned operation. Submission requires a caller-chosen
idempotency key and immediately returns a stable operation ID. The backend
continues execution independently of the submitting shell process, and callers
can reconnect to query status, retrieve the retained terminal result, or
request cancellation.

The contract covers rename, add-file, add-declaration, add-implementation,
add-statement, and replace-declaration. Planning remains read-only and does not
require an idempotency key.

`kast agent operation status` and `kast agent operation cancel` accept exactly
one stable selector: an operation ID or its idempotency key. Status and cancel
requests are idempotent. A successful submission, retry, status response, and
cancellation acknowledgement expose both identifiers.

## Idempotency binding

The server binds an idempotency key atomically to a canonical fingerprint made
from the JSON-RPC method and normalized typed request payload after removing
the idempotency key. Repeating the same key and fingerprint always returns the
same operation ID and retained state or result. Reusing the key for a different
fingerprint is a typed conflict and never starts another mutation.

The registry is authoritative for the lifetime of the backend daemon. This
decision does not add persistence across daemon restart. If the daemon loses
its registry, prior operation state is ambiguous rather than assumed failed or
safe to replay.

## Lifecycle and progress

Operation status is a closed set: queued, applying, validating, completed,
failed, or cancelled. Progress uses typed stages for identity resolution, edit
application, workspace refresh, import optimization, and diagnostics.

The server records the latest entered stage before invoking that stage. It
also records whether edit application began and whether it completed, so a
later validation failure or cancellation cannot imply that no write occurred.
Completed and failed outcomes retain the typed mutation result or typed error.

Cancellation is request and acknowledgement based. A cancellation request
sets a cancellation-requested fact and signals the running coroutine. The
operation becomes terminal `cancelled` only after execution cooperatively
stops. If edit application completed before cancellation was observed, the
terminal outcome retains that fact. The server never publishes cancellation as
proof that the workspace was untouched.

## Filesystem fallback

Filesystem fallback is safe only when a successfully queried operation state
proves edit application never began. Completed operations, operations that
entered edit application, missing operation state after daemon restart, and
unreachable operation state after disconnect are not safe fallback signals.
In those cases callers must recover the backend state and inspect the retained
result or workspace before deciding on another mutation.

## Source of truth

| Layer | Owner |
| --- | --- |
| Identifiers, lifecycle, progress, selectors, receipts, and terminal outcomes | `analysis-api/src/main/kotlin/io/github/amichne/kast/api/contract/mutation/` |
| Atomic key binding, operation execution, status, cancellation, and result retention | `analysis-server/src/main/kotlin/io/github/amichne/kast/server/mutation/` |
| Public `--idempotency-key` and operation commands | `cli-rs/src/cli/agent.rs`, `cli-rs/src/agent/` |
| Public recovery and fallback guidance | `docs/reference/agent-commands.md`, `docs/reference/mutation-selectors.md`, `cli-rs/resources/kast-skill/` |

## Validation

```console
./gradlew :analysis-api:test :analysis-server:test
cargo test --manifest-path cli-rs/Cargo.toml --locked --test agent_command_surface_smoke
cargo test --manifest-path cli-rs/Cargo.toml --locked
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
git diff --check
```

The server tests must cover an operation longer than ten seconds, disconnect
and reconnect, same-key retry, different-payload conflict, cancellation before
and after edit application, idempotent status and cancellation, progress
transitions, and terminal result retrieval.

## Change rule

Further mutation-lifecycle expansion must preserve atomic key binding, truthful
edit-application facts, typed terminal outcomes, and plan-first `--apply`
gating. Adding daemon-restart durability requires a superseding ADR that owns
the persistence, retention, schema migration, and crash-recovery contract.
