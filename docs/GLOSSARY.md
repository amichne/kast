# Provisional Kast glossary

This draft defines terms for the public documentation. Its definitions come from source contracts at `9449c653d3f30ee9565c97b70e5db2844063f4e5`.

The entries and uppercase spellings remain provisional. Source contracts, generated schemas, and installed catalogs govern behavior. This draft does not change runtime contracts.

The terminology assessment uses these entries as candidates. Findings identify differences for review, not completed adoption. Code identifiers, literal errors, product names, and quoted examples keep their existing spelling.

Known synonyms list existing alternatives for review. They do not define more accepted names. Public-page casing and alias changes need a separate adoption pass.

## Identity and read state

These terms distinguish declaration identity from the state that supports a read.

- §WORKSPACE ROOT§: The canonical root of the exact repository or worktree admitted for an operation.
  - Known synonyms: none
  - Source: [SemanticReadAuthority](../workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt).

- §EXACT REFERENCE§: An opaque handle issued by Kast for an exact declaration. Reuse needs current identity admission.
  - Known synonyms: exact symbol reference
  - Source: [Symbol references](public/reference/symbols.mdx).

- §CANONICAL IDENTITY§: The established declaration identity used to compare symbols. Display names and opaque reference strings alone do not establish equality.
  - Known synonyms: none
  - Source: [SymbolSelector](../symbol/contract/src/main/kotlin/io/github/amichne/kast/symbol/contract/exact/SymbolSelector.kt) and [result composition](public/query-pipelines.mdx).

- §SEMANTIC BASIS§: The provenance of an operation's evidence, expressed as published workspace state or live IDEA read state. A provenance record alone grants no execution authority.
  - Known synonyms: none
  - Source: [EvidenceBasis](../kernel/src/main/kotlin/io/github/amichne/kast/kernel/EvidenceEnvelope.kt).

- §READ EPOCH§: An opaque version of an IDEA project's admitted read state. Equality alone does not replace source or compiler revalidation.
  - Known synonyms: none
  - Source: [ProjectReadEpoch](../workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/ProjectReadEpoch.kt) and [live read authority](../workspace/contract/src/main/kotlin/io/github/amichne/kast/workspace/contract/epoch/SemanticReadAuthority.kt).

## Search scope and evidence

These terms describe the question and the proof that supports its answer.

- §DISCOVERY SCOPE§: The source restrictions used to find seed declarations. They do not restrict later relationship destinations.
  - Known synonyms: none
  - Source: [Query sources](public/reference/api.mdx#choose-an-input).

- §EXPANSION SCOPE§: The domain selected for native relationship search before enumeration. A later row filter does not reduce that search work.
  - Known synonyms: none
  - Source: [Relationship search domains](public/reference/api.mdx#choose-the-relationship-search-domain).

- §COMPLETE COVERAGE§: Finished enumeration within the declared, supported scope and requested traversal extent. It does not alone establish §SEMANTIC COMPLETION§.
  - Known synonyms: exhaustive coverage
  - Source: [QueryCoverage](../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt) and [completion coverage](../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt).

- §QUALIFIED COVERAGE§: Evidence of a proven minimum with stated limitations or unfinished work. An empty result with this status does not prove absence.
  - Known synonyms: none
  - Source: [QueryCoverage.Qualified](../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryExecution.kt).

- §KNOWN MINIMUM§: The established count of logical final output, including delivered rows. It does not count remaining pages or unfiltered provider candidates.
  - Known synonyms: none
  - Source: [Query result counts](public/reference/responses.mdx#compact-and-detailed-output).

- §COMPILER-STATIC MODEL§: The fixed `COMPILER_RESOLVED_STATIC_V1` model for compiler-resolved source relationships. It does not establish runtime activation or path feasibility.
  - Known synonyms: none
  - Source: [QueryStaticModelDocument](../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt).

- §SEMANTIC COMPLETION§: Exhausted execution under the §COMPILER-STATIC MODEL§ with no item failures or omitted evidence. Callback graphs and original investigation ledgers must also satisfy proof obligations.
  - Known synonyms: none
  - Source: [Completion requirements](public/reference/responses.mdx#read-an-automatic-query) and [QueryCompletionDocument](../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryCompletionDocument.kt).

## Saved results and output

These terms distinguish stored evidence from permission to continue an operation.

- §RETAINED RESULT§: Immutable query rows and evidence kept under their original read authority. They preserve row kind, limitations, and producer progress.
  - Known synonyms: saved result, saved query result
  - Source: [QueryRetainedResult](../query/contract/src/main/kotlin/io/github/amichne/kast/query/contract/QueryRetainedResult.kt).

- §RETAINED RESULT REFERENCE§: An issued handle for one §RETAINED RESULT§. It differs from an §EXACT REFERENCE§ and an §OUTPUT CONTINUATION§.
  - Known synonyms: saved-result reference
  - Source: [QueryResultReference](../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt).

- §ROW ID§: An issued identifier for one row in one §RETAINED RESULT§. Its owning result must match when Kast restores it.
  - Known synonyms: none
  - Source: [QueryResultRowReference](../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt).

- §OUTPUT CONTINUATION§: An issued token that permits `RESUME` to present remaining produced output. It does not authorize semantic execution or renew expiry.
  - Known synonyms: output token
  - Source: [QueryExecutionContinuation.Output](../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryExecutionContinuation.kt) and [output progress](public/reference/responses.mdx#follow-progress-not-row-count).

- §RESULT CURSOR§: A presentation position within the rows of a separately identified §RETAINED RESULT§. It does not authorize semantic execution.
  - Known synonyms: row cursor
  - Source: [QueryResultCursor](../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/QueryResultReferences.kt).

- §EVIDENCE CURSOR§: A presentation position within saved failures, omissions, and observations. It advances independently of the §RESULT CURSOR§.
  - Known synonyms: none
  - Source: [QueryRunRequest.ReadResult](../protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalQueryOperationModels.kt) and [evidence pages](public/reference/responses.mdx#read-rows-and-evidence-pages).

- §RESULT PREVIEW§: A bounded presentation of produced rows. It describes output presentation rather than search completeness.
  - Known synonyms: none
  - Source: [Query previews](public/reference/responses.mdx#read-rows-and-evidence-pages).
