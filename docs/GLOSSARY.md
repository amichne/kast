# Slopsentral Glossary

Generated from `glossary.json`. Edit the JSON source.

Kast public semantic query documentation.
The definitions preserve the source-backed draft at the inspected checkout. Independent reader review remains absent. Runtime contracts and code identifiers stay unchanged.

## Terms

### workspace root

The canonical root of the exact repository or worktree admitted for an operation.

Scope: Kast public query documentation and its documentation policy.
Example: A query belongs to one repository or worktree root.
Distinction: A second worktree has a different root, even when both contain the same commit.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [SemanticReadAuthority](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt), examined 2026-10-13. The canonical root of the exact repository or worktree admitted for an operation.

Local evidence:

- `workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt`: SemanticReadAuthority.

Rejected forms:

No replacement or review target passed the evidence review.

### exact reference

An opaque handle issued by Kast for an exact declaration. Reuse needs current identity admission.

Scope: Kast public query documentation and its documentation policy.
Example: A discovered function supplies a `ref` for a later `SYMBOL_REFS` query.
Distinction: A retained result reference identifies a stored result rather than one declaration.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [SymbolSelector](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt), examined 2026-10-13. An opaque handle issued by Kast for an exact declaration. Reuse needs current identity admission.

Local evidence:

- `symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt`: SymbolSelector.

Rejected forms:

No replacement or review target passed the evidence review.

### canonical identity

The established declaration identity used to compare symbols. Display names and opaque reference strings alone do not establish equality.

Scope: Kast public query documentation and its documentation policy.
Example: Two joined rows can identify the same declaration through different opaque references.
Distinction: Two functions with the same display name can have different owners and identities.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [SymbolSelector](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt), examined 2026-10-13. The established declaration identity used to compare symbols. Display names and opaque reference strings alone do not establish equality.

Local evidence:

- `symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt`: SymbolSelector.

Rejected forms:

No replacement or review target passed the evidence review.

### semantic basis

The provenance of an operation's evidence, expressed as published workspace state or live IDEA read state. A provenance record alone grants no execution authority.

Scope: Kast public query documentation and its documentation policy.
Example: Live evidence records the IDEA host, epoch, and content view for a read.
Distinction: A provenance record does not itself grant permission to run a query.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [EvidenceEnvelope](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt), examined 2026-10-13. The provenance of an operation's evidence, expressed as published workspace state or live IDEA read state. A provenance record alone grants no execution authority.

Local evidence:

- `kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt`: EvidenceEnvelope.

Rejected forms:

No replacement or review target passed the evidence review.

### read epoch

An opaque version of an IDEA project's admitted read state. Equality alone does not replace source or compiler revalidation.

Scope: Kast public query documentation and its documentation policy.
Example: A retained query result uses the state admitted for its original read.
Distinction: The same epoch does not replace a source-content or compiler identity examination.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [ProjectReadEpoch](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/ProjectReadEpoch.kt), examined 2026-10-13. An opaque version of an IDEA project's admitted read state. Equality alone does not replace source or compiler revalidation.

Local evidence:

- `workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/ProjectReadEpoch.kt`: ProjectReadEpoch.

Rejected forms:

No replacement or review target passed the evidence review.

### discovery scope

The source restrictions used to find seed declarations. They do not restrict later relationship destinations.

Scope: Kast public query documentation and its documentation policy.
Example: A declaration lookup searches `main` sources beneath `query/contract`.
Distinction: A workspace relationship search can reach declarations outside that seed scope.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [PublicToolDiscoveryDocuments](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt), examined 2026-10-13. The source restrictions used to find seed declarations. They do not restrict later relationship destinations.

Local evidence:

- `app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt`: PublicToolDiscoveryDocuments.

Rejected forms:

No replacement or review target passed the evidence review.

### expansion scope

The domain selected for native relationship search before enumeration. A later row filter does not reduce that search work.

Scope: Kast public query documentation and its documentation policy.
Example: `SOURCE_DOMAIN` restricts native relation destinations before enumeration.
Distinction: A later `WHERE` removes rows after native enumeration.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [PublicToolExpansionScopeMapping](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt), examined 2026-10-13. The domain selected for native relationship search before enumeration. A later row filter does not reduce that search work.

Local evidence:

- `app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt`: PublicToolExpansionScopeMapping.

Rejected forms:

No replacement or review target passed the evidence review.

### complete coverage

Finished enumeration within the declared, supported scope and requested traversal extent. It does not alone establish semantic completion.

Scope: Kast public query documentation and its documentation policy.
Example: An exhaustive caller read covers the declared source domain.
Distinction: A complete enumeration does not alone discharge callback activation obligations.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [QueryExecution](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt), examined 2026-10-13. Finished enumeration within the declared, supported scope and requested traversal extent. It does not alone establish semantic completion.

Local evidence:

- `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt`: QueryExecution.

Rejected forms:

No replacement or review target passed the evidence review.

### qualified coverage

Evidence of a proven minimum with stated limitations or unfinished work. An empty result with this status does not prove absence.

Scope: Kast public query documentation and its documentation policy.
Example: A stopped search reports its known rows and the unfinished work.
Distinction: An empty completed search can establish absence in its declared scope.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [QueryExecution](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt), examined 2026-10-13. Evidence of a proven minimum with stated limitations or unfinished work. An empty result with this status does not prove absence.

Local evidence:

- `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt`: QueryExecution.

Rejected forms:

No replacement or review target passed the evidence review.

### known minimum

The established count of logical final output, including delivered rows. It does not count remaining pages or unfiltered provider candidates.

