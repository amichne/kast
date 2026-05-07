This is the single authoritative plan combining all changes needed to go from "curl install → fully usable by an LLM agent" with zero manual steps after the initial installer.

---

## Phase 1: Consolidate On-Disk Layout Under `$HOME/.kast`

### 1A. Fix CLI `installOptions()` path defaults

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandParser.kt` (lines 586-598)

Change the defaults in `installOptions()`:
- `instancesRoot`: `home.resolve(".local/share/kast/instances")` → `home.resolve(".kast/releases")`
- `binDir`: `home.resolve(".local/bin")` → `home.resolve(".kast/bin")`

This aligns the CLI's `install` command with what `kast.sh` already does (`install_root="${HOME}/.kast"`, `bin_dir="${install_root}/bin"`).

### 1B. Move workspace data from config home to install root

File: `analysis-api/src/main/kotlin/io/github/amichne/kast/api/client/WorkspaceDirectoryResolver.kt` (lines 18-36)

Currently `workspaceDataDirectory()` resolves under `configHome()/workspaces/...`. Change it to resolve under `$HOME/.kast/workspaces/...`:
- Git remote repos: `$HOME/.kast/workspaces/<host>/<owner>/<repo>`
- Local repos: `$HOME/.kast/workspaces/local/<sanitized>--<uuid>`
- Ephemeral (under /tmp): `<workspace>/.gradle/kast` (unchanged)

Add a `kastInstallRoot()` helper that returns `Path.of(System.getProperty("user.home")).resolve(".kast")` and use it as the base for workspace directories instead of `configHome()`.

Move `local-workspaces.json` to `$HOME/.kast/workspaces/local-workspaces.json`.

Update tests in `analysis-api/src/test/kotlin/io/github/amichne/kast/api/KastConfigTest.kt` and any `WorkspacePathsTest.kt`.

### 1C. Move global skill install location

File: `kast.sh` (around line 1281 where `global_dir` is set)

Change `global_dir` from `"${HOME}/.agents/skills"` to `"${HOME}/.kast/lib/skills"`.

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/InstallSkillService.kt` (or wherever the default skill target-dir is resolved)

Update the default skill target directory resolution to prefer:
1. If `.agents/skills` exists in cwd → use it (backward compat for per-repo)
2. If `.github/skills` exists in cwd → use it (backward compat for per-repo)
3. If `.claude/skills` exists in cwd → use it
4. Otherwise → `$HOME/.kast/lib/skills` (global default)

### 1D. Proposed final directory structure

```
$HOME/.config/kast/
├── config.toml                          # sole user-editable config
└── env                                  # shell env snippet

$HOME/.kast/                             # KAST_HOME — single deletable tree
├── bin/
│   └── kast                             # launcher script
├── current -> releases/v1.2.3/platform  # active release symlink
├── releases/
│   └── v1.2.3/<platform>/
├── backends/
│   ├── current -> standalone-v1.2.3
│   └── standalone-v1.2.3/
├── plugins/
│   └── kast-intellij-v1.2.3.zip
├── lib/                                 # global AI primitives
│   ├── skills/
│   │   └── kast/
│   ├── hooks/
│   ├── extensions/
│   └── agents/
├── workspaces/
│   ├── github.com/<owner>/<repo>/
│   ├── local/<sanitized>--<uuid>/
│   └── local-workspaces.json
├── sessions/
├── cache/
│   └── daemons/
├── logs/
└── .manifest.json                       # global install manifest
```

---

## Phase 2: Unify All Binary Resolution (5 Entry Points)

The goal: every resolution path checks the same candidates in the same order:
1. `config.toml` `[cli] binaryPath` (explicit override, written by installer)
2. `$HOME/.kast/bin/kast` (canonical hardcoded fallback)
3. PATH lookup for `kast` or `kast-cli` (works in interactive/login shells)
4. Repo-local build artifacts (development only)

### 2A. Simplify `.github/extensions/kast/scripts/resolve-kast.sh`

