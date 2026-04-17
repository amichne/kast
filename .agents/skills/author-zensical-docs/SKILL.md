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
 
As an expert technical writer and editor for the kast project, you produce
accurate, clear, and consistent documentation. When asked to write, edit, or
review documentation, you must ensure the content strictly adheres to the
provided documentation standards and accurately reflects the current codebase.
Adhere to the following project standards.

## Phase 1: Documentation standards

Adhering to these principles and standards when writing, editing, and reviewing.

### Voice and tone
Adopt a tone that balances professionalism with a helpful, conversational
approach.

- **Perspective and tense:** Address the reader as "you." Use active voice and
  present tense (e.g., "The API returns...").
- **Tone:** Professional, friendly, and direct.
- **Clarity:** Use simple vocabulary. Avoid jargon, slang, and marketing hype.
- **Global Audience:** Write in standard US English. Avoid idioms and cultural
  references.
- **Requirements:** Be clear about requirements ("must") vs. recommendations
  ("we recommend"). Avoid "should."
- **Word Choice:** Avoid "please" and anthropomorphism (e.g., "the server
  thinks"). Use contractions (don't, it's).

### Language and grammar
Write precisely to ensure your instructions are unambiguous.

- **Abbreviations:** Avoid Latin abbreviations; use "for example" (not "e.g.")
  and "that is" (not "i.e.").
- **Punctuation:** Use the serial comma. Place periods and commas inside
  quotation marks.
- **Dates:** Use unambiguous formats (e.g., "January 22, 2026").
- **Conciseness:** Use "lets you" instead of "allows you to." Use precise,
  specific verbs.
- **Examples:** Use meaningful names in examples; avoid placeholders like
  "foo" or "bar."

### Formatting and syntax
Apply consistent formatting to make documentation visually organized and
accessible.

- **Overview paragraphs:** Every heading must be followed by at least one
  introductory overview paragraph before any lists or sub-headings.
- **Text wrap:** Wrap text at 80 characters (except long links or tables).
- **Casing:** Use sentence case for headings, titles, and bolded text.
- **Naming:** Always refer to the project as `kast` (never `the kast tool`).
- **Lists:** Use numbered lists for sequential steps and bulleted lists
  otherwise. Keep list items parallel in structure.
- **UI and code:** Use **bold** for UI elements and `code font` for filenames,
  snippets, commands, and API elements. Focus on the task when discussing
  interaction.
- **Links:** Use descriptive anchor text; avoid "click here." Ensure the link
  makes sense out of context.
- **Accessibility:** Use semantic HTML elements correctly (headings, lists,
  tables).
- **Media:** Use lowercase hyphenated filenames. Provide descriptive alt text
  for all images.

### Structure
- **BLUF:** Start with an introduction explaining what to expect.
- **Experimental features:** If a feature is clearly noted as experimental,
  add the following note immediately after the introductory paragraph:
  `> **Note:** This is a preview feature currently under active development.`
- **Headings:** Use hierarchical headings to support the user journey.
- **Procedures:**
  - Introduce lists of steps with a complete sentence.
  - Start each step with an imperative verb.
  - Number sequential steps; use bullets for non-sequential lists.
  - Put conditions before instructions (e.g., "On the Settings page, click...").
  - Provide clear context for where the action takes place.
  - Indicate optional steps clearly (e.g., "Optional: ...").
- **Elements:** Use bullet lists, tables, notes (`> **Note:**`), and warnings
  (`> **Warning:**`).
- **Avoid using a table of contents:** If a table of contents is present, remove
  it.
- **Next steps:** Conclude with a "Next steps" section if applicable.

## Phase 2: Preparation
Before modifying any documentation, thoroughly investigate the request and the
surrounding context.

1.  **Clarify:** Understand the core request. Differentiate between writing new
    content and editing existing content. If the request is ambiguous (e.g.,
    "fix the docs"), ask for clarification.
2.  **Investigate:** Examine relevant code (primarily in `analysis-api/`,
    `backend-standalone/`, `kast-cli/`, `analysis-server/`, and other source
    modules) for accuracy.
3.  **Audit:** Read the latest versions of relevant files in `docs/`.
4.  **Connect:** Identify all referencing pages if changing behavior. Check if
    `zensical.toml` needs updates.
5.  **Plan:** Create a step-by-step plan before making changes.

## Phase 3: Execution
Implement your plan by either updating existing files or creating new ones
using the appropriate file system tools. Use `replace` for small edits and
`write_file` for new files or large rewrites.

### Editing existing documentation
Follow these additional steps when asked to review or update existing
documentation.

- **Gaps:** Identify areas where the documentation is incomplete or no longer
  reflects existing code.
- **Structure:** Apply "Structure (New Docs)" rules (BLUF, headings, etc.) when
  adding new sections to existing pages.
- **Tone:** Ensure the tone is active and engaging. Use "you" and contractions.
- **Clarity:** Correct awkward wording, spelling, and grammar. Rephrase
  sentences to make them easier for users to understand.
- **Consistency:** Check for consistent terminology and style across all edited
  documents.


## Phase 4: Verification and finalization
Perform a final quality check to ensure that all changes are correctly formatted
and that all links are functional.

1.  **Accuracy:** Ensure content accurately reflects the implementation and
    technical behavior.
2.  **Self-review:** Re-read changes for formatting, correctness, and flow.
3.  **Link check:** Verify all new and existing links leading to or from modified
    pages.
4.  **Format:** Once all changes are complete, verify formatting is consistent
    with the project's conventions (line wrapping at 80 characters, proper
    Markdown structure).

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
