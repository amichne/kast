# Resumable installation recovery

Status: accepted requirements; implementation pending. This is the holding plan
for extending recovery, not a description of commands already available.

## Decision

An agent must be able to recover a malformed or unhealthy Kast installation
without asking the user to operate workspace or process-lifecycle commands.
Keep workspace setup behind the existing agent lifecycle tool. The agent should
inspect, open, wait for import and admission, and verify indexing readiness.
Placement in a future cradle suite remains a naming decision; do not introduce a
second workspace implementation.

Recovery may stop verified Kast services and their app-server processes. It may
stop a verified native Codex daemon when that daemon is an established blocker.
Default private endpoints should avoid that conflict without stopping Codex.
Never infer process ownership from a process name alone.

IntelliJ restart is allowed through a visible user decision and normal IDE
shutdown. Health checks cannot guarantee the absence of crashes or prove that
unsaved work is safe to discard. Recovery must not force-kill IntelliJ, dismiss
its dialogs, bypass shutdown vetoes, or automatically choose a response.

## Recovery sequence

1. Inspect the installation, configuration, service ownership, endpoint ownership,
   and selected IDE identity. Record bounded, typed stage outcomes. Admission of
   a damaged configuration must not prevent the offline recovery path from
   inspecting independently proven installation ownership.
2. Persist the recovery operation outside state scheduled for cleanup. Fence
   concurrent starts and acquire exclusive recovery ownership. Preserve source,
   authentication, conversation history, IDE settings, and mutation evidence.
3. Stop proven blocking services and processes with bounded deadlines. Verify
   retirement before quarantining or rebuilding their owned state. The recovery
   controller must survive the services it stops; do not kill its own ancestors.
4. If an IDE restart is needed, bring the selected IntelliJ instance forward and
   require a visible confirmation before requesting normal application exit.
   Prefer the IDE exit confirmation. If the supported API cannot enforce that
   dialog independently of the user's exit-confirmation preference, show an
   explicit IDE-owned recovery confirmation first. Do not change that preference
   or use internal force-exit flags to bypass the decision.
5. Report waiting for the user while the dialog is unresolved. A cancellation or
   shutdown veto leaves recovery incomplete and resumable. An unresponsive IDE
   is a blocked outcome, not permission to kill it. Restore any safely restorable
   services after cancellation; report remaining fences and unavailable services.
6. Confirm that the exact observed IDE process incarnation has exited. A closed
   socket, acknowledged request, or timeout is not exit proof. Replace or restore
   plugin files only after confirmed exit. Relaunch the selected installation,
   then require a new lifecycle handshake with matching installation identity.
7. Reconcile services, reopen the workspace through agent lifecycle operations,
   and wait for import and semantic admission. Report restored only after the
   coordinator and requested workspace health checks succeed. If no workspace
   was requested, report service restoration without claiming indexing readiness.

Use closed typed outcomes for inspection failure, ownership failure, waiting for
user, cancellation, shutdown veto, retirement timeout, relaunch failure, health
check failure, and verified restoration. Persist sufficient proven identity and
stage information to resume after interruption without repeating destructive
steps. Do not erase specific failures into a generic success or retry result.

## Implementation boundaries

- [Installed app-server management](../app-server/src/main/kotlin/io/github/amichne/kast/appserver/AppServerManagement.kt)
  currently resolves configuration before destructive repair. Its repair path
  resets managed state and bootstraps the coordinator; it does not implement the
  full recovery sequence above.
- [Offline recovery](../packaging/installation-recovery.py) and its
  [runbook](installation-recovery.md) provide independent ownership receipts,
  detachment, quarantine, and unresolved-state reporting. Retain these guarantees
  when adding restoration.
- [IDE lifecycle protocol](../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/IdeLifecycleDocuments.kt)
  has exact host and project identities. Application exit needs a distinct typed
  contract; project close must not be repurposed as whole-application shutdown.
- The installed IDEA 262 `ApplicationEx` exposes exit and restart methods.
  Inspection of its `ApplicationImpl.confirmExitIfNeeded` shows that confirmation
  may be skipped. Native acceptance must prove the chosen confirmation path with
  both exit-preference settings; API presence alone is insufficient evidence.

## Required evidence

Test malformed configuration, interrupted upgrades, stale endpoints, unrelated
endpoint owners, overlapping recovery attempts, process replacement, failed
retirement, and resumption after every persisted stage. Prove that unrelated
processes and preserved data remain untouched.

Native IDE acceptance must cover confirmation enabled and disabled, unsaved work,
active background tasks, user acceptance, cancellation, shutdown veto, an
unresponsive host, relaunch failure, and a fresh successful handshake. Verify
encoded output for every finite outcome, including waiting and failure, and
prove that a disconnected host cannot be reported as a completed restart.
