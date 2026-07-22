---
type: How-to Guide
title: Plan a Safe Kotlin Edit
description: Use exact symbol identity, impact evidence, and diagnostics before accepting a Kotlin change.
tags: [kotlin, refactoring, rename, diagnostics, impact]
code_sources:
  - path: cli-rs/src/agent/dispatch.rs
  - path: cli-rs/src/agent/relations.rs
  - path: cli-rs/src/runtime/workspace_admission.rs
---

# Plan a Safe Kotlin Edit

Use this guide for renames or scoped declaration changes where matching text is
not enough to identify the target.

## Resolve the target

Ask Codex to establish one exact compiler identity before editing:

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

## Request the edit and verification together

State the intended change and the proof you expect:

```text
Rename that exact declaration to evaluateInvoice. Update compiler-resolved
references only, then report changed files and Kotlin diagnostics.
```

Kast binds semantic mutations to an exact prepared workspace. It rejects an
unprepared or mismatched mutation authority instead of applying a best-effort
text rewrite.

## Review the result

Inspect the diff and run the narrow project check for the changed module. If
Codex reports diagnostics, resolve them before broadening the change. A safe
handoff includes:

- the exact declaration that was changed;
- every changed file;
- the semantic relationships used to choose that scope; and
- post-edit diagnostics or the focused build result.
