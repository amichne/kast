# Kast reader review

This audit improves the path from a first product question to source evidence and a useful engineering task.
It preserves compiler, library-rule, model, completion, presentation, and runtime distinctions.
The starting revision was `dac99b39a0b7b497d5c0e0d5c2a604f5817e5397`.

## Reader and scope

The reader knows Kotlin and ordinary developer tools but has no Kast experience.
The audit inventories all 308 tracked Markdown and MDX files at the starting revision.
The frozen prose corpus includes 105 maintained authored files.
The inventory gives each excluded file a reason, including generated projections and historical reports.
Generated public pages received a separate reader review through their source owners.
Machine-readable contracts, code, and generated navigation do not enter the prose baseline.

Page purposes and findings reside in these records:

- `inventory.json` records paths, categories, readers, purposes, exclusions, and starting hashes.
- `journey-pages.json` records the overview, onboarding, navigation, and instruction review.
- `concept-pages.json` records 44 explanation, workflow, reference, and model pages.
- `contributor-pages.json` records 47 contributor and operator pages.
- `supplemental-page-audit.json` records the remaining authored instruction and experiment pages.
- `independent-reader-review.md` records answers to ten reader questions without the original conversation.

The author and independent agent reviews simulate reader assessment.
They do not establish human comprehension or the success of every agent model with the example prompts.

## Conceptual model

The glossary keeps ownership of canonical terminology. These explanations introduce existing concepts without creating alternate names.

| Concept | New-reader explanation and distinction | Authoritative reader page | Current source evidence |
| --- | --- | --- | --- |
| Declaration and exact reference | A declaration defines a named code element. An exact reference identifies the selected declaration, beyond its name. | Symbol identity | `symbol/contract/.../exact/SymbolSelector.kt`, `CompilerSymbolKind` and `CompilerGroundedSymbolEvidence` |
| Source relationship | A caller, reference, or implementation relationship states a particular source fact. Equal text does not establish that fact. | Program tracing | `protocol/contract/.../QueryCompletionDocument.kt`, `QueryStaticModelDocument` |
| Callback ownership | The supplier, parameter, anonymous body, and invocation owner have separate roles. Lexical containment does not replace invocation proof. | Program tracing and callback observations | `relation/contract/.../CallbackArgumentBinding.kt`; `query/protocol/.../QueryCallbackGraphAdmission.kt` |
| Value correspondence | Exact transfers preserve the tracked value. Derived content and unestablished correspondence remain different outcomes. | Results, value-path relationships | `query/contract/.../QueryImpactClassification.kt` |
| Complete coverage and semantic completion | A finished scan and a satisfied proof policy establish different facts. Neither proves runtime execution. | Results | `QueryCompletionDocument.kt`; `CompleteOnlyQueryTest.kt`; `TraceSourceRelationshipCompletionTest.kt` |
| Output presentation | A preview can omit stored rows from a complete result. Retained evidence after rejection does not establish completion. | Results and retained-evidence guide | `QueryCompletionEvidenceDocument`; `QueryResultInterpretationDocument` |
| Source state | Fresh queries can establish current identities. They do not refresh earlier rows or source snapshots. | Results, saved state and identity | `app-server/.../query/PublicToolMapping.kt`, `SnapshotSymbol` lowering |
| Checked source change | A supported edit uses exact identity, a source preimage, and a receipt. Project tests establish separate behavior. | Make a targeted change | `cli/.../direct/KastDirectToolSession.kt`; current change schema and guide |

`concept-evidence.md` records full paths, source locations, and evidence limits for the semantic claims.
`contributor-pages.json` records the source owners for corrected release, limit, and qualification instructions.

## Material changes

| Pages | Comprehension problem | Result |
| --- | --- | --- |
| README and homepage | The slogan and illustrated path preceded a concrete explanation of compiler evidence. | A change question leads to source relationships, locations, scope, and runtime limits. |
| Install and first-query tutorial | An arbitrary function obscured success. The exercise lacked an unrelated same-text control. | The reader selects known source and compares a caller, an unrelated function, and an absent name. |
| Explanation overview | A list of architecture links assumed the concepts that those pages used. | One Kotlin example introduces identity, relationships, scope, completion, source state, and receipts before deeper links. |
| Value journey | The explanation depended on an interactive display for its code. | A static Kotlin example explains the customer, draft, and list without animation. |
| Results and callback pages | Exact fields appeared before the reader knew the order or purpose of interpretation. | A reading order and concrete ownership examples precede the detailed contracts. |
| Four generated public shells | Internal stages and source APIs appeared beside public usage without enough orientation. | Manifest-owned introductions route current public syntax and preserve canonical source qualifications. |
| Engineering-task guide | An optional benchmark protocol looked like a normal task-completion requirement. | The protocol moves to the contributor guide. Its old public anchor remains. |
| Contributor guides | Release behavior, response defaults, and qualification grants contradicted current source. Dense sections mixed independent mechanisms. | Corrected facts and focused sections preserve the exact invariants. |
| Historical experiment guides | Old producer-resume and setup commands appeared to describe the current public contract. | Opening scope notices distinguish the recorded experiment from current installation and output-only continuation. |