Scope: Kast public query documentation and its documentation policy.
Example: A preview of two rows can belong to a result with five established final rows.
Distinction: An unfiltered discovery candidate count is a different measure.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [responses](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/docs/public/reference/responses.mdx), examined 2026-10-13. The established count of logical final output, including delivered rows. It does not count remaining pages or unfiltered provider candidates.

Local evidence:

- `docs/public/reference/responses.mdx`: responses.

Rejected forms:

No replacement or review target passed the evidence review.

### compiler-static model

The fixed `COMPILER_RESOLVED_STATIC_V1` model for compiler-resolved source relationships. It does not establish runtime activation or path feasibility.

Scope: Kast public query documentation and its documentation policy.
Example: A call site supports a compiler-resolved source relationship.
Distinction: A source relationship does not prove that a runtime path executed.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [QueryCompletionDocument](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt), examined 2026-10-13. The fixed `COMPILER_RESOLVED_STATIC_V1` model for compiler-resolved source relationships. It does not establish runtime activation or path feasibility.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt`: QueryCompletionDocument.

Rejected forms:

No replacement or review target passed the evidence review.

### semantic completion

Exhausted execution under the compiler-static model with no item failures or omitted evidence. Callback graphs and original investigation ledgers must also satisfy proof obligations.

Scope: Kast public query documentation and its documentation policy.
Example: A completed run exhausts execution and discharges its original proof obligations.
Distinction: A final output page establishes presentation progress rather than stronger semantic proof.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [QueryCompletionDocument](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt), examined 2026-10-13. Exhausted execution under the compiler-static model with no item failures or omitted evidence. Callback graphs and original investigation ledgers must also satisfy proof obligations.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt`: QueryCompletionDocument.

Rejected forms:

No replacement or review target passed the evidence review.

### retained result

Immutable query rows and evidence kept under their original read authority. They preserve row kind, limitations, and producer progress.

Scope: Kast public query documentation and its documentation policy.
Example: `READ_RESULT` presents rows from the result that a prior query kept.
Distinction: A new `RUN` establishes a separate semantic answer.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [QueryRetainedResult](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt), examined 2026-10-13. Immutable query rows and evidence kept under their original read authority. They preserve row kind, limitations, and producer progress.

Local evidence:

- `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt`: QueryRetainedResult.

Rejected forms:

- `saved result`: Replacement. This phrase names the same stored query rows in the public query documentation.
- `saved query result`: Replacement. This phrase names the same stored query rows in the public query documentation.

### retained result reference

An issued handle for one retained result. It differs from an exact reference and an output continuation.

Scope: Kast public query documentation and its documentation policy.
Example: `result:v1:...` identifies one stored query result.
Distinction: An exact declaration reference belongs to a different token family.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [QueryResultReferences](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt), examined 2026-10-13. An issued handle for one retained result. It differs from an exact reference and an output continuation.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt`: QueryResultReferences.

Rejected forms:

No replacement or review target passed the evidence review.

### row id

An issued identifier for one row in one retained result. Its owning result must match when Kast restores it.

Scope: Kast public query documentation and its documentation policy.
Example: A result row identifier selects a row from its owning result.
Distinction: A result cursor gives a presentation position rather than row identity.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [QueryResultReferences](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt), examined 2026-10-13. An issued identifier for one row in one retained result. Its owning result must match when Kast restores it.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt`: QueryResultReferences.

Rejected forms:

No replacement or review target passed the evidence review.

### output continuation

An issued token that permits `RESUME` to present remaining produced output. It does not authorize semantic execution or renew expiry.

Scope: Kast public query documentation and its documentation policy.
Example: `RESUME` presents a produced suffix through its issued token.
Distinction: A historical execution checkpoint does not authorize public semantic continuation.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [QueryExecutionContinuation](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryExecutionContinuation.kt), examined 2026-10-13. An issued token that permits `RESUME` to present remaining produced output. It does not authorize semantic execution or renew expiry.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryExecutionContinuation.kt`: QueryExecutionContinuation.

Rejected forms:

No replacement or review target passed the evidence review.

### result cursor

A presentation position within the rows of a separately identified retained result. It does not authorize semantic execution.

Scope: Kast public query documentation and its documentation policy.
Example: `READ_RESULT.cursor` selects a position in stored rows.
Distinction: The evidence cursor selects a separate position in saved observations.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [QueryResultReferences](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt), examined 2026-10-13. A presentation position within the rows of a separately identified retained result. It does not authorize semantic execution.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt`: QueryResultReferences.

Rejected forms:

No replacement or review target passed the evidence review.

### evidence cursor

A presentation position within saved failures, omissions, and observations. It advances independently of the result cursor.

Scope: Kast public query documentation and its documentation policy.
Example: `evidence_window.end` identifies the next evidence position.
Distinction: An evidence-only page can advance without a new row.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [CanonicalQueryOperationModels](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt), examined 2026-10-13. A presentation position within saved failures, omissions, and observations. It advances independently of the result cursor.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt`: CanonicalQueryOperationModels.

Rejected forms:

No replacement or review target passed the evidence review.

### result preview

A bounded presentation of produced rows. It describes output presentation rather than search completeness.

Scope: Kast public query documentation and its documentation policy.
Example: A `PREFIX` preview presents an ordered portion of accumulated rows.
Distinction: Preview truncation does not change the producing query's coverage.
Justification: The cited Kast contract or existing public reference supplies this name and meaning. Provisional display spelling uses ordinary prose case.

Domain usage:

- [responses](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/docs/public/reference/responses.mdx), examined 2026-10-13. A bounded presentation of produced rows. It describes output presentation rather than search completeness.

Local evidence:

- `docs/public/reference/responses.mdx`: responses.

Rejected forms:

No replacement or review target passed the evidence review.
