---
type: Glossary
title: Glossary
description: The canonical terms used throughout Kast documentation.
resource: kast://glossary
tags:
  - kast
  - glossary
  - terminology
timestamp: '2026-08-21T00:00:00Z'
code_sources:
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalOperation.kt
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/SymbolProtocolModels.kt
    symbols: [SymbolDiscoveryDocument, SymbolDocument]
  - path: protocol/contract/src/main/kotlin/io/github/amichne/kast/protocol/contract/CanonicalChangeOperationModels.kt
    symbols: [ChangePlanResult, ChangeApplyResult, ChangeVerifyResult]
---

# Glossary

Use one term for one concept. Known synonyms are migration inputs, not permitted alternate names.

- **Application**: Execution of one admitted plan. An application remains unverified until verification returns a receipt.
  - Known synonyms: applied change, mutation attempt
- **Candidate**: One bounded discovery result that has not become exact symbol identity.
  - Known synonyms: match, discovery result
- **Candidate selector**: The opaque identity returned with a declaration candidate and consumed by resolution.
  - Known synonyms: candidate ID
- **Canonical root**: The one exact repository root discovered for an operation.
  - Known synonyms: project root, workspace root
- **Complete**: An outcome whose operation contract is satisfied.
  - Known synonyms: success
- **Coverage**: The portion of required scope for which Kast obtained admissible evidence.
  - Known synonyms: analyzed scope
- **Evidence**: Detached facts and provenance that support an outcome.
  - Known synonyms: proof data
- **Exact selector**: The generation-bound symbol identity returned by resolution and consumed by exact operations.
  - Known synonyms: selector, symbol ID, exact symbol key
- **Generation**: The immutable identity of one published semantic workspace state.
  - Known synonyms: semantic version, workspace version
- **Indexer**: The isolated JVM process that serves one canonical root.
  - Known synonyms: daemon, server, backend
- **Limitation**: The explicit reason a qualified outcome is not complete.
  - Known synonyms: caveat, partial reason
- **Operation**: One canonical typed semantic contract identified by an operation ID.
  - Known synonyms: command, action
- **Plan**: A generation-bound description of one intended change. A plan does not write source.
  - Known synonyms: edit plan, mutation plan
- **Qualified**: An outcome that contains useful evidence and explicit limitations.
  - Known synonyms: partial success, incomplete success
- **Receipt**: The terminal identity proving an application satisfied its plan and verification obligations.
  - Known synonyms: verified receipt, verification result
- **Recovery**: The process that restores or reports a known durable state for a plan journal.
  - Known synonyms: rollback
- **Rejected**: An outcome that could not produce an admissible value.
  - Known synonyms: failed, errored
- **Repository**: The developer-controlled directory tree containing the Kotlin or Gradle project.
  - Known synonyms: codebase, project, workspace
- **Semantic runtime**: The verified IntelliJ/K2 distribution acquired by the control product.
  - Known synonyms: runtime payload, indexer distribution
- **Workspace**: The semantic repository state admitted for one canonical root.
  - Known synonyms: project state, repository state
