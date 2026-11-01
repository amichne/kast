# Reload the local IntelliJ plugin

Use local reload for Kotlin code and resource changes inside the installed Host JAR layout.
This path supports macOS and IntelliJ IDEA `262.*`.
The IDE owns unload and load through its native dynamic-plugin lifecycle.

## Prepare the IDE once

1. Build the Host archive with the command in the next section.
2. In IntelliJ, select **Settings > Plugins > Install Plugin from Disk**.
3. Select the archive from `runtime/hosted/build/distributions/`.
4. Open **Help > Edit Custom VM Options**.
5. Add `-Didea.auto.reload.plugins=true` on a separate line.
6. Restart IntelliJ through its normal restart action.

The baseline Host must contain `IdeLifecycleStartup` with its dynamic-load listener and the platform-owned import callback.
An older Host can keep its `PluginClassLoader` alive after unload.
The first restart removes that older instance and starts the native file watcher.

## Repeat the local edit loop

Set these paths to your IntelliJ application and its active profile:

```bash
idea_home="$HOME/Applications/IntelliJ IDEA.app/Contents"
plugins_dir="$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.2/plugins"
bundle_version="0.0.local"
host_version="0.0.$(date -u +%s)"

mise exec -- ./gradlew :runtime:hosted:hostedPlugin \
  "-PhostedIdeaHome=$idea_home" "-Pversion=$bundle_version" \
  "-PhostedPluginVersion=$host_version"

uv run --locked --script packaging/reload_hosted.py \
  --archive "runtime/hosted/build/distributions/kast-ide-hosted-v$host_version-idea-262.zip" \
  --plugins-dir "$plugins_dir"
```

Keep `bundle_version` fixed from the initial installation through each reload.
For an existing baseline, use its component JAR version, such as the version in `kernel-<version>.jar`.
The default Git-derived version changes component filenames after a commit and causes `LAYOUT_CHANGED`.
Only `host_version` changes on each cycle.

Before the replacement, wait for workspace preparation and Gradle imports to finish.
The command rejects changes to the descriptor, hosted contract, or JAR layout.
It preserves the installed Host JAR filename because the native reload path keeps descriptor paths.
It replaces the complete `lib` directory with one atomic exchange.
Its `backup` field identifies the previous bundle.

The `STAGED` result proves file replacement only.
Return to IntelliJ to trigger its file refresh.
If needed, select **File > Reload All from Disk**, or press **Command+Option+Y**.
Wait for **Kast Existing IDE Runtime reloaded successfully**.

Request a fresh Kast query in the same agent session.
With verbose output, examine its `live.host` identity.
That identity must differ from the value before replacement.
Old symbol references belong to the previous Host and must be acquired again.
IntelliJ can need index updates before the fresh query succeeds.
The native reload notification does not prove semantic readiness.

## Limits and recovery

- `DESCRIPTOR_CHANGED`: Use ordinary installation for extension, service, listener, or dependency declarations.
- `CONTRACT_CHANGED`: Use ordinary installation for a changed SDK identity or hosted wire contract.
- `LAYOUT_CHANGED`: Use ordinary installation when a JAR filename enters or leaves the bundle.
- `AUTO_RELOAD_DISABLED`: Add the VM option, then restart IntelliJ once.
- `CONCURRENT_CHANGE`: Examine the installed bundle, then create a new replacement plan with the command.

Use [checkout installation](development.md#build-and-install-the-checkout) for changes outside these limits.
Restart IntelliJ after that installation.
To restore a compatible prior build, pass its original archive to the same reload command.
The command keeps backups under the profile's `.kast-plugin-reload` directory.

## Evidence scope

The local experiment used IntelliJ `262.10968.63` and JBR 25.
Two successive replacements kept IDE process `25699` and produced fresh application and project Host identities.
Each replacement completed a fresh Kotlin query through the existing agent connection.
An exact symbol reference from the previous Host rejected with `revalidation-unretained`.
The experiment also included a real Gradle import before unload.

The focused JVM tests cover callback ownership and import-completion order.
The Python tests cover archive admission, protected files on rejection, and the native atomic directory exchange.
These tests do not replace live IDE evidence.
Plugin replacement time and subsequent project preparation remain separate costs.

The platform documents [dynamic plugins](https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html) and [automatic reload](https://plugins.jetbrains.com/docs/intellij/ide-development-instance.html#enabling-auto-reload).
