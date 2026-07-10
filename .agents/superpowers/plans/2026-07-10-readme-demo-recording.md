# README Demo Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a trustworthy inline GIF of the real `kast demo` experience to the README while retaining the auditable Asciinema v2 source recording.

**Architecture:** Use the independently verified Homebrew 0.12.4 release and matching IntelliJ plugin against the real user checkout, prove that the live compiler backend and source index both return full evidence, then record the TUI in a fixed terminal without a shell prompt. Validate the cast before rendering it with `agg`, embed the GIF through a relative README path, and verify both the local artifacts and GitHub's actual README rendering before babysitting the follow-up pull request to green.

**Tech Stack:** Rust/Cargo, Kast IDEA backend and SQLite source index, Asciinema 3.2.0 asciicast v2, agg 1.9.0, ImageMagick, GitHub-flavored Markdown, Playwright, Zensical, GitHub Actions.

## Global Constraints

- The capture uses a 120-column by 40-row terminal with an `xterm-256color` environment.
- Run the released Homebrew `kast` 0.12.4 binary against the real user checkout prepared by the matching IntelliJ plugin and an already reachable, version-compatible backend.
- Do not substitute a fake backend or fixture responses.
- Stop without committing recording assets if full compiler and source-index evidence is unavailable.
- Demonstrate ranked repository stories, a repository-owned declaration, identity, relationships, impact, safety, and a hypothetical plan-only rename.
- Exit without applying or changing source files.
- Do not capture shell prompts, credentials, configuration values, unrelated desktop content, or uncompacted absolute user-specific paths.
- Commit `docs/assets/demo/kast-demo.cast` as Asciinema v2 and `docs/assets/demo/kast-demo.gif` as the inline rendering.
- Keep the GIF below 8 MiB and its terminal text legible at normal README width.
- Do not upload the cast to an external recording service.
- Use `apply_patch` for the README edit and preserve unrelated worktree changes.

---

### Task 1: Prove The Full-Evidence Recording Prerequisites

**Files:**
- Read: `/Users/amichne/code/kast/.kast/setup/workspace.json`
- Generate outside Git: `/tmp/kast-demo-evidence/preflight.json`
- Generate outside Git: `/tmp/kast-demo-evidence/kotlin-before.sha256`

**Interfaces:**
- Consumes: approved design at `.agents/superpowers/specs/2026-07-10-readme-demo-recording-design.md`, plugin-prepared workspace metadata, live IDEA backend, source-index database
- Produces: `DEMO_REPO`, `SOURCE_ROOT`, `KAST_BIN`, `FIRST_SYMBOL`, and a full-evidence JSON proof used by the recording task

- [ ] **Step 1: Define the two workspace roles and evidence directory**

Run from `/tmp/kast-pr-327-sync`:

```bash
export SOURCE_ROOT=/tmp/kast-pr-327-sync
export DEMO_REPO=/Users/amichne/code/kast
export EVIDENCE_DIR=/tmp/kast-demo-evidence
export KAST_BIN=/opt/homebrew/bin/kast
mkdir -p "$EVIDENCE_DIR"
test -f "$DEMO_REPO/.kast/setup/workspace.json"
```

Expected: the prepared workspace metadata exists. Task 1 Step 3 verifies the
source index through the public `kast demo` response because Git worktrees may
store the physical database outside the repository root.

- [ ] **Step 2: Prove the released CLI and plugin share the 0.12.4 contract**

Run:

```bash
test -x "$KAST_BIN"
test "$($KAST_BIN --version | awk '{print $2}')" = "0.12.4"
test "$(jq -er '.cliVersion' "$DEMO_REPO/.kast/setup/workspace.json")" = "0.12.4"
test "$(jq -er '.pluginVersion' "$DEMO_REPO/.kast/setup/workspace.json")" = "0.12.4"
test "$(brew list --versions kast | awk '{print $2}')" = "0.12.4"
test "$(jq -er '.cli.version' "$HOME/Library/Application Support/Kast/homebrew-install.json")" = "0.12.4"
test "$(jq -er '.plugin.version' "$HOME/Library/Application Support/Kast/homebrew-install.json")" = "0.12.4"
```

Expected: the executable, Homebrew formula, install receipt, workspace CLI,
and workspace plugin all report the independently verified 0.12.4 release.

- [ ] **Step 3: Fail closed unless the real backend and index return complete evidence**

Run:

