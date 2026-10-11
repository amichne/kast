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

## Validation plan and decisions

Use the existing producer-serialized query fixtures and adapter runner, plus pure
projection/framing tests and one private real-child fixture. Pin independent
expected references/evidence and count child starts, catalog/describe requests,
RPCs, semantic submissions, request/response/presentation bytes. Compare the
canonical legacy delivery with the compact projection under unchanged grants.
Cover complete, empty, qualified, rejected, multi-page, oversize, cancellation,
stale/invalid handles, duplicate occurrences, detail requests and first-outcome
preservation. Timings supplement counters; scripted host/describe counts are not
native latency evidence.

Limits: portable Pi/Copilot slice. Codex MCP/App Server presentation, actual model
usability, live native expiry/settlement, installation and merge remain outside
this draft. Parent owns independent reviews and merge/install decisions.
