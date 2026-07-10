# README Demo Recording Design

Status: Approved for specification

Date: 2026-07-10

## Goal

Give a developer scanning the README an immediate, trustworthy view of
`kast demo` running against a real Kotlin repository. The recording must show
the interactive terminal experience, remain viewable directly on GitHub, and
retain enough source material to audit or rerender it later.

## Considered Approaches

1. Embed a self-contained GIF and retain the source Asciinema cast. This is the
   selected approach because GitHub renders it inline, the cast preserves the
   terminal event stream, and neither artifact depends on an external service.
2. Link to an asciinema.org recording. This keeps the repository smaller but
   depends on account state and link retention.
3. Add a static screenshot. This is durable and small but does not demonstrate
   navigation or evidence changing between chapters.

## Recording Contract

The capture uses a 120-column by 40-row terminal with an `xterm-256color`
environment. It runs the released Homebrew `kast` 0.12.4 binary against the
real user checkout prepared by the matching 0.12.4 IntelliJ plugin and an
already reachable, version-compatible backend. It must not substitute a fake
backend or fixture responses. If full compiler and source-index evidence is
unavailable, recording stops instead of publishing a degraded or synthetic
session.

The session lasts approximately 10 to 15 seconds and demonstrates one coherent
flow:

1. Open the ranked repository stories.
2. Select a repository-owned declaration.
3. Move through identity, relationships, impact, and safety evidence.
4. Enter a hypothetical rename and show the plan-only preview.
5. Exit without applying or changing source files.

The recording must avoid shell prompts, absolute user-specific paths where the
TUI can compact them, credentials, configuration values, and unrelated desktop
content.

## Artifacts And README Placement

Commit these files:

- `docs/assets/demo/kast-demo.cast` — the Asciinema v2 source recording.
- `docs/assets/demo/kast-demo.gif` — the inline GitHub rendering.

Embed the GIF immediately after the opening paragraph of the README's
`Try it on your code` section. The alt text describes a read-only Kast semantic
story moving through evidence chapters. The prose continues to explain the
command and link to the full repository-demo guide.

The GIF should remain below 8 MiB. Rendering may reduce frame rate or color
depth, but must keep terminal text legible at the README's normal content
width.

## Validation

Before committing the recording:

1. Convert the cast to text and verify stable visible terms including
   `Kast Semantic Story`, a repository-owned symbol, chapter labels, and
   `read-only`.
2. Inspect the GIF dimensions, frame count, duration, and file size.
3. Open the README locally and confirm the relative asset path renders.
4. Compare relevant source-file hashes before and after the session.
5. Run the README/docs content and navigation contracts, a clean Zensical
   build, and `git diff --check`.

After pushing the recording commits, open a follow-up pull request and babysit
it until every required check returns to a terminal passing, skipped, or
neutral state.

## Failure Handling

Do not commit a cast that shows missing evidence, clipped labels, terminal
setup noise, or a source mutation. Re-record at the same dimensions after
fixing the prerequisite or timing issue. Do not upload the cast to an external
recording service unless separately requested.
