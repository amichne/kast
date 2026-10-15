# Slopsentral Glossary

Generated from `glossary.json`. Edit the JSON source.

Kast repository documentation and agent instructions.
These terms are canonical for repository prose. Independent reader review remains absent. Runtime contracts, identifiers, and attributed quotations keep their exact spelling.

## Terms

### declaration

A named Kotlin code element, such as a class, function, property, or type alias.

Scope: Current Kast documentation and agent instructions.
Example: `submitOrder` names a function declaration.
Distinction: A reference points to a declaration. It is not another declaration.
Justification: The cited Kast source supplies this established term and its local meaning.

Domain usage:

- [CompilerSymbolKind](https://github.com/amichne/kast/blob/52b5599de559d9b82f990bbd16b882762dbdf402/symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt), examined 2026-10-15. A named Kotlin code element, such as a class, function, property, or type alias.

Local evidence:

- `symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt`: CompilerSymbolKind.

Rejected forms:

No replacement or review target passed the evidence review.

### diagnostic

A compiler finding with a severity, message, source location, and admitted read identity.

Scope: Current Kast documentation and agent instructions.
Example: A diagnostic can report an unresolved Kotlin reference.
Distinction: A diagnostic describes a source issue. It does not establish that a project build passed.
Justification: The cited Kast source supplies this established term and its local meaning.

Domain usage:

- [DiagnosticFact](https://github.com/amichne/kast/blob/52b5599de559d9b82f990bbd16b882762dbdf402/diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticFact.kt), examined 2026-10-15. A compiler finding with a severity, message, source location, and admitted read identity.

Local evidence:

- `diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticFact.kt`: DiagnosticFact.

Rejected forms:

No replacement or review target passed the evidence review.

### Control

The Kast component that supplies the native command, coordinator, and agent adapters.

Scope: Current Kast documentation and agent instructions.
Example: `kast upgrade --control-only` replaces Control while a compatible Host stays in place.
Distinction: Host supplies the IDEA plugin and semantic runtime. Control and Host versions can differ.
Justification: The cited Kast source supplies this established term and its local meaning.

Domain usage:

- [Recorded release pair and compatibility](https://github.com/amichne/kast/blob/52b5599de559d9b82f990bbd16b882762dbdf402/docs/public/reference/compatibility.mdx), examined 2026-10-15. The Kast component that supplies the native command, coordinator, and agent adapters.

Local evidence:

- `docs/public/reference/compatibility.mdx`: Recorded release pair and compatibility.

Rejected forms:

No replacement or review target passed the evidence review.

### Host

The Kast component that supplies the IntelliJ IDEA plugin and semantic runtime.

Scope: Current Kast documentation and agent instructions.
Example: Restart IDEA after a Host update to load the new plugin.
Distinction: Control supplies the command and agent adapters. Host runs inside IDEA.
Justification: The cited Kast source supplies this established term and its local meaning.

Domain usage:

- [HostedEndpointService](https://github.com/amichne/kast/blob/52b5599de559d9b82f990bbd16b882762dbdf402/runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt), examined 2026-10-15. The Kast component that supplies the IntelliJ IDEA plugin and semantic runtime.

Local evidence:

- `runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt`: HostedEndpointService.

Rejected forms:

No replacement or review target passed the evidence review.

### workspace root

The canonical root of the exact repository or worktree admitted for an operation.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A query belongs to one repository or worktree root.
Distinction: A second worktree has a different root, even when both contain the same commit.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [SemanticReadAuthority](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt), examined 2026-10-13. The canonical root of the exact repository or worktree admitted for an operation.

Local evidence:

- `workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt`: SemanticReadAuthority.

Rejected forms:

No replacement or review target passed the evidence review.

### exact reference

An opaque handle issued by Kast for an exact declaration. Reuse needs current identity admission.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A discovered function supplies a `ref` for a later `SYMBOL_REFS` query.
Distinction: A retained result reference identifies a stored result rather than one declaration.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [SymbolSelector](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt), examined 2026-10-13. An opaque handle issued by Kast for an exact declaration. Reuse needs current identity admission.

Local evidence:

- `symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt`: SymbolSelector.

Rejected forms:

No replacement or review target passed the evidence review.

### canonical identity

The established declaration identity used to compare symbols. Display names and opaque reference strings alone do not establish equality.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: Two joined rows can identify the same declaration through different opaque references.
Distinction: Two functions with the same display name can have different owners and identities.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [SymbolSelector](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt), examined 2026-10-13. The established declaration identity used to compare symbols. Display names and opaque reference strings alone do not establish equality.

Local evidence:

- `symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt`: SymbolSelector.

Rejected forms:

No replacement or review target passed the evidence review.

### semantic basis

The provenance of an operation's evidence, expressed as published workspace state or live IDEA read state. A provenance record alone grants no execution authority.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: Live evidence records the IDEA host, epoch, and content view for a read.
Distinction: A provenance record does not itself grant permission to run a query.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [EvidenceEnvelope](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt), examined 2026-10-13. The provenance of an operation's evidence, expressed as published workspace state or live IDEA read state. A provenance record alone grants no execution authority.

Local evidence:

- `kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt`: EvidenceEnvelope.

Rejected forms:

No replacement or review target passed the evidence review.

### read epoch

An opaque version of an IDEA project's admitted read state. Equality alone does not replace source or compiler revalidation.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A retained query result uses the state admitted for its original read.
Distinction: The same epoch does not replace a source-content or compiler identity examination.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [ProjectReadEpoch](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/ProjectReadEpoch.kt), examined 2026-10-13. An opaque version of an IDEA project's admitted read state. Equality alone does not replace source or compiler revalidation.

Local evidence:

- `workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/ProjectReadEpoch.kt`: ProjectReadEpoch.

Rejected forms:

No replacement or review target passed the evidence review.

### discovery scope

The source restrictions used to find seed declarations. They do not restrict later relationship destinations.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A declaration lookup searches `main` sources beneath `query/contract`.
Distinction: A workspace relationship search can reach declarations outside that seed scope.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [PublicToolDiscoveryDocuments](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt), examined 2026-10-13. The source restrictions used to find seed declarations. They do not restrict later relationship destinations.

Local evidence:

- `app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt`: PublicToolDiscoveryDocuments.

Rejected forms:

No replacement or review target passed the evidence review.

### expansion scope

The domain selected for native relationship search before enumeration. A later row filter does not reduce that search work.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: `SOURCE_DOMAIN` restricts native relation destinations before enumeration.
Distinction: A later `WHERE` removes rows after native enumeration.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [PublicToolExpansionScopeMapping](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt), examined 2026-10-13. The domain selected for native relationship search before enumeration. A later row filter does not reduce that search work.

Local evidence:

- `app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt`: PublicToolExpansionScopeMapping.

Rejected forms:

No replacement or review target passed the evidence review.

### complete coverage

Finished enumeration within the declared, supported scope and requested traversal extent. It does not alone establish semantic completion.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: An exhaustive caller read covers the declared source domain.
Distinction: A complete enumeration does not alone discharge callback activation obligations.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [QueryExecution](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt), examined 2026-10-13. Finished enumeration within the declared, supported scope and requested traversal extent. It does not alone establish semantic completion.

Local evidence:

- `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt`: QueryExecution.

Rejected forms:

No replacement or review target passed the evidence review.

### qualified coverage

Evidence of a proven minimum with stated limitations or unfinished work. An empty result with this status does not prove absence.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A stopped search reports its known rows and the unfinished work.
Distinction: An empty completed search can establish absence in its declared scope.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [QueryExecution](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt), examined 2026-10-13. Evidence of a proven minimum with stated limitations or unfinished work. An empty result with this status does not prove absence.

Local evidence:

- `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt`: QueryExecution.

Rejected forms:

No replacement or review target passed the evidence review.

### known minimum

The established count of logical final output, including delivered rows. It does not count remaining pages or unfiltered provider candidates.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A preview of two rows can belong to a result with five established final rows.
Distinction: An unfiltered discovery candidate count is a different measure.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [responses](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/docs/public/reference/responses.mdx), examined 2026-10-13. The established count of logical final output, including delivered rows. It does not count remaining pages or unfiltered provider candidates.

Local evidence:

- `docs/public/reference/responses.mdx`: responses.

Rejected forms:

No replacement or review target passed the evidence review.

### compiler-static model

The fixed `COMPILER_RESOLVED_STATIC_V1` model for compiler-resolved source relationships. It does not establish runtime activation or path feasibility.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A call site supports a compiler-resolved source relationship.
Distinction: A source relationship does not prove that a runtime path executed.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [QueryCompletionDocument](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt), examined 2026-10-13. The fixed `COMPILER_RESOLVED_STATIC_V1` model for compiler-resolved source relationships. It does not establish runtime activation or path feasibility.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt`: QueryCompletionDocument.

Rejected forms:

No replacement or review target passed the evidence review.

### semantic completion

Exhausted execution under the compiler-static model with no item failures or omitted evidence. Callback graphs and original investigation ledgers must also satisfy proof obligations.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A completed run exhausts execution and discharges its original proof obligations.
Distinction: A final output page establishes presentation progress rather than stronger semantic proof.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [QueryCompletionDocument](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt), examined 2026-10-13. Exhausted execution under the compiler-static model with no item failures or omitted evidence. Callback graphs and original investigation ledgers must also satisfy proof obligations.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt`: QueryCompletionDocument.

Rejected forms:

No replacement or review target passed the evidence review.

### retained result

Immutable query rows and evidence kept under their original read authority. They preserve row kind, limitations, and producer progress.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: `READ_RESULT` presents rows from the result that a prior query kept.
Distinction: A new `RUN` establishes a separate semantic answer.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [QueryRetainedResult](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt), examined 2026-10-13. Immutable query rows and evidence kept under their original read authority. They preserve row kind, limitations, and producer progress.

Local evidence:

- `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt`: QueryRetainedResult.

Rejected forms:

- `saved result`: Replacement. This phrase names the same stored query rows in the public query documentation.
- `saved query result`: Replacement. This phrase names the same stored query rows in the public query documentation.

### retained result reference

An issued handle for one retained result. It differs from an exact reference and an output continuation.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: `result:v1:...` identifies one stored query result.
Distinction: An exact declaration reference belongs to a different token family.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [QueryResultReferences](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt), examined 2026-10-13. An issued handle for one retained result. It differs from an exact reference and an output continuation.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt`: QueryResultReferences.

Rejected forms:

- `saved-result reference`: Replacement. The response guide uses this phrase for the issued handle that selects one retained result.

### row id

An issued identifier for one row in one retained result. Its owning result must match when Kast restores it.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A result row identifier selects a row from its owning result.
Distinction: A result cursor gives a presentation position rather than row identity.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [QueryResultReferences](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt), examined 2026-10-13. An issued identifier for one row in one retained result. Its owning result must match when Kast restores it.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt`: QueryResultReferences.

Rejected forms:

No replacement or review target passed the evidence review.

### output continuation

An issued token that permits `RESUME` to present remaining produced output. It does not authorize semantic execution or renew expiry.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: `RESUME` presents a produced suffix through its issued token.
Distinction: A historical execution checkpoint does not authorize public semantic continuation.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [QueryExecutionContinuation](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryExecutionContinuation.kt), examined 2026-10-13. An issued token that permits `RESUME` to present remaining produced output. It does not authorize semantic execution or renew expiry.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryExecutionContinuation.kt`: QueryExecutionContinuation.

Rejected forms:

No replacement or review target passed the evidence review.

### result cursor

A presentation position within the rows of a separately identified retained result. It does not authorize semantic execution.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: `READ_RESULT.cursor` selects a position in stored rows.
Distinction: The evidence cursor selects a separate position in saved observations.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [QueryResultReferences](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt), examined 2026-10-13. A presentation position within the rows of a separately identified retained result. It does not authorize semantic execution.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt`: QueryResultReferences.

Rejected forms:

No replacement or review target passed the evidence review.

### evidence cursor

A presentation position within saved failures, omissions, and observations. It advances independently of the result cursor.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: `evidence_window.end` identifies the next evidence position.
Distinction: An evidence-only page can advance without a new row.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [CanonicalQueryOperationModels](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt), examined 2026-10-13. A presentation position within saved failures, omissions, and observations. It advances independently of the result cursor.

Local evidence:

- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt`: CanonicalQueryOperationModels.

Rejected forms:

No replacement or review target passed the evidence review.

### result preview

A bounded presentation of produced rows. It describes output presentation rather than search completeness.

Scope: Current Kast documentation and agent instructions. Historical records and literal identifiers keep their original terms.
Example: A `PREFIX` preview presents an ordered portion of accumulated rows.
Distinction: Preview truncation does not change the producing query's coverage.
Justification: The cited Kast source supplies this term and definition. The display term uses its established prose spelling.

Domain usage:

- [responses](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/docs/public/reference/responses.mdx), examined 2026-10-13. A bounded presentation of produced rows. It describes output presentation rather than search completeness.

Local evidence:

- `docs/public/reference/responses.mdx`: responses.

Rejected forms:

No replacement or review target passed the evidence review.
