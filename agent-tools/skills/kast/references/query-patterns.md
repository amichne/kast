# Common query patterns

The examples in [query-examples.json](query-examples.json) are complete
`query_symbols` argument documents generated from Kast's canonical tool schema.
Look up an example by its key under `examples`, then adapt its project-specific
name, package, path, source sets, and budget to the user's task. Discover the
installed schema before sending it.

Execution requires `COMPLETE_ONLY` within `COMPILER_RESOLVED_STATIC_V1`.
Supported questions run to exhaustion under the admitted grant, then return a
bounded presentation of the proven answer. Unproven answers reject with typed
evidence. RUN has no completion selector. Never treat retained partial proof as
a complete answer.

Exact-ref placeholders and all-zero UUID handles in these examples are
illustrative values for contract validation. They were never issued by Kast.
Replace them with unchanged values from the current workspace's preceding
responses. Structural admission proves the accepted argument shape; it does not
prove that a symbol, file, retained result, or continuation exists.

## 1. Find an exact declaration and read its context

Question: “Where is `OrderService` declared, and what does it do?”

Use `scopedExactDeclaration`. The source supplies the simple declaration name,
`CLASS` kind, package, and `main` source set before discovery. Output asks for
name, location, signature, and committed source. Exact matching is
case-sensitive and returns matching overloads rather than choosing one. Read
the signature and location before selecting a returned `ref`.

Use the existing `runByName` example when no scope is known. A qualified name or
signature is not a `declarationName`; put a package in `scope.packageName`.

## Trace the selected declaration

Use `traceAffectedUses` after selecting one exact declaration. Replace its
illustrative `symbolRefs` value with the unchanged issued `ref`. This separates
seed selection from tracing; a broad search for `execute` can match overrides
and unrelated methods as well as the intended interface method.

Keep the example's `WORKSPACE` expansion when clients outside the seed's module
matter. TRACE covers references, implementations or overrides, direct callers,
and one further caller layer. It groups declarations and preserves occurrence
evidence. Each scoped downstream callable is expanded once after its incoming
paths are gathered. Arbitrary depth controls and steps after TRACE are rejected.

Read the fixed `COMPLETE_ONLY` verdict before answering.
Use the admitted execution budget as the timing authority. The example requests
30,000 ms for TRACE; the short default grant can stop a native search earlier.
The host can reduce that request. If completion is unproven,
inspect the rejection's retained preview and result reference when available.
Use `READ_RESULT` to present retained proof. `RESUME` presents only already-produced
output. A historical semantic checkpoint grants no execution. A preview does
not prove completeness. Increasing a requested timeout cannot remove the host ceiling.

## 2. Inventory a public API or a naming family

Question: “Which public functions starting with `create` are in this module?”

Use `publicFunctionInventory`. It restricts discovery to functions in one
directory and source set, then applies visibility and name-prefix predicates.
Change the prefix or omit that predicate when listing the whole public API.
`ALL_DECLARATIONS` remains bounded by the execution grant. Strict execution
rejects when that grant cannot establish completeness. A presentation prefix
does not enumerate the whole inventory; page the retained result when needed.

## 3. Discover a declaration when the name is uncertain

Question: “Find the order service; I only remember part of its name.”

Use `fuzzyDeclarationDiscovery` with explicit `nameMatch: FUZZY` and the
smallest known directory and kinds. Compare returned signatures and locations
to identify the intended declaration. Fuzzy discovery does not authorize an
exact-target operation until Kast returns the selected exact `ref`.

## Find containing declarations from remembered text

Question: “Where is `launchd` involved under `app-server`?”

Use `scopedTextWord`. `SEARCH_TEXT` accepts one case-sensitive ASCII word,
such as `launchd` or `Disable`, and applies the same directory, package,
source-set and declaration-kind constraints as name discovery. It returns exact
refs for supported containing declarations, with a bounded exemplar in `matches`.
The match range uses zero-based UTF-16 offsets; `line` is one-based and `context`
may be a clipped part of that line. Matches explain lexical relevance and do not
establish a reference or call relationship. Reuse the returned `ref` for source,
callers or other semantic operations.

Phrases, qualified literals such as `AppServerAction.Disable`, and regexes are
rejected. Search the single word `Disable` in the narrowest known scope, then
inspect its context. Imports, file-level comments and unsupported nearest owners
produce qualification rather than invented declaration refs. Each owner is
refined once and carries one exemplar from this search; it does not enumerate
every text occurrence. A stopped indexed search leaves completion unproven. Retained proof keeps that
qualification; it does not authorize more execution.

## 4. Identify the declaration containing a position

Question: “Which named declaration owns this code location?”

Use `containingDeclaration` with a canonical workspace-relative file path and
a known zero-based UTF-16 offset within the declaration. It returns the nearest
containing named declaration. A line number or UTF-8 byte index needs conversion
before this request. It does not perform go-to-definition for a use-site token.

## 5. Find individual references and their use sites

Question: “Where is this exact declaration referenced?”

Use `referenceOccurrences` with the `ref` selected from an earlier query.
`EXPAND_RELATION` with `REFERENCES` and `OCCURRENCES` output preserves repeated
use sites and their compiler-confirmed relation facts. Read omissions and
qualification alongside the facts. Empty qualified output cannot prove that
the declaration has no references.

## 6. Trace one-hop calls

Question: “Which declarations call this function?” or “What does it call?”

Use `directCallers` or `directCallees`. `CALLERS` expands to calling declarations;
`CALLEES` expands to called declarations. `DISTINCT_SYMBOLS` returns one row per
canonical declaration identity and preserves its first evidence. For each call
site instead, omit distinct and request `OCCURRENCES` output.

