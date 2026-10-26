# Docs reader tracks

Kast has one Mintlify site. It has three tracks: Guides, Architecture, and Reference. `docs/public/docs.json` owns navigation. Existing URLs keep their established routes.

The [Diátaxis framework](https://diataxis.fr/) groups pages by what the reader needs. Its four groups are tutorials, how-to guides, reference, and explanation.

This policy applies to the authored public site. Guides for contributors have their own purpose. Past review reports keep their recorded source version.

Guides teach, show tasks, and help readers recover. Architecture explains how Kast works. Reference gives exact interface contracts. These tracks keep the four Diátaxis page purposes below.

## Choose the page's purpose

| Track | Reader need | Page contract | Expected evidence |
| --- | --- | --- | --- |
| Tutorials | Learn through one exercise. | One starting state, ordered steps, expected results, and cleanup of the learner's files. | The learner checks the result against known source. |
| How-to guides | Complete a known task. | Prerequisites, commands, choices, proof of success, and recovery. | A result that proves the stated task is done. |
| Reference | Find precise technical facts. | Inputs, outputs, defaults, valid forms, defined failures, and source owners. | Current contract or installed catalog. |
| Explanation | Understand a mechanism or decision. | Causes, owners, links, rules, tradeoffs, and limits on proof. | A flow proved by source, with its nearest distinct concept. |

Keep each page in one track. Link to the other tracks for steps, field meanings, or reasons.

The [reference guidance](https://diataxis.fr/reference/) favors neutral facts and consistent lookup pages. Put task steps in a how-to guide.

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

Some old `/reference/...` URLs teach a task or explain how it works. Their track and Vale rules match that reader need.

The Architecture track covers authority, query lifecycle, query reuse, retention and invalidation, topology and ownership, and change lifecycle. `/explanation` remains its overview route; `/architecture` redirects there. New pages use `/architecture/topology` and `/architecture/change-lifecycle`.

Do not move a page just to match a directory name. Keep its routes and heading anchors when their meaning stays the same.

## Source evidence and OpenWiki

Before a key claim, read its contract owner. Check its callers, encoded schema, and focused tests. Trace a flow through one real source path.

Use OpenWiki search and read for a specific design question. Follow its source links. Check the current code.

OpenWiki gives context and source history. It does not replace current source, generated contracts, or the installed catalog.

The public source map names the checked revision. It links contract owners, focused tests, and OpenWiki concepts.

Keep past captures tied to their source version and environment. State which examples only pass a schema check. State which record a native result.

Use OpenWiki's page jobs for a requested wiki update. OpenWiki owns the generated Claims, indexes, run details, and source history.

After cited source changes, run `knowledgeImpact`. If a material Claim changes, reconcile its assigned page through OpenWiki. Run `verifyKnowledgeBase` afterward.

## Generated contracts

The authored public tool contract owns input shapes and defaults. The installed client catalog governs its release.

The callable generator owns `docs/public/reference/callables.openapi.json`. Its `/callables/...` labels name parts of the docs. They are not HTTP endpoints.

From the repository root, use:

```shell
mise exec -- ./gradlew :cli:generateMintlifyCallableReference :cli:verifyMintlifyCallableReference
```

Generate it again only when its source inputs change. Keep model bindings and source links when changing prose.

## Glossary ownership

[The glossary](GLOSSARY.md) defines the terms for current docs and agent guides. `docs/glossary.json` owns its definitions and document scope. The public `/glossary` page and internal `GLOSSARY.md` are generated from it.

Each entry defines a term and gives an example. It states what makes the concept distinct. It cites source proof. Use that term for that concept. Keep code names, commands, and attributed quotes.

The glossary governs terms within its declared scope. Past review reports keep their recorded terms. It still needs an independent reader review.

Use these terms as repository policy. `PROVISIONAL` means the independent reader review is still absent.

An accepted spelling does not define a concept. Related concepts are not always synonyms. Reject a form only with proof that it names the same thing.

Kast owns the JSON parser, renderer, and schema in `docs/tooling`. The retired shared tool no longer does this work.

From the repository root, regenerate both reader views:

```shell
uv run --locked --project docs/tooling python docs/tooling/check_glossary.py docs/glossary.json --render docs/GLOSSARY.md --public-render docs/public/glossary.mdx
```

Export terminology rules into a new temporary directory:

```shell
uv run --locked --project docs/tooling python docs/tooling/check_glossary.py docs/glossary.json --vale-dir /tmp/kast-glossary-rules
```

Copy the generated rules into `config/vale/styles/Kast/`. Remove a generated rule only when the glossary no longer produces it.

Before acceptance, examine the reader view, rule bytes, and current prose:

```shell
uv run --locked --project docs/tooling python docs/tooling/check_glossary.py \
  docs/glossary.json README.md AGENTS.md docs \
  --check-render docs/GLOSSARY.md --check-public-render docs/public/glossary.mdx --check-vale config/vale/styles/Kast
```

`verifyDocumentationGlossary` runs this procedure through `check` and `productBuildGate`. It rejects stale views, changed rules, extra rules, and rejected forms.

The parser rejects unknown variants and clashing labels. It rejects missing evidence and invalid concept links. It keeps `REVIEW` forms without replacing them.

The root `.vale.ini` applies these rules to the same authored scope. The glossary view shows rejected forms as literals.

## Local Vale integration

Vale `3.24.0` runs locally with checked-in rules and licenses. Normal lint needs no style download, hosted service, or installed plugin.

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

`config/vale/styles/config/vocabularies/Kast/accept.txt` owns reviewed spellings. `docs/glossary.json` owns domain meaning. [The Vale record](../config/vale/README.md) lists imported rules and licenses.

Vale reports word and page structure findings. Exit zero can still leave warnings or suggestions.

For the shared prose assessment, set `KAST_WRITING_SKILL` to the installed `skills/controlled-technical-writing` directory. Use its locked procedure:

```shell
uv run --locked --script "$KAST_WRITING_SKILL/scripts/assess_text.py" \
  --vale-bin "$(mise which vale)" \
  README.md AGENTS.md docs/documentation-structure.md
```

List each changed prose file. Save JSON reports under `build/reports/documentation/`. Keep engine, policy, and file digests. Keep each finding's source location.

Assessment report v2 omits glossary data. Keep its findings apart from the Kast glossary report and source digest.

Past reports keep their recorded schema and glossary fields. Do not claim that they prove the current state.

`ASSESSED` means that the procedure ran. It does not mean that findings are absent. Record each unresolved rule, location, and reason.

The shared procedure has a fixed policy for prose metrics. Root Vale also checks spelling, modal verbs, contractions, and page structure. Report these evidence levels on their own.

Review grade-8 and 25-word advisories. Keep the precision the reader needs. Also check the 20-word limit for task steps and the controlled-English rules.

## Acceptance procedure

1. Make sure that navigation reaches each authored MDX page once.
2. Examine preserved schema bindings, routes, heading anchors, and request examples.
3. Compare examples with the generated input schema and the installed catalog when native use matters.
4. Run `mint validate` and `mint broken-links` from `docs/public`.
5. Run the callable, installer-entrypoint, JSON, and knowledge guards from the repository root.
6. Run local Vale and the shared prose assessment.
7. Read each changed page without conversation context and identify its prerequisites, next action, and completion evidence.

An author review simulates a reader review. A prose score and docs build do not prove that readers understand the page. They do not prove native behavior.

The live site reads `docs/public` from `main`. Get separate approval to publish. After it is live, check the rendered tracks and redirects.

If the deploy fails, restore the prior docs revision. Review local changes before they go live.

## Public diagram source

Use beautiful-mermaid for public diagrams.
Keep the authored `.mmd` files in `docs/public/images/architecture`.
The [diagram tool](tooling/diagrams/README.md) owns the renderer, page manifest, and output checks.

Pages import generated JSX snippets. The inline SVG files inherit the site font and theme colors.
Keep the source and generated SVG files at their established URLs.
After a source edit, render the files and examine the page in light and dark themes.
