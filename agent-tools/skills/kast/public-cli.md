# Public CLI reads

The installed `kast` command answers Kotlin queries without MCP registration.
Kast supplies a private IntelliJ Host; no user IDEA installation is needed.
Session hooks and discovery remain passive. Use the first useful operation rather than a readiness probe.
Queries do not fetch releases. A missing runtime requires explicit management repair.
Semantic results, errors, plans, and descriptors use JSON by default. Keep request documents and issued references unchanged.

## Choose the operation first

`query` runs one pipeline. Selection, traversal, trace, and impact are choices inside that pipeline.

| Question | Operation | Inputs and expansion | Evidence returned |
| --- | --- | --- | --- |
| Which declaration is this? | Selection | Name, scope, location, or issued exact refs; no implicit relationship expansion | Exact symbols with signatures and locations |
| Who calls it, or what lies within N relationship hops? | Relation / traversal | `EXPAND_RELATION` follows one selected relation; `WALK` repeats it to `maximumDepth` | Symbols or individual occurrences; `TRAVERSAL_RECORDS` preserve walk depth |
| What declarations are affected through the standard use recipe? | `TRACE` terminal | Exact declarations; references, implementations or overrides, direct callers, and one further caller layer | Grouped symbols with proven connections and occurrences |
| Where does this produced value flow? | `IMPACT` source | Exact enclosing and callable refs plus the whole producer invocation range; forward Kotlin value flow | `VALUE_PATHS` or six `IMPACT_WITNESS` views, retaining model provenance and unresolved boundaries |

| Which declarations satisfy a compound condition? | Set stages over retained results | `--intersect`, `--union`, `--minus`, `--with`, `--without` with a retained right side | Symbols by canonical identity; absence stages need complete right coverage |

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

## Compose retained results

`--retain` publishes the complete investigation as an immutable result on the current read basis.
`--from RESULT` starts a new pipeline from it. `--all` enumerates declarations in the `--in` and `--source-set` scope.
Pipeline flags lower to `steps` in the order that they appear:

```sh
kast query --all --kind function --in services/orders --source-set main --visibility public \
  --without "$tested" --fields name,signature,location
```

`--minus` lowers to `DIFFERENCE`. `--with` and `--without` lower to semi and anti `JOIN` stages.
`--minus` and `--without` reject a right side without complete coverage.
Inner joins and binding rows still need a request document.

`--relation-in DIR`, `--relation-source-set NAME`, `--relation-production-only`, and `--relation-exclude-generated` lower to one `SOURCE_DOMAIN` expansion scope.
That scope applies to every `EXPAND_RELATION` stage and to a `TRACE` terminal. It restricts destinations before native enumeration.
Omitted source sets select `main` and `test`. Without the two policy flags, the scope includes test and generated sources.
These flags reject when the query has no relation stage and no trace.

## Request documents

For `WALK`, `IMPACT`, retention, or composition, adapt one installed example.
List the summaries, then select one example by its summary:

```sh
kast describe query --section examples --jq '.content | map(.summary)'
kast describe query --section examples \
  --jq '.content[] | select(.summary | startswith("Trace callers for two hops")) | .value'
```

Replace each placeholder ref with an issued ref. For a `RUN` request, set `request.executionBudget.maxResults`.
Pass the document with `kast query --request FILE`, or with `--request -` when stdin holds it.
If the example leaves a schema question, read the root page, then follow returned pointers:

```sh
kast describe query --section input --pointer ''
```

## Retained output

`result read` and `result resume` deliver existing evidence without another semantic investigation.
Use a retained cursor with `result read`. Use `result resume --continuation TOKEN` only with an issued output continuation.
Each command emits one page. Never treat a visible page as full membership.
Retained policy rejection remains unsuccessful on every page.
Omitted `--fields`, empty `--fields ''`, and explicit fields have distinct meanings.

## Failures

A rejection prints its document without the `--jq` projection and exits with a nonzero status.
Read `.document.rejection`, `.document.failure`, and `.document.next_action`.
Never repeat an identical failed call. Change an input or a prerequisite first.

| Exit | Meaning | Action |
| --- | --- | --- |
| 2 | Invalid command, flag, value, or request | Inspect the flag, then correct the call. |
| 3 | Workspace root rejected, or a path outside it | Use the trusted workspace root. |
| 4 | Runtime or workspace preparation failure | Act on the cause code below. |
| 10 | Semantic rejection or qualified result | Report the cause. Act on `next_action`. |
| 11 | Byte limit reached before delivery | Narrow `--fields`, or raise `--max-returned-bytes`. |
| 124 | Explicit time limit reached | Native work can continue. Do not retry automatically. |

| Code | Meaning | Action |
| --- | --- | --- |
| `GRADLE_DISTRIBUTION_UNAVAILABLE` | No usable `gradle/wrapper/gradle-wrapper.properties` | Tell the user. Do not create a wrapper unless asked. |
| `GRADLE_JVM_UNAVAILABLE` | No compatible JDK for that Gradle version | Tell the user to set `org.gradle.java.home` or install a JDK. |
| `IMPORT_FAILED` | Gradle import failed | Report it. Do not repeat preparation until the build changes. |
| `UNLINKED_BUILD` | The Host does not own the Gradle link | This can follow a failed first import. Report the earlier cause. |
| `reacquire_authority` | The `next_action` for a reference that this Host does not accept | Select the declaration again and use the new ref. |
| `CALLBACK_GRAPH_UNPROVEN` | Kast cannot prove who runs a stored lambda | Do not name the enclosing function as the caller. |

If Kast cannot answer, use ordinary source reads and state the limitation.
A client timeout or cancellation leaves Host settlement uncertain. Keep the failure and its recovery facts.
Set `KAST_DEBUG=1` to send preparation stages to stderr.

## Optional MCP tools

`kast install <harness> --mcp` selects the optional MCP composition.
CLI, MCP, and tool RPC share the managed workspace Host and semantic contracts.
Use `find` before exact relationship tasks. Use `kind` `class` for interfaces.
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
