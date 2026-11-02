# Navigation and density record

The navigation keeps three visible tracks and gives every current public page one canonical location.
The source baseline is `a81d443f7705356af92398e5fcfb6f40efd265fe` on `origin/main`.
The review date is November 2, 2026.

## Scope and acceptance

The reader is a Kotlin developer with an existing coding agent.
The task changes navigation, sidebar labels, contextual links, and navigation records.
It preserves content, examples, headings, anchors, diagrams, qualifications, and generated contracts.
Publication and content rewrites remain outside this task.

The existing Set up page already contains installation, agent guidance, and a first useful question.
The change extends navigation and contextual links without another ordinary setup step.

Acceptance needs one canonical location per page, resolved contextual destinations, preserved content structures, and the existing documentation guards.
A missing page, duplicate location, changed example, or broken fragment invalidates structural acceptance.
These facts do not prove native semantic behavior or reader comprehension.

## Navigation changes

- Guides keeps Overview, Set up, Examples, and Help together in the expanded Get started group.
- Explore guides contains the visible, initially collapsed Task guides, Setup and maintenance, and Tutorials groups.
- Architecture leads with Understand an answer and Follow a value.
- Explore mechanisms contains the visible, initially collapsed Evidence and tracing, Query execution, and Runtime and ownership groups.
- Reference contains exact query and client contracts. Program tracing now belongs to Query contracts.
- Schemas and samples keeps all generated callable entries and model entries beneath its existing schema groups.
- Integration options distinguishes the detailed integration page from Agent guidance.
- Authored pages disable automatic footer pagination. The shared knowledge manifest owns the same metadata for generated page shells.

Mintlify always expands top-level groups. Nested groups supply the requested initial collapse state.
No group uses `hidden`. Existing routes and redirects remain unchanged.

## Records

- [Page contracts](page-contracts.md) cover all 56 public MDX pages and five generated callable entries.
- [Contextual links](contextual-links.md) contain the outgoing register and its derived incoming view.
- [Density assessment](density.md) separates total source footprint from initial visible density.
- [Density data](density-data.json) preserve each page count, panel count, schema footprint, assessment, and measurement limit.
- [Structural evidence](structural-evidence.json) preserve canonical parents, input digests, and content-preservation results.

Navigation parents derive from `docs/public/docs.json`. The proposal supplies intended responsibilities and link relationships.
The current inventory determines coverage. The proposal's historical page count does not control it.

## Journeys

| Reader need | Implemented route | Valid endpoint |
| --- | --- | --- |
| First use | Overview → Set up | Inspect the returned source locations in the coding agent. |
| Existing user | Overview → Examples | Ask the selected question in the coding agent. |
| Evaluation | Overview → Follow a value → Set up | Select setup after the illustration. |
| Guided learning | Set up → optional first-query tutorial → Examples | Finish the exercise and remove its file. |
| Known task | Task guides → selected procedure | Establish the procedure's stated outcome. |
| Exact lookup | Reference → selected contract | Find the requested fact and its qualifications. |
| Recovery | Interrupted procedure → Help or detailed recovery → explicit task destination | Resume the interrupted procedure after recovery. |

Set up links to the tutorial as optional learning. The tutorial's final navigation no longer makes architecture a prerequisite for Examples.
Examples keeps the diagnostics requirement for optional MCP at the question that needs it.
Recovery pages expose explicit destinations. Their return route does not depend on browser history.

## Density findings

The static assessment records 46 pages Within target, eight Review needed, one Justified exception, and six Not measured.
The eight review findings name the material that accounts for the excess and propose a later content-owner review.
The schema index exception preserves necessary authority and admission qualifications with direct model and callable links.
The missing measurements concern the interactive explorer and five generated callable pages.
Their static prose counts do not represent the complete rendered footprint.

No content moved into accordions, and no page split or prose rewrite occurred.

## Protected generated content

Six proposed contextual links remain deferred: five callable return links and one glossary link.
The supplied future-rewrite map requests them. This task preserves their generated content and contracts.
The outgoing register lists only implemented edges and records these six omissions separately.
A future navigation change through their generators can add those edges without changing contract facts.
The record does not claim complete acceptance of that broader proposal.

## Visual evidence

The local preview uses the pinned Mintlify package in `.github/tools/mintlify`.
The desktop review uses 1440 × 1000 pixels. The mobile review uses 390 × 844 pixels.
The Agent Browser skill supplies the browser procedure.