File: `.github/extensions/kast/scripts/resolve-kast.sh` (lines 31-65)

Replace the current logic with the 4-step order above. Remove the 6-level parent directory walk. Remove the `$HOME/.local/bin/kast` fallback (line 59). Add `$HOME/.kast/bin/kast` as an explicit check before the error exit. Keep the `read_config_binary_path` function for reading config.toml.

### 2B. Replace `.github/hooks/resolve-kast-cli-path.sh` with delegation

File: `.github/hooks/resolve-kast-cli-path.sh` (lines 17-31)

Replace the body with a delegation to the canonical resolve script:
```bash
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "${SCRIPT_DIR}" rev-parse --show-toplevel 2>/dev/null || echo "${SCRIPT_DIR}/../..")"
RESOLVE_SCRIPT="${REPO_ROOT}/.github/extensions/kast/scripts/resolve-kast.sh"
if [[ -x "${RESOLVE_SCRIPT}" ]]; then
    exec bash "${RESOLVE_SCRIPT}"
fi
# Inline fallback: config.toml → ~/.kast/bin/kast → PATH → error
config_dir="${KAST_CONFIG_HOME:-${HOME}/.config/kast}"
# ... minimal inline fallback using the same 4-step order ...
```

### 2C. Unify `.agents/skills/kast/scripts/resolve-kast.sh`

File: `.agents/skills/kast/scripts/resolve-kast.sh` (lines 15-36)

