---
type: Draft API Knowledge Contract
title: Progressive API knowledge disclosure
description: A tested projection profile separates directory navigation, declaration contracts, and optional detail without erasing documentation.
code_sources:
  - path: .github/scripts/api_knowledge.py
  - path: .github/scripts/test_api_knowledge.py
  - path: .github/scripts/test_code_kb.py
---

# Progressive API knowledge disclosure

Status: **draft, contract-first prototype**. The executable slice consumes a
strict, detached documentation record and emits segmented JSON and Markdown.
It does **not** yet extract Kotlin, run Dokka, resolve KDoc links, bind knowledge
concepts or agent guides, or publish a production `knowledge/api/` tree. The
fixtures are explicitly hand-authored, not compiler or Dokka evidence.

## The enforced separation

| Resource | Permitted payload | Forward ownership edges |
| --- | --- | --- |
| Index | Identity and bounded child descriptors only | Index or card |
| Card | Declaration identity, signature, provenance, complete caller-facing documentation, detail descriptors | Detail |
| Detail | One explicitly deferred section and a descriptor of its owning card | None |

A descriptor contains only `path`, `kind`, `title`, `summary`, and
`summary_is_excerpt`. It cannot embed a signature, contract, implementation,
example, or another resource. Indexes contain descriptors, not card objects;
cards carry metadata and contract sections, not the unsegmented input record.
The renderer has distinct `_Index`, `_Card`, and `_Detail` inputs. Exact
regeneration is the structural enforcement boundary; this Python prototype
makes no claim of compiler-enforced semantic classification of prose.

Containment is a tree; owner backlinks are a separate navigation relation.
Each declaration card has one owning index, and each detail has one owning
card. Descriptor metadata is projected from its target, not authored twice.
All resources are reachable from `index.json`; Markdown mirrors the same
edges. There is no required whole-corpus catalog or recursive expansion call.
`path` is bundle-relative in JSON; Markdown links are relative to their page.
The resource graph, not arbitrary filesystem enumeration, defines the walk.

A consumer reads one index, selects a descriptor, then opens exactly that
resource. A direct search hit may open a card without walking from the root.
Details point back to their card so direct hits remain interpretable. Consumers
must not concatenate JSON and Markdown versions of the same resource or
recursively preload children. Static files cannot prevent a client from reading
the entire tree; the profile governs resource composition and default traversal,
not access control.

## Segmentation is conservative, not summarization

Description, parameter, return, exception, receiver, property, constructor,
version, deprecation, cross-reference, and unknown custom-tag sections remain on
the card. This includes preconditions, invariants, failure semantics, effect
boundaries, and caller obligations wherever the author documented them.
Only explicitly classified `implementation` and `sample` sections become detail
resources. Their descriptors do not quote implementation/example bodies.
Unclassified prose stays on the card. Unknown projection section kinds are
rejected, never dropped; an extractor retains unknown KDoc tags as `custom`,
including their original names and contents.

Do not move an obligation into detail just because the card is large. Conversely,
an entire private implementation declaration can be deferred until its own card
is opened; visibility is not a license to remove part of a selected declaration's
contract. This prototype does not yet select a visibility policy or model nested
declaration containment.

Every input section keeps its ordinal and exact text in exactly one body owner
per output format. Summaries may repeat an excerpt but are not body owners. The
first description paragraph supplies the routing synopsis; no LLM paraphrasing
or new semantic claim is introduced. A long synopsis is explicitly marked as an
excerpt. Full text survives on the card. Sections are never cut at arbitrary byte
or token boundaries.

**Semantic limitation:** a validator cannot prove that prose labeled as an
implementation note contains no caller obligation. The producer must classify
from explicit KDoc structure, default to the card, and retain review ownership
for explicit deferrals. The tests prove placement and conservation, not the truth
or adequacy of the documentation.

## Profile v1 limits and identity

The executable constants in `api_knowledge.py` own the limits: 16 children per
index; 240 Unicode characters per synopsis; 16 KiB per index file; 32 KiB per
card or detail file; 8 MiB per detached input. File budgets measure actual UTF-8
bytes including metadata, not estimated model tokens. These are initial profile
limits, not benchmark-derived optimal settings.

Overflowing indexes are split into deterministic range buckets until every level
is bounded. No declaration disappears because a directory has too many entries.
An oversized card/detail rejects generation; it does not silently truncate text,
hide a contract section, or publish a partial success. Larger corpora require
measured profile changes or module-scoped extraction/assembly, not an unlimited
root response. Tests cover more than two levels of index fan-out.

A declaration ID hashes the unambiguous JSON tuple of actual Gradle project
identity, source-set identity, and opaque DRI. It is not a Kast selector or proof
of current semantic identity. Overloads require distinct extractor-issued DRIs.
Repeated IDs reject the input. Project/source-set metadata comes from the
producer, not from guessing a module directory from a Gradle path.

Every resource records the same canonical-input snapshot and producer identity.
The snapshot includes declared source content hashes and section text, making
input order irrelevant while detecting supplied content changes. It is **not**
evidence that an extractor actually read those source bytes: verifying extraction
provenance against the checkout is a separate, unfinished boundary. A release
commit alone cannot identify a dirty working tree.