## 7. Explore implementations, inheritance, and overrides

Question: “Which declarations implement this interface?”

Use `implementations`. Select the interface or abstract declaration with an
exact preceding query, then expand `IMPLEMENTATIONS` and deduplicate symbols.
The installed relation vocabulary also includes `INHERITORS`, `OVERRIDES`, and
`TYPE_USES`. Choose the relation that matches the question and retain its exact
meaning in the answer.

## 8. Trace bounded call paths

Question: “What reaches this function within two call hops?”

Use `boundedCallerWalk`. It walks `CALLERS` to depth two with explicit
breadth-first exploration and returns `TRAVERSAL_RECORDS` with hop depth and
relation evidence. Read walk progress and partial expansions before describing
the covered graph. `BOUNDED_FAN_OUT` can sample deeper paths; it qualifies
truncated coverage rather than proving an exhaustive traversal.

Omitting `maximumDepth`, or supplying null, requests exhaustive reachable-graph
traversal with default `WORKSPACE` expansion. An explicit depth defaults to
`RETAINED_SEED`; an explicit `expansionScope` overrides either default.

## Restrict native relationship destinations

Use `narrowReferenceExpansion` when only one source domain matters. Its
`SOURCE_DOMAIN` supplies exact imported `sourceSets`, `sourcePolicy`,
`generatedSources`, and an optional relative `directory` before native
enumeration. A later `WHERE` only filters returned rows. Use
`workspaceReferenceWalk` for an explicit one-hop workspace walk independent of
the seed's discovery scope. Preserve requested and effective domains and proven
exclusions when explaining coverage.

## Read an anchored occurrence or anonymous body

Use `readCallbackSource` with a `candidateSelector` issued in the current
workspace's occurrence, callback, or proof evidence. Pass it unchanged as
`request.candidateRef`. A symbol `ref` cannot substitute for this capability,
and the enclosing named function cannot substitute for the anonymous source
range. Reacquire the candidate after an epoch change; preserve source-read
freshness and authority failures.

## Investigate value impact and inspect retained proof

Use `representationImpact` for qualified static provenance from an exact
producer invocation. Supply current issued `enclosing` and producer `callable`
references and a UTF-16 anchor covering the whole invocation; native admission
revalidates those claims. Keep `steps` empty, choose an explicit `domain`, and
use the installed schema for reviewed `models` and their `declarations`.
Empty models supply no representation or boundary meaning and can leave
obligations unresolved. Static paths do not prove runtime execution.

The example retains `VALUE_PATHS` under the fixed complete-only policy.
After retention issues a result reference, use `readImpactProducers` or
`readImpactFindings` to inspect the original investigation. A finding links its
original path: present `VALUE_PATHS` with its `pathOrdinal` as `cursor` and
`executionBudget.maxResults: 1`. Retain terminal qualifications and obligations.
An unfinished evidence-only ledger can reject witness fields as
`RESULT_FIELD_UNAVAILABLE`; preserve that finite rejection.

Use `representationImpactRequestedSite` only with current exact site identity,
range, and role claims. Native admission revalidates them. Then use
`readImpactSiteAccounting` to distinguish `REACHED`, proven `EXCLUDED`, and
`RELATIONSHIP_UNPROVEN` sites. Identity alone does not prove a producer route.

Use `completeImpactFindings` when a complete static investigation is required.
It selects `COMPLETE_ONLY` and `IMPACT_WITNESS` findings, completing the original
ledger before presentation. Missing evidence or unresolved obligations reject;
do not infer completion from a compact finding or from retained paging.

## Retain, page, and compose

Use `retainByName` when a later query needs the immutable rows. Continue only
after retention reports an issued result reference. Retention failure does not
erase the query's returned facts, and cannot supply a usable result reference.

Use `readRetained` to present that result, replacing the illustrative result
handle. Use only a cursor issued for the same result when paging; presentation
does not execute semantic providers or refresh old evidence. Output must match
the retained row kind. A source window can be presented later only if the
original query captured it.

Use `intersectRetained` to compare two retained symbol results by canonical
identity. Replace both handles with current issued values. `UNION` and
`DIFFERENCE` share the retained right-input shape; `DIFFERENCE` requires complete
right coverage. `JOIN` adds a typed mode: `INNER` with different `leftName` and
`rightName` fields emits `BINDING_ROWS`, `SEMI` keeps matching left rows, and
`ANTI` requires complete right coverage. `PROJECT_BINDING` selects one named
cell before returning to symbol steps. Never infer equality from name alone.

Use `resumeQuery` only with an issued `query-output:v1` continuation to present
already-produced output. Its action carries that output token and an optional
presentation budget, with no source or steps. Public RESUME cannot continue
semantic execution; internal execution checkpoints, result references and row
cursors are not output tokens. If the original evidence is stale or unavailable,
report that blocker. A fresh semantic query requires a new explicit decision;
never rerun it merely to recover delivery.

## Source and result details

Directory scopes use canonical relative directories, including `.` for the
repository root. Package scopes use semantic Kotlin package names. Exact
source-set names intersect with that scope; omission defaults to `main` and
`test`. Discovery scope does not restrict where later relation expansion can
lead.

Output fields are independent of search eligibility. `NAME`, `LOCATION`,
`SIGNATURE`, and `SOURCE` are the supported symbol fields. An empty field list
asks for mandatory identity and kind only. `SOURCE` adds a bounded saved,
committed source window and can produce a finite per-item source failure.
Budgets bound time, work, returned rows, and encoded bytes. Increasing a grant
does not remove operator ceilings or make incomplete coverage complete.
