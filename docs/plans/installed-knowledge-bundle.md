# Installed knowledge bundle

## Goal

Ship a zero-configuration, read-only knowledge bundle with the Kast CLI that exposes two existing sources of repository knowledge:

1. scoped `AGENTS.md` guidance already bound to verified Gradle modules; and
2. public Kotlin/Java declaration documentation extracted from the source tree with signatures and KDoc/Javadoc text.

The installed product must support knowledge lookup without a checkout, IDE, semantic sidecar, Gradle invocation, or network access.

## Authority

- `generateKastModuleKnowledge` remains the authority for verified module identity, dependency edges, and scoped `AGENTS.md` bindings.
- declaration identity and signatures are extracted from source at build time and bound to a single Gradle module.
- generated Markdown/JSON resources are immutable release artifacts.
- any search index is derived acceleration only; it is not semantic or documentation authority.

## First iteration

The first iteration intentionally indexes only Kast's own source declarations. It does not index external dependency documentation or live repository usage.

The bundle is staged under `share/kast/knowledge/` and contains:

```text
manifest.json
modules/
  <project-path>/
    index.json
    guidance.json
    declarations/
      index.json
      <stable-id>.json
```

Each module index is shallow. It contains descriptors, not child documentation bodies. A declaration card contains the exact signature, caller-facing documentation, source location, and applicable guide references. This preserves progressive disclosure mechanically.

## CLI surface

`kast knowledge <query>` searches the installed descriptors. `kast knowledge --resource <relative-path>` reads one exact resource. Both operations use only the proven installed control root.

## Fail-closed requirements

Generation rejects:

- a declaration that cannot be assigned to exactly one included Gradle module;
- duplicate declaration identities;
- unsupported declaration/source shapes that would otherwise be silently dropped;
- missing governing guide coverage for an indexed module;
- generated output that exceeds the bounded resource format.

Installed admission rejects a missing or malformed manifest or a requested resource outside the knowledge root.

## Verification

The first implementation must prove:

- deterministic generation from a fixed checkout;
- shallow module indexes contain no declaration bodies;
- declaration cards retain complete extracted documentation;
- module guidance references match `generateKastModuleKnowledge` bindings;
- the control distribution contains the bundle;
- installed CLI lookup works from an unrelated directory with no network, Gradle, or IDE dependency.

## Explicit uncertainty

The preferred extractor is Dokka because it exposes Kotlin/Java documentables and structured KDoc/Javadoc. The first implementation must compile the adapter against the repository's pinned Kotlin/Gradle versions before claiming that integration works. If Dokka cannot be introduced without destabilizing the current build, the implementation may use the Kotlin compiler/PSI already available in build tooling, but must preserve the same detached declaration contract and must not fall back to regex-based semantic parsing.
