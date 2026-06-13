# homebrew-kast

Homebrew tap for the macOS developer distribution of
[Kast](https://github.com/amichne/kast).

## Install

Install directly from the tap:

```bash
brew install amichne/kast/kast
brew install --cask amichne/kast/kast-plugin
```

Homebrew will add the tap automatically. To add the tap first and then install
by short token:

```bash
brew tap amichne/kast
brew install kast
brew install --cask kast-plugin
```

`kast` installs the macOS Rust CLI asset from `amichne/kast`.
`kast-plugin` installs the IDEA plugin bundle from `amichne/kast` as a cask and
links it into every local JetBrains IDE profile it can find. Restart each IDE
after installation or upgrade so the IDE reloads its plugins.

If your JetBrains config directory is somewhere else, point the cask at it:

```bash
KAST_JETBRAINS_CONFIG_ROOT="$HOME/Library/Application Support/JetBrains" brew reinstall --cask kast-plugin
```

The Homebrew distribution is for macOS developer installs. It does not install
the Linux headless runtime or a Homebrew-managed JDK; use the Linux headless
tarball from the Kast release when you need headless operation.

## Enterprise mirrors

The package files default to GitHub release assets, but the release host is
resolved at install time. To use the same tap against an internal Artifactory mirror,
mirror the release tree under one root and set:

```bash
export HOMEBREW_KAST_ARTIFACT_ROOT="https://artifactory.example.com/artifactory/kast-releases"
brew install amichne/kast/kast
brew install --cask amichne/kast/kast-plugin
```

The shared mirror root must expose the same repository-shaped paths:

```text
${HOMEBREW_KAST_ARTIFACT_ROOT}/kast/releases/download/v0.7.29/kast-v0.7.29-macos-arm64.zip
${HOMEBREW_KAST_ARTIFACT_ROOT}/kast/releases/download/v0.7.29/kast-idea-v0.7.29.zip
```

If your enterprise artifact layout separates the CLI and plugin roots, set the
component-specific roots instead:

```bash
export HOMEBREW_KAST_CLI_RELEASE_ROOT="https://artifactory.example.com/artifactory/kast-cli"
export HOMEBREW_KAST_PLUGIN_RELEASE_ROOT="https://artifactory.example.com/artifactory/kast-plugin"
```

Those roots should point at the directory that contains each `vX.Y.Z` release
directory. Checksums remain pinned in the tap, so mirrored artifacts must be
byte-for-byte copies of the published release assets.

The tap tracks the current published release in `release-state.json`. The
Homebrew package files and release state are rendered atomically by the
monorepo `amichne/kast` release workflow after the Rust CLI and IDEA plugin
assets are published from the same tag. A single shared version is used for all
components; the renderer rejects partial component updates so `kast` and
`kast-plugin` cannot drift.