```bash
"$KAST_BIN" --output json demo --workspace-root "$DEMO_REPO" > "$EVIDENCE_DIR/preflight.json"
jq -e '
  .type == "KAST_DEMO" and
  .ok == true and
  .availability == "full" and
  .mutates == false and
  .backend.referenceIndexReady == true and
  (.candidates | length) > 0 and
  .candidates[0].file != null and
  .candidates[0].module != null and
  .selectedStory.compilerIdentity != null and
  .selectedStory.compilerReferenceCount != null and
  .selectedStory.diagnostics != null and
  ([.chapters[] | select(
    (.chapter == "identity" or
     .chapter == "relationships" or
     .chapter == "impact" or
     .chapter == "safety") and .available == true
  )] | length) == 4
' "$EVIDENCE_DIR/preflight.json"
export FIRST_SYMBOL="$(jq -er '.candidates[0].fqName' "$EVIDENCE_DIR/preflight.json")"
```

Expected: `jq` prints `true`, and `FIRST_SYMBOL` is a fully qualified declaration owned by `DEMO_REPO`. Any `indexOnly`, `backendOnly`, missing compiler field, or unavailable chapter stops the implementation without creating a cast.

- [ ] **Step 4: Record the immutable Kotlin baseline**

Run:

```bash
(
  cd "$DEMO_REPO"
  git ls-files -z -- '*.kt' '*.kts' \
    | xargs -0 shasum -a 256 \
    | LC_ALL=C sort
) > "$EVIDENCE_DIR/kotlin-before.sha256"
test -s "$EVIDENCE_DIR/kotlin-before.sha256"
```

Expected: the checksum file is non-empty and contains every tracked Kotlin source or script in the demo repository.

---

### Task 2: Capture And Audit The Real TUI Session

**Files:**
- Create: `docs/assets/demo/kast-demo.cast`
- Generate outside Git: `/tmp/kast-demo-evidence/kast-demo.raw`
- Generate outside Git: `/tmp/kast-demo-evidence/kotlin-after.sha256`

**Interfaces:**
- Consumes: `KAST_BIN`, `DEMO_REPO`, `FIRST_SYMBOL`, and `kotlin-before.sha256` from Task 1
- Produces: an auditable 120x40 Asciinema v2 recording with full compiler and source-index evidence

- [ ] **Step 1: Start a prompt-free Asciinema v2 capture in a real PTY**

Run the following with a PTY from `DEMO_REPO`:

```bash
mkdir -p "$SOURCE_ROOT/docs/assets/demo"
PATH="$(dirname "$KAST_BIN"):$PATH" \
TERM=xterm-256color \
asciinema record \
  --quiet \
  --overwrite \
  --output-format asciicast-v2 \
  --window-size 120x40 \
  --idle-time-limit 1 \
  --capture-env TERM,SHELL \
  --command "kast demo" \
  "$SOURCE_ROOT/docs/assets/demo/kast-demo.cast"
```

Expected: the first visible frame says `Kast Semantic Story`, `compiler + index evidence ready`, `Choose a story from your codebase`, and `read-only`. The cast header records `command` as `kast demo`, not an absolute binary or workspace path.

- [ ] **Step 2: Drive one coherent 10-to-15-second story**

Send these keys to the recording PTY with roughly one second between visible states:

```text
Enter
Right
Right
Right
Right
r
KastStoryPreview
Enter
q
```

Expected visible checkpoints:

```text
Identity
Relationships
Impact
Safety
Hypothetical Kotlin name:
Plan only — apply is unavailable in the demo
New name: KastStoryPreview
read-only
```

Wait for `Loading compiler evidence…` to be replaced by compiler identity before navigating away from Identity. The two initial `Right` keys move through `Why semantics` and land on `Relationships`; do not send them as one burst.

- [ ] **Step 3: Inspect the alternate-screen event stream and validate its public content**

Run:

```bash
asciinema convert \
  --overwrite \
  --output-format raw \
  "$SOURCE_ROOT/docs/assets/demo/kast-demo.cast" \
  "$EVIDENCE_DIR/kast-demo.raw"
rg -aF "Kast Semantic Story" "$EVIDENCE_DIR/kast-demo.raw"
rg -aF "compiler + index evidence ready" "$EVIDENCE_DIR/kast-demo.raw"
rg -aF "$FIRST_SYMBOL" "$EVIDENCE_DIR/kast-demo.raw"
rg -aF "Relationships" "$EVIDENCE_DIR/kast-demo.raw"
rg -aF "Impact" "$EVIDENCE_DIR/kast-demo.raw"
rg -aF "Safety" "$EVIDENCE_DIR/kast-demo.raw"
rg -aF "Plan only — apply is unavailable in the demo" "$EVIDENCE_DIR/kast-demo.raw"
rg -aF "KastStoryPreview" "$EVIDENCE_DIR/kast-demo.raw"
rg -aF "read-only" "$EVIDENCE_DIR/kast-demo.raw"
! rg -aF -e "compiler backend unavailable" -e "Compiler evidence unavailable" -e "source index unavailable" -e "No ready compiler backend" "$EVIDENCE_DIR/kast-demo.raw"
```