No page moved or disappeared. Existing routes and legacy anchors remain.
The benchmark procedure is the only material content relocation.
The public schema and product implementation remain unchanged.
The projector gains one optional reader-introduction field, with rejection tests for invalid values.

## Technical evidence

The public installer command and platform requirements were inspected in `install.sh`.
The inspection covered stable release selection, IDEA `262.*`, the JBR requirement, Codex selection, and the command path behavior.
The hook source declares Python 3.9 or later.
A public-channel fresh installation was not part of the initial tutorial run.

The first live tutorial run used the existing installed Host in this exact worktree.
The exercise source was extracted from the revised tutorial and placed in the `protocol/contract` module's `main` source set.
Kast returned `kastTutorialMessage`, then exactly `kastTutorialCaller`, with complete coverage and no failures or omissions.
The unrelated same-text function did not appear as a caller.
The absent-name query completed with zero matches.
Only the audit's temporary source file was removed afterward.
`tutorial-live.json` preserves requests, responses, source text, hash, host, epochs, and limitations.
Those historical handles are evidence, not reusable examples.

The static value example compiled and ran with one-item and empty-input assertions.
This proves ordinary Kotlin behavior for the example. It does not prove a Kast value-flow query.
The semantic completion and classification tests were inspected for independent expected results.
No claim here treats inspected assertions as newly executed semantic tests.

## Rendered review

Ardia inspected the published homepage and installation page at `https://kast.michne.com`.
The browser showed the current homepage and Guides, Architecture, and Reference tabs.
An earlier web retrieval returned stale content. The browser result superseded that retrieval for the published-page assessment.
No deployment or published revision identity was inferred.

The local Mintlify preview rendered the revised homepage, explanation, tutorial, and results pages.
The tutorial rendered at a 390 by 844 mobile viewport with readable prose, a source block, and a scrollable result table.
The native navigation menu opened, and the theme controls rendered the page in dark mode.
Desktop source, heading, and reference layouts were inspected through screenshots and rendered text.
These samples do not constitute exhaustive browser, accessibility, or device coverage.

## Assessment and remaining limits

The two-stage results, compatibility tests, commands, and located advisory decisions are in `README.md` and the adjacent JSON reports.
The prose stage has no terminology vocabulary.
The root spelling allowlist gained established technical spellings. That change does not affect either two-stage profile.
No readability threshold changed to reduce findings.

Generated OpenWiki content keeps its recorded source evidence and freshness qualifications.
Reader introductions distinguish current public forms from internal descriptions without claiming that every historical Claim received fresh native proof.
The glossary remains provisional pending an independent human reader review.
New-reader completion and comprehension remain unmeasured by a human study.

## Repository acceptance

The final documentation procedure passed 378 Python cases and six value-explorer cases.
Mintlify validation and local link analysis passed.
Knowledge, JSON-contract, and callable-projection guards passed.
The full product gate passed with 638 tasks, including cached tasks.
Its query-protocol tests ran the completion and source-trace cases with no failures.
Semantic fixture suites remained disabled. The tutorial has separate live evidence.
`validation.json` records exact commands, outcomes, counts, and log hashes.

## Current-head installation

The requested local reinstall used source revision `dac99b39a0b7b497d5c0e0d5c2a604f5817e5397`.
Control and Host version `0.20261028.203727` installed successfully, and IDEA loaded that Host.
The Codex plugin is installed and enabled at the same version.
`installation.json` records the candidate hashes and installation evidence.

Two initial installation attempts rejected a stale process receipt after its PID passed to an unrelated process.
A start-identity comparison established that the original process no longer existed.
The single stale receipt moved to a recovery directory with its original bytes and hash.
Normal installation then passed without a global reset.
The installer kept one prior entry for review.

The current chat's old MCP connection closed during retirement.
A fresh Codex session is necessary for that integration to reconnect.

A fresh standalone MCP session then passed all three tutorial queries against the new Host.
The directory-scoped discovery returned the exact function. Its caller query returned only the real caller and the compiler-backed call location.
The absent-name query completed with no matches. Every query had complete coverage and no failures or omissions.
The temporary source file was removed after its bytes matched the exercise.
`tutorial-current-head.json` preserves the requests, responses, source, and assertions.
This proves the installed source-checkout path for one Kotlin/JVM example. It does not establish a fresh public-channel installation.
