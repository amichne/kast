# Documentation reader tracks

Kast uses one Mintlify site with four reader tracks. `docs/public/docs.json` owns navigation. Existing URLs keep their established routes.

The [Diátaxis framework](https://diataxis.fr/) distinguishes tutorials, how-to guides, reference, and explanation by the reader's need.

This policy applies to the authored public site. Contributor guides and historical review reports keep their separate purpose and evidence revisions.

## Choose the page's purpose

| Track | Reader need | Page contract | Expected evidence |
| --- | --- | --- | --- |
| Tutorials | Learn through one exercise. | One starting state, ordered steps, expected results, and owned cleanup. | The learner compares the result with controlled source. |
| How-to guides | Complete a known task. | Prerequisites, commands, decision points, completion evidence, and recovery. | A result that establishes the task's stated outcome. |
| Reference | Find precise technical facts. | Inputs, outputs, defaults, legal combinations, finite failures, and source owners. | Current contract or installed catalog. |
| Explanation | Understand a mechanism or decision. | Causes, ownership, relationships, invariants, tradeoffs, and evidence limits. | Source-backed flow and the nearest distinct concept. |

Keep the page in one track. Link to another track when the reader needs a procedure, field definition, or explanation.

The [reference guidance](https://diataxis.fr/reference/) favors neutral descriptions and standard lookup patterns. A procedure belongs in a how-to guide.

## Public routes

| Purpose | Routes |
| --- | --- |
| Site orientation | `/`, `/tutorials`, `/how-to`, `/reference`, `/explanation`. |
| First exercise | `/tutorials/first-query`. |
| Everyday setup | `/start`, `/agent-harnesses`, `/troubleshooting`. |
| Task selection | `/search`. |
| Investigation | `/reference/search`, `/how-to/inspect-diagnostics`, `/how-to/read-retained-evidence`, `/reference/query-pipelines`. |
| Source changes | `/reference/change`. |
| Installation and maintenance | `/reference/installation`, `/reference/integrations`, `/reference/troubleshooting`, `/reference/management`. |
| Semantic contracts | `/reference/api`, `/reference/responses`, `/reference/symbols`, `/reference/composition`, `/reference/limits`. |
| Tracing, state, and lifecycle contracts | `/reference/program-tracing`, `/reference/staleness`, `/reference/lifecycles`. |
| Client contracts | `/reference/mcp-catalog`, `/reference/rpc-catalog`, `/reference/compatibility`. |
| Schemas and recorded evidence | `/reference/schemas`, `/reference/walkthrough`, `/reference/models/...`, `/reference/callables/...`. |
| Evidence explanation | `/reference/evidence-boundaries`, `/reference/callbacks`, `/explanation/query-lifecycle`. |
| Runtime explanation | `/reference/internals/architecture`, `/reference/internals/background-startup`. |
| Source ownership | `/reference/source-map`. |

Several established `/reference/...` URLs contain procedures or explanations. Their navigation group and Vale section use their reader purpose.

Do not move a page solely to match a directory name. Preserve inbound routes and heading anchors when their meaning stays unchanged.

## Source evidence and OpenWiki

Before a material claim, examine its contract owner, callers, encoded schema, and focused tests. Trace cross-component behavior through one real source path.

Use OpenWiki search and read for the concrete architecture question. Read its source citations and examine the current implementation.

OpenWiki supplies context and provenance. Its summary does not replace current source, generated contracts, or the installed catalog.

The public source map records the inspected revision and links contract owners, focused tests, and OpenWiki concepts.

Keep historical captures tied to their original revision and environment. Distinguish schema-valid examples from recorded native results.

Use OpenWiki's page-job lifecycle for a requested wiki update. Keep generated Claims, indexes, run metadata, and provenance under OpenWiki ownership.

After cited source changes, run `knowledgeImpact`. If a material Claim changes, reconcile its assigned page through OpenWiki. Run `verifyKnowledgeBase` afterward.

## Generated contracts

The authored public tool contract owns input shapes and normalization defaults. The installed client catalog governs its release.

The callable generator owns `docs/public/reference/callables.openapi.json`. Its `/callables/...` labels describe documentation sections, not HTTP endpoints.

From the repository root, use:

```shell
mise exec -- ./gradlew :cli:generateMintlifyCallableReference :cli:verifyMintlifyCallableReference
```

Regenerate only when its authoritative inputs change. Preserve model bindings and source links during a prose rewrite.

## Glossary ownership

[The glossary](GLOSSARY.md) defines the canonical vocabulary for current documentation and agent instructions. `docs/glossary.json` owns its definitions and document scope.

Each entry records a definition, example, distinction, and source evidence. Use the established term for that concept. Preserve identifiers, commands, and attributed quotations.

The glossary governs terminology within its declared scope. Historical review reports keep their recorded terms. Independent reader review remains absent.

Canonical use is repository policy. `PROVISIONAL` records the absent independent reader review.

An accepted spelling does not establish a definition. A related concept does not establish a synonym. Add a rejected form only with evidence of the same referent.

Repository Knowledge `3.1.0` owns the existing JSON parser and renderer. Set `KAST_WRITING_SKILL` to its installed `skills/controlled-technical-writing` directory.

From the repository root, regenerate the reader view:

```shell
uv run --locked --script "$KAST_WRITING_SKILL/scripts/check_glossary.py" docs/glossary.json --render docs/GLOSSARY.md
```

Export terminology rules into a new temporary directory:

```shell
uv run --locked --script "$KAST_WRITING_SKILL/scripts/check_glossary.py" docs/glossary.json --vale-dir /tmp/kast-glossary-rules
```

Copy the generated rules into `config/vale/styles/Kast/`. Remove a generated rule only when the glossary no longer produces it.

Before acceptance, compare the exported rules with the checked-in rules. Then examine the reader view and current prose:

```shell
uv run --locked --script "$KAST_WRITING_SKILL/scripts/check_glossary.py" docs/glossary.json README.md AGENTS.md docs/public --check-render docs/GLOSSARY.md
```

The root `.vale.ini` applies these rules to the same authored document scope. The glossary reader view describes rejected forms as literals.

## Local Vale integration

Vale `3.24.0` runs locally with checked-in rules and licenses. The repository needs no style download, hosted service, or plugin installation for ordinary lint.

From the repository root, install the pinned executable:

```shell
mise install vale
```

Examine changed prose with the root configuration:

```shell
mise exec -- vale --no-global --config=.vale.ini --output=JSON README.md AGENTS.md docs/documentation-structure.md
```

`.vale.ini` assigns rules by reader purpose. It also supports Conventional Commits through Vale's Commit view:

```shell
mise exec -- vale --no-global --config=.vale.ini --path=COMMIT_EDITMSG < /path/to/message.txt
```

`config/vale/styles/config/vocabularies/Kast/accept.txt` owns reviewed spellings. `docs/glossary.json` owns domain meaning. [Vale provenance](../config/vale/README.md) records imported rules and licenses.

Ordinary Vale output reports lexical and structural findings. Exit zero does not mean that warnings or suggestions are absent.

For the shared prose assessment, use the pinned Repository Knowledge procedure:

```shell
uv run --locked --script "$KAST_WRITING_SKILL/scripts/assess_text.py" \
  --vale-bin "$(mise which vale)" \
  --glossary docs/glossary.json \
  README.md AGENTS.md docs/documentation-structure.md
```

Supply each changed prose file explicitly. Save JSON reports under `build/reports/documentation/`. Keep engine, policy, glossary, and document digests with located findings.

`ASSESSED` means that the procedure ran. It does not mean that findings are absent. Record each unresolved rule, location, and reason.

The shared procedure uses a fixed measurement policy. Root Vale also examines spelling, modal verbs, contractions, and page structure. Keep these evidence levels separate.

Review grade-8 and 25-word advisories without removal of necessary precision. Examine the 20-word procedural limit and controlled-English rules separately.

## Acceptance procedure

1. Make sure that navigation reaches each authored MDX page once.
2. Examine preserved schema bindings, routes, heading anchors, and request examples.
3. Compare examples with the generated input schema and the installed catalog when native use matters.
4. Run `mint validate` and `mint broken-links` from `docs/public`.
5. Run the callable, installer-entrypoint, JSON, and knowledge guards from the repository root.
6. Run local Vale and the shared prose assessment.
7. Read each changed page without conversation context and identify its prerequisites, next action, and completion evidence.

An author review is a simulated reader review. A readability score and documentation build do not establish human comprehension or native behavior.

The deployment reads `docs/public` from `main`. Publication needs separate authorization. After publication, examine the rendered tracks and redirects.

If deployment fails, restore the previous documentation revision. Local changes remain reviewable before publication.
