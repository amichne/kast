# TRACE completeness r1 implementation record

Both frozen compiler-static TRACE questions complete on the private candidate:
Attributes retains 32 rows; FlowCollector retains 13 rows. The original scope,
eleven RUN requests and grants remain unchanged. This is native qualification
of the selected fixture, not runtime activation or full upstream coverage.

## Candidate and authority

The local implementation is on `feat/trace-completeness-r1`, based on
`a5391de62078f911d91e33a9b6920fc693f58f45`. The candidate is
`0.20261009.5120-4-ga5391de620`: the assembled Host plugin and matching Java
Control distribution include the local product changes. The base SHA alone does
not identify those changes. The artifact receipt records 90 installed jar hashes,
the plugin archive hash and all 1,591 production Kotlin source hashes. Those
source hashes were checked against the final working tree.

The private IDEA process was PID 542, build `IU-262.10968.63`. Starting and ending
pins reported saved, committed source, smart indexes and imported authored main
and test Gradle roots. All 15 loaded class hashes and all 12 disk/PSI source
hashes agreed at the end, after the freshness edit was restored.

The frozen fixture has 18 source/build/wrapper files and 66,733 bytes. Its
manifest SHA-256 is
`3111951fa903d4983d9af6f131e9c54cd39d008b487b307e45c64b634e3a3dd1`;
the requests manifest SHA-256 is
`4ac38e418885ee7c18439fed81a77fb2615d7c2b543318baf9efe6794f07e02a`.
Every RUN retained COMPLETE_ONLY, COMPILER_RESOLVED_STATIC_V1, RETAIN,
15,000 ms, 100,000 work units, 100 results and 524,288 bytes.

## Changes proved

Anonymous objects now retain their actual local address, compiler superclass
facts and containing owner through symbol, relation, source and public
projections. Their member overrides belong to the anonymous object. Exact
source reads restore both objects and preserve the same canonical identity.

Binary callback admission uses the actual resolved library function's declared
calls-in-place contract and mapped function parameter. A single matching
EXACTLY_ONCE effect can establish the supported static connection. The typed
proof preserves source occurrence, owner, semantic basis, parameter position
and an independently checked digest of the actual binary class. It neither
creates a dependency body invocation nor substitutes an authored source handle
for the detached dependency target. Nested callback obligations remain intact.
Bounded read, admitted and rejected counters instrument this boundary.

Public serialization retains both representations. Exact serializer reuse
keeps the tool schema within its existing size limit; no schema, work, time,
output, result or storage allowance was increased.

## Native acceptance

| Frozen question | Native result |
| --- | --- |
| A.TRACE | Complete, exhaustive; 32 retained rows |
| B.TRACE | Complete, exhaustive; 13 retained rows |
| A.IMPLEMENTATIONS | Complete; 2 rows |
| B.IMPLEMENTATIONS / B.OVERRIDES | Complete; 3 rows each |
| A.library.CALLEES / A.helper.CALLEES | Complete; 2 / 3 rows |
| B.named.CALLERS | Rejected: NESTED_CALLBACK_EXECUTION; useful evidence retained |
| N.stored.CALLEES | Rejected: PARAMETER_ESCAPES and NO_INVOCATION_PROVEN |
| N.conditional.CALLEES | Complete static evidence; one `also` row |
| N.spring.IMPLEMENTATIONS | Complete empty result |

The [source oracle](ORACLE.md) defines membership independently of execution.
The original draft's B count of 17 was wrong: four outer consumers require an
additional caller hop after the USE phase ends. The correct set is nine named
rows, two anonymous objects and two anonymous `emit` overrides. It has the same
count as the rejected baseline but different membership and stronger evidence.
This correction changes neither the frozen request nor TRACE fanout semantics.

All 15 public calls passed the generated contract validator. The oracle checked
exact membership, distinct object ownership, UTF-16 ranges, direct override
facts, declared contract provenance and negative controls. Oracle negative
checks rejected a missing anonymous implementation and a changed frozen grant.

Repeated retained pages preserved items, relation evidence, coverage, retention
and page progress. The published native discovery and relation counters were
zero for these reads. No absent compiler-refinement counter was interpreted as
zero, and no latency claim is made.

An owned prefix edit moved an anonymous object's anchors. Its old strict
reference rejected with `reference-rejected`, reason
`revalidation-unsupported-declaration`. Restoring the original bytes and
committing the IDE documents allowed rediscovery of all three implementations.

## Mechanical checks

The final product source passed:

```sh
mise exec -- ./gradlew build verifyJsonContracts verifyKastArchitecture \
  knowledgeImpact verifyKnowledgeBase --continue --console=plain
```

Focused selectors ran first for local identity, binary contract admission,
static callback graphs, encoded wire shapes, dependency target projection,
schema reuse and actual PSI source restoration. The full build then passed
with 596 tasks. After the OpenWiki lifecycle completed, the four contract,
architecture and knowledge guards passed again; knowledge validation reported
28 concepts and zero issues. Existing opt-in Codex/upstream native tests were
skipped by the ordinary build. This private suite supplies its own native proof.

The suite's preparation and pin carrier worked in two additional owned roots,
with unchanged fixture bytes and rejection of an existing root. Those checks
prove filesystem portability only. The matched native baseline and candidate
reused the same isolated r1 root across normal private IDEA restarts. A second
fresh-root native qualification was not performed. Native provisioning and
import remain explicit steps in the [suite procedure](README.md).

Python compilation and repository whitespace checks pass. Vale is unavailable;
its prose policy check is therefore unverified.

## Preserved evidence and cleanup

The original baseline remains in the local validation-suite archive as
`suite.zip`, SHA-256
`d5975a63604a399ec9a4f02fa09a6cd5f8ec4cb3825fc058ee2d33c5f891b9eb`.
The verified candidate archive is
`candidate-0.20261009.5120-4-ga5391de620.zip`, SHA-256
`2a15cb784db4621a499bb3b16e9b4d617ff345597a9525928459107184a1a443`.
Both reside beneath
`~/.local/share/kast/validation-suites/trace-completeness/r1-a5391de620/`.
The candidate has 45 entries and 2,312,323 uncompressed bytes, within the
128-entry and 32 MiB export limits. Its receipt verifies every entry's hash.
Source-read replies are excluded; their hashes and bounded assertions remain.
Repository fixture source and upstream licenses remain in this suite.

The private IDEA exited normally. Its owned Gradle daemons stopped, the private
compiler daemon exited, and no process retained an owned-root command anchor.
Only `/private/tmp/ktrace-eezyvuyb` was removed after archive verification.
`cleanup.json` records the checks. The daily IDEA remained PID 34151 at its
original application path. The candidate was installed only in the private
profile; at qualification, these implementation changes were local and unpublished.
