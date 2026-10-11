# Compact awaited query delivery

Base: main `4d675dcf015bcd91480a2e38b9f077553b7ce1b7` (fetched and verified 2026-10-11).
Tracks the remaining portable-client delivery work in #987. #915 owns composed
impact investigations; this change does not add an investigation engine.

## Contract

A Pi/Copilot query is one awaited logical invocation. The existing generated
portable delivery rule owns READ_RESULT and output-only RESUME; it never retries
RUN. Canonical typed RPC documents remain the semantic authority. The first
outcome, coverage, qualifications and rejection survive later output reads.

The default text is a bounded useful projection: exact references and locations,
shared descriptions once, semantic outcome, coverage, delivery stop, live basis,
and recovery. Distinct occurrences remain distinct. Detailed canonical output is
available through READ_RESULT on the original authority-bound result handle;
verbose requests retain canonical presentation. Invalid, stale or expired handles
are rejected by the existing owner. No client cache revives authority.

When the catalog advertises support, one child owns a sequential query session
for this logical invocation. Otherwise the existing one-shot transport remains
compatible. The child accepts at most 64 bounded query records, and is closed on
completion, cancellation or the aggregate transport deadline. It is not retained
between invocations. Catalog discovery still occurs once per adapter load. Native
describe remains an authority check per physical RPC; no stale caching is added.

Semantic budgets, each response's wire-byte bound, aggregate delivery bytes and
transport lifetime remain distinct. This slice does not raise any quota, make
partial proof exhaustive, or promise native cancellation settlement.

## Decisions and measured work

Reuse the existing public query admission, typed canonical replies and generated portable
output-drain rule. Add an optional boolean catalog capability and one invocation-owned
NDJSON child; do not introduce a daemon or cache native describe authority. After the
first request the server accepts only the preceding reply's issued result reference or
output checkpoint, never another RUN. Public contract version 14 and request shapes
remain unchanged. The new finite transport failure is generated into both adapters.

Use a human text projection rather than another wire DTO. Keep canonical results intact
inside the adapter (Pi SDK details) and through `verbose: true`; detailed retained output
uses existing `READ_RESULT`. Group identical descriptions, never distinct occurrences.
Render every failure and omission inline, preserving the first qualified or rejected
outcome after later complete pages. Do not drop evidence without a usable retained handle.
Default projection avoids serializing the complete canonical envelope, but dictionary-key
construction still costs work on dense occurrence documents.

| Fixed boundary | Before | After |
| --- | ---: | ---: |
| 43-reference model-visible UTF-8 bytes | 9,542 | 5,073 |
| Qualified 43-reference visible bytes | 8,255 | 5,186 |
| Rejected proof visible bytes | 10,451 | 6,187 |
| Saved native 20-occurrence prefix visible bytes | 40,084 | 26,228 |
| Saved native 16-row/11-observation prefix visible bytes | 41,314 | 15,712 |
| Matched adapter complete child starts (including catalog) | 4 | 2 |
| Matched adapter complete RPC / catalog / simulated describe | 3 / 1 / 3 | 3 / 1 / 3 |
| Matched adapter complete semantic executions | 1 | 1 |
| Matched request JSON / response bytes read | 448 / 9,351 | 448 / 9,351 |
| Complete presentation JSON serialization bytes / calls | 9,542 / 1 | 6,124 / 50 |
| Dense occurrence presentation serialization bytes / calls | 40,084 / 1 | 53,320 / 68 |

Counters, exact boundaries and source hashes are in
[query-delivery-baseline.json](query-delivery-baseline.json) and
[query-delivery-transport.json](query-delivery-transport.json). Both adapters match.
The session adds one newline per request (3 bytes for this case); the capability adds
20 catalog bytes (75,819 → 75,839). Describe counts are simulated provider callbacks,
not evidence of live native work. Process observations separately record spawn return,
first stdout byte, child closure and stdout bytes. These timings are supplementary,
contended Node fixture observations, not a controlled native latency result.

The prior main production rule is replayed directly from Git against a fixed typed
producer fixture: canonical result/evidence equality holds for all seven baseline cases.
The saved native documents are replayed without executing native work. The 185-reference
receipt still states 16 displayed rows, original total 185 and 11 available observations;
this track does not claim an exhaustive visible answer or expand the original byte cap.

## Validation and compatibility

