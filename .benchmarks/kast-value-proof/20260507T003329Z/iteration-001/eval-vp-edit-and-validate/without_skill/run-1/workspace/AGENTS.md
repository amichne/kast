# Konditional repository guide

- This file applies to the whole repository unless a deeper `AGENTS.md` narrows it.
- Start with `settings.gradle.kts` before assuming a directory is part of the active build. The root graph includes
  `konditional-types`, `konditional-engine`, `konditional-json`, and `smoke-test`; `build-logic/` is an included build.

## Work surfaces

- `konditional-types/`: shared identifiers, contexts, parse results, and the `io.amichne.kontracts` schema/value
  packages.
- `konditional-engine/`: namespace DSL, evaluation, runtime registry, and shared test fixtures.
- `konditional-json/`: Moshi codecs and strict JSON snapshot parsing on top of engine types.
- `smoke-test/`: end-to-end verification of the public API; not a published library.
- `build-logic/`: precompiled Gradle convention plugins used by module builds.
- `docs/` plus `zensical.toml`: Zensical docs source and site configuration.
- `scripts/`: publish and version shell entrypoints.
- `detekt-rules/`: standalone custom Detekt rules that live in the repo but are not included by the active root module
  graph.

## Verify

- General repo validation: `make build`, `make test`, `make detekt`, `make check`
- Docs changes: `make docs-build`
- Publish or release changes: `make validate-publish`

## Edit rules

- Do not hand-edit generated or local-runtime outputs: `**/build/`, `site/`, `docs/venv/`, `.gradle/`, and
  `build-logic/.gradle/`.
- Keep module boundaries aligned with `docs/reference/module-dependency-map.md`.
- When API shape, JSON format, docs navigation, or release flow changes, update the matching docs or workflow files in
  the same change.
- Prefer the nearest child `AGENTS.md` for module-specific rules.
