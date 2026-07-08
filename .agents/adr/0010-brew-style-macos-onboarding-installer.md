# ADR 0010: brew-style macOS onboarding installer

Status: Accepted

Date: 2026-07-08

This ADR supersedes ADR 0007: macOS onboarding installer for the public
first-run command shape. ADR 0006 remains the broader product surface charter,
and ADR 0007: macOS plugin setup authority remains the macOS workspace setup
boundary.

## Decision

The mainline macOS developer onboarding path is still the root `install.sh`
script, but public docs should present it with a Homebrew-style remote shell
entrypoint:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)"
```

The script defaults to `install` and treats the current working directory as
the workspace root. Command arguments remain available after the `bash -c`
placeholder:

```console
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- update --workspace-root "$PWD"
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/amichne/kast/main/install.sh)" -- verify --workspace-root "$PWD"
```

For mutating commands, the script must explain the planned machine changes and
pause before it invokes Homebrew or `kast`. Automation may opt out of the pause
with `NONINTERACTIVE=1`, matching Homebrew's documented unattended installer
convention. Verification remains read-only and does not prompt.

The checked-out `./install.sh ...` form remains supported for repository
development, local contract tests, and users who intentionally save the script.
It is no longer the primary public onboarding command in README or published
docs.

The script remains macOS-only. It owns the hidden Homebrew path for developer
machines: tapping the configured Homebrew repository, installing or updating
the `kast` formula, invoking the version-coupled IDEA plugin installer through
the CLI, and verifying readiness for the selected workspace.

Workspace setup on macOS remains plugin-owned. After `install` or `update`, the
developer opens the repository in IntelliJ IDEA or Android Studio; the plugin
writes skill-facing guidance, invocation metadata, and
`.kast/setup/workspace.json`. The installer must not reintroduce CLI
`kast setup` as the macOS workspace setup path.

## Source Of Truth

| Surface | Source |
| --- | --- |
| macOS onboarding script | `install.sh` |
| script contract test | `.github/scripts/test-macos-installer-contract.sh` |
| mainline onboarding docs | `README.md`, `docs/index.md`, `docs/getting-started/install.md`, `docs/getting-started/quickstart.md` |
| docs contract | `.github/scripts/test-docs-content-contract.sh` |

## Validation

Run these checks when the onboarding installer, mainline docs, or Homebrew tap
contract changes:

```console
.github/scripts/test-macos-installer-contract.sh
.github/scripts/test-docs-content-contract.sh
```

Run `zensical build --clean` when rendered docs output matters.
