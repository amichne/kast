# Codex dynamic tools earnest-attempt plan

Status: executed on 2026-08-25.

The original unassisted run reached the correct relation result but repeated deferred discovery and
symbol resolution, so it was `no-go` under this plan's stop conditions. A follow-up experiment made
same-program result retention and selector chaining explicit in the shared capability-adaptive
prompt. That workflow completed with one tool search, two dynamic calls, no corrective calls, and
a `go` decision. The final evidence is in the session-local task `004` artifacts. This distinction
is intentional: the transport and typed-tool experiment passed, while the stricter unassisted
orchestration criterion did not.

For a repeatable operator-facing run, use
`integration-tests/codex-app-server-evaluation/evaluate.py` and its adjacent README. That path adds
a versioned scenario request, a plan-only review, inherited-capability suppression, safe
dynamic-only defaults, explicit authorization for the full-access comparison, and a retained
evidence bundle. It remains pre-production evaluation infrastructure rather than a public Kast
command.

This plan records the smallest change used to give the existing Codex App Server `dynamicTools`
spike a fair second run. The work stopped after terminal evidence was captured and did not turn the
spike into production infrastructure.

## Agent brief

Continue the disposable spike committed as `192634923362` (`feat: initial dynamicTools support
attempt, nonfunctional`). Do not rebuild its App Server client or dynamic-tool adapter unless this
plan names the change.

The target outcome is one unassisted Codex task:

> Find the exact `CanonicalSymbolDiscoverHandler`, then show its direct callers using Kast.

Codex must discover two deferred tools, resolve the class, pass the returned selector unchanged to
`relation_read`, receive the direct callers, and answer without creating or executing a `kast`
shell command.

Before editing code:

1. Read the repository-root `AGENTS.md` and the nearest guides for every file you expect to change.
2. Inspect `git status --short` and the full diff. The existing CLI test changes are the disposable
   spike and must not be discarded or overwritten.
3. Create a new session task directory with `TASK.md`, `red.md`, `red.sh`, `green.md`, and
   `green.sh` as required by `AGENTS.md`.
4. Use the captured failed run as source evidence, but run a fresh red proof for the new task.

Read these sources first:

- [Latest dynamic-tools evidence](.agent-turn/kotlin-agentic-correctness/20260825T004601Z-codex-app-server-dynamic-tools-spike/tasks/001/codex-dynamic-tools-e2e.json)
- [Failed green proof](.agent-turn/kotlin-agentic-correctness/20260825T004601Z-codex-app-server-dynamic-tools-spike/tasks/001/green-proof.out)
- [Dynamic-tool definitions](cli/src/test/kotlin/io/github/amichne/kast/cli/codex/CodexDynamicToolDefinitions.kt)
- [Dynamic-tool adapter](cli/src/test/kotlin/io/github/amichne/kast/cli/codex/CodexDynamicToolsAdapter.kt)
- [App Server runner](cli/src/test/kotlin/io/github/amichne/kast/cli/codex/CodexAppServerSpike.kt)
- [Relation search](relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt)
- [Relation K2 projection](relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationProjection.kt)
- [Relation compiler identity](relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2SymbolIdentity.kt)
- [The required construction caller](runtime/composition/src/main/kotlin/io/github/amichne/kast/runtime/composition/protocol/core/CanonicalKastOperationHandlerFactory.kt)

## Objective

Make `RelationMeaning.Callers` return K2-confirmed construction callers when the exact subject is a
classlike selector. Then rerun the existing two-tool spike and compare it with an observed
CLI-driven run.

The change must preserve these invariants:

- The selector returned by `symbol_resolve` remains the class selector.
- The adapter does not create a constructor selector or derive identity from a name, path,
  signature, or display value.
- K2 remains the only authority that admits a constructor as belonging to the selected class.
- The dynamic path calls the existing canonical Kast wire operations and never calls the Kast CLI.
- Invalid dynamic-tool arguments fail before a Kast operation executes.
- Empty qualified evidence never becomes a successful caller answer.

## Current evidence

