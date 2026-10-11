# Automatic invocation retention decisions

Base: main `059d7cdc57d45a5404c3a31d8d6dcaa7e3f00dde`.

The sealed native Pi fixture rejects unfinished task storage at 3,597,622
conservative bytes against a remaining allowance of 3,417,074 bytes. Its known
minimum is 194 of 1,001 references. That observation does not contain the exact
invocation facts/issued-state/request decomposition. The global store gauge is
not a substitute. No native replay or provider inference is part of this work.

The existing pure dense fixture uses different grants. On unchanged main it
retains 393 rows and rejects a 789,650-byte checkpoint against 685,851 bytes.
Facts retain 19,921,968 bytes; final invocation charge is 31,609,640 bytes.
The existing inventory identity and transaction proof remains authoritative.

`CanonicalQueryProtocol.execute` runs internal pages with Immediate publication.
Every consumed checkpoint becomes a stored immutable replay page. That is real
retained state and its conservative charge is legitimate. Automatic invocation
tokens have not been returned to the caller; only final resumable progress may
escape. The bounded source option is to end the private replay lifetime after
successful consumption, preserving the final checkpoint and every public replay.

The implementation must prove private allocation ownership before release.
Existing ready checkpoints can be reused, so merely observing a token in an
automatic page is insufficient. A private checkpoint must also be excluded from
public reissuance until it is explicitly published as final progress. Reservation
and publication rollback stay in their existing owner; expiry must not extend
authority. Non-advancing semantic checkpoints remain checked independently.

Alternatives: lowering multipliers without allocation evidence would weaken the
bound; raising quotas would hide finite demand; deduplicating equal detached
objects would lose true allocation identity. This change will do none of those.
Dropping semantic checkpoint history would weaken the non-advancing proof and is
outside this slice. Public hosted page fitting and client delivery belong to
separate owners.

No incorrect charge was established. This source option removes retained state
whose private lifetime has ended. Its matched pure fixture proves semantic
identity, rollback, cancellation, repeated reuse, public replay and unrelated
owner controls. Accounted bytes are not heap or latency measurements.

## Source checkpoint

Tracking PR: [#1005](https://github.com/amichne/kast/pull/1005). The initial
characterization head `95e14c5b8c6801a867127f2595fbb12086fd2168` passed the
full pre-push product gate. Source head
`313df82feec75bd5879e7481dc8a81ed23bd6459` includes main's independently
merged presentation change `cf58079b92623cd6ddf84e32234cf84b60ec87fc` and
passed the full 573-task product gate. Its owning-module test reports contain
285 protocol, 179 service, 82 contract and 316 hosted tests, with zero failures
or skips. The parent still owns independent review and CI qualification.

The working source adds an invocation identity capability to private checkpoint
publication. Only newly allocated children of a private attempt inherit it.
Reused public checkpoints and their replay chains keep their existing lifetime.
Private checkpoints cannot be publicly reissued while the invocation owns them.
A successful commit ends the consumed private replay lifetime. Final progress
transfers the remaining checkpoint to the final publication claim. Public
restoration and acquisition stay unavailable until that claim commits; discard
releases the staged checkpoint and result. Projection can restore progress only
through the proven final claim. Exceptional exits use existing publication
rollback before private cleanup. Dependency and active claim pins still prevent
release. Facts and non-advancing checkpoint history keep their existing charge.

Completion rejection may advertise progress through `originalCoverage` even
when retained evidence is unavailable. Publication now follows actual page
dependencies, so that progress receives the same final commit/discard boundary.

The focused fixtures cover fifteen cases through the production protocol,
runner and store. A reused public checkpoint exposes an entire public replay
chain; ancestors replay unchanged while its final successor is running and
after automatic completion. A private Ready checkpoint cannot be reissued or
looked up publicly, or aliased by a second invocation with the exact same
request/checkpoint identity. Foreign and repeated cleanup are harmless.

Cooperative cancellation after consumption preserves the final progress and
original expiry. Hard cancellation before producer commit rolls back the
reservation. Five repeated invocations amid twenty unrelated public owners
preserve those exact owners and charges. A suspended private producer survives
another invocation's cleanup; retirement prevents further production and keeps
only its legitimate 256-byte revocation receipt. Invalid final projection,
publication rejection and PREPARED discard release their staged state.

| Pure boundary | Before | Working source |
| --- | ---: | ---: |
| Three-page semantic calls | 3 | 3 |
| Store entries after inline completion | 2 | 0 |
| Store charge after matched three-page replay (full gate) | 684,312 | 0 |
| Dense known minimum | 393 | 633 |
| Dense facts before rejecting execution | 19,710,623 | 31,362,371 |
| Dense issued state at that boundary | 12,467,838 | 989,406 |
| Dense request charge | 4,268 | 4,268 |
| Required checkpoint task charge | 789,650 | 789,650 |
| Remaining checkpoint allowance | 685,851 | 599,193 |

The dense endpoints differ because the new source advances farther. Their byte
totals are not a matched-prefix allocation comparison. The three-page fixture
is the matched deterministic lifetime comparison. The new dense run performs
32 provider batches, retains at most one private store checkpoint at each
observation, and still rejects legitimate finite demand. Native Pi minimum194
remains unqualified by this different pure fixture. Inventory sharing, detached
copy charging and graph transaction rollback were not changed.

The table records the full-gate observation. The focused selector measured
684,336 bytes for the public three-page control. Existing
`QueryRetainedEvidence.retainedStorageBytes` estimates rows via `toString()`;
`SymbolDescription` includes a `SymbolSelector` with default identity text.
That text's length may vary between JVM executions. The stable proof is two
public replay allocations versus zero private replay allocations, the same
three semantic calls and preserved compiler identity. Absolute conservative
byte observations are not a deterministic heap measurement; this source option
does not change that existing estimate.

The named zero-owner check first failed with expected zero versus two retained
entries, then passed through the production protocol/runner/store path. The
public-page negative control reproduces the two charged replay owners with the
same three semantic calls and rows, and replays without additional effects.
Run `./gradlew :query:protocol:test --tests '*AutomaticPrivate*Test' --tests
'*AutomaticFinalPublicationTest' --tests '*AutomaticDensePrivateLifetimeTest'`
for the focused proof.
The existing `AutomaticDenseRetentionTest` uses Immediate publication to retain
the historical replay-owner control. Broader validation ran
`:query:protocol:check :query:service:check :query:contract:check
:runtime:hosted:check verifyJsonContracts verifyArchitecture knowledgeImpact
verifyKnowledgeBase`; all passed. OpenWiki reported affected concepts and zero
citation issues. Generated pages remain owned by its scheduled lifecycle.

Final PREPARED cases exercise the real store and publication capability at the
protocol boundary. Existing hosted encoder and rejection publication tests pass;
the new fixtures do not instantiate a native `HostedSemanticReadContext` or
claim a live `HostedQueryPublicationSession` composition. Parent owns independent
review, merge, exact-head CI qualification and later rollout.

## Separate observed boundaries

Sanitized user-supplied enterprise observations identify latency before the
first eligible index callback, argument-contract guidance and saved-versus-active
configuration provenance. They were not independently reproduced here. Private
replay release occurs after admitted semantic pages and does not address those
boundaries. Callback count and response bytes cannot establish the cause of
pre-callback latency. No matched timing or deployment-setting claim follows
from this source change.
