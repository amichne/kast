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

Use `kast query --request FILE` for the complete public argument document.
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
