# Public documentation and shared OKF

The public site can reuse tracked OpenWiki OKF without another owner for shared prose.
This audit examined revision `356f4a6d658e1c4960a6c1188ee3d866517fc3f4` on October 28, 2026.
It covers all 55 public MDX pages outside `snippets/`.
It examines attribution and ownership. It does not establish current semantic truth for every statement.

## Ownership decision

The repository already tracks 65 OKF concepts with Claim sidecars under `openwiki/`.
All 65 page digests match their recorded sidecars at the examined revision.
The repository citation checker examines paths and declaration citations.
Those checks do not prove that prose still describes current behavior.

Ten public pages now consume 17 shared concepts through generated snippets.
Their public routes, presentation metadata, diagrams, and legacy anchors remain under `docs/okf-public.json`.
Each concept has one generated snippet, even when several routes consume it.
The projector preserves code literals and changes source links through a CommonMark tree.

The other 45 pages keep explicit ownership entries in the same manifest.
They include reader navigation, procedures, schema bindings, illustrations, and seven named gaps in canonical coverage.
The glossary and callable schemas keep their existing generators.
Repository instructions and historical reports keep their distinct purposes.

## Attribution result

The 17 projected concepts contain 106 evidence references.
Of those references, 45 whole-file digests match current source bytes and 41 differ.
The other 20 references use versions that this projection does not assess.
Every cited file exists and is tracked.

The public views qualify changed and unassessed evidence.
The generated provenance report keeps the recorded versions and current file digests.
A matching digest proves byte equality only.
The projector never upgrades a recorded Claim to current semantic proof.

## Content that still needs canonical reconciliation

These public pages contain details that the current shared concepts do not fully preserve.
Their content remains until the native OpenWiki lifecycle reconciles those facts and qualifications.

| Public route | Detail that needs canonical reconciliation |
| --- | --- |
| `/architecture/query-reuse` | Exact-name exclusion, shared TRACE arrivals, reference inventory admission, and route-preserving IMPACT reuse. |
| `/reference/lifecycles` | Current workspace preparation deadlines, recovery, request settlement, and transport-specific ownership. |
| `/reference/program-tracing` | Source and invocation interpretations, producer representations, and runner-controlled execution. |
| `/reference/callback-observations` | Current named-edge policies, binding variants, scan outcomes, and exact failure subjects. |
| `/reference/responses` | Current result projections, detail selectors, summaries, and original completion evidence. |
| `/reference/api` | Current public grammar, terminal operations, defaults, and execution modes. |
| `/reference/limits` | Current work, storage, output, and runner-controlled execution limits. |

This audit does not claim that all duplicated prose disappeared.
It prevents silent content loss and names each remaining canonical-coverage obligation.
Use the existing OpenWiki store for that reconciliation.

## Acceptance and upkeep

`okf_public.py --check` rejects changed generated content, unowned public routes, missing citations, and untracked evidence.
A changed canonical page also needs its matching Claim sidecar before projection.
The focused tests use independently specified source text, links, code literals, and rejection cases.
The Gradle gate and documentation CI lane use the same projector.

The OpenWiki update workflow regenerates public views after a successful update.
It proposes the canonical changes and public projections together.
Native OpenWiki still owns page queues, Claims, indexes, and provenance.

Local rendering, remote deployment, and reader comprehension need separate evidence.
This audit grants no merge or deployment authority.
