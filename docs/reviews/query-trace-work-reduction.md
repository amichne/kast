# TRACE work reduction: matched production rules

The matched query cases reduce relation dispatches and charged interpreter work
while preserving the same unique declaration, occurrence, authority, provenance,
and exhausted-question witnesses. A separate inventory-owner comparison removes
repeated preparation effects while preserving confirmation, examination,
pagination, qualifications, and omissions.

This is `SCRIPTED_PRODUCTION_RULE` evidence. It runs the real interpreter and
inventory reader with scripted external observations. It does not run IDEA's
native search, K2 confirmation, or the installed public transport. It does not
establish a live `lessWork` result, CPU, allocation, retained-memory, latency, or
completion improvement in the photographed repository.

## Exact source and inputs

The query comparison uses two compiled source revisions:

- Baseline: `7ec5427a664da2062a5cf0825749a22bee76d01b`.
- Candidate production head: `dc4a93e79d20b01ba82be4416b1ec989592aec68`.

The follow-up commit adds the measurement tests and this report; it changes no
production rule. The same [query workload](../../query/service/src/test/kotlin/io/github/amichne/kast/query/service/QueryTraceWorkTest.kt)
and [case-owned fixture](../../query/service/src/test/kotlin/io/github/amichne/kast/query/service/MethodTraceSymbols.kt)
were compiled on both revisions. Their SHA-256 hashes match:

| Common file | SHA-256 |
| --- | --- |
| `QueryTraceWorkTest.kt` | `8e53007a8363852a9b6492a83f44bb02702a46e69663e9edf4ba7ca21d413196` |
| `MethodTraceSymbols.kt` | `9b5cf637b391ad67a820bb359a142e65965682cdc13f4ba5244d5eb9acd7d163` |

The private comparison receipt also pins the compiled scheduler, checkpoint, and
identity-grouping classes, both JUnit reports, and each measurement. All 13 query
pairs complete and match their independently declared symbol and occurrence
witnesses. Missing work receipts fail the test; they cannot become zero work.

Each pair uses identical exact selectors, scope, scripted relation inputs,
result/byte/time grants, and work grant. Cases have 100 result slots, 1,000,000
returned bytes, a 10,000 ms allowance, and a case-owned frozen query clock.
Only the declared work grant varies between cases. Concurrency is one, and each
case has fresh service/checkpoint ownership. Every continuation drains under the
original grant; no failed case is retried or averaged into success.

The method graph contains the interface method, an override, one adapter, and one
downstream client. Reference multiplicity varies independently: 2, 8, or 32
arrivals at the same adapter. The expected TRACE output is four declarations and
respectively 6, 12, or 36 unique occurrence offsets. The downstream client must
never be expanded beyond the fixed TRACE topology. Denser relation batches use
the declared 1,024-unit grant because they cannot fit a four-unit fixture response.

## Query work observations

| Path | Reference inputs | Work grant | Relation dispatches, before → after | Charged work units, before → after |
| --- | ---: | --- | ---: | ---: |
| Method TRACE | 2 | 4, 6, 1,024 | 9 → 6 | 11 → 8 |
| Method TRACE | 8 | 1,024 | 15 → 6 | 23 → 14 |
| Method TRACE | 32 | 1,024 | 39 → 6 | 71 → 38 |
| One-hop REFERENCES | 2 | 4, 1,024 | 1 → 1 | 3 → 3 |
| One-hop REFERENCES | 8 | 1,024 | 1 → 1 | 9 → 9 |
| One-hop REFERENCES | 32 | 1,024 | 1 → 1 | 33 → 33 |
| One-hop CALLERS | 2 | 4, 1,024 | 1 → 1 | 2 → 2 |
| Exact-reference projection | 2 | 4, 1,024 | 0 → 0 | 1 → 1 |

TRACE performs one downstream caller read in all these cases. Baseline counts
were 4, 10, and 34. Dispatch reduction ranges from 33.3% to 84.6%; charged-work
reduction ranges from 27.3% to 46.5%. These percentages describe the named counters
in this graph. They are not percentages of native CPU or elapsed time.

The four-unit, two-reference TRACE drains in two pages instead of three. The
six-unit case takes two pages on both revisions. The sufficient-grant cases
complete in one page. The exact-reference case is projection of an already issued
capability; it does not measure declaration-name discovery or `AT_LOCATION`.