The latest live run recorded these observations:

| Observation | Value |
| --- | --- |
| Deferred tool search calls | 1 |
| Full schemas present before discovery | false, inferred from deferred configuration and event order |
| Dynamic-tool calls | 3 |
| Malformed calls | 0 |
| Recorded corrective calls | 2 |
| First selector transfer unchanged | true |
| Command execution observed | false |
| Kast process execution observed | false |
| Relation targets | empty |
| Relation qualification | `COVERAGE_INCOMPLETE` |
| Correct relation result | false |
| Model tokens before the first useful Kast result | 12,587 |
| Repository shape | one tracked violation: 11 direct children under the CLI test owner |

The first two dynamic calls followed the required shape. Codex resolved one exact classlike symbol,
then called `relation_read` with the exact selector string returned by `symbol_resolve`. The third
call was a corrective `references` query with a changed selector, and the adapter rejected it as
`SELECTOR_NOT_REUSED`.

The dynamic-tool plumbing has therefore proved:

- App Server experimental initialization and thread registration work.
- Both tools can remain deferred until tool search.
- App Server sends structured arguments to the host.
- The host refines those arguments into generated Kast request documents.
- The first producer-to-consumer selector transfer is byte-for-byte unchanged.
- The dynamic path does not synthesize or execute a Kast command.

Do not change those parts to address the failed relation result.

The remaining product behavior is in the relation adapter. `Callers` uses call-only
`ReferencesSearch`. `confirmTarget` then compares the resolved K2 target identity directly with the
subject identity. `IntellijK2SymbolIdentity.kt` gives `KaConstructorSymbol` and
`KaClassLikeSymbol` distinct canonical identities. A class construction can therefore fail the
exact-identity check even when the constructor belongs to the exact selected class.

The historical commits preserve exact endpoint authority, but they do not record a decision about
whether callers of a class include construction calls. Treat that meaning as a decision, not an
adapter bug to hide.

## Semantic decision gate

Adopt this contract before implementation:

> For an exact classlike subject, `Callers` includes a declaration that contains a direct
> constructor invocation when K2 resolves the invocation to a `KaConstructorSymbol` whose
> `containingClassId` equals the selected `KaClassSymbol.classId`.

The relation still targets the exact class subject. Constructor ownership is the semantic proof
for this relation. It does not make the constructor and class identities equal.

The result excludes:

- imports and type annotations;
- inheritance and implementation references that are not constructor invocations;
- callable references that do not invoke a constructor;
- calls to a member or `invoke` function on the class or object;
- constructors owned by another class with the same source name; and
- any candidate whose K2 ownership cannot be proved.

The result may include primary constructors, secondary constructors, and delegated constructor
calls when K2 proves the same owner. A caller result remains the nearest supported containing named
declaration, matching the existing relation projection.

If this meaning is not acceptable for Kast, stop and record a no-go result for the stated scenario.
Do not compensate by translating the selector, changing the prompt, adding a skill, adding a new
relation kind, or resolving a constructor in the Codex adapter.

## Design choice

Use a request-local typed relation plan in `:relation:intellij`.

The plan must distinguish at least these cases:

1. Exact-symbol reference confirmation, which keeps the current identity-equality rule.
2. Class-construction caller confirmation, which requires a call-shaped reference and K2-proved
   constructor ownership.

Keep the plan inside the IntelliJ adapter because it contains live PSI and K2 facts. Do not add a
public property bag or pass PSI, K2 symbols, or class IDs across the adapter boundary.

The rejected alternatives are:

| Alternative | Reason to reject it for this task |
| --- | --- |
| Return a constructor selector from `symbol_resolve` | Multiple constructors make the result ambiguous, and the requested exact symbol is the class. |
| Add `construction_callers` | This changes the public contract, registry, wire schema, and CLI. |
| Rewrite the selector in the dynamic adapter | This violates exact selector reuse and creates a second semantic implementation. |
| Search by text or qualified name | This weakens compiler authority and admits collisions. |

## Required changes

### Relation semantics

Change only the smallest set of production files needed for the typed plan and K2 confirmation.
The likely files are:

