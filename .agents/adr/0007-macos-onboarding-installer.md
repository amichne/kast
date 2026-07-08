# ADR 0007: macOS onboarding installer

Status: Accepted

Date: 2026-07-08

This ADR supersedes ADR 0006 only for the mainline macOS developer onboarding
command shape. ADR 0006 remains the broader system definition and public
surface charter.

## Decision

The mainline macOS developer onboarding path is the root `install.sh` script.
Public onboarding docs should send developers through:

```console
./install.sh install --workspace-root "$PWD"
./install.sh update --workspace-root "$PWD"
./install.sh verify --workspace-root "$PWD"
```

The script is macOS-only. It owns the hidden Homebrew path for developer
machines: tapping the configured Homebrew repository, installing or updating
the `kast` formula, invoking the version-coupled IDEA plugin installer through
the CLI, and verifying readiness for the selected workspace.

Workspace setup on macOS remains plugin-owned. After `install` or `update`, the
developer opens the repository in IntelliJ IDEA or Android Studio; the plugin
writes skill-facing guidance, invocation metadata, and
`.kast/setup/workspace.json`. The installer must not reintroduce CLI
`kast setup` as the macOS workspace setup path.

The default tap is `amichne/kast`. Enterprise or mirrored installs may pass a
different tap and a custom-host Git URL:

```console
./install.sh install \
  --tap internal/kast \
  --tap-url https://git.example.com/internal/homebrew-kast.git \
  --workspace-root "$PWD"
```

The script must fail before mutation for unsupported hosts, unknown commands,
unknown flags, missing option values, invalid tap names, invalid tap URLs, or
missing workspace roots.

## Documentation Boundary

README, the docs overview, install guide, and quickstart should expose
`install.sh` as the first-run developer-machine path. Deeper command reference,
runtime, metrics, distribution, and troubleshooting pages may still document
`kast developer ...` commands when those commands are the relevant operator or
release-engineering surface.

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
