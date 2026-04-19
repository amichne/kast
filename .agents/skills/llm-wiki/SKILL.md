---
name: llm-wiki
description: Build and maintain a persistent, interlinked markdown wiki that sits between raw source material and user queries. Use when Codex needs to bootstrap, ingest into, query, or lint an Obsidian-style knowledge base where raw sources stay immutable and the LLM owns the synthesized wiki layer. Trigger for personal research vaults, book companion wikis, due-diligence notebooks, course notes, team knowledge bases, and any workflow that should compound knowledge across many sources instead of re-deriving answers from raw documents each time.
---

# LLM Wiki

Treat the wiki as a compiled knowledge layer. Keep raw sources immutable, keep the wiki editable, and make synthesis persistent so future work starts from accumulated understanding rather than fresh retrieval.

## Start Every Task

- Read the local schema first: `AGENTS.md`, `CLAUDE.md`, or the project-specific equivalent.
- Inspect the existing wiki shape before editing: top-level directories, `index.md`, `log.md`, and any overview or schema pages.
- Infer which operation the user wants: `bootstrap`, `ingest`, `query`, or `lint`.
- Prefer the existing local conventions over the defaults in this skill when the repository already has a clear structure.
- Keep the raw-source layer read-only unless the user explicitly asks to organize or rename source files.

## Maintain The Three Layers

- Keep raw sources as the source of truth. Read them; do not rewrite them.
- Treat the wiki as LLM-owned working memory. Create and update summaries, entity pages, concept pages, comparisons, timelines, and synthesis pages there.
- Treat the schema file as operating instructions. Update it only when the user wants to change the workflow or the wiki has clearly outgrown its current conventions.
- Prefer rich markdown links over plain mentions so the wiki remains traversable in Obsidian and similar tools.
- Preserve provenance. Attribute non-trivial claims to source pages or raw documents instead of flattening everything into unattributed prose.
- Call out contradictions, uncertainty, and superseded claims explicitly instead of silently overwriting them.

## Bootstrap A Wiki

- Create the minimum structure that makes future ingest easy. Prefer a small layout over a fully generalized one.
- Add `index.md` as the content-oriented entry point and `log.md` as the chronological record if they do not already exist.
- Create an overview page that explains the subject area, scope, and major sections of the wiki.
- Group pages by function rather than by date: for example `sources/`, `entities/`, `concepts/`, `analyses/`, or similarly clear buckets.
- Keep the first version simple. Do not add search tooling, Dataview conventions, or extra automation until the wiki actually needs them.
- Use the templates in [references/page-patterns.md](references/page-patterns.md) when creating the initial structure.

## Ingest A Source

- Read the new source carefully before touching the wiki.
- Read linked local images separately when they materially affect the interpretation of the source.
- Decide what durable knowledge the source adds: entities, concepts, events, arguments, metrics, timelines, contradictions, or open questions.
- Create or update a source-summary page.
- Update every affected wiki page that should now reflect the new information, not just the source page.
- Add new cross-links while editing so later queries do not need to rediscover the same relationships.
- Note when the new source strengthens, weakens, or contradicts an existing claim.
- Update `index.md` so the new or revised pages are discoverable.
- Append a parseable entry to `log.md` after the ingest is complete.
- Prefer one-source-at-a-time ingest unless the user explicitly wants batch processing.

## Answer Questions Against The Wiki

- Start with `index.md` to find the relevant area of the wiki.
- Read only the pages needed to answer the question; do not blindly load the whole vault.
- Use local search tools if present. Otherwise rely on `index.md`, targeted file reads, and fast text search such as `rg`.
- Answer from the synthesized wiki first, then dip into raw sources only when the wiki is missing coverage or the question depends on exact wording.
- Cite the pages or source summaries that support the answer.
- When a response creates durable value, file it back into the wiki as a new analysis/comparison page or update an existing page if the workflow calls for compounding query outputs.

## Lint The Wiki

- Look for contradictions across pages.
- Look for claims that newer sources have superseded.
- Look for orphan pages with weak or missing inbound links.
- Look for concepts that are mentioned repeatedly but still lack dedicated pages.
- Look for thin summaries that should be split into entities, concepts, or timelines.
- Look for missing source attribution on high-value claims.
- Suggest source gaps that would materially improve the wiki if the user went and found them.
- Fix obvious structural problems directly. For content gaps or uncertain merges, surface the recommendation clearly.

## Keep `index.md` And `log.md` Useful

- Treat `index.md` as a catalog of current knowledge, organized by category, with one-line summaries for each page.
- Update `index.md` during every ingest and whenever you add a durable analysis page.
- Treat `log.md` as append-only.
- Start each log entry with a predictable heading such as `## [2026-04-14] ingest | Source Title` so humans and shell tools can skim recent work quickly.
- Keep log entries brief: what changed, which pages were touched, and what remains unresolved.

## Prefer Simplicity Over Infrastructure

- Start with markdown files and local search.
- Add external indexing or retrieval tooling only after the wiki is large enough that `index.md` plus local search stops being sufficient.
- Prefer workflows the user can inspect and understand in plain files.
- Keep the wiki readable without requiring a custom app or database.

## Use The Reference File

- Read [references/page-patterns.md](references/page-patterns.md) when you need starter layouts, page templates, or a lint checklist.
- Keep SKILL.md lean. Put new detailed conventions or templates into `references/` rather than bloating the main instructions.