Expected: every required term is found and no degraded-evidence message is
present. The TUI uses the terminal's alternate screen, so plain-text conversion
intentionally yields no final scrollback; raw conversion preserves the
auditable event stream rendered into the GIF.

- [ ] **Step 4: Validate recording metadata, timing, and source immutability**

Run:

```bash
head -n 1 "$SOURCE_ROOT/docs/assets/demo/kast-demo.cast" \
  | jq -e '.version == 2 and .width == 120 and .height == 40 and .env.TERM == "xterm-256color" and .command == "kast demo"'
tail -n 1 "$SOURCE_ROOT/docs/assets/demo/kast-demo.cast" \
  | jq -e '.[0] >= 8 and .[0] <= 18'
(
  cd "$DEMO_REPO"
  git ls-files -z -- '*.kt' '*.kts' \
    | xargs -0 shasum -a 256 \
    | LC_ALL=C sort
) > "$EVIDENCE_DIR/kotlin-after.sha256"
diff -u "$EVIDENCE_DIR/kotlin-before.sha256" "$EVIDENCE_DIR/kotlin-after.sha256"
```

Expected: metadata validation prints `true`, recorded duration is 8–18 seconds, and the source checksum diff is empty. Re-record if text is clipped, evidence is degraded, timing falls outside the bound, or any source hash changes.

- [ ] **Step 5: Commit the audited source recording as its own slice**

Run:

```bash
git add docs/assets/demo/kast-demo.cast
git diff --cached --check
git commit -m "docs: capture repository demo session"
```

Expected: one commit containing only `kast-demo.cast`.

---

### Task 3: Render The GIF And Embed It In The README

**Files:**
- Create: `docs/assets/demo/kast-demo.gif`
- Modify: `README.md`

**Interfaces:**
- Consumes: the validated Asciinema v2 cast from Task 2
- Produces: a sub-8-MiB inline GIF and a GitHub-relative README embed

- [ ] **Step 1: Install or verify the pinned GIF renderer**

Run:

```bash
command -v agg >/dev/null || brew install agg
test "$(agg --version | awk '{print $2}')" = "1.9.0"
```

Expected: `agg 1.9.0` is available. If Homebrew has moved past 1.9.0, inspect `agg --help` and use the installed compatible version only after confirming all options in Step 2 still exist.

- [ ] **Step 2: Render a legible, compact looping GIF**

Run:

```bash
agg \
  --theme asciinema \
  --font-size 14 \
  --speed 1 \
  --idle-time-limit 1 \
  --fps-cap 15 \
  --last-frame-duration 2 \
  docs/assets/demo/kast-demo.cast \
  docs/assets/demo/kast-demo.gif
```

Expected: `agg` exits 0 and creates a looping animated GIF from the audited cast without changing the 120x40 terminal geometry.

- [ ] **Step 3: Validate dimensions, animation, duration, and size**

Run:

```bash
test "$(magick identify -format '%wx%h\n' docs/assets/demo/kast-demo.gif | sort -u | wc -l | tr -d ' ')" = "1"
test "$(magick identify docs/assets/demo/kast-demo.gif | wc -l | tr -d ' ')" -gt 10
test "$(stat -f '%z' docs/assets/demo/kast-demo.gif)" -lt 8388608
gif_duration_cs="$(magick identify -format '%T\n' docs/assets/demo/kast-demo.gif | awk '{ total += $1 } END { print total }')"
test "$gif_duration_cs" -ge 800
test "$gif_duration_cs" -le 2200
magick identify -format 'dimensions=%wx%h frames=%n bytes=%b\n' docs/assets/demo/kast-demo.gif | head -n 1
printf 'duration=%s.%02ss\n' "$((gif_duration_cs / 100))" "$((gif_duration_cs % 100))"
```

Expected: all frames have one consistent dimension, there are more than 10 frames, the animation lasts 8–22 seconds including its final-frame hold, the file is below 8 MiB, and ImageMagick prints the concrete dimensions/frame count/duration/size for PR evidence. Inspect the animation visually with the local image viewer before editing the README; re-render if any terminal label is unreadable or clipped.

- [ ] **Step 4: Add the inline asset immediately after the README introduction**

Use `apply_patch` to change the opening of `## Try it on your code` to exactly:

````markdown
## Try it on your code

Once the workspace is prepared and its backend is ready, run the read-only
repository tour:

![Read-only Kast semantic story moving through identity, relationships, impact, and safety evidence](docs/assets/demo/kast-demo.gif)

```console
kast demo
```
````

