## Repository: amichne/kast

This plan implements four independently-mergeable workstreams. Do them in order since WS2 depends on WS1.

---

### Workstream 1: Standalone gets its own distribution and lifecycle

**Goal:** `backend-standalone` ships as its own installable artifact that manages its own process lifecycle, registers its own descriptor, and can be discovered by the CLI without being bundled inside it.

1. **Add a distribution task to `backend-standalone/build.gradle.kts`.**
   - Add a `syncPortableDist` or equivalent task (similar to the one in `kast-cli/build.gradle.kts` lines 106-127) that produces a self-contained distribution: the standalone fat JAR (or runtime-libs + classpath.txt) plus a launcher script.
   - The distribution should be publishable as a release artifact alongside the CLI native binary and the IntelliJ plugin zip.

2. **Add a standalone launcher script.**
   - Create a `kast-standalone` shell wrapper (analogous to the `kast` CLI wrapper) that:
     - Locates `java` via `JAVA_HOME` or `PATH`
     - Launches `StandaloneMainKt` with the correct classpath from its own bundled `runtime-libs/classpath.txt`
     - Accepts the same `StandaloneServerOptions` arguments the daemon already accepts
   - This replaces the role that `ProcessLauncher.startDetached()` currently plays inside the CLI.

3. **Make the standalone self-registering (already done).**
   - Verify that `backend-standalone` already registers its `ServerInstanceDescriptor` in the `DescriptorRegistry` at `~/.kast/daemons.json` on startup. It does — no change needed here. The CLI's `WorkspaceRuntimeManager.inspectWorkspace()` already discovers backends via this registry.

---

### Workstream 2: Slim the CLI — remove standalone bundling and auto-launch

**Goal:** The CLI no longer bundles standalone JARs or spawns the daemon process. It discovers backends via the descriptor registry and fails with a helpful message if none is available.

1. **Remove `ProcessLauncher` daemon-spawning logic from `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/ProcessLauncher.kt`.**
   - Remove `DefaultProcessLauncher.startDetached()` and all the `resolveDetachedClassPath`, `runtimeLibClassPath*`, `runtimeLibDirectoryCandidates`, `detachedJavaCommand` helper functions (lines 22-201).
   - Keep the `ProcessLauncher` interface but make it a no-op or remove it entirely. The `CliService` constructor takes a `ProcessLauncher` — this parameter can be removed.

2. **Simplify `WorkspaceRuntimeManager` (`kast-cli/src/main/kotlin/io/github/amichne/kast/cli/WorkspaceRuntimeManager.kt`).**
   - Remove the `startStandaloneAndWait()` method (lines 143-181) and the `processLauncher` field.
   - In `ensureRuntime()` (line 63), where it currently falls through to `startStandaloneAndWait()` at line 136, instead throw a `CliFailure` with code `"STANDALONE_NOT_INSTALLED"` and a message like: `"No backend is running for this workspace. Start the standalone daemon with 'kast-standalone start --workspace-root=...' or open the project in IntelliJ with the Kast plugin."` This is the "standalone not found, install it" error path.
   - Keep the descriptor-based discovery (`inspectWorkspace`, `inspectDescriptor`, `selectServableCandidate`) — this is the mechanism that both the plugin and standalone already use.

3. **Remove the `internal daemon-run` command.**
   - In `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliExecution.kt`, remove the `CliCommand.InternalDaemonRun` branch (lines 264-272).
   - Remove the `internalDaemonRunner` parameter from `DefaultCliCommandExecutor` (line 40) and from `KastCli` constructor (`kast-cli/src/main/kotlin/io/github/amichne/kast/cli/KastCli.kt` line 18).
   - Remove the `CliCommandMetadata` entry for `internal daemon-run` in `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliCommandCatalog.kt` (lines 830-839).

4. **Remove standalone bundling from `kast-cli/build.gradle.kts`.**
   - Remove the `stageNativeRuntimeLibs` task (lines 96-104).
   - Remove the `runtime-libs` copy and validation from `syncPortableDist` (lines 106-127) — the CLI dist no longer needs standalone JARs.
   - Remove the `KAST_RUNTIME_LIBS` environment variable and `:backend-standalone:syncRuntimeLibs` dependency from the test task (lines 69-86). CLI tests should instead start a standalone daemon externally (or mock the descriptor registry).

5. **Remove `ProcessLauncher` from `CliService` constructor.**
   - In `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/CliService.kt` (line 45), remove the `processLauncher` parameter.
   - In `KastCli.kt` (lines 22-24), remove `configuredProcessLauncher` from the factory lambda.

---

### Workstream 3: Installer updates (`kast.sh`)

**Goal:** The installer installs the CLI (thin) + plugin by default, and can install the standalone backend as a separate component.

1. **Add a `_install_standalone_backend` function to `kast.sh`.**
   - Similar to `_install_intellij_plugin` (around line 1002), add a function that downloads the standalone backend distribution artifact from the GitHub release and unpacks it to `$KAST_INSTALL_ROOT/standalone/` (or similar).
   - The standalone launcher script from WS1 should end up on `PATH`.

2. **Change the default `--components` value.**
   - Currently defaults to `standalone` (which means CLI + bundled standalone). Change the default to install the thin CLI + the IntelliJ plugin. The standalone backend becomes an opt-in component (`--components=standalone` or `--components=all`).
   - Update the `--components` flag documentation in the install docs.

3. **Add a `kast install standalone` CLI command.**
   - Extend `InstallService` / `InstallOptions` in `kast-cli/src/main/kotlin/io/github/amichne/kast/cli/InstallService.kt` to support downloading and installing the standalone backend artifact, similar to how `install` already works for the CLI itself.

---

### Workstream 4: Documentation reframe

**Goal:** Docs should communicate that kast is for agents, the CLI is the agent's interface, and users configure kast by installing the plugin + skill.

1. **Rewrite `docs/index.md`.**
   - Lead with: "Kast gives your AI agent compiler-backed Kotlin intelligence."
   - The two entry points become: (1) Install the IntelliJ plugin (recommended when IDE is open), (2) Install the standalone backend (for CI/headless).
   - Remove language that implies humans should call `kast` commands directly. Frame the CLI as the agent's tool.

2. **Rewrite `docs/getting-started/install.md`.**
   - Lead with plugin installation as the primary path.
   - Frame standalone as "for environments without an IDE."
   - Remove the "Choose a runtime mode" table that presents them as equal peers. Instead: "Install the plugin. If you also need headless analysis, install the standalone backend."

3. **Rewrite `README.md`.**
   - Same reframe: lead with "give your agent compiler-backed Kotlin answers."
   - The install section should say: "Install the IntelliJ plugin for IDE-backed analysis. Install the standalone backend for terminal/CI/headless agents. Install the skill to let your agent use kast."
   - Remove `kast demo` as the first thing users see — move it to a "Try it yourself" section lower down.

4. **Update `docs/for-agents/index.md`.**
   - This is already agent-focused. Strengthen the messaging: "Your agent calls `kast skill ...` commands. You do not need to call kast directly."
   - Remove the "Direct CLI usage" link from the "Next steps" section (line 100), or relabel it as "Advanced: direct CLI usage (not recommended for most workflows)."

5. **Update `docs/for-agents/direct-cli.md`.**
   - Add a prominent note at the top: "Most agents should use the skill interface (`kast skill ...`). Direct CLI usage is for advanced integrations only."

6. **Update `docs/getting-started/backends.md`.**
   - Reframe from "two independent runtime modes" to "two backend options that serve the same contract." Emphasize that the user's choice is about their environment (IDE open vs. not), not about learning two different tools.
