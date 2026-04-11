# Zensical page patterns

Use these page shapes before drafting. The goal is to make the information
architecture obvious before the prose gets polished.

## Landing or index page

Use this page when a section needs a compelling front door.

Suggested structure:

1. Front matter with `title`, `description`, and often `icon`
2. Lead paragraph that explains the section in one breath
3. Card grid or link group for the main paths
4. Short guidance on how to choose between those paths
5. Optional callout for constraints, status, or migration notes
6. Next steps or related sections

Use Zensical well:

- Use grids to turn the page into a navigation surface.
- Use icons or statuses only if they help readers scan.
- Avoid long narrative blocks above the first actionable choices.

## Tutorial or how-to

Use this page when the reader wants to accomplish one task.

Suggested structure:

1. Lead paragraph with outcome and scope
2. Prerequisites or assumptions
3. Numbered steps with one action per step
4. Verification section showing what success looks like
5. Troubleshooting or sharp edges
6. Next steps

Use Zensical well:

- Use admonitions for caveats, not for main steps.
- Use tabs for environment-specific variants.
- Use code blocks with copy affordances for commands and config.

## Reference page

Use this page when readers need quick lookup instead of narrative.

Suggested structure:

1. Lead paragraph defining the object of reference
2. Short orientation on how to use the page
3. Tables, definition lists, or tightly scoped subsections
4. Minimal examples for non-obvious entries
5. Cross-links to tutorials or concepts

Use Zensical well:

- Use tables for parallel data and lookup.
- Use front matter metadata so the page is easy to identify in nav and search.
- Keep each subsection shallow and scannable.

## Comparison or decision page

Use this page when readers must choose between approaches.

Suggested structure:

1. Lead paragraph framing the choice
2. Decision criteria
3. Side-by-side table or card grid
4. Recommendation and trade-offs
5. Worked examples for common scenarios
6. Escape hatches for edge cases

Use Zensical well:

- Use tables when comparison happens row by row.
- Use tabs when the content is alternate implementation detail after the
  decision is already made.
- Use warning or info callouts to flag sharp edges.

## Design or process page

Use this page when the document exists to align people before implementation or
to explain how work moves through the system.

Suggested structure:

1. Context or opportunity
2. Problem framing
3. Alternatives considered
4. Chosen direction and rationale
5. Risks and mitigations
6. Scope, rollout, or feedback path

Use Zensical well:

- Use Mermaid for sequence, state, or relationship diagrams.
- Use tables for trade-offs and decision records.
- Keep links to backlog items, design docs, proposals, or adjacent process
  pages explicit.
