---
title: JSON-RPC operations
description: The schema-backed command catalog shared by docs, skills, and
  Copilot extension tools.
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
nested object fields, variants, enum values, response types, and Copilot
`kast_*` tool exposure. Walkable minimal and maximal request examples live under
`resources/kast-skill/references/requests/`.

The Copilot extension does not maintain a parallel tool schema. Its shared
`kast-tools.mjs` module loads the same catalog, derives each tool's parameter
schema from the command request model, and calls the catalog's JSON-RPC
`method` value. During `kast install copilot`, the Rust installer
copies the catalog into `extensions/_shared/commands.json` so installed
extensions use the same operation definitions as the packaged skill.

Update the catalog first when adding or changing a JSON-RPC method. Docs,
packaged skills, extension tool schemas, and installer smoke tests should then
point back to that file instead of defining method-specific shapes by hand.
Run `python3 resources/kast-skill/scripts/generate-rpc-contract.py --check` and
`python3 resources/kast-skill/scripts/validate-rpc-request.py --all-samples` to
catch stale YAML/examples and invalid request fixtures before sending or
publishing request payloads.
