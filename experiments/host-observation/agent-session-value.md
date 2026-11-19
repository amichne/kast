# Kast in a Pi session

Pi identified the active Kotlin declaration in four of four sessions with Kast. With the same source files alone, Pi answered `UNKNOWN` four times.

## Which file does Gradle import?

Two workspaces had identical tracked files. An external selection file chose which adapter Gradle imported. Both adapters declared `formatInvoiceReference(Long)` in the same package.

The fixture owner read the selection file to establish the expected path. An independent preflight queried each Host. Both replies contained one declaration, exhaustive coverage, zero failures, and zero omissions.

| Information that Pi received | Correct paths | Other outcomes |
| --- | ---: | --- |
| Source snapshot | 0/4 | Four abstentions |
| Same snapshot and a Kast query tool | 4/4 | Each trace contained a complete query with the expected path |
| Same snapshot and the selection file | 4/4 | All four answers used the supplied model selection |

Each adapter had two fresh sessions per condition. The Kast condition explicitly directed Pi to a tool for the imported declaration. These trials establish the value of imported model information for this question. Automatic skill discovery needs a separate host trial.

## How much discovery does a command draft need?

The revised skill names both call directions and gives the command for the next retained page. Each prompt asks for a draft. The installed planner admitted the relation drafts. The page assertion examined the result reference, cursor, and output kind.

| Case | Prior skill calls | Revised skill calls | Prior median tokens | Revised median tokens |
| --- | ---: | ---: | ---: | ---: |
| `callees` | 2 | 1 | 3,831 | 1,497 |
| `incoming-callers` | 1 | 1 | 1,424 | 1,495 |
| `retained` | 4–5 | 1 | 10,314 | 1,503 |
| `retained-occurrences` | 4–5 | 1 | 12,256 | 1,502 |

Counts include tool calls to read the skill. Tokens include input, output, and cache tokens. Each cell uses completed sessions from three scheduled repetitions.
One prior `retained` session ended with `Pi did not finish normally`. Its two completed sessions used 12,261 and 8,367 tokens.
A separate matched repetition passed with both skills. It used 10,223 tokens with the prior skill and 1,503 with the revision.
Every completed draft passed. The incoming caller case already needed one call and used 71 more median tokens with the revision.

The two relation cases change the direction. The two page cases change the output kind. Offline controls exercised the live assertions for the four drafts and both adapter worlds.
Each assertion accepted its expected answer and rejected the wrong, omitted, and extra answers. These controls measure the grader.
The live comparisons used Pi 1.5.2, `promptfoo@0.125.1`, and `openai-codex/gpt-6.1-sol` with low reasoning effort and the evaluation cache off.

The [trial records](agent-session-results.tsv) give each run ID, case, answer, tool count, and token count.

## Reproduce the comparison

Use the [pinned evaluation suite](https://github.com/amichne/kast/tree/08c2e46b7a699a0cbb77dd16b30c3d45691a02ef/experiments/host-observation/promptfoo) in a disposable checkout.
Set `KAST_EVAL_HARNESS` to its absolute directory. Install the packages from `package-lock.json` with `npm ci`.
Set `KAST_EVAL_KAST` to an absolute Kast executable. The recorded macOS arm64 installation used source `bc00d8df5257312070f9b75a38ea1c3aba48f5c1` and version `0.0.464034`.

For the model comparison, use the suite's [fixture procedure](https://github.com/amichne/kast/blob/08c2e46b7a699a0cbb77dd16b30c3d45691a02ef/experiments/host-observation/promptfoo/imported-model.md) with `--repeat 2`.
For the command comparison, select each skill in turn. Set `KAST_EVAL_SKILL` to the directory with the selected `SKILL.md` and `public-cli.md`.
Copy both files into the disposable checkout's `agent-tools/skills/kast` directory for the canonical cases.
Set `PAIR_CONFIG` to the absolute path of [the paired cases](skill-command-pairs.config.mjs).

```sh
cd "$KAST_EVAL_HARNESS"
KAST_EVAL_CONTROLS=1 npm run eval -- -c "$PAIR_CONFIG" --no-write
npm run eval -- -c skill.config.mjs --filter-pattern '(callees|retained) / canonical' --repeat 3 --no-write
npm run eval -- -c "$PAIR_CONFIG" --repeat 3 --no-write
```

The offline pair run expects two passes and six assertion failures. A provider error invalidates that comparison.
The prior skill came from the Kast source revision listed here. The revised skill is the version in this change.

| Supplied text | SHA-256 |
| --- | --- |
| Prior skill | `83fc03e0efd2eeb32da1301288239e1ff274ef208e710c0f0411c9f7a4fbb983` |
| Revised skill | `1aa9c19f467388188a8c4821ff412fa29ee48594ef3b59501e4199f39670a2ee` |
| CLI guide for both skills | `33c35e5fdf325dda79e0a937b3c1f2b0bfe26b9760bd32346f8b81b5ca191cd7` |
