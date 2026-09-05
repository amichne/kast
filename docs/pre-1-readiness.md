# Pre-1.0 requirement implementation record

Assessment authority: the supplied **Kast 1.0 audit**, which reviewed
`9e4c246c4bae0be9b78fbbe967ceb65c1c6a920c`. Implementation starts from
`8edd1a43012f82a75e27bba6f5cd10b0f7736a06` and preserves its module architecture.
This record separates implemented checks from observed installed acceptance.

| Requirement | Owner and proof boundary | Current evidence |
| --- | --- | --- |
| 1. Independent semantic CLI | CLI composition and installed journey without Codex | Runtime stream; focused CLI tests passing, integration pending |
| 2. Publish after exact-asset proof | `releaseSourceGate`, release receipt, publication admission | Missing-proof publication test observed failing then passing; exact installed gate pending integration |
| 3. Explicit Gradle import environment/JVM | Distribution admission, cache identity, IntelliJ import | Import stream in progress |
| 4. Startup phase and actionable failure | Bootstrap schema, phase publisher, lifecycle/status | Lifecycle stream; focused contract proof passing, integration pending |
| 5. 1.x compatibility and migration | Public policy, release compatibility diff, installer journeys | Follow-on stream pending |
| 6. Cold broker budget/cancellation | Canonical execution budgets, broker and process ownership | Runtime stream; CLI and registry tests passing; installed cold harness in progress |
| 7. Derived Codex presentation/replay | Observer projection, canonical evidence, history replay | Existing rich rendering validated; reload reconstruction gap being implemented |

## Refined findings

- The source has advanced since the assessment. PR #667 supplies rich observer
  rendering; it needs replay validation and reconstruction rather than replacement.
- The current production startup timeout is 17 minutes. The installed acceptance
  threshold is a stricter 240 seconds. Operation metadata must preserve the actual
  timeout authority while the installed gate independently enforces performance.
- Uninstall previously scanned machine-wide indexer processes even under an
  isolated `HOME`. `--installation-only` now confines removal to selected paths,
  requires selected workspaces to be stopped, and preserves unrelated processes.
- The release installer now admits exact local archive/checksum files with
  `--assets-directory`, retaining normal release URL and manifest identity checks.

## Semantic tooling observations

Native Kast `symbol discover` succeeded on the original checkout. This session
exposes no callable Kast or IntelliJ-index MCP tools, so native Kast is the
available semantic authority. A failed `status --root` attempt was a CLI usage
error: Kast selects the repository from its working directory. Worktree-specific
failures and fallback evidence belong below as they are observed.

## Release decision

No 1.0 release is authorized or produced by this work. The decision remains
unproven until all required journeys pass at one exact combined head and the
stacked PR checks are observed passing. Source, module tests, staged-product
tests, installed acceptance, and remote checks are distinct evidence levels.