- `relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationSearch.kt`
- `relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2RelationProjection.kt`
- `relation/intellij/src/main/kotlin/io/github/amichne/kast/relation/intellij/IntellijK2SymbolIdentity.kt`, only if it owns the typed ownership check
- `relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/RelationRequest.kt`, only to document the admitted `Callers` meaning

The implementation must:

- derive the relation plan only after exact subject revalidation;
- require both a call-shaped PSI reference and K2 constructor ownership;
- compare K2 `ClassId` values inside one analysis session;
- leave normal exact-reference behavior unchanged;
- preserve budgets, cancellation, scope, deterministic order, and qualified coverage;
- emit the existing exact class endpoint as the relation target; and
- emit the containing caller declaration through the existing fact projection.

Do not weaken `compareIdentity`. Class and constructor identities must remain distinct.

### Relation proof

Add focused tests or fixtures for these cases:

- a direct primary-constructor call is returned;
- a secondary-constructor call is returned through the same class ownership proof;
- a type-only reference is excluded;
- an unrelated same-named class is excluded;
- a member or `invoke` call is excluded; and
- unproved ownership produces qualified incomplete evidence rather than a fact.

The final semantic proof must use the installed repository and the real K2 indexer. Pure tests do
not replace that observation.

### Spike accounting

Keep the two deferred tool definitions unchanged unless their existing schema fails App Server
validation.

Correct the disposable runner in these ways:

- Count one corrective invocation at most once, even when it violates more than one expected-flow
  condition. A call is corrective when it repeats a completed step or deviates from the expected
  `symbol_resolve` then `relation_read` sequence.
- Return a rejected tool response to Codex and continue until `turn/completed`. Do not stop the
  runner at the first rejected `relation_read` call.
- Record whether the final answer names the returned callers.
- Continue to fail the dynamic run if any `commandExecution` item occurs.
- Use the repository root as the App Server thread working directory for both comparison paths.

The test-only `symbol_resolve` tool may continue to compose canonical `symbol.discover`,
`symbol.resolve`, and `symbol.describe` wire calls. It must not compute symbol identity or return a
noncanonical result. Keep its exact-name and unique-result policy local to this disposable scenario.

### CLI comparison

Replace the `EQUIVALENT_CLI_INVOCATIONS` constant with one observed comparison run.

Run a separate App Server thread with:

- the same model;
- the same prompt;
- the same repository working directory;
- the same ready Kast indexer;
- no dynamic tools; and
- the shell tool enabled only for the CLI comparison thread.

The CLI comparison may execute public `kast` commands. Record its Kast command count, model tokens
before the first useful Kast result, model and tool turns, malformed commands, corrective commands,
and final caller answer. Do not use any CLI command as a fallback in the dynamic-tools thread.

Treat a nonzero Kast command caused by invalid invocation arguments as malformed. Treat a later
Kast command that retries the same semantic step as corrective. Count each command once.

Do not add operation-specific instructions to either prompt. If the CLI or dynamic path needs a
large skill or an `AGENTS.md` addition to complete, record that result instead of coaching it.

### Repository shape

The committed spike makes `cli/src/test/kotlin/io/github/amichne/kast/cli` exceed the limit of ten
direct children. The current count is eleven. Resolve this before final proof with a file-layout-only
split of the existing CLI tests. Preserve package declarations and test behavior, add or update the
nearest `AGENTS.md`, and do not mix a semantic change into the split.

If the shape correction requires deleting a test or changing production behavior, stop and choose
a different layout.

## Ordered work plan

1. Define the new session task and capture a fresh red proof from the current installed product.
   The red observation is an exact class selector followed by qualified empty `callers` evidence.
2. Add the smallest focused relation test that expresses the class-construction caller contract.
3. Introduce the request-local typed relation plan.
4. Add K2 constructor-owner confirmation for classlike `Callers`.
5. Run the focused relation tests and inspect every changed production Kotlin file against the
   proof-transition and serialization rules in `AGENTS.md`.
