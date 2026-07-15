# Kast Homebrew tap

Homebrew is the sole installation and update authority for the Kast CLI on
macOS. It does not install, link, repair, or inspect the IntelliJ plugin.

```bash
brew tap amichne/kast
brew install amichne/kast/kast
kast repair --for machine --apply
```

The repair command writes the fail-closed CLI-only receipt and, for the 0.13.0
cutover release only, can remove exactly recognized legacy Homebrew plugin
symlinks. It never creates an IDE profile or plugin link.

Install the signed plugin ZIP with JetBrains **Install Plugin from Disk**. Add
the published signing certificate and custom plugin repository in the IDE,
then reopen the exact project. JetBrains owns subsequent plugin updates.

## Release mirrors

The formula defaults to the monorepo `amichne/kast` release workflow. An
enterprise mirror may override the CLI artifact root without changing the
formula:

```bash
export HOMEBREW_KAST_CLI_RELEASE_ROOT="https://artifactory.example.com/kast/releases/download"
curl -LO "$HOMEBREW_KAST_CLI_RELEASE_ROOT/v0.7.29/kast-v0.7.29-macos-arm64.zip"
```

The signed IDEA ZIP, update feed, checksums, and provenance remain release
assets, but they are not Homebrew packages.
