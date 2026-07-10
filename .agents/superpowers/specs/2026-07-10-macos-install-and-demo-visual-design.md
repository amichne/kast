# macOS Install Completion And Demo Visual Design

Status: Approved for implementation

Date: 2026-07-10

## Goal

Make the released macOS developer install contract complete in the real user
environment, then sharpen `kast demo` into a modern, high-signal showcase of
Kast's compiler and source-index evidence without changing the demo workflow,
read-only guarantee, or machine-readable output.

## Selected Approach

Use a structural install correction and a restrained semantic visual system.
This is preferable to a minimal symptom patch, which would leave Rust and
Kotlin receipt semantics liable to drift, and to a full TUI redesign, which
would add interaction and recording risk without improving the story.

The visual direction follows the `hyperb1iss/hyperskills@tui-design` skill
selected through the Vercel Skills CLI. It uses semantic color, spatial
consistency, contextual controls, and graceful monochrome degradation. Kast's
AXI contract continues to own stdout, structured output, errors, and agent
ergonomics.

## Install Authority

### Homebrew execution boundary

The formula installs the CLI only. It must not run user-profile convergence in
`post_install`, because Homebrew supplies a temporary `HOME` there and Kast
cannot discover or safely mutate the real user's JetBrains profiles.

The root `install.sh` entrypoint remains the public developer-machine
orchestrator. After `brew install`, `brew upgrade`, or a required reinstall, it
resolves `${formula_prefix}/bin/kast` and runs `kast developer machine plugin`
once in the real user environment. Direct formula users receive Homebrew
caveats with that explicit follow-up command rather than a failing hidden
mutation.

### Receipt identity

The receipt writer records the stable formula-owned executable
`${formula_prefix}/bin/kast`, not the spelling used to invoke the current
process. Before writing, Rust canonicalizes both the running executable and
formula executable and requires them to resolve to the same file beneath the
canonical formula prefix.

Rust and Kotlin readers both enforce the same sequence:

1. Require absolute paths and complete receipt fields.
2. Require the formula prefix and executable to exist.
3. Canonicalize the executable and formula prefix.
4. Require canonical containment beneath the formula prefix.
5. Require CLI, plugin, and running versions to agree.

No reader performs a contradictory lexical-prefix check before canonical
validation. Symlink spellings such as `/opt/homebrew/bin/kast` remain safe when
they resolve inside the formula and are rejected when they escape it.

## Demo Visual Language

Introduce one small semantic theme boundary for the public demo renderer. The
theme names intent rather than colors: default, muted, emphasis, focus,
compiler, index, success, warning, danger, selection, and read-only.

The default dark-terminal presentation uses a restrained cyan/blue primary
accent, violet for source-index evidence, green for verified/clean evidence,
and amber for hypothetical plan-only actions. Color is paired with text,
symbols, weight, and position; removing color leaves the interface usable.
`NO_COLOR` and non-color terminals receive a monochrome hierarchy.

The existing layout and keys remain stable. The focused refinement is:

- a compact branded header with distinct evidence-status and read-only badges;
- rounded, muted inactive borders and a clear accented active surface;
- ranked story rows with a selection rail, strong title, and quieter metadata;
- a chapter rail that distinguishes current, available, and unavailable steps;
- evidence labels that visually separate compiler facts, index facts, safety,
  and reusable commands;
- contextual footer keycaps with muted descriptions;
- a plan-preview state that reads as safe, hypothetical, and non-mutating.

The first screen still appears immediately, compiler loading remains async,
and no animation delays input. The fixed 120x40 recording remains legible, and
80x24 remains the minimum supported layout.

## Compatibility And Output

The change is limited to TTY human rendering. JSON and TOON schemas, exit
codes, command names, keybindings, evidence selection, and read-only behavior
do not change. Piped output receives no new ANSI sequences. Terminal state is
restored on normal exit and interruption as before.

## Validation

Install validation must include:

- a formula contract proving no user-profile mutation occurs in
  `post_install` and caveats name the explicit convergence command;
- root-installer tests proving plugin convergence runs once after Homebrew;
- Rust receipt tests for formula-path recording, canonical symlink acceptance,
  and symlink escape rejection;
- Kotlin receipt tests proving the same canonical cases;
- focused Rust install smoke tests and `:backend-idea:test`;
- a real disposable macOS install/update run with JetBrains closed.

Demo validation must include:

- focused renderer snapshots at 80x24 and 120x40;
- color and `NO_COLOR` assertions at the semantic theme boundary;
- the existing real-PTY read-only and source-immutability test;
- full JSON evidence preflight against the real IDEA backend;
- a new Asciinema v2 cast and GIF recorded from the released/source-verified
  stack, with required evidence terms and unchanged Kotlin hashes;
- local and GitHub README rendering checks.

The work lands as narrow commits for install authority, receipt parity, demo
visual language, and refreshed recording. PR #328 is updated and merged only
after exact-head CI is terminal green.
