# Page Patterns

Use these patterns as defaults. Adapt them to the local vault instead of forcing them verbatim.

## Suggested Minimal Layouts

### Small Personal Wiki

```text
raw/
wiki/
  index.md
  log.md
  overview.md
  sources/
  entities/
  concepts/
  analyses/
```

### Flat Obsidian-Friendly Layout

```text
raw/
raw/assets/
index.md
log.md
overview.md
sources/
entities/
concepts/
analyses/
```

Pick the layout that best matches the existing vault. Prefer consistency with neighboring files over abstract neatness.

## `index.md` Pattern

```md
# Index

## Overview
- [[overview]] - Scope, working thesis, and major sections.

## Sources
- [[sources/source-slug]] - One-line summary of what this source adds.

## Entities
- [[entities/entity-name]] - Who or what it is and why it matters.

## Concepts
- [[concepts/concept-name]] - Working definition plus why it matters here.

## Analyses
- [[analyses/question-or-comparison]] - Durable synthesis generated from multiple pages.
```

Keep entries short enough that the index scans well.

## `log.md` Pattern

```md
# Log

## [2026-04-14] ingest | Example Source Title
- Added [[sources/example-source-title]].
- Updated [[concepts/example-concept]] and [[entities/example-entity]].
- Open question: verify whether Source A or Source B is more current.

## [2026-04-14] query | Compare X vs Y
- Answered using [[concepts/x]], [[concepts/y]], and [[analyses/x-vs-y]].
- Filed the durable comparison as [[analyses/x-vs-y]].
```

Keep the heading parseable and the bullets terse.

## Source Summary Page Pattern

```md
# Source Title

## Source
- Type: article | paper | transcript | note | dataset
- Date:
- Author:
- Location:

## Summary
2-5 paragraphs covering the main contribution of the source.

## Key Claims
- Claim 1
- Claim 2

## Connections
- Reinforces [[concepts/...]]
- Contradicts [[sources/...]] on ...
- Adds detail to [[entities/...]]

## Open Questions
- Question 1

## Pages Updated From This Source
- [[concepts/...]]
- [[entities/...]]
```

## Entity Or Concept Page Pattern

```md
# Page Title

## Summary
Short current synthesis.

## What The Wiki Currently Believes
- Point 1
- Point 2

## Evidence And Sources
- [[sources/...]] - Supports ...
- [[sources/...]] - Complicates ...

## Related Pages
- [[entities/...]]
- [[concepts/...]]
- [[analyses/...]]

## Open Questions
- Question 1
```

Prefer explicit uncertainty over false neatness.

## Analysis Page Pattern

Use for comparisons, thematic syntheses, timelines, or answers that should persist after the chat ends.

```md
# Analysis Title

## Question
What this page answers.

## Short Answer
Brief synthesis.

## Comparison Or Analysis
Structured discussion, table, or timeline.

## Evidence Used
- [[sources/...]]
- [[concepts/...]]
- [[entities/...]]

## Follow-Ups
- What to ingest or investigate next.
```

## Lint Checklist

- Does every important page have at least one inbound link path from `index.md` or another hub?
- Did any newer source silently invalidate an older summary?
- Are high-value claims tied to sources?
- Are recurring nouns or themes still stranded as plain text instead of pages?
- Do source summaries mention what other pages were updated?
- Is `index.md` still usable as a first stop?
- Is `log.md` still append-only and parseable?