## Executable seam

Input is versioned JSON with exactly `formatVersion`, `producer`, and a nonempty
`declarations` array. Each declaration requires `projectPath`, `sourceSet`, `dri`,
`name`, `signature`, `sourcePath`, `sourceSha256`, and `sections`. Each section has
exactly `kind` and `text`. An empty section list explicitly represents an
undocumented declaration. Duplicate keys, missing/unknown fields, unsafe paths,
unknown section kinds, and duplicate declaration identities reject ingress.
`test_api_knowledge.py` contains a small synthetic example and the executable
contract; there is no separately maintained parallel schema in this slice.

```sh
python3 .github/scripts/test_api_knowledge.py
# With a detached export from the future extractor:
python3 .github/scripts/api_knowledge.py export.json build/generated/api-knowledge
python3 .github/scripts/api_knowledge.py export.json build/generated/api-knowledge --check
```

The CLI stages admitted output before publishing a new directory. It refuses to
overwrite an existing tree. `--check` is read-only and rejects missing, edited,
stale, extra, or symlinked output files. It compares against fresh projection,
not merely a schema-valid stale artifact. Neither command edits source, authored
knowledge, agent instructions, or runtime state. The existing `knowledgeBaseTest`
entry point also runs the disclosure suite; `verifyKnowledgeBase` and root
`check` already depend on that entry point.

## Dokka translation gate

Use Dokka's declaration/documentation models, not regex extraction, HTML
scraping, or a new Kotlin parser. The adapter's output becomes the strict input
above. The adapter must be tested against Kast's pinned Kotlin version before
this draft can claim end-to-end extraction.

1. Obtain declarations, source sets, DRIs, signatures, and documentation from
   Dokka's `Documentable`/`DocumentationNode` models before presentation-specific
   flattening. Run on actual Gradle source/classpath inputs, retaining overloads,
   nested declarations, extension receivers, and explicit documentation absence.
2. Render structured description and tag blocks without paraphrasing. Preserve
   parameter/tag subjects, unknown tags, code fences, and resolved references.
   Map all contract-bearing tags to card sections. Map `@sample` to a detail only
   with its binding/body accounted for. Introduce any custom implementation-note
   tag explicitly and test its parsing; no unsupported tag spelling is assumed
   to work merely because it appears in a comment.
3. Prove section conservation and source hashes against real KDoc fixtures,
   including inherited comments, ambiguous references, overloads, and custom
   tags. Unresolved references must remain explicit, not turn into guessed links.
4. Generate a kernel pilot with repository-relative source bindings and run both
   the disclosure parity gate and existing OKF checks. Prove actual generated
   Markdown links as well as JSON graph edges. The current synthetic fixtures do
   not establish correctness for arbitrary Markdown embedded in KDoc.

Dokka supports custom output formats and renderer/translation extension points;
this does not mean a JSON export matching this profile exists out of the box.

## Joining existing knowledge and instructions

Reuse accepted architecture/guide evidence from `generateKastModuleKnowledge`
([module architecture](../modules/architecture.md)), with a separate local-input
identity path rather than inventing a release version. Resolve concept
`code_sources` against the declaration inventory. Distinguish an exact declaration
binding from a file-level association, ambiguity, and unresolved names.

For a source declaration, compute governing `AGENTS.md` ancestry from its source
path and existing guide scope semantics. Do not infer governing instructions from
the generated card's directory; do not promote descendant guides to module-wide
policy. Concepts and guide contents remain in their existing files. Add typed,
lazy references rather than copying them into each card or flattening all three
sources into a prompt.

Those bindings need a versioned addition to the projection model and resolved
link tests before publication. This draft does not emit placeholder or supposedly
verified bindings. After the kernel pilot proves them, add one API-index link to
the existing knowledge/module navigation; preserve authored `AGENTS.md` policy.
No production tool-provider, live IntelliJ index, or release packaging change is
part of this initial segmentation contract.

## References

- [Kotlin KDoc conventions](https://kotlinlang.org/docs/kotlin-doc.html): summary
  paragraph, descriptions, and supported tags.
- [Dokka documentable and documentation models](https://kotlin.github.io/dokka/2.0.0/developer_guide/architecture/data_model/documentable_model/).
- [Dokka extension points](https://kotlin.github.io/dokka/2.0.0/developer_guide/architecture/extension_points/core_extension_points/) and
  [plugin configuration](https://kotlinlang.org/docs/dokka-plugins.html).
- [Open Knowledge Format specification](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md):
  indexes and concept documents remain distinct. This profile adds Kast-owned
  constraints; it does not change the repository's declared OKF version or impose
  closed extension fields on arbitrary third-party OKF documents.
- [Slopsentral semantic ratchet](https://github.com/amichne/slopsentral/blob/47a6fe61b8e48c8eb79b9f9af02f17489e8a0a96/source/skills/semantic-ratchet/SKILL.md):
  preserve admitted structure and prove the named misuse at the narrowest boundary.
