# Public documentation rewrite and assessment

The public site now separates tutorials, how-to guides, reference, and explanation. The rewrite covers all 34 existing MDX pages and adds 11 pages.

This report records the local authoring snapshot before PR delivery. The inspected source revision is `e62692cc5e0be164941a6c551c00d35ad6739ded`.

The authoring branch is `feature/diataxis-documentation`. The pull request records subsequent hosted checks and merge state.

## Scope and preserved facts

The decision is to extend the existing Mintlify site, generated contracts, and OpenWiki context. The rewrite adds no semantic execution owner.

The four tracks give learners an exercise, operators a task procedure, integrators a contract, and maintainers a source-backed explanation.

Existing routes, legacy heading anchors, nine schema bindings, and 69 original fenced examples remain intact. The generated callable artifact stays byte-identical.

The new material covers diagnostics, retained evidence, composition, finite failures, callback fields, query lifecycle, and source ownership.

The callback explanation includes reviewed `kotlin.lazy` initializer supply. It preserves the distinction between deferred supply and runtime execution.

The JSON glossary preserves 19 provisional concepts. Its scope covers the public pages and documentation policy. The generated reader view has no independent terminology authority.

## Executed guards

| Command or procedure | Outcome | Evidence limit |
| --- | --- | --- |
| `npx --yes mint@4.2.841 validate` from `docs/public` | PASSED | Site compilation. |
| `npx --yes mint@4.2.841 broken-links` from `docs/public` | PASSED | Mintlify route and link analysis. |
| `mise exec -- ./gradlew :cli:verifyMintlifyCallableReference installerEntrypointTest knowledgeImpact verifyKnowledgeBase verifyJsonContracts --quiet` | PASSED | Generated contracts, documented installer arguments, JSON syntax, and knowledge citations. |
| `git diff --check` | PASSED | Diff whitespace. |
| Navigation and preservation assertions | PASSED | All 45 MDX pages appear once. Existing bindings and fenced examples remain intact. |
| Generated-schema assertions | PASSED | 25 request templates and three fragments after explicit synthetic-handle substitution. |
| Three generated-schema negative controls | PASSED | Rejection of `path` for diagnostics, retired `completion`, and the unsupported `INTERFACE` request kind. |
| `check_glossary.py` with `--check-render` | PASSED | Registration, scoped terms, and the generated view. |
| Three Vale routing controls | PASSED | Reader-purpose rules apply to new and established routes. |

The schema procedure substitutes syntax-valid synthetic handles for 16 placeholders. It establishes input shape, not handle authority, workspace freshness, or native results.

Two response or transport-wrapper examples remain outside that input-shape procedure. Their unchanged content and generated result contracts have separate guards.

`knowledgeImpact` names `openwiki/contracts/public-tools.md` because navigation is a cited input. Its generator-ownership facts remain accurate after the navigation change.

No material runtime Claim changes. OpenWiki search and read informed source research. The OpenWiki page-job lifecycle was not needed for this rewrite.

## Local prose assessment

Repository Knowledge `3.1.0` supplies the locked assessment and resource policy. The local consumer uses Vale `3.24.0` and the reviewed Kast vocabulary.

The shared procedure is `uv run --locked --script scripts/assess_text.py` with `--vale-bin`, `--glossary`, and explicit prose files.

Its result is `ASSESSED`. Grade advisories remain. The public MDX pages report no diction, terminology, or sentence-length findings under that procedure.

The structural command uses `.vale-local/bin/vale --no-global --config=.vale.ini --output=JSON --glob='*.mdx' docs/public`.

Its exit status is zero. Warnings and suggestions remain. A zero exit status does not establish an issue-free report.

The local reports remain in `build/reports/documentation`:

- `text-assessment.json` keeps engine, policy, glossary, and document digests with located findings.
- `diataxis-vale.json` keeps structural findings.
- `finding-decisions.json` records each remaining rule, location, and reason.
- `glossary.json` keeps registration and document scope.
- `example-schemas.json` records the admitted templates, synthetic substitutions, and negative controls.
- `navigation-and-preservation.json` records page coverage and preserved artifacts.
- `vale-routing-controls.json` records reader-purpose selection.
- `legacy-glossary-baseline.json` keeps the initial assessment of the query reference with the earlier provisional glossary.

## Remaining prose findings

`Slopsentral.Grade` remains on technical material. Precise identifiers, compiler qualifications, and source links contribute to the estimate. Necessary meaning stays intact.

`Explanation.Reference` remains on the single result-comparison table in `reference/evidence-boundaries.mdx`. It distinguishes exact identity, coverage, absence, and rejection.

`HowTo.Reference` remains on the symptom-action table in `reference/troubleshooting.mdx`. Each row selects recovery for a different failed stage.

`Tutorial.We` remains on the first-query exercise. The procedure addresses the learner directly and gives expected evidence without an invented first-person experience.

These findings remain visible. No rule suppression or threshold change hides them.

## Reader and rendered-site assessment

The author did a simulated reader review. The review found the starting state, next action, and completion evidence in each reader track.

The tutorial states expected target source and caller evidence. It includes an absent-name control and cleanup limited to its exercise file.

The retained-evidence guide separates row and evidence positions. The change guide establishes the source effect before another write.

The local browser showed the four reader tracks, task navigation, the reference landing page, and the generated query schema.

Human comprehension is NOT ASSESSED. The native tutorial is NOT RUN. A schema-shaped request does not establish a compiler result.

At this authoring snapshot, `productBuildGate`, hosted CI, and production publication are NOT RUN. The executed guards cover documentation and contracts.

After publication, examine the deployed navigation, generated schema pages, and preserved redirects at the published revision.
