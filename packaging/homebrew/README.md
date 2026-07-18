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

For an absent plugin, the root `install.sh` reads the installed CLI version and
delegates release-matched installation to a closed IDE's `installPlugins`
command. The command does not replace an installed plugin. For native updates,
add
`https://github.com/amichne/kast/releases/latest/download/updatePlugins.xml`
as a custom plugin repository. JetBrains owns the installed plugin files and
update application. If no launcher is available for initial installation, use
JetBrains' **Install Plugin from Disk** with the matching GitHub Release ZIP.

## Release mirrors

The formula defaults to the monorepo `amichne/kast` release workflow. An
enterprise mirror may override the CLI artifact root without changing the
formula:

```bash
export HOMEBREW_KAST_CLI_RELEASE_ROOT="https://artifactory.example.com/kast/releases/download"
curl -LO "$HOMEBREW_KAST_CLI_RELEASE_ROOT/v0.7.29/kast-v0.7.29-macos-arm64.zip"
```

The unsigned IDEA ZIP and update feed remain GitHub Release assets, not
Homebrew packages or inputs to the non-IDEA checksum/provenance bundle.
