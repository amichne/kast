package io.github.amichne.kast.api.client.fields

import java.nio.file.Path

private val userHome: Path
    get() = Path.of(System.getProperty("user.home"))

internal fun defaultConfigInstallRoot(): Path = userHome.resolve(".kast")
internal fun defaultConfigBinDir(installRoot: String = defaultConfigInstallRoot().toString()): Path = Path.of(installRoot).resolve("bin")
internal fun defaultConfigLibDir(installRoot: String = defaultConfigInstallRoot().toString()): Path = Path.of(installRoot).resolve("lib")
internal fun defaultConfigCacheDir(installRoot: String = defaultConfigInstallRoot().toString()): Path = Path.of(installRoot).resolve("cache")
internal fun defaultConfigLogsDir(installRoot: String = defaultConfigInstallRoot().toString()): Path = Path.of(installRoot).resolve("logs")
internal fun defaultConfigDescriptorDir(cacheDir: String = defaultConfigCacheDir().toString()): Path = Path.of(cacheDir).resolve("daemons")
internal fun defaultConfigSocketDir(): String = System.getProperty("java.io.tmpdir")
internal fun defaultConfigCliBinaryPath(binDir: String = defaultConfigBinDir().toString()): Path = Path.of(binDir).resolve("kast")
internal fun defaultConfigHeadlessRuntimeLibsDir(libDir: String = defaultConfigLibDir().toString()): Path =
    Path.of(libDir).resolve("backends/headless/current/runtime-libs")
