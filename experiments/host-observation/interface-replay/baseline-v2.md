# Clarified baseline

The TOON arm completed all twelve decisions correctly under the clarified v2 contract.
It supplies the fixed quality baseline for later candidates.
The JSON arm completed eleven decisions correctly. Neither arm excludes a trial.

| Measure | TOON | JSON |
| --- | --- | --- |
| Correct decisions | 12 of 12 | 11 of 12 |
| Total provider tokens | 11,669 | 11,107 |
| Median trial duration | 7.93 seconds | 6.59 seconds |
| Actual provider sends | 12 | 12 |
| Native semantic calls | 0 | 0 |

The source commit is `e6b08bd8b8a840c74602104a54e6f713fa854557`.
The [manifest](baseline-v2-manifest.json) records the exact plan and adapter identities.
The [comparison](baseline-v2-comparison.json) preserves usage, latency, misuse, and every pair.
Private receipts and complete payloads remain under `build/interface-codex-baseline-v2.json.run/`.
Both inputs survived SDK projection before either arm sent. Each actual payload contained its intended result exactly once.

C4-r1-JSON appended the literal argument `timeout` after a valid retained-page command.
Production admission rejected the unchanged command as `INVALID_COMMAND`.
The smallest reproduction is:

```sh
kast result read --ref result:v1:00000000-0000-0000-0000-000000000004 --cursor 1 timeout
```

All C5 decisions copied the issued limitation codes under the explicit contract.
The v1 decisions and original verdicts remain unchanged.
The two repetitions cover six tasks. They do not establish general model accuracy or a format winner.
Provider tokens measure work. Billed cost remains unavailable.
