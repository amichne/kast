# Kast Cloud and CI Setup

How to install and use kast in headless, CI, and container environments.

---

## Requirements

- **Java 21 or newer** (OpenJDK or vendor distribution)
- One of: Linux x86-64, macOS x86-64, macOS arm64
- Supported install tools: `curl` + `python3` (for `install.sh`)

---

## Platform Support

| Platform ID | OS | Architecture |
|-------------|-----|-------------|
| `linux-x64` | Linux | x86_64 |
| `macos-x64` | macOS | x86_64 (Intel) |
| `macos-arm64` | macOS | arm64 / aarch64 (Apple Silicon) |

The platform is auto-detected by `install.sh` and `resolve-kast.sh`.

---

## Install Paths

### Option 1: `./install.sh` (recommended for CI)

Downloads the latest GitHub release for the current platform.

```bash
# Install latest release
./install.sh

# Install a specific version
KAST_VERSION=v1.2.3 ./install.sh

# Install to a custom directory (useful in CI)
KAST_INSTALL_ROOT=/opt/kast \
KAST_BIN_DIR=/opt/kast/bin \
KAST_SKIP_PATH_UPDATE=true \
  ./install.sh
export PATH="/opt/kast/bin:$PATH"
```

**Environment variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `KAST_VERSION` | latest | GitHub release tag to install |
| `KAST_INSTALL_ROOT` | `~/.local/share/kast` | Directory for versioned installs |
| `KAST_BIN_DIR` | `~/.local/bin` | Directory for the `kast` symlink |
| `KAST_SKIP_PATH_UPDATE` | `false` | Skip writing to shell rc files |
| `KAST_RELEASE_REPO` | `amichne/kast` | Override GitHub repo for releases |
| `KAST_ARCHIVE_PATH` | — | Use a local archive instead of downloading |
| `KAST_EXPECTED_SHA256` | — | Expected SHA-256 for local archive |

After install, the `kast` binary is at `${KAST_BIN_DIR}/kast`, pointing to `${KAST_INSTALL_ROOT}/current/kast`.

### Option 2: `./build.sh` (build from source)

```bash
# Requires Java 21+ on PATH or JAVA_HOME set
./build.sh --no-install
# Produces: dist/kast/kast
export PATH="$PWD/dist/kast:$PATH"
```

### Option 3: Gradle direct

```bash
./gradlew :kast:writeWrapperScript --no-configuration-cache
# Produces: kast/build/scripts/kast
export PATH="$PWD/kast/build/scripts:$PATH"
```

---

## CI Pattern

Standard pattern for GitHub Actions or any CI runner:

```yaml
# .github/workflows/analyze.yml (illustrative)
steps:
  - uses: actions/checkout@v4

  - name: Set up Java 21
    uses: actions/setup-java@v4
    with:
      java-version: '21'
      distribution: 'temurin'

  - name: Install kast
    run: |
      KAST_BIN_DIR="$HOME/.local/bin" \
      KAST_SKIP_PATH_UPDATE=true \
        ./install.sh
      echo "$HOME/.local/bin" >> $GITHUB_PATH

  - name: Ensure workspace
    run: kast workspace ensure --workspace-root="$GITHUB_WORKSPACE"

  - name: Run diagnostics
    run: |
      kast diagnostics \
        --workspace-root="$GITHUB_WORKSPACE" \
        --file-paths="$GITHUB_WORKSPACE/src/main/kotlin/MyFile.kt"

  - name: Stop daemon
    if: always()
    run: kast daemon stop --workspace-root="$GITHUB_WORKSPACE" || true
```

**Key points:**
- Always run `workspace ensure` before analysis commands.
- Always run `daemon stop` in a cleanup step (use `|| true` — ok if already stopped).
- Use absolute paths for `--workspace-root` and all file arguments.
- In containers, `$GITHUB_WORKSPACE` (or equivalent) is the workspace root.

---

## CI: JSON Processing Without `jq`

`jq` is not present in all CI images, and embedded `jq` expressions inside shell strings
are a common source of quoting failures. Use `kast-plan-utils.py` instead — it ships with
the skill and requires only `python3 >= 3.8`, which is available everywhere.

```yaml
# Example: GitHub Actions step for a rename + diagnostics check
- name: Rename symbol with kast
  env:
    SKILL_ROOT: .agents/skills/kast
    WS: ${{ github.workspace }}
  run: |
    set -euo pipefail

    # Resolve kast
    KAST="$(command -v kast || bash "$SKILL_ROOT/scripts/resolve-kast.sh")"

    # Full rename: plan → apply → diagnostics exit code
    bash "$SKILL_ROOT/scripts/kast-rename.sh" \
      --workspace-root="$WS" \
      --file-path="$WS/path/to/File.kt" \
      --offset=556 \
      --new-name=AcquisitionMapper
```

If you need the step-by-step approach instead of `kast-rename.sh`, replace every `jq`
invocation with the matching `kast-plan-utils.py` subcommand:

| Previously (jq) | Now (kast-plan-utils.py) |
|---|---|
| `jq '{edits:.edits,fileHashes:.fileHashes}' plan.json > req.json` | `python3 "$UTILS" extract-apply-request plan.json req.json` |
| `jq -r '.affectedFiles \| join(",")' plan.json` | `python3 "$UTILS" affected-files-csv plan.json` |
| `jq '.diagnostics \| map(select(.severity=="ERROR")) \| length' diag.json` | `python3 "$UTILS" check-diagnostics diag.json` |

---

## Headless / Container Usage

kast communicates over a Unix domain socket (`.kast/instances/` in the workspace root). This means:

- No network ports are opened.
- Multiple workspaces can have independent daemons simultaneously.
- The socket path must be writable; ensure the workspace directory is not read-only.

For Docker, mount the workspace volume with write permissions:

```dockerfile
# Dockerfile snippet
RUN apt-get install -y openjdk-21-jdk-headless curl python3

COPY install.sh .
RUN KAST_INSTALL_ROOT=/opt/kast KAST_BIN_DIR=/usr/local/bin \
    KAST_SKIP_PATH_UPDATE=true ./install.sh
```

---

## Java Version Detection

`install.sh` calls `java -XshowSettings:properties -version` and parses `java.specification.version`. The same logic is used in `resolve-kast.sh`.

To override the Java binary:
```bash
export JAVA_HOME=/path/to/jdk-21
```

---

## Caching in CI

Cache the kast install directory to avoid re-downloading on every run:

```yaml
- uses: actions/cache@v4
  with:
    path: ~/.local/share/kast
    key: kast-${{ runner.os }}-${{ hashFiles('**/kast-version.txt') }}
```

The installed binary structure under `${KAST_INSTALL_ROOT}/releases/<tag>/<platform>/` is stable across runs for the same version.
