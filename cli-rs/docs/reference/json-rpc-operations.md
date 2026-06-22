---
title: JSON-RPC operations
description: The schema-backed command catalog shared by docs, skills, and
  LSP custom method generation.
icon: lucide/file-json
---

# JSON-RPC operation catalog

Kast's JSON-RPC operation catalog lives at
`resources/kast-skill/references/commands.json`, with a generated
human-readable YAML projection at
`resources/kast-skill/references/commands.yaml`. The JSON catalog is validated
by `resources/kast-skill/references/commands.schema.json`; the YAML and request
sample tree are generated from the same source.

The catalog is the single checked-in source for method names, request fields,
nested object fields, variants, enum values, response types, Copilot `kast_*`
tool exposure, and generated `kast/*` LSP custom request routes. Walkable
minimal and maximal request examples live under
`resources/kast-skill/references/requests/`.

The Rust LSP adapter does not maintain a parallel custom-method list. Its build
script reads the same catalog and generates the `kast/*` custom request routing
table used by `kast lsp --stdio`. The repository Copilot package extension also
loads the same catalog from its packaged `_shared/commands.json` copy, so the
LSP routes and `kast_*` extension tools stay catalog-backed.

Update the catalog first when adding or changing a JSON-RPC method. Docs,
packaged skills, LSP route generation, repository Copilot package assets, and
installer smoke tests should then point back to that file instead of defining
method-specific shapes by hand. Run `kast generate contract --check` and `kast
validate --all-samples` to catch stale YAML, examples, schemas, and invalid
request fixtures before sending or publishing request payloads.
