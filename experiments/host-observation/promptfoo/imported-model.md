# Imported Gradle model evaluation

This scenario asks which file supplies `formatInvoiceReference(Long)` to the imported Gradle `main` source set.
Both workspaces contain the same tracked sources and build files.
Each workspace has a separate selection file one directory outside its root.
That file selects the project that Gradle imports.

## Prepare and qualify both workspaces

Run these commands from the repository root. Set `evaluation_root` to a new directory under a parent that already exists.

```sh
evaluation_root="$PWD/build/imported-model-worlds"
mkdir -p "$PWD/build"
npm ci --prefix experiments/host-observation/promptfoo
node experiments/host-observation/promptfoo/imported-model.mjs --prepare "$evaluation_root"
node experiments/host-observation/promptfoo/imported-model.mjs --qualify "$evaluation_root"
```

The qualification command checks the Git indexes and reads both Hosts through `kast query`.
It requires an exhaustive result with one declaration, zero failures, and zero omissions from each Host.
The two results must identify different files and agree with the independent selections.

## Run the Promptfoo scenario

Use the prepared fixture and the authenticated Pi setup for this suite.

```sh
KAST_EVAL_IMPORTED_MODEL_ROOT="$evaluation_root" \
  npm --prefix experiments/host-observation/promptfoo run eval -- \
  -c imported-model.config.mjs --no-write -o "$evaluation_root/promptfoo-results.json"
```

All three arms use the same answer assertion.
The `source` and `kast` arms receive the same question and source text in both worlds.
The `materialized` arm also receives the contents of the selection file.
The `kast` arm can run one exact declaration query with the installed CLI.
Its `queryPath` records the file when a tool call returns a complete result that identifies the declaration.

Compare two arms with the shared comparison command.

```sh
npm --prefix experiments/host-observation/promptfoo run compare -- "$evaluation_root/promptfoo-results.json" source kast
npm --prefix experiments/host-observation/promptfoo run compare -- "$evaluation_root/promptfoo-results.json" kast materialized
```

Stop each of the two owned Hosts after the run.

```sh
kast stop --workspace "$evaluation_root/adapter-a/workspace" --json
kast stop --workspace "$evaluation_root/adapter-b/workspace" --json
```
