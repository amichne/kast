# Density assessment

This assessment preserves page content. Counts describe source footprint and do not prove readability or successful use.

## Counting rules

The count includes reader-facing titles, descriptions, headings, information blocks, table text, labels, and every static tab and accordion panel.

Each MDX import expands at its use site. Collapsed content counts in full. Generated explanation prose receives no schema exemption.

The token rule counts Unicode word sequences with internal apostrophes and hyphens. Inline identifiers count as words. URLs and markup syntax do not count.

Fenced code and raw-output examples do not count as prose. Code lines count nonblank physical lines across every panel.

Generated diagrams contribute SVG text, their captions, and their static controls. Their SVG syntax does not count as prose.

Table rows include the header and exclude the delimiter. Sections count level-two headings, including imported evidence sections.

Panels count Tabs and Accordions. Expanded detail counts explicit default-open disclosures. The active tab remains visible and contributes to initial density.

A contract binding counts as one generated section. Recursive schema expansion depends on Mintlify controls, so source lines do not represent displayed schema lines.

The value explorer contains scenario-dependent JSX prose and source panels. Its static MDX count is partial, and its full density remains Not measured.

Callable rows report operation summary and description words only. Their full prose and rendered schema lines remain Not measured.

## Per-page results

The ceiling is a review target. A smaller count needs no added text. Reference has no blanket ceiling.

