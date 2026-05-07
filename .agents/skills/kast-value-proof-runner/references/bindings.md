# Bindings bootstrap

Only use this on the first run for a repository that does not already have a bindings file.

## Target file

Create:

```text
.agents/skills/kast/value-proof/bindings/<repo-name>.json
```

Start from:

```text
.agents/skills/kast/value-proof/bindings/template.json
```

## Slot selection rules

Pick concrete symbols from the current repository that satisfy these roles:

- `SEALED_HIERARCHY`: a sealed class or interface with 3+ implementations, ideally across modules
- `DISAMBIGUATE_MEMBER`: a property name that appears on unrelated types, so raw text search would over-match
- `CROSS_MODULE_CLASS`: a type referenced from at least two modules
- `OVERLOADED_OR_COMMON_FUNCTION`: a common member function name that needs `containingType` or `fileHint` to disambiguate
- `RENAME_TARGET`: a type or interface with references in multiple files so rename safety is meaningful
- `LARGE_CLASS`: a large class where scaffolded structure is meaningfully cheaper than raw reads
- `MODULE_LIST`: the module names expected from workspace discovery

Prefer stable, non-generated symbols and avoid tests unless the slot explicitly benefits from test coverage.

## Validation

After writing the bindings file, prove it is usable by rendering the catalog:

```bash
python3 .agents/skills/kast/value-proof/scripts/render_prompts.py \
  --catalog .agents/skills/kast/value-proof/catalog.json \
  --bindings .agents/skills/kast/value-proof/bindings/<repo-name>.json \
  --output /tmp/<repo-name>-rendered-catalog.json
```

Do not continue to benchmark execution until rendering succeeds.