6. Install the changed Kast build, start the exact-root indexer, and prove the caller result through
   the public installed product.
7. Fix the spike accounting without changing its two dynamic tools or prompt.
8. Add the separate observed CLI comparison.
9. Resolve the known CLI test-owner shape violation with a layout-only split.
10. Run the dynamic task again with shell disabled.
11. Capture one evidence document containing both paths and judge every go or no-go criterion.
12. Stop. Do not generate tool definitions from the registry in this task.

## Validation

Use the nearest module guides to adjust this ladder if they require a stronger check. At minimum,
run:

```shell
./gradlew :relation:intellij:test --tests '*RelationReadTest'
./gradlew :relation:intellij:test
./gradlew :relation:service:test :runtime:composition:test
./gradlew :cli:test --tests '*CodexDynamicToolsAdapterTest'
./gradlew verifyKastModuleGraph verifyForbiddenEffects
```

Then refresh and exercise the installed product from the canonical repository root:

```shell
./gradlew installLocal
kast --version
kast start
./gradlew :cli:codexAppServerEvaluation
```

Run the final repository check:

```shell
python3 .github/scripts/check-repository-shape.py --root .
```

The final dynamic evidence must report:

- `completed = true`;
- one deferred tool search;
- a protocol event order consistent with both full schemas remaining deferred until tool search;
- exactly two dynamic-tool calls;
- zero malformed invocations;
- zero corrective invocations;
- an unchanged selector round trip;
- no command execution;
- no Kast process execution;
- a limitation-free relation result containing `symbolDiscover`;
- no relation rejection; and
- a final answer derived from the returned caller documents.

Inspect the protocol transcript and the evidence JSON. Do not rely only on a Gradle success line.

## Go or no-go decision

Record go only when every condition holds:

1. Codex discovers and selects the tools from their names, descriptions, and schemas without
   operation-specific prompt instructions.
2. Deferred discovery keeps both full schemas out of the model context before tool search.
3. The exact selector string from `symbol_resolve` is the selector sent to `relation_read`.
4. Structural argument errors are rejected before a Kast operation executes.
5. The dynamic path creates and executes no shell command.
6. The relation result contains the K2-confirmed direct callers and is not qualified because of a
   class-to-constructor identity mismatch.
7. The dynamic path uses exactly two model-visible Kast calls and is materially less corrective or
   uses fewer model and tool turns than the observed CLI path.

Report no-go when any condition fails. Preserve the raw token and turn counts even if they make the
dynamic path look worse.

## Stop conditions

Stop after one clean end-to-end dynamic run and one comparable CLI run establish the decision.

Stop earlier and ask for a contract decision when any of these conditions occurs:

- classlike `Callers` semantics are rejected;
- correct construction callers require a new public relation kind;
- correct symbol resolution requires changing the public twelve-operation registry;
- the adapter must reconstruct or replace the selector;
- the model needs operation-specific coaching; or
- the relation implementation requires text, path, signature, or name matching as semantic
  authority.

Do not repair unrelated caller shapes, add broader relation coverage, or keep iterating after the
validation scenario passes.

## Out of scope

Do not implement:

- registry-to-Codex generation;
- broad dynamic-tool coverage;
- a new public Kast operation;
- manifest or version management;
- thread migration;
- plugin packaging;
- source-mutating dynamic tools or workflows;
- approval infrastructure;
- MCP;
- CLI fallback in the dynamic path;
- a generalized dynamic-tool framework;
- prompt or skill instructions for these two operations; or
- production ownership for the disposable CLI test package.

## Smallest next change after success

The existing canonical registry owns separate `symbol.discover`, `symbol.resolve`, and
`symbol.describe` operations. The spike's `symbol_resolve` tool composes all three. The two
hardcoded dynamic definitions therefore cannot be generated from the current registry without an
explicit workflow definition.

After a successful spike, the smallest next design change is a typed registry-owned workflow
projection that names the existing operation composition and its input and output schemas. A
generator could then project that workflow and `relation.read` into App Server dynamic-tool
definitions. Do not design or implement that change during this task.
