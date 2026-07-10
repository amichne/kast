# macOS Installer Banner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the original Kast ASCII banner for root macOS `install` and `update` invocations only.

**Architecture:** Keep presentation owned by `install.sh` through a side-effect-free `print_banner` function that reuses the existing color helper and writes to standard error. Extend the existing macOS installer contract to prove inclusion for `install` and `update`, exclusion for `verify` and `--help`, and preservation of current mutation behavior.

**Tech Stack:** Bash, repository shell contract tests, GitHub Actions.

## Global Constraints

- Restore the exact six-line `KAST` artwork, original tagline, and repository link.
- Write banner output only to standard error.
- Reuse `colorize` so `NO_COLOR` remains authoritative.
- Render the banner once for `install` and `update` after argument, host, and workspace validation and before the mutation plan.
- Keep `verify` and `--help` banner-free.
- Do not alter installer exit codes, subprocess calls, or mutation order.

---

### Task 1: Restore and contract-test the installer banner

**Files:**
- Modify: `.github/scripts/test-macos-installer-contract.sh`
- Modify: `install.sh`

**Interfaces:**
- Consumes: existing `colorize(code, text)` and root installer command dispatch.
- Produces: `print_banner()` with no arguments and no standard-output data.

- [ ] **Step 1: Write the failing installer contract**

Add this helper beside `require_stderr_contains`:

```bash
require_stderr_not_contains() {
  local stderr_file="$1"
  local unexpected="$2"
  local description="$3"
  if grep -Fq -- "$unexpected" "$stderr_file"; then
    printf '%s\n' "stderr contents:" >&2
    cat "$stderr_file" >&2
    die "${description}: found '${unexpected}'"
  fi
}
```

Capture `install` and `update` standard error and assert both the artwork and
tagline:

```bash
require_stderr_contains "$install_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "install should render the Kast banner"
require_stderr_contains "$install_stderr" "Kotlin semantic analysis — from your terminal" "install should render the original tagline"

update_stderr="${tmp_root}/update.stderr"
run_installer_noninteractive "$repo_root" update \
  --tap custom/tap \
  --tap-url https://git.example.test/homebrew/kast.git \
  --workspace-root "$workspace" 2>"$update_stderr"
require_stderr_contains "$update_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "update should render the Kast banner"
require_stderr_contains "$update_stderr" "Kotlin semantic analysis — from your terminal" "update should render the original tagline"
```

Capture `verify` and help standard error and assert the banner signature is
absent:

```bash
verify_stderr="${tmp_root}/verify.stderr"
run_installer "$repo_root" verify --workspace-root "$workspace" 2>"$verify_stderr"
require_stderr_not_contains "$verify_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "verify should remain banner-free"

help_stderr="${tmp_root}/help.stderr"
run_installer "$repo_root" --help 2>"$help_stderr"
require_stderr_not_contains "$help_stderr" "██╗  ██╗ █████╗ ███████╗████████╗" "help should remain banner-free"
```

- [ ] **Step 2: Run the contract to verify it fails**

Run:

```bash
.github/scripts/test-macos-installer-contract.sh
```

Expected: FAIL because `install.sh` does not yet render the banner for
`install`.

- [ ] **Step 3: Restore the exact banner and route it to mutating commands**

Add this function after `colorize` in `install.sh`:

```bash
print_banner() {
  printf '\n' >&2
  printf '  %s\n' "$(colorize '1;36' '  ██╗  ██╗ █████╗ ███████╗████████╗')" >&2
  printf '  %s\n' "$(colorize '1;36' '  ██║ ██╔╝██╔══██╗██╔════╝╚══██╔══╝')" >&2
  printf '  %s\n' "$(colorize '1;36' '  █████╔╝ ███████║███████╗   ██║   ')" >&2
  printf '  %s\n' "$(colorize '1;36' '  ██╔═██╗ ██╔══██║╚════██║   ██║   ')" >&2
  printf '  %s\n' "$(colorize '1;36' '  ██║  ██╗██║  ██║███████║   ██║   ')" >&2
  printf '  %s\n' "$(colorize '1;36' '  ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝   ╚═╝  ')" >&2
  printf '\n' >&2
  printf '  %s\n' "Kotlin semantic analysis — from your terminal" >&2
  printf '  %s\n' "$(colorize '2' 'https://github.com/amichne/kast')" >&2
  printf '\n' >&2
}
```

Invoke it only in the existing mutating-command case:

```bash
case "$command_name" in
  install|update)
    print_banner
    require_jetbrains_ides_closed
    confirm_mutation "$command_name" "$tap" "$tap_url" "$workspace_root"
    ;;
esac
```

- [ ] **Step 4: Run focused and hygiene validation**

Run:

```bash
.github/scripts/test-macos-installer-contract.sh
git diff --check
```

Expected: the installer contract prints `macOS installer contract passed`, and
`git diff --check` emits no output.

- [ ] **Step 5: Commit the scoped implementation**

```bash
git add install.sh .github/scripts/test-macos-installer-contract.sh cli-rs/tests/machine_plugin_repair_smoke.rs
git diff --cached --check
git commit -m "fix: restore macOS installer banner"
```

The test file in the commit carries the already-validated Linux CI assertion
correction for the same PR head. Do not stage the worktree-local `AGENTS.md`
path rewrite.
