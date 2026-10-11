# Received partial-prefix admission

This bounded Pi harness interpretation permits at most one answer from a received
`BYTE_LIMIT` prefix when `evidenceOnlyDelivery=true`. It preserves the native
rejection and canonical bytes and authorizes no continuation. Only exact-symbol
and reference-occurrence output are supported. Production execution, retention,
transport bounds and authority owners are unchanged.

The complete generated QUERY_RUN result schema admits the rejection and every
page first. This proves DTO shape, closed variants, required fields and primitive
bounds. It does not prove compiler truth, producer origin, current liveness or
later handle validity. Saved results remain historical observations.

## Observable field inventory

The table covers the initial rejection, recovery proof, pages and outer envelope.
Nested rows and evidence are schema admitted; only relationships needed by this
interpretation are checked here. Derivations requiring the original execution
are recorded as historical observations, rather than reconstructed from a prefix.

| Fields | Existing owner | Admission or retained limitation |
|---|---|---|
| Initial operation/status/rejection | Canonical schema / `CanonicalQueryCliDocuments` | COMPLETION_UNPROVEN rejection; never rewritten to success |
| Initial next_action | `QueryRunRejection.recoveryAction` in `ReadRecoveryAction` | Exactly report_failure |
| Detail model/policy progress | `QueryCompletionRejectionCliDocument` | Closed model; EVIDENCE_ONLY; model agrees with pages |
| Detail cause | `completionProof(QueryRetainedResult)` in `QueryCompletionFailure` | INCOMPLETE_EXECUTION: qualified original coverage precedes item failures/omissions for these outputs |
| Original coverage | `QueryCoverage` / `QueryCompletionEvidenceRead` | Qualified, positive minimum, terminal incomplete; equal in all interpretations. Historical minimum/limitations/progress retained, not derived from received rows |
| Detail stop/original failure/diagnostic read ID; initial execution budget | Completion/protocol failure and scoped trace owners | Typed historical data preserved. Prefix cannot reconstruct execution or replay a diagnostic handle |
| Proof result/question/preview | `QueryCompletionEvidenceDocument.Retained` | Same issued result throughout; same full question; preview equals ordered cursor-zero received prefix, including IDs |
| Proof nextQuery | `QueryCompletionRejectionCliDocument` | READ_RESULT, same result, cursor zero, public output derived from question |
| Optional seedQuery | `completionSeedQuery` | Absent/null accepted. Otherwise symbols, exact-name discovery, no steps, first exact preview reference and matching public output. No grant for RUN |
| Page operation/status/question | Canonical schema / `QueryResultEnvelope` | Qualified QUERY_RUN; question equals proof, including scope/operators/completion/fields |
| Page live | `QueryReferenceAuthority` / canonical live presentation | Stable observed host/root/epoch/view/version; non-null host, positive epoch, supported version. Historical consistency does not renew liveness |
| Page interpretation | `QueryCompletionEvidenceRead` | POLICY_REJECTED_EVIDENCE; model/cause/original coverage equal initial detail |
| Qualification/coverage/terminal reason | `completionEvidenceRead` / `RetainedQueryPresentation.pageCoverage` | Nonexhaustive; original minimum/limitations plus static-model-unproven and upstream-incomplete. Extra result-limit-reached requires row cursor; inherited original result limit remains valid. Matching terminal reason |
| Invocation/continuation | `QueryOutcomeProjection.readRetained` / `QueryResultEnvelope` | Absent/null; no new semantic invocation/continuation |
| Impact accounting | `QueryResultEnvelope` / `validateImpactAccounting` | Exactly NOT_APPLICABLE for supported non-value outputs |
| Items/identity | `QueryItemProjector` / issued row identity | Correct family; no repeated issued ID. Legacy symbols use exact reference; legacy occurrence identity preserves target/meaning/file/range/context/ownership/provenance; distinct occurrences remain distinct |
| Symbol fields | `QueryItemProjector` | Unrequested fields absent/null. Selected field contents are typed historical compiler observations |
| Occurrences | `QueryItemProjector` / relation contract | Target selector equals row reference, ordered source range, canonical ownership/provenance shape; no compiler reconstruction |
| Six evidence arrays | `QueryEvidencePresentation.Partition.select` | Typed failures/omissions/walk/reference/discovery/relation observations preserved and counted; family order cannot move backwards. Nested semantic claims remain producer observations |
| Evidence window | `QueryEvidencePresentation` | Contiguous from zero; fixed total; bounded start/end; MORE iff evidence remains; entry count equals advance |
| Row cursor | `RetainedQueryPresentation` / shared client | Contiguous row count; no revival after row exhaustion; legacy absent/null final cursor preserves its meaning |
| Every page's progress/successor | `awaitQueryDelivery` | Advances rows or evidence and retains row/MORE-evidence successor; no nonadvancing or intermediate terminal page the client could not traverse |
| Full page progress | `QueryPageProgressCliDocument` | Exact rows/evidence counts, including nested records/callbacks/exclusions/failures/omissions |
| Retention | `QueryResultEnvelope` | Reference equals proof/delivery; invalid or stale outcome cannot enable exception |
| Page execution budget/reference acquisitions | Scoped READ_RESULT accounting / canonical schema | Typed observations retained. Prefix cannot infer native usage or revalidate acquisition compiler history |
| Outer stop/bounds/outcome/RPC and byte counters | `query-delivery-contract` / `awaitQueryDelivery` | Existing finite closed decoding; BYTE_LIMIT only, intact rejection and nonempty pages. Historical counts do not prove transport origin |
| Harness/answer requests/first terminal | `CasePolicy` / provider guard | Explicit true policy; one full-body-admitted answer; no RUN/READ_RESULT/RESUME; canonical unchanged; native REJECTED and exhaustive verification false. False policy/other stops unchanged |