Expected: the existing command and repository-demo guide prose remain unchanged below the new image.

- [ ] **Step 5: Render and inspect the README locally**

Run:

```bash
grip --export README.md "$EVIDENCE_DIR/README.preview.html"
ln -sfn "$SOURCE_ROOT/docs" "$EVIDENCE_DIR/docs"
python3 -m http.server 8765 --directory "$EVIDENCE_DIR"
```

Open `http://127.0.0.1:8765/README.preview.html#try-it-on-your-code` with Playwright. Verify that the image with the approved alt text has `naturalWidth > 0` and `naturalHeight > 0`, that the terminal text is legible at a desktop viewport, and that the page has no horizontal scrollbar. Stop the local server after saving a screenshot to `$EVIDENCE_DIR/local-readme.png`.

Expected: the local GitHub-flavored Markdown preview resolves `docs/assets/demo/kast-demo.gif` from the repository-relative path and displays it inline.

- [ ] **Step 6: Validate and commit the README slice**

Run:

```bash
test -f docs/assets/demo/kast-demo.gif
rg -n -F '](docs/assets/demo/kast-demo.gif)' README.md
.github/scripts/test-docs-content-contract.sh
.github/scripts/test-docs-navigation-contract.sh
zensical build --clean
git diff --check
git add README.md docs/assets/demo/kast-demo.gif
git diff --cached --check
git commit -m "docs: embed interactive kast demo"
```

Expected: both docs contract scripts pass, Zensical reports a successful build, and the commit contains only `README.md` plus `kast-demo.gif`.

---

### Task 4: Prove The GitHub Experience And Bring The Follow-Up PR To Green

**Files:**
- Create remotely: follow-up pull request for `docs/readme-demo-recording`
- Generate outside Git: `/tmp/kast-demo-evidence/github-readme.png`

**Interfaces:**
- Consumes: the cast commit and README/GIF commit from Tasks 2–3
- Produces: remote GitHub rendering proof and terminal PR check evidence at the final head SHA

- [ ] **Step 1: Push both reviewed recording slices**

Run:

```bash
git push -u origin docs/readme-demo-recording
git ls-remote --heads origin docs/readme-demo-recording
```

Expected: the remote branch SHA matches `git rev-parse HEAD`.

- [ ] **Step 2: Inspect the actual GitHub README rendering with Playwright**

Resolve and open the commit-stable URL in a real browser:

```bash
export HEAD_SHA="$(git rev-parse HEAD)"
printf 'https://github.com/amichne/kast/tree/%s#try-it-on-your-code\n' "$HEAD_SHA"
```

Verify that the `Try it on your code` section contains an `<img>` whose alt text is `Read-only Kast semantic story moving through identity, relationships, impact, and safety evidence`, whose `naturalWidth` and `naturalHeight` are both greater than zero, and whose current frame is legible without horizontal scrolling at a desktop viewport. Save a screenshot to `/tmp/kast-demo-evidence/github-readme.png`.

Expected: GitHub renders the GIF inline from the committed relative path; no broken-image icon or authentication-only content appears.

- [ ] **Step 3: Create the follow-up PR with concrete recording evidence**

Run:

```bash
gh pr create \
  --base main \
  --head docs/readme-demo-recording \
  --title "docs: add interactive kast demo recording" \
  --body-file "$EVIDENCE_DIR/pr-body.md"
```

The body must explain that the pull request:

```markdown
- embeds a source-backed `kast demo` GIF in the README and retains its auditable Asciinema v2 cast
```

Under verification, include the concrete cast validation, source-hash
comparison, ImageMagick dimensions/frame count/size, docs contract commands,
released-version proof, and browser-render proof.

- [ ] **Step 4: Babysit every check for the final head SHA**

Run `gh pr checks <number>` in bounded polling intervals and use
`gh run view <run-id> --json jobs` for any non-terminal or failed workflow.
Keep polling until every check at `git rev-parse HEAD` is success, skipped, or
neutral.

Expected terminal evidence:

```text
headRefOid == local HEAD
failures == 0
pending == 0
mergeable == MERGEABLE
```

If a check fails, use `superpowers:systematic-debugging`, repair only the root cause, rerun the narrow local validation, commit the repair separately, push, and repeat this step for the new SHA.

- [ ] **Step 5: Verify the worktree and hand off the ready PR**

Run:

```bash
git status --short --branch
git log -4 --oneline --decorate
gh pr view <number> --json url,headRefOid,isDraft,mergeable,mergeStateStatus,statusCheckRollup
```

Expected: the temporary implementation worktree is clean, the follow-up pull
request is ready for review, all checks for its final head are
terminal-green/skipped/neutral, and any remaining `BLOCKED` state is
attributable only to review or branch policy.
