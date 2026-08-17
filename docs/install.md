# Install Kast

Kast `v0.25.0` supports macOS ARM64 and requires Java 21. The default download is the small
control product; it does not contain IntelliJ, Kotlin IDE, or `kast-indexer` payloads.

```shell
version=v0.25.0
install_root="$HOME/.local/share/kast/$version"
mkdir -p "$install_root" "$HOME/.local/bin"
curl -fLO "https://github.com/amichne/kast/releases/download/$version/kast-control-$version-macos-aarch64.tar.gz"
curl -fLO "https://github.com/amichne/kast/releases/download/$version/kast-control-$version-macos-aarch64.tar.gz.sha256"
shasum -a 256 -c "kast-control-$version-macos-aarch64.tar.gz.sha256"
tar -xzf "kast-control-$version-macos-aarch64.tar.gz" -C "$install_root"
ln -sfn "$install_root/bin/kast" "$HOME/.local/bin/kast"
```

`kast --help`, `kast --version`, and `kast --schema` run locally without a workspace or semantic
runtime. The first semantic command, such as `kast workspace inspect`, downloads and verifies the
exact content-addressed runtime named by the embedded manifest. Later commands reuse that runtime
without downloading or extracting it again.