The raw TRACE projection differs. Repeated downstream reads constructed fresh
endpoints and emitted repeated canonically identical facts. TRACE removes 3, 9,
and 33 duplicate fact projections, respectively. The comparison retains both raw
and unique-evidence digests and counts duplicates explicitly. Unique fact
projections include relation meaning, exact source/target fingerprints, file and
range, authority revision, provenance, and coverage. Unique exhausted questions
include the subject fingerprint, meaning, and domain fingerprint. The 13 unique
evidence digests match. Raw digests also match for every control path.

This establishes preserved unique proof and reduced duplicate output; it does
not claim byte-for-byte TRACE projection parity.

## Inventory preparation observations

The [inventory workload](../../relation/intellij/src/test/kotlin/io/github/amichne/kast/relation/intellij/ReferenceInventoryWorkTest.kt)
compares the production reader's original `Disabled` owner with its admitted
`Recent` owner on the candidate artifact. It is an owner comparison, not a second
compiled baseline artifact. Each nonempty question has 12 detached locators,
128 work units, 100,000 returned bytes, a 1,000 ms allowance, and a frozen clock.
All seven cases run at result limits 1, 5, and 20: 21 admitted pairs.

| Questions within the case | Preparation effects, before → after | Confirmation effects on both owners |
| --- | ---: | ---: |
| REFERENCES then CALLERS | 2 → 1 | 24 |
| CALLERS then REFERENCES | 2 → 1 | 24 |
| REFERENCES, REFERENCES, CALLERS | 3 → 1 | 36 |
| One-hop REFERENCES | 1 → 1 | 12 |
| REFERENCES then a different destination domain | 2 → 2 | 24 |
| REFERENCES and CALLERS with separate invocation owners | 2 → 2 | 24 |
| Two empty definition-provider questions | 2 → 2 | 0 |

Both owners produce identical full canonical fact lists, qualified page proofs,
provider-state projections, and omission evidence. The only intermediate
qualification is the declared result limit. The terminal page proves its exact
count and has no omission. Confirmation and examined-work counts do not decrease.
Page counts also match: a paired read takes 24, 6, or 2 pages at the three result
limits. The receiver preserves the original destination boundary on every resume.

These observations establish fewer invocations of the native-preparation effect
boundary. They do not measure work inside `ReferencesSearch`. An unchanged
one-hop path is a negative control; separate owners and destination domains must
remain separate. Empty definition inventories are deliberately ineligible for
the reference slot. There is no claimed saving in these controls.

## Reproduction and remaining qualification

Compile the common workload and fixture files on the exact baseline and candidate
checkouts, verifying their hashes before running this selector in each:

```sh
mise exec -- ./gradlew :query:service:test --tests '*QueryTraceWorkTest'
```

Run the inventory-owner comparison on the candidate:

```sh
mise exec -- ./gradlew :relation:intellij:test --tests '*ReferenceInventoryWorkTest'
```

The JUnit `system-out` records declare path, input/grant, pages, dispatches,
charged work, duplicates, and raw/unique digests. The inventory records declare
path, result limit, preparations, confirmations, examined work, pages, and facts.
Reject any missing record, incomplete trial, differing common-file hash, differing
unique witness, or increased dispatch/work count. Control paths must match exactly.

Affected `check` tasks, JSON contracts, architecture, knowledge impact, and
knowledge verification passed after the measurement tests. The earlier full
product gate passed for `dc4a93e79`; the follow-up push runs that gate again.
The shared prose assessment remains unavailable because the required Vale executable is unavailable.

The installed host observed during this task serves Konditional with version
`0.20261009.5120`. It is not either measured artifact and is not the photographed
private repository. No installation or IDE restart was used for these results.

Native qualification still requires independently pinned baseline and candidate
hosts, the same immutable source fixture, identical public requests and budgets,
complete continuation drainage, and correlated native preparation/reuse receipts.
The existing [native reproduction workflow](../../experiments/host-observation/SEMANTIC_REPRODUCTION.md)
owns that evidence. Its current representative profile measures exact-source,
dense-reference, and scoped-inventory paths; these tests do not manufacture a
native TRACE comparison from that profile.