Passed `:cli:check verifyJsonContracts verifyArchitecture knowledgeImpact
verifyKnowledgeBase`, public adapter generation parity and 15 generator tests.
CLI verification includes 417 JVM tests, 39 required pure Node presentation/session tests,
and 116 passing adapter tests with one opt-in installed-harness loader skipped.
The real JVM-child bridge fixture preserves exact replies and issued authority with
scripted providers. Cases cover complete/empty/qualified/rejected, multiple pages,
oversize replies, bounded cancellation/deadline/pipe cleanup, invalid/stale handles,
duplicate occurrences, detail reads with zero RUNs, and first-terminal preservation.

Machine consumers parsing adapter text as JSON must request `verbose: true`.
The offline Pi evaluator validates existing canonical SDK details for compact query
text (including saved-result continuation), with the same closed decoders, while
counting only actual visible text bytes. Legacy JSON-only records remain supported.
Raw RPC, public query inputs, wire outcomes and retained authority remain canonical.
Absent/false session capability keeps one-shot transport; invalid capability types
reject catalog admission. The generated callable reference includes the finite
query-session transition failure. Mintlify rendering was not completed: the installed
validator writes the shared user preview cache outside this worktree; canonical
reference generation/verification passed. No native quota or semantic budget is raised.

Limits: portable Pi/Copilot slice. Dictionary serialization cost is still higher for
dense occurrence fixtures. Catalog discovery and per-RPC native authority checks remain.
Canonical drain bounds may still leave output/evidence undelivered; compact text does
not buy extra drainage. Codex MCP/App Server presentation, actual model usability,
live native expiry/remote cancellation settlement, installation and merge remain
outside this draft. Parent owns independent reviews and merge/install decisions.


## Independent review follow-up

A received observation cannot be replaced with a detail placeholder after the same
invocation has observed `DELIVERY_UNAVAILABLE`. The retained native fixture followed
by the committed expired-result rejection reproduced that bug. The repaired projection
keeps its 16 occurrence rows, original total 185, one discovery and ten relation
observations inline, alongside the exact unavailable rejection and recovery. It offers
no READ_RESULT request for that known-dead handle. The regression executes exactly one
RUN and one READ_RESULT, with no semantic recovery. Canonical replies remain unchanged.

Dense 20-occurrence presentation serializes **53,320 bytes in 68 JSON calls** versus
40,084 bytes in one legacy envelope serialization: **+13,236 bytes (+33.0%)**. Of the
new total, 40 dictionary-key construction calls serialize 26,129 bytes. Independently,
visible text falls 40,084 → 26,228: **−13,856 bytes (−34.6%)**. These count generated
JSON string bytes, not CPU time or wire traffic; they exclude SDK transcript persistence,
provider framing and transport serialization. No dense CPU-speed claim follows.

The offline installed Pi 1.0.2 test runs its actual `convertToLlm` and OpenAI Codex
`convertResponsesMessages` against seven fixed projections, including the repaired
unavailable-handle case. Intermediate SDK tool-result messages retain canonical details.
The provider converter emits exactly the compact content and does not serialize those
details: changing details leaves provider input byte-for-byte equal, and its sentinel
never appears. Every output character reaches the provider converter; occurrence/failure
and already-received-observation checks remain executable. This pure conversion test
uses no credentials, session, provider request or inference; source hashes are recorded
in the local review evidence. It is opt-in via `KAST_PI_PACKAGE`; ordinary CI does not
require an installed Pi. This proof covers that installed SDK/OpenAI Codex converter,
not every provider, extension, custom transform or future SDK version. Canonical details
still incur local SDK/archive storage and may be exposed by other consumers.

Old catalogs still receive the same compact text. For the matched 43-reference fixture,
one-shot fallback costs four children (catalog + three calls), versus two for a supported
session. Both use three RPCs, one catalog, three simulated describes, one RUN, 448 JSON
request bytes and 9,351 response bytes; fallback adds no new semantics or descriptions.
The old catalog reads 75,819 bytes and the supported catalog 75,839; total child stdout
is 85,170 versus 85,190. Sessions add three request framing bytes. These byte boundaries
exclude SDK/provider serialization and must not be inferred as transport latency savings.


A thrown retained read can also yield `DELIVERY_UNAVAILABLE` without a canonical
handle rejection. The text therefore says "detail delivery unavailable" and does
not infer invalid authority from transport loss. An exact zero-rejection-page
regression keeps already-received observations inline and forbids semantic retry.


Detail request availability is separate from evidence presentation: only canonical
`execution-rejected` with `result-unavailable`/`result-stale-basis`, or explicit
qualified `retention_unavailable`, removes the
retained READ_RESULT request. Transport/decoder loss keeps that exact structured
request while displaying all received observations inline. A field/cursor rejection
also leaves the original result authority available. Recovery text refers to an
action only when a canonical reply supplied it. Explicit expiry, stale basis,
field rejection and zero-page transient loss have separate assertions.
