# Tasks mined from agent sessions

Real Kast development sessions supply engineering tasks with known answers.
They do not supply a control group. Most of them already call Kast, they start from unrecorded commits, and human steering changes their course.
This procedure turns a session into a fixed task: a pinned base commit, an ordinary request, and a mechanical check.

`session_tasks.py` owns three steps:

| Command | Effect |
|---|---|
| `snapshot` | Copies each new transcript version into a content-addressed archive. It never overwrites a version. |
| `summarize` | Writes one record per session from the latest archived version. |
| `validate` | Checks task drafts against Git and the blinding vocabulary. |

## Archive transcripts

Transcripts are private local records. Keep the archive and summaries outside this repository.
Claude Code can remove old transcripts, so take a snapshot before a session becomes the source of a task.

```sh
cd experiments/host-observation
uv run --locked --project . python session_tasks.py snapshot --archive ~/.local/state/kast-session-archive
```

The command reads project directories under `~/.claude/projects` whose names contain `kast`.
Set `--projects-root` or `--match` to select others. Subagent transcripts are archived with their parent.
A live session produces a new version at each snapshot. `manifest.jsonl` keeps every version with its SHA-256 digest and capture time.

## Summarize sessions

```sh
uv run --locked --project . python session_tasks.py summarize \
  --archive ~/.local/state/kast-session-archive --repository ../.. --output /private/tmp/kast-sessions.json
```

Each record keeps the model, effort, token counts, reported cost, tool counts, human prompts, interruptions, compactions, delegated agent transcripts, and pull request links.
`--repository` adds `inferred_base_commit`: the `origin/main` commit at the session's first timestamp. The session can have started from another branch. Confirm the base before you use it.
A session recorded in two project directories lists the other in `also_recorded_in`.

`kast.exposure` classifies the session's own Kast use:

| Exposure | Meaning |
|---|---|
| `NONE` | No Kast command, MCP tool, or skill load. Only these sessions are `control_eligible`. |
| `OPERATIONAL_ONLY` | Version, status, stop, upgrade, or installation commands only. |
| `SEMANTIC` | At least one `query`, `source`, `result`, or `diagnostic` command, or a `describe` or help read. |

`semantic_outcomes` counts tool calls, not Kast commands. One shell call can run several commands.
The outcome comes from the text the agent received, after any `jq` or `head` in the same pipeline. `UNCLASSIFIED` therefore includes complete results that a pipeline reshaped.
These counts grant no semantic authority.

## Draft a task

Each file in `session-tasks/` is one `TaskDraft`. Its `id` matches the file name.
A draft carries no transcript text. It names the session ID, the observation time, and public evidence such as an issue or a commit.

| Kind | Check | Admission |
|---|---|---|
| `answer` | `exact_paths`: the exact set of repository paths | `git grep -l -E` at the base commit must reproduce the expected set |
| `edit` | `reference_commit`: the change that landed | The reference must descend from the base and change only allowed or hidden paths. Each `absent_symbols` entry must exist at the base and not at the reference |

Write the prompt as an ordinary engineering request. The `validate` command rejects a prompt that contains the measurement vocabulary owned by `promptfoo/participant-surface.mjs`.
Set `kast_relevance` to `NOT_EXPECTED` for a task that Kast cannot help, such as a Python change. These tasks measure Kast's overhead.
Record each unverified assumption in `open_questions`.

```sh
uv run --locked --project . python session_tasks.py validate --repository ../.. session-tasks/*.json
```

The command exits with 1 and prints one finding per line when a draft fails.

## Current drafts

| Draft | Kind | Source | What the task exercises |
|---|---|---|---|
| `thread-binding-document-uses` | answer | structure audit, [#1323](https://github.com/amichne/kast/issues/1323) | Constructor uses in test sources that Kast 0.0.4551 omitted from a complete result |
| `invocation-fence-migration-rejected-uses` | answer | structure audit, [#1323](https://github.com/amichne/kast/issues/1323) | An enum entry name that two enums share |
| `managed-host-bridge-production-callers` | answer | structure audit | A direct call that a file-scoped Kast query omitted from a complete result |
| `retire-kast-service-install` | edit | structure audit, `b9ac813e66` | Removal of a CLI entry point and its references across modules |
| `activation-retires-live-coordinator` | edit | installer session, `9931547a65` | A Python installer change with hidden tests from the reference commit |

Every draft has the status `DRAFT`. No agent has run these tasks.
No edit command has been run against its reference commit. Run each once before admission.

## Next step

A Promptfoo provider can consume these drafts in the way that `promptfoo/developer-cases.mjs` consumes the developer tasks.
For each run, it checks out the base commit in a participant directory and sends the prompt.
For an edit, it overlays the hidden paths and runs the commands. For an answer, it parses a JSON array and compares path sets.
