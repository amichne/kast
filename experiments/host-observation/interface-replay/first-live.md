# First Codex-backed screen

The first live screen completed 24 requests with complete usage records and no excluded trials.
The 3× target is `NOT_MET`. Both formats fail the current exact-code grader for C5.
The original task, inputs, raw decisions, and verdicts remain intact.

| Measure | TOON | JSON |
| --- | --- | --- |
| Correct under the v1 grader | 9 of 12 | 10 of 12 |
| Total provider tokens | 11,500 | 10,874 |
| Tokens per correct decision | 1,277.78 | 1,087.40 |
| Median trial duration | 8.14 seconds | 6.63 seconds |
| Actual provider sends | 12 | 12 |
| Native semantic calls | 0 | 0 |

The paired correctness result has one JSON win and eleven ties across twelve pairs.
No pair is missing. The two repetitions cover six distinct tasks.
The target assessment reports a 1.18× token ratio and a 1.22× paired latency ratio for JSON against TOON.
The paired latency ratio uses the median of the six per-case median ratios.
These screen results supply no stable general-format preference or native-performance claim.

## Execution identity

The model was `openai-codex/gpt-6.1-sol` with high effort through Pi 1.4.0.
The runner used Codex's existing ChatGPT access token in memory.
It used the same SDK provider route, session owner, request inspection, and shell executor.
It did not launch a separate Codex agent loop or change either credential file.
The access token was locally unexpired. The completed inference established access for these requests.

The implementation source is `e76c008f45cca6193a474aac08684b1f5206a7b3`.
Each staged source file matched its recorded run hash before that commit.
The [manifest](first-live-manifest.json) preserves the original base, working-tree digest, artifact digest, model, limits, and plan identity.
The first prepared Codex plan failed its artifact guard before worker startup and sent zero requests.
The second plan used refreshed artifact identities and completed the declared screen.

The [comparison](first-live-comparison.json) contains the complete accounting ledger.
The [target assessment](first-live-target.json) reports the failed performance and functionality gates.
Private commands, argv, stdin, stdout, stderr, provider payloads, and model responses remain under `build/interface-codex-screen-002.json.run/`.
No credentials or authorization headers appear in those records.

## Findings

C1 through C4 passed in both formats and both repetitions.
One C6 TOON command added unrelated trailing arguments. Production admission rejected it as `INVALID_COMMAND`.
The runner preserved that exact command and did not repair it.

All four C5 responses chose `insufficient-evidence`, kept the requested scope, and set `exhaustive` to false.
They described the supplied limitations in prose. The grader expected the exact issued limitation-code array.
The visible response contract did not state that exact-code requirement.
This is an underspecified response contract, not evidence of a false absence claim.
The original `WRONG_COVERAGE` verdicts remain recorded. No retrospective grade change creates an improvement.

The v2 contract tells the model to copy only issued limitation codes verbatim.
It exposes no expected answer beyond the evidence already present in the tool result.
The original tasks, canonical inputs, references, and strict grader expectations stay unchanged.
A separate regression removes a spurious argument-rejection label from finish decisions that never enter the CLI.
A new bounded baseline will use v2 for both formats and every case.
The v1 and v2 runs cannot establish a format-only performance improvement against each other.

## Next iteration

The user-selected goal needs 3× fewer provider tokens per correct decision and 3× lower paired latency, with no per-case accuracy loss.
Each of the six cases needs a successful candidate decision.
The next baseline freezes the clarified contract before performance candidates change one declared factor at a time.
All failed work stays in the accounting ledger. No failed case receives a hidden retry.
Provider tokens measure work. Billed cost remains unavailable.
