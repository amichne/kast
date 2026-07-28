package io.github.amichne.kast.api.client.fields

import io.github.amichne.kast.api.client.resolveKastPathDefaults
import java.nio.file.Path

internal fun defaultConfigInstallRoot(): Path =
    resolveKastPathDefaults().installRoot

internal fun defaultConfigBinDir(): Path =
    resolveKastPathDefaults().binDir

internal fun defaultConfigLibDir(installRoot: String? = null): Path =
    installRoot?.let(Path::of)?.resolve("current/lib")
        ?: resolveKastPathDefaults().libDir

internal fun defaultConfigCacheDir(): Path =
    resolveKastPathDefaults().cacheDir

internal fun defaultConfigLogsDir(): Path =
    resolveKastPathDefaults().logsDir

internal fun defaultConfigRuntimeDir(installRoot: String? = null): Path =
    installRoot?.let(Path::of)?.resolve("state/runtime")
        ?: resolveKastPathDefaults().runtimeDir

internal fun defaultConfigDescriptorDir(runtimeDir: String? = null): Path =
    runtimeDir?.let(Path::of)?.resolve("daemons")
        ?: resolveKastPathDefaults().descriptorDir

internal fun defaultConfigSocketDir(runtimeDir: String? = null): String =
    runtimeDir ?: resolveKastPathDefaults().socketDir.toString()

internal fun defaultConfigCliBinaryPath(binDir: String? = null): Path =
    binDir?.let(Path::of)?.resolve("kast")
        ?: resolveKastPathDefaults().cliBinary

internal fun defaultConfigHeadlessRuntimeLibsDir(libDir: String? = null): Path =
    libDir?.let(Path::of)?.resolve("backends/headless/current/runtime-libs")
        ?: resolveKastPathDefaults().headlessRuntimeLibsDir