Owners are under `protocol/contract`, `protocol/wire/presentation`,
`query/contract`, `query/protocol` and `cli/src/main/js`. JavaScript adds no second
native semantic authority. Unsupported outputs stay blocked. This inventory is
not a general validator of every canonical semantic combination or proof of native
origin. Coupled historical mutations can require facts absent from the prefix.

## Producer-backed executable check

`PartialPrefixProducerTest` supplies detached compiler facts to retained result
and state owners. Real `CanonicalQueryProtocol.executePage(READ_RESULT)` and
`CanonicalQueryCliDocuments.project` emit pages and recovery fields. An erroring
`QueryOperations` capability proves zero evaluator calls. The retained execution
is deliberately qualified/incomplete; no native discovery or compiler acceptance
is performed.

Its required Node child runs the real shared client with canned replies and the
unchanged 262,144-byte cap. Exact-name, fuzzy-name, location, occurrence and
exhausted-row/remaining-evidence cases reach BYTE_LIMIT. Fresh/saved admission
preserves canonical data; false policy blocks; every further query request is
denied; only one provider admission is permitted. Single/coupled mutations of real
producer output reject contradictory recovery action, cause and accounting. No
provider send occurs. The synthetic minimal body does not prove full SDK context
fit. The preexisting guard cases separately test complete-body byte rejection.

About 2.5 MB of generated fixtures remain only in owned build reports. One test
child has a 30-second wait and five-second owned teardown. All consumed JS/schema
files are declared Gradle inputs. There is no new shipped fixture asset, model-path
process/RPC or inference dependency. Checks use existing runners:

```sh
node --test experiments/host-observation/pi_evaluation*.test.mjs
python3 experiments/host-observation/test_pi_evaluation_question.py
./gradlew :query:protocol:test --tests '*PartialPrefixProducerTest' --max-workers=2
```

## Cost and a smaller future boundary

The existing generated result schema snapshot is 676,659 bytes; finite view
consistency checks are in one harness function. Response bytes, production
processes/grants/schemas are unchanged. The required cross-runtime fixture adds
material test work; it is not native latency evidence. Repeated review findings
expose the cost of admitting full recovery/evidence documents into provider context.

A smaller separate design could present one compact admitted prefix: exact received
rows, question/scope, native failure/minimum, received/missing evidence, observed
live basis and detail reference. Canonical archive and authority-bound details
would remain available. This would reduce exposed recovery machinery and checks,
but needs an explicit guard/presentation contract and compatibility tests. This
patch does not silently drop canonical data or implement that alternative.
