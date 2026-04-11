---
name: author-zensical-docs
description:
  Create, revise, or restructure documentation for Zensical-powered sites and
  Zensical framework content. Use when Codex needs to draft or improve
  technical documentation, landing pages, tutorials, references, design docs,
  or process pages that should feel polished, modern, highly navigable, and
  easy to customize in Zensical. Especially useful when the work should
  leverage Zensical authoring features such as front matter, navigation, grids,
  content tabs, admonitions, code blocks, diagrams, tables, images, theme
  overrides, or `zensical.toml`/`mkdocs.yml` examples.
---

# Author Zensical Docs

## Overview

Use the `documentation` skill's three-stage co-authoring workflow as the
backbone, then adapt every drafting decision to Zensical's authoring model.
Produce docs that are technically clear, visually intentional, and organized so
readers can skim, dive deep, and customize confidently.

## Default posture

- Treat the doc site as a product surface, not a text dump.
- Optimize for orientation first: title, lead, structure, cross-links, and next
  actions.
- Use Zensical features to improve comprehension, not to decorate.
- Prefer explicit trade-offs, rationale, and examples over marketing language.
- Make pages easy to consume at three speeds: skim, guided read, and deep
  reference.
- Match the repo's existing configuration dialect. If the site already uses
  `zensical.toml`, stay there. If it uses `mkdocs.yml`, stay there. Only show
  both dialects when the document is framework-level or intentionally
  comparative.

## Workflow

### 1. Gather the page system context

- Identify the document type, audience, and the reader action the page should
  enable.
- Identify whether the site uses `zensical.toml` or `mkdocs.yml`.
- Identify which Markdown extensions and theme features are already enabled.
- Identify where the page belongs in navigation and which pages it should
  connect to.
- Identify available screenshots, diagrams, brand colors, icons, or templates.
- Ask only for the smallest missing context that blocks a confident draft.

### 2. Design information architecture before writing prose

- Choose a page shape from `references/page-patterns.md`.
- Decide whether the page is a landing page, tutorial, reference, comparison,
  design doc, or process note.
- Draft the reader path before writing body text: lead, key decisions, primary
  content blocks, cross-links, and next steps.
- Promote information into navigation structure when multiple sibling pages are
  emerging. Do not bury architecture inside a long scrolling page.
- Use front matter deliberately: `title`, `description`, `icon`, `status`,
  `hide`, and `template`.

### 3. Select the right Zensical primitives

- Read `references/feature-playbook.md` before reaching for layout or
  interaction features.
- Use content tabs for alternatives, not steps.
- Use grids for entry pages and option overviews, not for dense paragraphs.
- Use admonitions for side content, constraints, or cautions, not for the main
  narrative.
- Use tables for comparison and lookup, not for prose.
- Use Mermaid when structure or sequence is easier to understand visually.
- Use captions, alt text, and light/dark variants for images when visuals carry
  meaning.
- Use CSS or JavaScript customization only after exhausting content structure
  and existing theme features.

### 4. Draft with technical communication discipline

- Open every page and major section with a short orienting paragraph before
  lists or subheadings.
- Lead with what the reader will learn, decide, or do.
- Keep headings task- or concept-oriented.
- Prefer tight examples with realistic identifiers.
- Pair explanation with the exact file, config key, or page artifact the reader
  will edit.
- Show minimal working examples first and advanced customization second.
- Make rationale explicit whenever a non-obvious design decision, override, or
  feature flag appears.
- Cut filler. If a paragraph does not help the reader decide, do, or
  understand, rewrite or remove it.

### 5. Refine for polish and adaptability

- Audit the draft for scanability. Page title, summary, callouts, tables, code,
  and link labels should stand on their own.
- Audit the draft for customization hooks. Palette, navigation, templates,
  extra CSS or JavaScript, icons, and status markers should be easy to find.
- Convert duplicated configuration snippets into tabs or comparison tables.
- Suggest navigation updates when a page would be hard to discover in the
  current tree.
- Keep customization guidance surgical. Explain what to override, why, and
  where it lives.
- End with concrete next steps or adjacent paths when the reader is likely to
  continue.

### 6. Reader-test like the `documentation` skill

- Predict the top questions a new reader will ask.
- Check whether the page answers them without relying on unstated team context.
- Check whether the page still makes sense when skimmed from headings, captions,
  tables, and callouts alone.
- Tighten ambiguous labels, hidden prerequisites, and missing verification
  steps before stopping.

## Zensical-specific rules

- Prefer explicit navigation and section landing pages once a topic grows beyond
  a handful of peers.
- Keep configuration examples visually consistent. If you show both dialects,
  use tabs with stable labels such as `zensical.toml` and `mkdocs.yml`.
- Use page icons and status metadata to improve orientation in navigation.
- Hide sidebars only for intentional special cases such as custom landing
  pages.
- Document any JavaScript customization with `document$` when instant
  navigation is in play.
- Treat color and branding choices as readability decisions, not decoration.
- Prefer a small number of powerful components used well over stacking many
  features on one page.

## Output expectations

- Deliver Markdown that is ready to drop into the Zensical site.
- Include front matter when it materially improves navigation, metadata, or
  presentation.
- Call out any required config changes, asset additions, or override files
  separately from the main prose.
- Suggest navigation updates when the page changes the information architecture.

## Read these references when needed

- Read `references/feature-playbook.md` when choosing between tabs, grids,
  admonitions, tables, diagrams, images, or customization.
- Read `references/page-patterns.md` when designing a page from scratch or
  restructuring a weak page.
- Read `references/ways-of-working.md` when writing process docs, change
  proposals, design docs, or pages that should reflect how Zensical frames
  collaboration and decision-making.
