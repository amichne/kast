# Evaluate Codex App Server with an enterprise repository

This command runs the pre-production Kast dynamic-tools integration against one approved local
repository. It produces an evidence directory that another engineer can inspect before this work
becomes production infrastructure.

The command is an evaluation tool. It does not install a Codex integration into an enterprise
environment, expose a network service, or add a public `kast` command.

## Approve the data boundary first

Codex App Server uses the account configured by the local `codex` installation. The model receives
the evaluation prompt and the symbol and relation documents returned by Kast. Those documents can
contain source paths, declaration names, qualified identities, and source ranges.

Before you run the evaluation:

1. Confirm that the repository is approved for the configured Codex account and workspace.
2. Confirm the applicable retention, residency, access-control, and audit policies.
3. Use a dedicated evaluation machine or account if your organization requires isolation.
4. Choose a class with a known direct constructor caller. The command uses that caller as a
   fail-closed assertion. It does not send the expected caller name to the model.

The default `dynamic-only` mode starts Codex App Server with read-only sandboxing. It disables the
shell tool, hooks, plugins, apps, and app-backed MCP. Before startup, the evaluator enumerates the
enabled MCP servers inherited from the local Codex configuration and disables each one explicitly.
The run is a no-go if an inherited MCP startup event or an unexpected model tool call still occurs.
Kast writes its index and runtime state outside the target repository through the normal
installed-product boundaries.

## Check the prerequisites

Run the evaluation from a Kast source checkout on a supported macOS workstation. The checkout must
contain the commit that you want to evaluate.

Check Codex authentication before you start:

```shell
codex --version
codex login status
codex mcp list --json
```

Kast requires a supported IntelliJ IDEA or Android Studio installation for its isolated semantic
indexer. The runner installs Kast from the current checkout unless you pass `--skip-install`.

## Create the request

Copy `example-request.json` outside the repository and edit these fields:

| Field | Required | Meaning |
| --- | --- | --- |
| `schemaVersion` | yes | Must be `1`. |
| `mode` | yes | Use `dynamic-only` unless you intend to run the full-access comparison. |
| `workspaceRoot` | yes | Absolute path to the approved target repository. |
| `symbolQuery` | yes | Exact source name of one Kotlin class. |
| `expectedCallerNames` | yes | One or more direct caller names that Kast must return. |
| `model` | no | Exact Codex model ID. Omit it to use the configured account default. |

The runner rejects missing fields, unknown fields, relative workspace paths, duplicate expected
callers, control characters, and unsupported schema versions before it creates output or runs a
command.

## Inspect the command plan

Run the plan first. This command validates the request and prints every command without installing
or starting anything:

```shell
python3 integration-tests/codex-app-server-evaluation/evaluate.py \
  --request /path/to/request.json \
  --output-directory /path/to/evaluation-output \
  --plan-only
```

Confirm the workspace path, the mode, and the output path in the printed JSON.

## Run the safe evaluation

Run the same command without `--plan-only`:

```shell
python3 integration-tests/codex-app-server-evaluation/evaluate.py \
  --request /path/to/request.json \
  --output-directory /path/to/evaluation-output
```

The output directory must not exist or must be empty. The runner performs these steps:

1. Record the Kast source commit and worktree state.
2. Install Kast from that checkout.
3. Record the Codex and Kast versions without retaining login-status output.
4. Start Kast for the target repository.
5. Suppress inherited Codex MCP and app capabilities, then start a read-only App Server process
   with two deferred Kast tools.
6. Ask Codex to resolve the configured class and pass its exact selector to `relation_read`.
7. Fail unless the result contains every expected caller and the model answers from that result.

## Inspect the evidence

Keep the whole output directory. Its main files are:

| File | Contents |
| --- | --- |
| `request.json` | Normalized request that the Kotlin runner consumed. |
| `environment.json` | Kast commit, worktree state, product versions, workspace, and mode. |
| `result.json` | Final `passed` or `failed` status. |
| `evidence.json` | Isolation observations, tool counts, selector preservation, result, and decision. |
| `dynamic.app-server.protocol.jsonl` | App Server response and event transcript. |
| `dynamic.app-server.stderr.log` | App Server diagnostics. |
| `*.log` | Working directory, argument array, exit code, stdout, and stderr for each step. |

The runner redacts the output of `codex login status`. Review every other file before you move the
bundle into an enterprise evidence system because source paths and symbol names can be sensitive.

## Run the full-access comparison only in isolation

Comparison mode starts a second Codex App Server process that can use shell commands under
`danger-full-access`. It allows the model to invoke the public `kast` CLI so you can compare the
dynamic-tool path with CLI orchestration.

Use comparison mode only in a disposable checkout on an isolated evaluation machine. Set `mode`
to `comparison`, then add the separate authorization flag:

```shell
python3 integration-tests/codex-app-server-evaluation/evaluate.py \
  --request /path/to/comparison-request.json \
  --output-directory /path/to/comparison-output \
  --allow-full-access-comparison
```

The runner rejects a comparison request without that flag before it creates the output directory.

## Interpret failure

The command fails closed. Check `result.json` and the named command log. Common failure codes are:

- `request-invalid`: the request schema or target workspace is invalid.
- `full-access-comparison-not-authorized`: comparison mode lacks the explicit authorization flag.
- `command-unavailable`: a required executable did not start or exceeded the timeout.
- `command-failed`: a prerequisite, installation, startup, or Gradle step failed.
- `evaluation-no-go`: the App Server run completed but violated an evidence condition.
- `evidence-invalid`: the Kotlin runner did not produce readable evidence.

A passing evaluation proves this configured scenario on this workstation, repository state, Codex
version, Kast version, and model. It does not prove organization-wide deployment readiness.
