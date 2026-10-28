# Task access candidate

This local experiment exposes Kast through a skill and task commands.
It uses the installed tool RPC with `--catalog=COMPLETE`.
The binary, IDEA plugin, MCP server, and wire contracts stay unchanged.

The current candidate supports installed contract 17. Other contract versions reject locally.
The installed catalog admits each request before its semantic call.
The command preserves the original reply, including failures, qualifiers, references, and cursors.

## Use

Give the agent [SKILL.md](SKILL.md), then use the command from the target repository.
Python 3.12 or 3.13 and uv are necessary.

```sh
uv run --locked --script experiments/task-access/access.py find QueryCompletionDocument --kind interface --directory protocol --source-set main
uv run --locked --script experiments/task-access/access.py --help
```

Add `--emit-request` to examine the request without a semantic call.
Use `--rpc PATH` for another existing installation.
Use `--workspace PATH` to select the exact repository.
Use `--save NEW_FILE` to keep the original reply.

The task commands cover discovery, direct relationships, trace, traversal, source, diagnostics, and output recovery.
The `raw` command preserves access to advanced existing operations.
Catalog-declared writes need `--write` and a user-requested source change.

Exit 0 means an admitted request preview, schema read, or complete RPC envelope.
Exit 1 preserves an incomplete or rejected RPC reply.
Exit 2 reports a local input, catalog, process, or reply failure.
A complete envelope alone does not establish the answer's scope or completeness.

## Pi sessions

On October 28, 2026, Pi 1.4.0 ran separate sessions with `gpt-6.1-terra` and `gpt-6-luna` through `openai-codex`.
Each session used high thinking, with retries and compaction disabled.
Terra used a manual model entry in an isolated Pi configuration.
Session records contain the selected model and thinking level.

The baseline asked each model to author requests from the installed COMMON schema.
The candidate supplied the skill and allowed actual CLI calls with `--emit-request`.
The eight questions covered discovery, overloads, occurrences, trace budgets, result recovery, cursor identity, source windows, and three-hop callers.

| Case | Baseline behavior | Candidate behavior |
|---|---|---|
| Cursor on a call | Both used AT_LOCATION as the callee | Both separated declaration discovery from callers |
| Nested declarations | Luna excluded descendants in `protocol` | Both used recursive discovery |
| Three-hop callers | Terra chained three expansions | Both used WALK with traversal records |
| Overloads | Both expanded all name matches together | Both discovered declarations before relationships |
| Output budget | Both recognized the 4096-byte minimum | Both preserved that limit |
| Retained result | Both kept the output kind and both cursors | Both preserved those fields |

The overload prompt did not identify one signature. Its result shows different selection behavior, not a proven wrong answer.
The candidate changed the illustrative reference into a syntactically valid placeholder.
It also used COMPLETE for advanced traversal. These sessions do not establish a controlled comparison or an improvement rate.
No illustrative reference reached semantic execution.

Luna still inferred the callee's directory from the call site in its first candidate response.
The skill now distinguishes those scopes.
Two fresh sessions then used workspace discovery and disclosed the default `main` and `test` source sets.
Both omitted the known function kind in that focused exercise.
The skill now includes `find NAME --kind function`. The cursor case has no separate trial after that sentence.

Both models then used the live candidate for discovery, source, and implementations of `QueryCompletionDocument`.
Both selected `CLASS_BODY`, which rejected with `region-absent` because the declaration has only constructor properties.
Terra corrected its selector to `ANCHOR`. Luna selected another absent region and then used a file read.
Luna also presented UTF-16 offsets as a line range.

The revised skill starts source reads with `ANCHOR` and distinguishes offsets from line numbers.
Both fresh live sessions then completed the same task in three semantic calls without a rejected source region.
Both identified the declaration as a data class and kept the scope of the empty implementation result.
Luna omitted the false line citation. These few sessions establish behavior for this exercise only.

The ignored `.evidence/` directory keeps local prompts, responses, session records, and catalog snapshots.
The temporary launch configuration resides at `/private/tmp/kast-task-access/pi-agent`.
No credential contents appear in the experiment artifacts.

## Evidence limits

The live candidate preserved `daemon-operation-lifecycle-unlinked-build` from the installed runtime.
After IDEA opened this checkout, the exposed MCP query returned `daemon-operation-lifecycle-deadline-exceeded`.
After the Gradle import, the candidate returned a complete declaration result with zero reported failures and omissions.
The revised Pi sessions also returned successful source and relationship results through the unchanged installed runtime.
Live retained-result recovery remains unproved.

Reference syntax checks do not prove issued authority. Kast remains the authority for identity, freshness, and completion.
The candidate keeps original responses. It does not yet offer a compact presentation or an automatic evidence store.
Skill discovery in a host and production deployment remain outside this experiment.

## Local checks

```sh
uv run --locked --script experiments/task-access/setup.py
```

The setup procedure examines locks, Ruff format, Ruff lint, strict Pyright, pytest, and the standalone command entry.
The 20 focused tests, Ruff format, Ruff lint, and strict Pyright passed after the final CLI change.
They cover independent request shapes, invalid arguments, schema admission, explicit writes, and an actual child process.
The child fixture proves argument binding and unchanged rejection bytes. It does not prove IDEA behavior.
`mise exec -- ./gradlew verifyJsonContracts` passed with zero violations.
The full product gate and hard model evaluations were not run.
The pinned prose assessment is `ASSESSED` with readability advisories. It does not establish reader comprehension.
