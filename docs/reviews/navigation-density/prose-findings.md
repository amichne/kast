# Prose findings

Root Vale exits zero with advisory findings. The locked assessment returns ASSESSED and preserves its advisory findings.

The first batch included metadata-only generated shells and returned `NO_PROSE`. The final procedure assesses prose changes separately from metadata-only changes.

Each raw report keeps the source digest, engine version, rule, and source location. The assessment does not claim reader comprehension.

## Root Vale findings

The baseline report uses the same root configuration against source from the examined commit. Advisory counts and grades can change after navigation labels change.

No new spelling error or diction error remains in authored text. The report keeps grade and page-structure advisories without prose changes.

| File | Line | Rule | Reason |
| --- | ---: | --- | --- |
| `docs/public/agent-harnesses.mdx` | 1 | `HowTo.Steps` | The preserved procedure uses its existing steps or MDX controls. This navigation task does not rewrite the procedure. |
| `docs/public/architecture/change-lifecycle.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/explanation.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/how-to/complete-engineering-task.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/callbacks.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/installation.mdx` | 1 | `HowTo.Steps` | The preserved procedure uses its existing steps or MDX controls. This navigation task does not rewrite the procedure. |
| `docs/public/reference/installation.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/integrations.mdx` | 1 | `HowTo.Steps` | The preserved procedure uses its existing steps or MDX controls. This navigation task does not rewrite the procedure. |
| `docs/public/reference/internals/architecture.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/internals/background-startup.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/management.mdx` | 1 | `HowTo.Steps` | The preserved procedure uses its existing steps or MDX controls. This navigation task does not rewrite the procedure. |
| `docs/public/reference/management.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/management.mdx` | 63 | `HowTo.Reference` | The preserved procedure keeps its existing interface links. A content rewrite remains outside scope. |
| `docs/public/reference/mcp-catalog.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/responses.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/search.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/source-map.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/source-map.mdx` | 1 | `Reference.Examples` | The preserved lookup page keeps its existing examples or schema bindings. This task adds no contract examples. |
| `docs/public/reference/staleness.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/reference/staleness.mdx` | 1 | `Reference.Examples` | The preserved lookup page keeps its existing examples or schema bindings. This task adds no contract examples. |
| `docs/public/reference/symbols.mdx` | 1 | `Reference.Examples` | The preserved lookup page keeps its existing examples or schema bindings. This task adds no contract examples. |
| `docs/public/reference/troubleshooting.mdx` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/public/start.mdx` | 1 | `HowTo.Steps` | The preserved procedure uses its existing steps or MDX controls. This navigation task does not rewrite the procedure. |
| `docs/public/troubleshooting.mdx` | 1 | `HowTo.Steps` | The preserved procedure uses its existing steps or MDX controls. This navigation task does not rewrite the procedure. |
| `docs/public/tutorials/first-query.mdx` | 1 | `Tutorial.Steps` | The tutorial keeps its existing MDX Steps structure and source exercise. This task changes only navigation. |
| `docs/reviews/navigation-density/README.md` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/reviews/navigation-density/contextual-links.md` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/reviews/navigation-density/density.md` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |
| `docs/reviews/navigation-density/page-contracts.md` | 1 | `Slopsentral.Grade` | The page keeps technical names and qualifications. The grade is advisory and does not prove comprehension. |

| `docs/reviews/navigation-density/prose-findings.md` | 1 | `Slopsentral.Grade` | Technical rule names and source explanations remain necessary. The grade remains advisory. |

## Assessment findings

The report [prose-assessment.json](evidence/prose-assessment.json) includes per-file outcomes, glossary scope, and preserved findings.

Technical paths and concepts remain necessary in page contracts and density records. Grade-8 advisories remain unresolved for that reason.

Root Vale also assesses page structure. Its rules remain separate from the fixed metric procedure and the glossary assessment.

The glossary assessment marks historical review records OUT_OF_SCOPE. It still assesses current public prose within the glossary scope.
