# Native storage policy

Status: implemented policy with six unchanged oracle cases. The actual RED failed all six cases. The same procedure then passed all six.

The examined working tree starts at `543509a7f4d22d6ec850f52b70c898bca478c37d`.
[`ValueFlowFootprint.kt`](../../../../relation/contract/src/main/kotlin/io/github/amichne/kast/relation/contract/ValueFlowFootprint.kt) remains the owner.
One request-local meter accounts for one detached step.
It uses JVM identity and the existing `stateEndpoints()` collector.
An extracted frame overload shares that collector with source-operation evidence.
The meter adds no semantic cache or query dependency.

Each distinct site, edge, obligation, path segment, and predicate keeps its 2,048-byte record.
Each distinct frame keeps its 4,096-byte record.
Each distinct endpoint keeps 2,048 bytes plus twice its `detachedTextUnits()`.
A repeated immutable payload costs an 8-byte reference.
`NormalBranchResult` keeps its existing 4,096-byte allowance.
All sums and products saturate.

An exact root-source alias adds no payload charge.
Its parent edge or obligation record covers the pointer.
The 21 refusal causes keep their 43,008-byte reserve.
The quota, reserve, and query multiplier remain unchanged.

The oracle isolates six costs:

- A new target costs its edge record, site record, and four shared-payload references.
- A detached equal source costs its distinct site record and four shared-payload references.
- A detached equal endpoint costs its full allowance instead of one reference.
- A detached equal path segment costs 2,048 bytes instead of one reference.
- A detached equal frame costs 4,096 bytes instead of one reference.
- A detached equal predicate keeps its record and the reference to its existing exact subject.

The earlier alias oracle assumed a detached site always costs its full standalone estimate.
The historical source copies preserve that exact assertion:

- [`ValueFlowSourceAliasStorageTest`](segment4-native-physical-historical-ValueFlowSourceAliasStorageTest.kt.txt)
- [`ValueSiteContractTest`](segment4-native-physical-historical-ValueSiteContractTest.kt.txt)

The unchanged assertions failed after the meter change, with expected costs of 4,702 and 4,696 bytes against 2,080 actual bytes.
The revised assertions explicitly preserve a 2,048-byte header and four 8-byte references.
The original RED/GREEN evidence and the later assertion failures remain separate records.
All 29 focused cases then passed, with no failures, errors, or skips.
`detekt`, `spotlessCheck`, and both file-length tasks passed.

The required C24 positive path passed after the query-evidence correction.
The deeper route still stopped at `Outcome.kt:571:594` with `BYTE_LIMIT_REACHED`.
Its measured candidate costs 120,866 bytes with a 43,008-byte reserve against a 125,375-byte grant.
[`native-c24-query-evidence-meter-green.xml`](native-c24-query-evidence-meter-green.xml) keeps that receipt.
Native behavior after the meter change is **NOT RUN** in this procedure.
