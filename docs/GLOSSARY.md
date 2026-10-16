# Kast glossary

Generated from `docs/glossary.json`. Edit the JSON source.

Kast repository documentation and agent instructions.
These terms are canonical for repository prose. Independent reader review remains absent. Runtime contracts, identifiers, and attributed quotations keep their exact spelling.

The JSON source also records each term's scope, reason, and source meaning.

## Terms

### declaration

A named Kotlin code element, such as a class, function, property, or type alias.

Example: `submitOrder` names a function declaration.
Keep distinct: A reference points to a declaration. It is not another declaration.

Source evidence:

- [`CompilerSymbolKind`](https://github.com/amichne/kast/blob/52b5599de559d9b82f990bbd16b882762dbdf402/symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt), checked 2026-10-15.
- `symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt`: `CompilerSymbolKind`.

### diagnostic

A compiler finding. It gives a severity, message, source location, and the identity of the admitted read.

Example: A diagnostic can report an unresolved Kotlin reference.
Keep distinct: A diagnostic describes a source issue. It does not establish that a project build passed.

Source evidence:

- [`DiagnosticFact`](https://github.com/amichne/kast/blob/52b5599de559d9b82f990bbd16b882762dbdf402/diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticFact.kt), checked 2026-10-15.
- `diagnostic/contract/src/main/kotlin/io/github/amichne/kast/diagnostic/contract/DiagnosticFact.kt`: `DiagnosticFact`.

### Control

The part of Kast that provides the native command, coordinator, and agent adapters.

Example: `kast upgrade --control-only` replaces Control while a compatible Host stays in place.
Keep distinct: Host supplies the IDEA plugin and semantic runtime. Control and Host versions can differ.

Source evidence:

- [`Recorded release pair and compatibility`](https://github.com/amichne/kast/blob/52b5599de559d9b82f990bbd16b882762dbdf402/docs/public/reference/compatibility.mdx), checked 2026-10-15.
- `docs/public/reference/compatibility.mdx`: `Recorded release pair and compatibility`.

### Host

The part of Kast that provides the IntelliJ IDEA plugin and semantic runtime.

Example: Restart IDEA after a Host update to load the new plugin.
Keep distinct: Control supplies the command and agent adapters. Host runs inside IDEA.

Source evidence:

- [`HostedEndpointService`](https://github.com/amichne/kast/blob/52b5599de559d9b82f990bbd16b882762dbdf402/runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt), checked 2026-10-15.
- `runtime/hosted/src/main/kotlin/io/github/amichne/kast/runtime/hosted/HostedEndpointService.kt`: `HostedEndpointService`.

### workspace root

The canonical root of the exact repository or worktree that Kast admits for a call.

Example: A query belongs to one repository or worktree root.
Keep distinct: A second worktree has a different root, even when both contain the same commit.

Source evidence:

- [`SemanticReadAuthority`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt), checked 2026-10-13.
- `workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt`: `SemanticReadAuthority`.

### exact reference

An opaque handle that Kast issues for one exact declaration. Reuse needs a check of the current identity.

Example: A discovered function supplies a `ref` for a later `SYMBOL_REFS` query.
Keep distinct: A retained result reference identifies a stored result rather than one declaration.

Source evidence:

- [`SymbolSelector`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt), checked 2026-10-13.
- `symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt`: `SymbolSelector`.

### canonical identity

The identity that Kast has proved for a declaration. Display names or opaque reference strings alone cannot prove that two symbols are the same.

Example: Two joined rows can identify the same declaration through different opaque references.
Keep distinct: Two functions with the same display name can have different owners and identities.

Source evidence:

- [`SymbolSelector`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt), checked 2026-10-13.
- `symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt`: `SymbolSelector`.

### semantic basis

Where a call's evidence came from: published workspace state or live IDEA read state. This record grants no right to run a call.

Example: Live evidence records the IDEA host, epoch, and content view for a read.
Keep distinct: A provenance record does not itself grant permission to run a query.

Source evidence:

- [`EvidenceEnvelope`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt), checked 2026-10-13.
- `kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt`: `EvidenceEnvelope`.

### read epoch

An opaque version of an IDEA project's admitted read state. Equal values do not replace current source or compiler checks.

Example: A retained query result uses the state admitted for its original read.
Keep distinct: The same epoch does not replace a source-content or compiler identity examination.

Source evidence:

- [`ProjectReadEpoch`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/ProjectReadEpoch.kt), checked 2026-10-13.
- `workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/ProjectReadEpoch.kt`: `ProjectReadEpoch`.

### discovery scope

The source filters used to find seed declarations. They do not restrict later relationship destinations.

Example: A declaration lookup searches `main` sources beneath `query/contract`.
Keep distinct: A workspace relationship search can reach declarations outside that seed scope.

Source evidence:

- [`PublicToolDiscoveryDocuments`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt), checked 2026-10-13.
- `app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt`: `PublicToolDiscoveryDocuments`.

### expansion scope

The domain that Kast searches for native relationships. Kast selects it before the scan. A later row filter cannot reduce that search work.

Example: `SOURCE_DOMAIN` restricts native relation destinations before enumeration.
Keep distinct: A later `WHERE` removes rows after native enumeration.

Source evidence:

- [`PublicToolExpansionScopeMapping`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt), checked 2026-10-13.
- `app-server/src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolExpansionScopeMapping.kt`: `PublicToolExpansionScopeMapping`.

### complete coverage

A finished scan of the declared supported scope and requested traversal extent. This fact alone does not prove semantic completion.

Example: An exhaustive caller read covers the declared source domain.
Keep distinct: A complete enumeration does not alone discharge callback activation obligations.

Source evidence:

- [`QueryExecution`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt), checked 2026-10-13.
- `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt`: `QueryExecution`.

### qualified coverage

Evidence of a proved minimum, with stated limits or work still to do. An empty result with this status does not prove absence.

Example: A stopped search reports its known rows and the unfinished work.
Keep distinct: An empty completed search can establish absence in its declared scope.

Source evidence:

- [`QueryExecution`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt), checked 2026-10-13.
- `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt`: `QueryExecution`.

### known minimum

The proved count of logical final output. It includes rows already sent. It does not count pages left or provider candidates before filters.

Example: A preview of two rows can belong to a result with five established final rows.
Keep distinct: An unfiltered discovery candidate count is a different measure.

Source evidence:

- [`responses`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/docs/public/reference/responses.mdx), checked 2026-10-13.
- `docs/public/reference/responses.mdx`: `responses`.

### compiler-static model

The fixed `COMPILER_RESOLVED_STATIC_V1` model of source relationships resolved by the compiler. It does not prove that code ran or that a path can run.

Example: A call site supports a compiler-resolved source relationship.
Keep distinct: A source relationship does not prove that a runtime path executed.

Source evidence:

- [`QueryCompletionDocument`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt), checked 2026-10-13.
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt`: `QueryCompletionDocument`.

### semantic completion

Execution has exhausted its work under the compiler-static model. No item has failed, and no evidence is omitted. Callback graphs and original investigation ledgers also meet their proof rules.

Example: A completed run exhausts execution and discharges its original proof obligations.
Keep distinct: A final output page establishes presentation progress rather than stronger semantic proof.

Source evidence:

- [`QueryCompletionDocument`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt), checked 2026-10-13.
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt`: `QueryCompletionDocument`.

### retained result

Immutable query rows and evidence kept under the read authority that produced them. They keep each row's kind, stated limits, and producer progress.

Example: `READ_RESULT` presents rows from the result that a prior query kept.
Keep distinct: A new `RUN` establishes a separate semantic answer.

Source evidence:

- [`QueryRetainedResult`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt), checked 2026-10-13.
- `query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt`: `QueryRetainedResult`.

Rejected forms:

- `saved result`: Replacement. This phrase names the same stored query rows in the public query documentation.
- `saved query result`: Replacement. This phrase names the same stored query rows in the public query documentation.

### retained result reference

A handle that Kast issues for one retained result. It differs from an exact reference and an output continuation.

Example: `result:v1:...` identifies one stored query result.
Keep distinct: An exact declaration reference belongs to a different token family.

Source evidence:

- [`QueryResultReferences`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt), checked 2026-10-13.
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt`: `QueryResultReferences`.

Rejected forms:

- `saved-result reference`: Replacement. The response guide uses this phrase for the issued handle that selects one retained result.

### row id

An issued ID for one row in one retained result. The owning result must match when Kast restores it.

Example: A result row identifier selects a row from its owning result.
Keep distinct: A result cursor gives a presentation position rather than row identity.

Source evidence:

- [`QueryResultReferences`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt), checked 2026-10-13.
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt`: `QueryResultReferences`.

### output continuation

An issued token that lets `RESUME` present the produced output that is left. It grants no right to run semantic work or extend expiry.

Example: `RESUME` presents a produced suffix through its issued token.
Keep distinct: A historical execution checkpoint does not authorize public semantic continuation.

Source evidence:

- [`QueryExecutionContinuation`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryExecutionContinuation.kt), checked 2026-10-13.
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryExecutionContinuation.kt`: `QueryExecutionContinuation`.

### result cursor

A position in the saved rows of a result identified on its own. It grants no right to run semantic work.

Example: `READ_RESULT.cursor` selects a position in stored rows.
Keep distinct: The evidence cursor selects a separate position in saved observations.

Source evidence:

- [`QueryResultReferences`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt), checked 2026-10-13.
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt`: `QueryResultReferences`.

### evidence cursor

A position in saved failures, omissions, and observations. It moves on its own, apart from the result cursor.

Example: `evidence_window.end` identifies the next evidence position.
Keep distinct: An evidence-only page can advance without a new row.

Source evidence:

- [`CanonicalQueryOperationModels`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt), checked 2026-10-13.
- `protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt`: `CanonicalQueryOperationModels`.

### result preview

A view of produced rows with a fixed size limit. It describes the output shown. It does not describe search completeness.

Example: A `PREFIX` preview presents an ordered portion of accumulated rows.
Keep distinct: Preview truncation does not change the producing query's coverage.

Source evidence:

- [`responses`](https://github.com/amichne/kast/blob/e62692cc5e0be164941a6c551c00d35ad6739ded/docs/public/reference/responses.mdx), checked 2026-10-13.
- `docs/public/reference/responses.mdx`: `responses`.
