package io.github.amichne.kast.api.client.fields

import java.nio.file.Path

private val userHome: Path
    get() = Path.of(System.getProperty("user.home"))

internal fun defaultConfigInstallRoot(): Path =
    System.getenv("KAST_INSTALL_ROOT")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(Path::of)
        ?: userHome.resolve(".local/share/kast")
internal fun defaultConfigBinDir(installRoot: String = defaultConfigInstallRoot().toString()): Path = userHome.resolve(".local/bin")
internal fun defaultConfigLibDir(installRoot: String = defaultConfigInstallRoot().toString()): Path = Path.of(installRoot).resolve("current/lib")
internal fun defaultConfigCacheDir(installRoot: String = defaultConfigInstallRoot().toString()): Path = userHome.resolve(".cache/kast")
internal fun defaultConfigLogsDir(installRoot: String = defaultConfigInstallRoot().toString()): Path = userHome.resolve(".local/state/kast/logs")
internal fun defaultConfigDescriptorDir(runtimeDir: String = defaultConfigInstallRoot().resolve("runtime").toString()): Path = Path.of(runtimeDir).resolve("daemons")
internal fun defaultConfigSocketDir(): String = defaultConfigInstallRoot().resolve("runtime").toString()
internal fun defaultConfigCliBinaryPath(binDir: String = defaultConfigBinDir().toString()): Path = Path.of(binDir).resolve("kast")
internal fun defaultConfigHeadlessRuntimeLibsDir(libDir: String = defaultConfigLibDir().toString()): Path =
    Path.of(libDir).resolve("backends/headless/current/runtime-libs")
