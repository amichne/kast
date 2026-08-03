---
type: How-to Guide
title: How to Apply a Safe Kotlin Edit
description: Plan, apply, verify, and recover a proof-carrying Kotlin mutation.
tags: [kotlin, refactoring, rename, diagnostics, impact]
code_sources:
  - path: cli-rs/src/agent/core/dispatch/mod.rs
  - path: cli-rs/src/agent/navigation/relations.rs
  - path: cli-rs/src/execution/runtime/backend/workspace_admission.rs
---

# How to Apply a Safe Kotlin Edit

Use this guide for `rename`, `replace`, `add-file`, or `add-declaration`. Kast
retains either a verified final state or the exact source pre-state.

## Resolve the target

Ask the coding agent to establish one exact compiler identity before editing:

```text
Resolve the Kotlin declaration BillingPolicy.evaluate, including its owner,
signature, and source location. Do not edit yet.
```

If Kast returns multiple candidates, add package, file, owner, or parameter
types until one declaration remains.

## Inspect impact

Ask for the relationships that matter to the change:

```text
Using that exact declaration, identify references, callers, implementations,
and affected tests. Report whether each result has complete or limited
coverage. Do not edit yet.
```

Review the proposed scope. Limited coverage means the plan needs more evidence;
it does not mean the remaining workspace is unaffected.

## Create the plan

Run `kast change` for one operation. For example:

```console
kast change rename <SYMBOL> evaluateInvoice
```

For `replace`, `add-file`, or `add-declaration`, supply the requested content on
standard input. Review the returned preview, proof, limitations, and plan
identifier. Do not apply a plan with unresolved limitations.

## Apply the plan

Apply the reviewed plan:

```console
kast apply <PLAN_ID>
```

Kast acquires the workspace lease, revalidates the root-bound plan, applies the
mutation, refreshes semantic evidence, checks diagnostics, and verifies the
postcondition. Do not manage a lease separately.

Read the terminal receipt. Only `VERIFIED` means that the intended final state
was retained. `REJECTED`, `CONFLICTED`, and `ROLLED_BACK` are non-success
outcomes that do not retain an unverified edit.

## Recover an interrupted mutation

If the receipt is `RECOVERY_REQUIRED`, retain its recovery identifier. In a new
process if necessary, run:

```console
kast recover <RECOVERY_ID>
```

Recovery completes verification or restores the exact source pre-state. Retry
the same plan or recovery identifier when needed; terminal retries do not
repeat source writes.

## Review the result

After a `VERIFIED` receipt, inspect the diff and run the narrow project check
for the changed module. A safe handoff includes:

- the exact declaration that was changed;
- every changed file;
- the semantic relationships used to choose that scope; and
- the terminal receipt; and
- post-edit diagnostics or the focused build result.