This is the weakest resolver — it only checks PATH and walks parent dirs. It does NOT check config.toml or `$HOME/.kast/bin/kast`. Add:
1. The `read_config_binary_path()` function (copy from the extension's resolve script)
2. config.toml check after the PATH check
3. `$HOME/.kast/bin/kast` hardcoded fallback before the error exit

Since this skill is packaged as an embedded resource via `syncPackagedCopilotExtensionResources`, the source file that gets synced into the CLI's resources must also be updated.

### 2D. Fix `extension.mjs` hardcoded fallback path

File: `.github/extensions/kast/extension.mjs` (line 120)

Change:
```javascript
addCandidate(join(homedir(), ".local", "bin", "kast"));
```
to:
```javascript
addCandidate(join(homedir(), ".kast", "bin", "kast"));
```

### 2E. IntelliJ plugin resolution (no change needed)

File: `backend-intellij/src/main/kotlin/io/github/amichne/kast/intellij/actions/KastInstallAction.kt` (lines 76-86)

The IntelliJ plugin reads `config.toml` `[cli] binaryPath` which the installer writes. This is sufficient since IntelliJ always runs in a user session where config.toml is available. No change needed.

---

## Phase 3: Auto-Indexing on Session Start

### 3A. Add `workspace ensure` call to `extension.mjs` `onSessionStart`

File: `.github/extensions/kast/extension.mjs` (around line 435, in the `onSessionStart` handler)

After successfully resolving the binary and passing the version check, add a fire-and-forget call to start the backend:

```javascript
// After version parity check, before the return statement:
const repoRoot = REPO_ROOT;
execBash(
  `${JSON.stringify(bin)} workspace ensure --workspace-root=${JSON.stringify(repoRoot)} --accept-indexing=true`
).then(({ok, stderr}) => {
  if (!ok) {
    session.log(
      `kast extension: workspace ensure failed for ${repoRoot}. stderr: ${stderr.trim().slice(0, 200)}`,
      { level: "warning" },
    );
  } else {
    session.log(`kast extension: backend ready for ${repoRoot}`, { ephemeral: true });
  }
}).catch(() => {});
```

Key: use `--accept-indexing=true` so the daemon is servable immediately (partial results during indexing). Fire-and-forget so session start isn't blocked.

### 3B. Make `workspace ensure` auto-start the standalone daemon

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/WorkspaceRuntimeManager.kt` (lines 66-115)

Currently `ensureRuntime()` throws `NO_BACKEND_AVAILABLE` when no daemon exists. Before the `throw`, add auto-start logic:

```kotlin
// Before the final throw CliFailure(NO_BACKEND_AVAILABLE):
if (!options.noAutoStart) {
    val config = KastConfig.load(options.workspaceRoot.toJavaPath())
    val runtimeLibsDir = Path.of(config.backends.standalone.runtimeLibsDir.value)
    if (Files.isDirectory(runtimeLibsDir)) {
        startStandaloneDaemon(options, runtimeLibsDir)
        return WorkspaceEnsureResult(
            workspaceRoot = options.workspaceRoot.toString(),
            descriptorDirectory = inspection.descriptorDirectory.toString(),
            started = true,
            selected = waitForServable(
                options = options.copy(backendName = BackendName.STANDALONE),
                backendName = BackendName.STANDALONE,
                acceptIndexing = !requireReady,
            ),
        )
    }
}
```

Implement `startStandaloneDaemon()` to launch `kast daemon start` as a background process. The existing `--no-auto-start=true` flag should bypass this to preserve fail-fast behavior for users who want it.

---

## Phase 4: Version Mismatch Auto-Recovery

File: `.github/extensions/kast/extension.mjs` (lines 447-455)

Currently, when CLI version != extension version, the extension returns `KAST EXTENSION BLOCKED`. Replace with auto-recovery:

```javascript
if (cliVersion && installedVersion && cliVersion !== installedVersion) {
  const syncResult = await execBash(
    `${JSON.stringify(bin)} install copilot-extension --target-dir=${JSON.stringify(join(REPO_ROOT, ".github"))} --yes=true`
  );
  if (syncResult.ok) {
    await session.log(
      `kast extension: auto-synced copilot extension from ${installedVersion} to ${cliVersion}`,
      { level: "info" },
    );
    // Continue normally — don't block
  } else {
    const msg = `kast version mismatch: CLI=${cliVersion}, extension=${installedVersion}. Auto-sync failed. Run \`kast install copilot-extension\` manually.`;
    await session.log(`kast extension: ${msg}`, { level: "error" });
    return { additionalContext: `KAST EXTENSION WARNING — ${msg}` };
  }
}
```

Change from BLOCKED to WARNING so tools still attempt to work even if sync fails.

---

## Phase 5: Installer Hardening

### 5A. Fix PATH propagation for non-interactive shells

File: `kast.sh` (function `_install_ensure_bin_dir_on_path`, around line 661)

The installer writes PATH to one rc file (e.g., `.bashrc`), but `extension.mjs` uses `bash -lc` (login shell) which sources `.bash_profile`/`.profile` but NOT `.bashrc`. Update the function to:
1. Write the PATH export to the chosen rc file (existing behavior)
2. If the user's shell is bash AND the chosen file is `.bashrc`, ALSO write the PATH block to `.bash_profile` (or `.profile` if `.bash_profile` doesn't exist)

This ensures `bash -lc` in extension.mjs will have kast on PATH.

### 5B. Add copilot-extension as an installer phase

File: `kast.sh` (around line 1610, after Phase 8 skill install)

Add a new phase between skill install and summary:
- Detect if cwd is inside a git repo (`git rev-parse --show-toplevel`)
- If so, prompt (interactive) or auto-install (non-interactive with `--yes`) the copilot extension
- Add `--skip-copilot-extension` flag to bypass
- Call `"$kast_bin" install copilot-extension --target-dir="$repo_root/.github" --yes=true`

### 5C. Add `kast workspace ensure` to installer summary

File: `kast.sh` (in `_install_summary_phase`, around line 1321, and the non-wizard summary around line 1623)

Add to the "Next steps" output:
```
  cd /your/kotlin/project && kast workspace ensure
```

### 5D. Add global install manifest

Create: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/InstallManifest.kt`

```kotlin
@Serializable
data class InstallManifest(
    val version: String,
    val installedAt: String,  // ISO-8601
    val platform: String,
    val components: List<String>,
    val managedPaths: List<String>,  // relative to ~/.kast
    val shellRcPatches: List<ShellRcPatch>,
    val repos: List<ManagedRepo>,
)

@Serializable
data class ShellRcPatch(val file: String, val marker: String)

@Serializable
data class ManagedRepo(val path: String, val copilotExtensionVersion: String)
```

File: `kast.sh` — after the summary phase, write `$HOME/.kast/.manifest.json` with version, platform, components, managedPaths, and shellRcPatches. Use a python3 heredoc to write JSON.

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/InstallCopilotExtensionService.kt` — after a successful copilot-extension install, update the manifest's `repos` list.

### 5E. Fix destructive upgrade in `InstallEmbeddedResourceService`

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/InstallEmbeddedResourceService.kt` (lines 40-54)

When upgrading with `--yes=true`, the service calls `deletePathRecursively(targetPath)` which nukes the entire `.github` directory including user files (workflows, CODEOWNERS, etc.).

Change the upgrade logic to:
- Instead of deleting the entire target directory, iterate over `bundle.manifest` and delete only the managed files (same approach as `uninstallPackagedResources`)
- Then call `bundle.writeTree(targetPath)` to write the new versions
- Update the version marker

---

## Phase 6: Add `kast self` Subcommand Group

### 6A. Add CLI parsing

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/tty/CliCommandParser.kt`

Add parsing for `self status`, `self doctor`, `self uninstall`, `self upgrade` subcommands.

### 6B. Implement `SelfManagementService`

Create: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/SelfManagementService.kt`

- `status()`: Read `$HOME/.kast/.manifest.json`, print version, components, paths, managed repos
- `doctor()`: Verify binary exists and is executable, config.toml is valid TOML, all managedPaths exist, resolve scripts can find the binary, python3 is available (for hooks), standalone backend runtime libs exist (if installed)
- `uninstall()`: Read manifest, remove all managedPaths under `$HOME/.kast`, clean shell RC patches (remove the `# Added by the Kast installer` blocks), remove `$HOME/.kast` if empty, print summary
- `upgrade()`: For now, print instructions to re-run the curl one-liner. Future: download latest release, swap symlinks, re-sync AI primitives, update manifest.

Wire into `CliService.kt`'s command dispatch.

---

## Phase 7: Reduce `python3` Dependency in Hooks

### 7A. Replace python3 SHA-256 in `hook-state.sh`

File: `.github/hooks/hook-state.sh` (lines 4-16)

Replace the python3 heredoc with `shasum`/`sha256sum`:
```bash
hook_state_file() {
    local repo_root="$1"
    local session_key
    if command -v sha256sum >/dev/null 2>&1; then
        session_key="$(printf '%s' "${repo_root}" | sha256sum | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
        session_key="$(printf '%s' "${repo_root}" | shasum -a 256 | awk '{print $1}')"
    else
        session_key="$(printf '%s' "${repo_root}" | python3 -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.read().encode()).hexdigest())')"
    fi
    printf '%s/copilot-hook-paths-%s.txt\n' "${TMPDIR:-/tmp}" "${session_key}"
}
```

Apply the same pattern to `hook_skill_state_file` and `hook_shadowed_extension_state_file` in the same file.

### 7B. Promote `export-session.py` into the packaged manifest

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/EmbeddedCopilotExtensionResources.kt` (lines 52-55)

Remove `"hooks/export-session.py"` from `EXCLUDED_SOURCE_FILES` and add it to `MANIFEST`.

File: `kast-cli/build.gradle.kts` — update `embeddedCopilotHookFiles` to include `export-session.py`.

File: `.github/hooks/session-end.sh` (lines 38-155) — replace the inline session export block with a call to `python3 "${SCRIPT_DIR}/export-session.py"`, passing hook input via environment.

---

## Phase 8: Make `skill-shadowing.json` Portable

File: `.github/hooks/skill-shadowing.json` (lines 1-27)

The packaged version references skills (`refresh-affected-agents`, `llm-wiki`) that only exist in the kast repo. For the embedded/packaged version:

In `kast-cli/build.gradle.kts`, add a Gradle task that reads the source `skill-shadowing.json`, filters to only entries where `shadowingExtensionId` is present (i.e., `kast` and `kotlin-gradle-loop`), and writes the filtered version to the generated resources directory. Wire this into `syncPackagedCopilotExtensionResources`.

The kast repo's own `.github/hooks/skill-shadowing.json` keeps all entries unchanged.

---

## Phase 9: Post-Install Verification

File: `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/InstallCopilotExtensionService.kt`

After a successful copilot-extension install, add verification:
- Check that `hooks.json` is valid JSON
- Check that all shell scripts referenced in `hooks.json` exist and are executable
- Run `resolve-kast-cli-path.sh` to verify binary resolution works
- Check that `python3` is available (warn if not)

Add a `warnings: List<String>` field to `InstallCopilotExtensionResult` (in `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/results/InstallCopilotExtensionResult.kt`) and populate it with any verification failures.

---

## Phase 10: Documentation Updates

Files:
- `docs/getting-started/install.md` — update "Where kast stores configuration" to reflect new `$HOME/.kast` layout
- `docs/troubleshooting.md` — update path references
- `docs/for-agents/install-the-skill.md` — update global skill path from `~/.agents/skills/kast` to `~/.kast/lib/skills/kast`
- `docs/getting-started/backends.md` — update to mention auto-start behavior of `workspace ensure`

---

## Phase 11: Test Updates

- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/WorkspacePathsTest.kt` — workspace data now resolves under `$HOME/.kast/workspaces`, not config home
- `analysis-api/src/test/kotlin/io/github/amichne/kast/api/KastConfigTest.kt` — workspace directory resolver tests
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/CliServiceRuntimePathTest.kt` — verify new `installOptions()` defaults
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/EmbeddedCopilotExtensionResourcesTest.kt` — manifest audit catches `export-session.py` inclusion and `skill-shadowing.json` filtering
- `kast-cli/src/test/kotlin/io/github/amichne/kast/cli/InstallCopilotExtensionServiceTest.kt`:
  - Assert `export-session.py` exists after install
  - Add test: install into `.github` that already has `workflows/` subdirectory, upgrade with `--yes=true`, verify `workflows/` is preserved (non-destructive upgrade)
  - Add test: verification warnings are populated when `hooks.json` references missing scripts
- `.github/scripts/smoke-installer.sh` — update assertions for new manifest file and paths

---

## Phase 12: Migration for Existing Users

File: `kast.sh` (at the start of `cmd_install()`)

Add a one-time migration function that runs when the installer detects the old layout:
1. Detect old paths: `~/.local/bin/kast`, `~/.local/share/kast/instances`, `~/.agents/skills/kast`
2. Move/symlink them to new locations under `~/.kast`
3. Update config.toml paths if they reference old locations (e.g., `binaryPath = ~/.local/bin/kast` → `~/.kast/bin/kast`)
4. Print a migration summary showing what was moved

---

## Execution Order

The phases should be implemented roughly in this order due to dependencies:

1. **Phase 1** (on-disk layout) — foundational, everything else depends on canonical paths
2. **Phase 2** (resolve unification) — depends on Phase 1 for canonical path
3. **Phase 5A-5B** (PATH propagation + copilot-extension phase) — depends on Phase 1
4. **Phase 3** (auto-indexing) — depends on Phase 2 for reliable resolution
5. **Phase 4** (version mismatch recovery) — depends on Phase 2
6. **Phase 5C-5E** (manifest, summary, non-destructive upgrade) — can be parallel with 3-4
7. **Phase 6** (self commands) — depends on Phase 5D (manifest)
8. **Phase 7-8** (python3 reduction, portable skill-shadowing) — independent
9. **Phase 9** (post-install verification) — depends on Phase 2
10. **Phase 10-11** (docs, tests) — after all code changes
11. **Phase 12** (migration) — last, after new layout is stable