| Route | Profile / ceiling | Prose words | Code blocks / lines | Table rows | Sections / panels / expanded details | Contract sections | Assessment |
| --- | --- | ---: | --- | ---: | --- | ---: | --- |
| `/` | overview / 250 | 144 | 0 / 0 | 0 | 1 / 0 / 0 | 0 | Within target |
| `/start` | setup / 600 | 318 | 5 / 5 | 0 | 4 / 4 / 0 | 0 | Within target |
| `/search` | examples / 350 | 188 | 0 / 0 | 0 | 3 / 0 / 0 | 0 | Within target |
| `/troubleshooting` | help / 600 | 314 | 2 / 3 | 0 | 6 / 0 / 0 | 0 | Within target |
| `/how-to` | index / 200 | 85 | 0 / 0 | 0 | 0 / 0 / 0 | 0 | Within target |
| `/reference/search` | task / 700 | 638 | 5 / 36 | 5 | 5 / 0 / 0 | 0 | Within target |
| `/how-to/inspect-diagnostics` | task / 700 | 261 | 2 / 4 | 0 | 4 / 0 / 0 | 0 | Within target |
| `/how-to/read-retained-evidence` | task / 700 | 451 | 2 / 15 | 4 | 4 / 0 / 0 | 0 | Within target |
| `/reference/query-pipelines` | task / 700 | 704 | 10 / 106 | 3 | 8 / 0 / 0 | 0 | Review needed |
| `/reference/change` | task / 700 | 423 | 4 / 10 | 0 | 3 / 0 / 0 | 0 | Within target |
| `/how-to/complete-engineering-task` | task / 700 | 502 | 0 / 0 | 0 | 6 / 0 / 0 | 0 | Within target |
| `/agent-harnesses` | task / 700 | 158 | 3 / 3 | 0 | 2 / 3 / 0 | 0 | Within target |
| `/reference/installation` | task / 700 | 673 | 7 / 8 | 0 | 5 / 0 / 0 | 0 | Within target |
| `/reference/integrations` | task / 700 | 379 | 8 / 10 | 0 | 4 / 3 / 0 | 0 | Within target |
| `/reference/management` | task / 700 | 511 | 4 / 6 | 6 | 5 / 0 / 0 | 0 | Within target |
| `/reference/troubleshooting` | task / 700 | 424 | 1 / 1 | 0 | 3 / 0 / 0 | 0 | Within target |
| `/tutorials` | index / 200 | 77 | 0 / 0 | 0 | 0 / 0 / 0 | 0 | Within target |
| `/tutorials/first-query` | tutorial / 900 | 439 | 1 / 10 | 4 | 1 / 0 / 0 | 0 | Within target |
| `/explanation` | explanation / 900 | 828 | 1 / 3 | 0 | 6 / 0 / 0 | 0 | Within target |
| `/explanation/value-journey` | explanation / 900 | 366 | 1 / 11 | 0 | 4 / 0 / 0 | 0 | Not measured |
| `/reference/evidence-boundaries` | explanation / 900 | 1339 | 0 / 0 | 9 | 2 / 0 / 0 | 0 | Review needed |
| `/reference/callbacks` | explanation / 900 | 1038 | 0 / 0 | 0 | 16 / 0 / 0 | 0 | Review needed |
| `/explanation/value-journey-evidence` | explanation / 900 | 493 | 0 / 0 | 0 | 4 / 0 / 0 | 0 | Within target |
| `/explanation/query-lifecycle` | explanation / 900 | 1052 | 0 / 0 | 0 | 15 / 0 / 0 | 0 | Review needed |
| `/architecture/query-reuse` | explanation / 900 | 894 | 0 / 0 | 5 | 5 / 4 / 0 | 0 | Within target |
| `/reference/staleness` | explanation / 900 | 886 | 0 / 0 | 0 | 14 / 0 / 0 | 0 | Within target |
| `/reference/internals/architecture` | explanation / 900 | 1597 | 1 / 4 | 0 | 13 / 0 / 0 | 0 | Review needed |
| `/architecture/topology` | explanation / 900 | 1607 | 1 / 4 | 0 | 13 / 0 / 0 | 0 | Review needed |
| `/reference/lifecycles` | explanation / 900 | 982 | 0 / 0 | 6 | 6 / 2 / 0 | 0 | Review needed |
| `/reference/internals/background-startup` | explanation / 900 | 855 | 0 / 0 | 0 | 14 / 0 / 0 | 0 | Within target |
| `/architecture/change-lifecycle` | explanation / 900 | 853 | 0 / 0 | 0 | 14 / 0 / 0 | 0 | Within target |
| `/reference` | index / 200 | 393 | 0 / 0 | 16 | 4 / 0 / 0 | 0 | Review needed |
| `/reference/cli` | reference / none | 350 | 5 / 11 | 0 | 6 / 0 / 0 | 0 | Within target |
| `/glossary` | reference / none | 601 | 0 / 0 | 0 | 24 / 0 / 0 | 0 | Within target |
| `/reference/api` | reference / none | 1690 | 1 / 10 | 47 | 8 / 4 / 0 | 0 | Within target |
| `/reference/responses` | reference / none | 1600 | 1 / 8 | 31 | 8 / 0 / 0 | 0 | Within target |
| `/reference/symbols` | reference / none | 1753 | 0 / 0 | 0 | 10 / 0 / 0 | 0 | Within target |
| `/reference/program-tracing` | reference / none | 1678 | 2 / 42 | 7 | 8 / 0 / 0 | 0 | Within target |
| `/reference/callback-observations` | reference / none | 784 | 1 / 1 | 27 | 8 / 0 / 0 | 0 | Within target |
| `/reference/composition` | reference / none | 473 | 0 / 0 | 0 | 7 / 0 / 0 | 0 | Within target |
| `/reference/limits` | reference / none | 1011 | 1 / 7 | 21 | 10 / 0 / 0 | 0 | Within target |
| `/reference/mcp-catalog` | reference / none | 428 | 1 / 9 | 10 | 3 / 0 / 0 | 0 | Within target |
| `/reference/rpc-catalog` | reference / none | 346 | 2 / 3 | 6 | 4 / 0 / 0 | 0 | Within target |
| `/reference/compatibility` | reference / none | 245 | 2 / 3 | 0 | 3 / 0 / 0 | 0 | Within target |
| `/reference/schemas` | index / 200 | 334 | 1 / 1 | 5 | 4 / 0 / 0 | 0 | Justified exception |
| `/reference/walkthrough` | reference / none | 219 | 4 / 70 | 0 | 5 / 0 / 0 | 0 | Within target |
| `/reference/callables/query-symbols` | reference / none | 324 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Not measured |
| `/reference/callables/check-diagnostics` | reference / none | 56 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Not measured |
| `/reference/callables/add-declaration` | reference / none | 69 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Not measured |
| `/reference/callables/replace-body` | reference / none | 43 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Not measured |
| `/reference/callables/workspace-lifecycle` | reference / none | 110 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Not measured |
| `/reference/models/mcp-tool-call-result` | reference / none | 100 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Within target |
| `/reference/models/mcp-read-result` | reference / none | 89 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Within target |
| `/reference/models/mcp-investigation-result` | reference / none | 83 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Within target |
| `/reference/models/mcp-change-result` | reference / none | 79 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Within target |
| `/reference/models/tool-rpc-reply` | reference / none | 90 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Within target |
| `/reference/models/query-item` | reference / none | 129 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Within target |
| `/reference/models/relation-fact` | reference / none | 103 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Within target |
| `/reference/models/diagnostic` | reference / none | 104 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Within target |
| `/reference/models/live-evidence` | reference / none | 106 | 0 / 0 | 0 | 0 / 0 / 0 | 1 | Within target |
| `/reference/source-map` | reference / none | 230 | 0 / 0 | 0 | 6 / 0 / 0 | 0 | Within target |

