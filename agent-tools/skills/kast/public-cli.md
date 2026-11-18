# Public CLI reads

Use the installed `kast` command for Kotlin queries without MCP registration.
Kast supplies a private IntelliJ Host; no user IDEA installation is needed.
The first explicit semantic request trusts the exact canonical workspace root and prepares that Host.
Session hooks and discovery remain passive. Use the first useful operation rather than a readiness probe.
Queries do not fetch releases. A missing runtime requires explicit management repair, not a desktop fallback.

Semantic CLI results use JSON by default, including errors, plans, and descriptors.
Set `KAST_OUTPUT_TOON=1` to select TOON.
An unset or empty variable selects JSON, as does `KAST_OUTPUT_TOON=0`.
Management commands keep their human output unless `--json` or TOON is selected.
Keep JSON request documents and issued references unchanged.
Help, version text, stderr diagnostics, MCP, and tool RPC keep their existing formats.

## Choose the operation first

`query` runs one pipeline. Selection, traversal, trace, and impact are choices inside that pipeline.
Choose from the question before reading schemas or executing requests.

| Question | Operation | Inputs and expansion | Evidence returned |
| --- | --- | --- | --- |
| Which declaration is this? | Selection | Name, scope, location, or issued exact refs; no implicit relationship expansion | Exact symbols with signatures and locations |
| Who calls it, or what lies within N relationship hops? | Relation / traversal | `EXPAND_RELATION` follows one selected relation; `WALK` repeats it to `maximumDepth` | Symbols or individual occurrences; `TRAVERSAL_RECORDS` preserve walk depth |
| What declarations are affected through the standard use recipe? | `TRACE` terminal | Exact declarations; references, implementations or overrides, direct callers, and one further caller layer | Grouped symbols with proven connections and occurrences |
| Where does this produced value flow? | `IMPACT` source | Exact enclosing and callable refs plus the whole producer invocation range; forward Kotlin value flow | `VALUE_PATHS` or six `IMPACT_WITNESS` views, retaining model provenance and unresolved boundaries |

`TRACE` has a fixed topology. `WALK` has an explicit relation and depth.
`IMPACT` investigates values and their qualified transfers.
None establishes runtime behavior or unrestricted transitive reachability.
Discovery scope selects seeds; expansion scope restricts destinations.
`CALLERS` follows incoming calls; `CALLEES` follows outgoing calls, including constructors.

### Calls inside stored lambdas

Kast attributes some calls differently from IntelliJ IDEA.
IntelliJ call hierarchy and find usages name the function that encloses the call text.
A Kast caller is a function that runs the call in place, including through an inline lambda.

A lambda passed to a `fun interface` constructor, a class constructor, or a non-inline parameter is stored.
Its enclosing function does not run it.
For `fun build() = Gateway { target() }`, IntelliJ reports `build` as a caller of `target`. Kast does not.
Kast records the call in `relation_observations[].callback_observations`.
Its `named_policy` has type `EXCLUDED` and reason `NON_INLINE_ARGUMENT`.
The actual caller is the code that later invokes the interface method.

`TRACE` includes these calls as source relationships.
An explicit `CALLERS` query can reject with `CALLBACK_GRAPH_UNPROVEN` when Kast cannot prove who invokes the stored lambda.
Do not report the enclosing function as a caller from that evidence.

`source read` reads source under an issued selector.
`result read` and `result resume` deliver existing evidence without another semantic investigation.
Keep the original output kind for retained reads, including canonical detail reads.
Use a retained cursor with `result read`; use `result resume` only with an issued output continuation.

## Discover only what you need

For a request document, list example summaries if you need to find a match.
Select one example by its summary and adapt its illustrative values.
For a `RUN` request, set `request.executionBudget.maxResults` to bound the presentation.

```sh
kast describe query --section examples --jq '.content | map(.summary)'
kast describe query --section examples \
  --jq '.content[] | select(.summary | startswith("Trace callers for two hops")) | .value'
```

If the example leaves a schema question, start at the root page or use a known JSON Pointer.
Use a returned pointer to examine a child.

```sh
kast describe query --section input --pointer ''
```

Pass a `RUN` action or a complete public argument document to `kast query --request FILE`.
Use `kast query --request -` only when stdin contains that document.
The default catalog is `COMPLETE`.
An explicit `COMMON` selection remains restrictive.

Use `kast result read --ref REF` for retained output.
Use `kast result resume --continuation TOKEN` for an issued output continuation.
These commands emit one page without an implicit drain or semantic retry.
Never treat a visible page as full membership.

Read the outcome, qualifications, and recovery evidence before a semantic conclusion.
Retained policy rejection remains unsuccessful on every page.
An empty qualified result does not prove absence.
Omitted `--fields`, empty `--fields ''`, and explicit fields have distinct meanings.

Enable `set -o pipefail` when a shell pipeline depends on the Kast exit status.
Local cancellation does not prove native settlement.

## Optional MCP tools

`kast install <harness> --mcp` selects the optional MCP composition.
CLI, MCP, and tool RPC share the managed workspace Host and semantic contracts.
Use `find` before exact relationship tasks.
Use `source` with `ANCHOR` before a body region.
Use `schema` before an advanced existing operation through `raw`.
Source changes need the user's request, an issued exact target, and explicit write admission.
Do not infer live readiness from registration or schema admission.

## Fresh sessions

A core update interrupts owned work and invalidates Host-issued references after a Host transition.
Kast refreshes guidance for its installed harness integrations.
Start a fresh agent session after installation or an update.
If the core result reports `HARNESS_REFRESH_REQUIRED`, repeat `kast install <harness>` before that session.
A standalone skill export keeps its bytes until explicit removal or another output directory.
