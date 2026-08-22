# Trust the evidence

A Kast result is useful because it says both what was established and where
that claim stops. Transport success alone never means semantic success.

## Three closed outcomes

<div class="kast-grid kast-outcome-grid" markdown>

<div class="kast-card kast-tone-evidence" markdown>

### Complete

The payload met the operation's declared scope, bounds, and completeness
policy. The evidence envelope retains the operation and generation that
produced it.

</div>

<div class="kast-card kast-tone-effect" markdown>

### Qualified

The payload remains valid within a named limitation. Operations that permit
bounded or provider-limited evidence can return this state without pretending
the answer is complete.

</div>

<div class="kast-card kast-tone-muted" markdown>

### Rejected

The operation returns one of its closed rejection reasons and no evidence
payload. Unknown, stale, unsupported, or inadmissible input cannot become a
successful empty answer.

</div>

</div>

Some operations allow qualified evidence because a bounded answer is still
useful. Exact symbol resolution, description, and every change phase require a
complete result. Their authority would be unsafe if a limitation were allowed
to pass as success.

## Every answer has coordinates

Read a semantic result as a coordinate, not a detached fact:

<div class="kast-proof-chain" aria-label="Evidence coordinates">
  <span class="kast-step kast-tone-identity">exact root</span>
  <span class="kast-step kast-tone-identity">operation</span>
  <span class="kast-step kast-tone-evidence">generation</span>
  <span class="kast-step kast-tone-discovery">scope and bounds</span>
  <span class="kast-step kast-tone-effect">qualification</span>
</div>

- **Exact root** separates checkouts and prevents cross-workspace reuse.
- **Operation** identifies the proof contract that produced the payload.
- **Generation** identifies the published semantic state used for the answer.
- **Scope and bounds** say which files, symbol, relation, depth, and result
  budget were admitted.
- **Qualification** names the limit on otherwise usable evidence.

Selectors and change identities carry these coordinates into later
operations. If the generation moves or the root differs, Kast rejects the
transition instead of unpacking the identity into an unproven string.

## Judge a negative answer

An empty result can establish absence only when all of these statements hold:

1. The operation matches the question.
2. The exact root and semantic generation are current.
3. The requested scope and limits cover the intended claim.
4. The outcome is complete, or its qualification does not weaken the claim.

If any statement is false, narrow the conclusion or ask a new question. This
is especially important for relation traversal and diagnostics, where explicit
limits make partial evidence useful but not universal.

The [CLI reference](../reference/cli.md) lists the current operations. [How
Kast works](../explanation/how-kast-works.md) shows where their evidence is
created and retained.