## Review findings

### `/reference/query-pipelines`

Profile: task. Observed static prose: 704 words.

Composition, source context, output continuation, and absence rules share this page. The exact forms and qualifications remain necessary.

Propose a content-owner review of the separate source-read and retained-output responsibilities. Keep every current example until that review.

### `/reference/evidence-boundaries`

Profile: explanation. Observed static prose: 1339 words.

Two imported concepts cover evidence authority, outcome variants, recovery actions, and impact accounting. Each qualification protects interpretation.

Propose narrower public projections from the existing canonical explanations. Keep encoded variants and their qualifications.

### `/reference/callbacks`

Profile: explanation. Observed static prose: 1038 words.

Two imported concepts cover supplier identity, recursive invocation proof, dependency effects, reuse, completion, and source evidence.

Propose a section-responsibility review. Keep source qualifications and direct field-level links.

### `/explanation/query-lifecycle`

Profile: explanation. Observed static prose: 1052 words.

Two imported concepts and two diagrams cover execution, composition, retention, publication, and presentation. The topics serve different lookup needs.

Propose a reader-focused selection from the canonical concepts. Keep publication and cancellation rules at their point of use.

### `/reference/internals/architecture`

Profile: explanation. Observed static prose: 1597 words.

The authority and module concepts include ownership flows, rejection rules, focused tests, and complete source-owner lists.

Propose a source-owner review of repeated architecture context. Preserve each authority qualification and source citation.

### `/architecture/topology`

Profile: explanation. Observed static prose: 1607 words.

The module and hosted-composition concepts include public composition, module responsibilities, transitions, and source-owner lists.

Propose a review of repeated module context across architecture pages. Keep publication responsibilities and ownership facts.

### `/reference/lifecycles`

Profile: explanation. Observed static prose: 982 words.

Request, connection, preparation, and daemon lifetimes share one explanation. Deadlines, settlement, and recovery qualifications protect correct interpretation.

Propose a section-purpose review before a rewrite. Keep each lifecycle owner and its distinct completion condition.

### `/reference`

Profile: index. Observed static prose: 393 words.

The index combines contract routing, related architecture, schemas, source ownership, and semantic qualifications.

Propose an index-only wording review. Preserve the linked contracts and the necessary warning against runtime claims.

## Justified exception

`/reference/schemas` exceeds the index target. It preserves the schema-authority qualifications, admission limits, four input sources, and example limits.

The new navigation block links directly to all five callable entries and nine model entries. These lookup destinations serve the index outcome.

Keep those qualifications and direct destinations. A future content review can examine repeated orientation text without weakening them.

## Missing measurements

- `/explanation/value-journey`: Static prose includes every imported MDX use. The interactive explorer has authored labels, source panels, and scenario states outside this count.
- `/reference/callables/query-symbols`: Only the operation summary and description count here. Mintlify expands schema descriptions and controls at render time. The full prose footprint remains unmeasured.
- `/reference/callables/check-diagnostics`: Only the operation summary and description count here. Mintlify expands schema descriptions and controls at render time. The full prose footprint remains unmeasured.
- `/reference/callables/add-declaration`: Only the operation summary and description count here. Mintlify expands schema descriptions and controls at render time. The full prose footprint remains unmeasured.
- `/reference/callables/replace-body`: Only the operation summary and description count here. Mintlify expands schema descriptions and controls at render time. The full prose footprint remains unmeasured.
- `/reference/callables/workspace-lifecycle`: Only the operation summary and description count here. Mintlify expands schema descriptions and controls at render time. The full prose footprint remains unmeasured.

## Initial visible density

The desktop preview uses 1440 × 1000 pixels. The mobile preview uses 390 × 844 pixels. Screenshots reside in the visual evidence directory.

Overview shows its purpose and Set up action before optional background at both widths. The mobile action starts within the initial viewport.

Set up shows its purpose and essential prerequisites before the installer. The page keeps the three-stage path on one page.

The full setup prerequisite list extends beyond the initial mobile viewport. Installation and the first-question prompt need vertical scroll.

The source contains three agent tabs and one update accordion. All panels count toward total density, although the first view shows fewer commands.

Overview also exposes CLI and architecture destinations beyond the two secondary discovery targets. This is a density finding, not permission to remove content.

## Evidence limits

No density budget authorizes a rewrite. No count proves task completeness, reader comprehension, native query behavior, or the absence of information overload.

The source inventory and static extraction support these counts. The visual review covers entry navigation and setup, not every generated schema expansion.