The desktop sidebar shows the entry pages and collapsed deeper groups.
Keyboard Enter expands Evidence and tracing. The native mobile menu exposes the entry pages and collapsed guide groups.
Overview presents its purpose and Set up action before optional background.
Set up keeps necessary prerequisites before installation and keeps its first useful question on the same page.

The screenshots support this limited visual review. They do not prove comprehension, native execution, or the complete rendered schema footprint.
The connected Mintlify deployments do not list Kast. No deployment change occurred.

## Verification results

| Procedure | Outcome | Evidence scope |
| --- | --- | --- |
| Pinned `mint validate` before and after navigation edits | Passed | Mintlify accepts the site configuration and page syntax. |
| Pinned `mint broken-links` before and after navigation edits | Passed | Mintlify reports no broken links. |
| `mise exec -- python3 .github/scripts/ci/verify_documentation.py` | Passed | Parser, glossary, examples, generated views, source links, and documentation tools pass. |
| Canonical inventory and fragment comparison | Passed | All 61 pages appear once. The 360 contextual edges resolve, including their fragments. |
| Content-preservation comparison | Passed | Every original heading, explicit anchor, fenced example, table, and model binding remains exact. |
| `mise exec -- ./gradlew :cli:verifyMintlifyCallableReference verifyJsonContracts verifyKnowledgeBase knowledgeImpact --console=plain` | Passed | Callable artifact, JSON guard, knowledge inputs, and impact report pass. |
| Root Vale `3.24.0` on changed prose | Passed with advisories | No error findings. The report preserves the unresolved advisories. |
| Locked Repository Knowledge assessment | ASSESSED with advisories | The pinned procedure assessed changed prose files. It does not prove comprehension. |
| Desktop, keyboard, and mobile preview review | Passed for the stated scope | Entry navigation, initial collapse state, keyboard expansion, and the native mobile menu. |
| `mise exec -- python3 distribution/release/run_product_gate.py` | Failed | Two unchanged test owners failed before the full product gate completed. |
| Broader proposal's generated-page links | Not implemented | Five callable return links and one glossary link remain deferred. |
| Native semantic execution and reader comprehension | Not run | Neither result follows from navigation or density evidence. |
| Site publication | Not run | The user excluded publication. |

The documentation runner passed 382 tooling tests, 61 installer-entrypoint tests, six value-explorer tests, and eight reader-audit tests.
Its static tools and generated-content comparisons also passed.

The structural comparison also preserves the entire callable artifact, imported snippets, diagram assets, and route redirects.
Metadata-only changes need no new prose assessment. The assessment path list names each file whose prose changed.

The knowledge impact report names `openwiki/contracts/public-tools.md` because it cites `docs/public/docs.json`.
Its callable-contract claims remain unchanged. No OpenWiki page edit is necessary for these navigation-only changes.

[Prose findings](prose-findings.md) record unresolved rules, source locations, and reasons.
[Raw evidence](evidence/) keeps report inputs, source digests, and screenshots.

## Full-gate failures

`ExtractIdeaDistributionCacheTest` expected `SUCCESS` after an archive change. Gradle reported `UP_TO_DATE` at line 37.
The test owner and its production task remain unchanged. The cause and recurrence remain unproved.

`CandidateFixture.test_missing_control_record_rejects_before_release_creation` expected a diagnostic path under `/tmp`.
The diagnostic used the equivalent macOS path under `/private/tmp`. The assertion failed at line 405.
Its test and publisher owners remain unchanged.

These failures do not establish a navigation regression. A separate pre-edit reproduction did not run, so this report does not label them proven baseline failures.
The full gate remains failed. Later product and native assertions did not run.
The default semantic-fixture suites remain skipped under the repository's existing switch.

The navigation-specific procedures passed. A push remains blocked by the full-gate failure. No pull request exists yet.

The isolated cache selector passed without a source change. Its original failure did not recur in that run.
The isolated publication selector passed with `TMPDIR=/private/tmp`.

The full gate failed again with that environment. `VerificationReportTask` replaces the child environment with its declared values.
The outer `TMPDIR` value therefore does not reach that test process. The path assertion still fails.
No test assertion, source file, or hook changed to bypass the failure.

The final pinned prose procedure assessed 45 files. The per-file reports distinguish no-findings outcomes from advisory findings.
Root Vale exits zero with grade and page-structure advisories. The raw reports keep their exact rule locations.
