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

`docs/glossary.json` owns the provisional definitions and document scope. `docs/GLOSSARY.md` is its generated reader view.

The structured glossary preserves the earlier source-backed concepts with ordinary prose spellings. It does not rename runtime identifiers or generated fields.

The scoped replacement targets cover established names for retained query results. Ambiguous concepts need review rather than a guessed synonym.

Independent reader review remains absent. Provisional definitions do not establish general agreement on terminology.

## Local Vale integration

Use Repository Knowledge `3.1.0`, its locked scripts, and Vale `3.24.0`. Keep prose and assessment local.

Set `KAST_WRITING_SKILL` to the installed plugin's `skills/controlled-technical-writing` directory. Use the versioned bundle that owns the assessment policy.

Install the local consumer from that skill directory. The destination must not exist:

```shell
uv run --locked --script scripts/install_vale.py --destination /absolute/path/to/kast/.vale-local
```

The installer examines the archive digest and bundled resource digests. It supplies a local executable, rules, and licenses without global configuration changes.

The repository's `.vale.ini` points to `.vale-local/styles`. It assigns Diátaxis rules to new directories and established routes by reader purpose.

Copy the reviewed Kast vocabulary into the local consumer from the repository root:

```shell
mkdir -p .vale-local/styles/config/vocabularies/Kast
cp docs/vale-vocabulary/accept.txt .vale-local/styles/config/vocabularies/Kast/accept.txt
```

The vocabulary preserves product names and source terms. It supplies spelling evidence, not domain definitions or rule exemptions.

From the repository root, run the structural assessment:

```shell
mkdir -p build/reports/documentation
.vale-local/bin/vale --no-global --config=.vale.ini --output=JSON --glob='*.mdx' docs/public > build/reports/documentation/diataxis-vale.json
```

The structural assessment examines lexical and page-form rules. It does not establish full Diátaxis conformance or reader comprehension.

For changed prose, use the shared measurement procedure from the skill directory:

```shell
uv run --locked --script scripts/check_glossary.py /absolute/path/to/kast/docs/glossary.json --check-render /absolute/path/to/kast/docs/GLOSSARY.md
uv run --locked --script scripts/assess_text.py \
  --vale-bin /absolute/path/to/kast/.vale-local/bin/vale \
  --glossary /absolute/path/to/kast/docs/glossary.json \
  /absolute/path/to/kast/docs/public/reference/api.mdx
```

Supply every changed prose file explicitly. Keep the JSON report with engine version, policy digest, glossary digest, document digests, and located findings.

To regenerate the reader view, use:

```shell
uv run --locked --script scripts/check_glossary.py /absolute/path/to/kast/docs/glossary.json --render /absolute/path/to/kast/docs/GLOSSARY.md
```

`ASSESSED` means that the procedure ran. It does not mean that findings are absent. Record each unresolved rule, location, and technical reason.

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
