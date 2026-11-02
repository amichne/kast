package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Path

/** Native discovery uses the actual process executable. JVM development keeps its archive-based admission. */
internal fun discoverInstalledControlRoot(): Refinement<Path, InstalledKastControlProductFailure> =
    if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") nativeControlRoot() else jvmControlRoot()

internal fun admitNativeControlRoot(executable: Path): Refinement<Path, InstalledKastControlProductFailure> =
    if (
        !executable.isAbsolute ||
            executable.normalize() != executable ||
            !executable.endsWith(Path.of("share/kast/libexec/kast-management"))
    )
        Refinement.Rejected(InstalledKastControlProductFailure.NATIVE_LAYOUT_INVALID)
    else
        when (val root = executable.parent?.parent?.parent?.parent) {
            null -> Refinement.Rejected(InstalledKastControlProductFailure.NATIVE_LAYOUT_INVALID)
            else -> Refinement.Refined(root)
        }

private fun nativeControlRoot(): Refinement<Path, InstalledKastControlProductFailure> =
    try {
        val command = ProcessHandle.current().info().command()
        if (command.isEmpty) Refinement.Rejected(InstalledKastControlProductFailure.NATIVE_EXECUTABLE_UNAVAILABLE)
        else admitNativeControlRoot(Path.of(command.get()).toRealPath())
    } catch (_: IOException) {
        Refinement.Rejected(InstalledKastControlProductFailure.NATIVE_EXECUTABLE_UNAVAILABLE)
    } catch (_: java.nio.file.InvalidPathException) {
        Refinement.Rejected(InstalledKastControlProductFailure.NATIVE_LAYOUT_INVALID)
    } catch (_: SecurityException) {
        Refinement.Rejected(InstalledKastControlProductFailure.NATIVE_EXECUTABLE_UNAVAILABLE)
    }

private fun jvmControlRoot(): Refinement<Path, InstalledKastControlProductFailure> =
    try {
        when (val location = InstalledKastCliComposition::class.java.protectionDomain.codeSource?.location) {
            null -> Refinement.Rejected(InstalledKastControlProductFailure.CODE_SOURCE_UNAVAILABLE)
            else -> admitJvmControlRoot(Path.of(location.toURI()).toRealPath())
        }
    } catch (_: IOException) {
        Refinement.Rejected(InstalledKastControlProductFailure.CODE_SOURCE_UNAVAILABLE)
    } catch (_: URISyntaxException) {
        Refinement.Rejected(InstalledKastControlProductFailure.CODE_SOURCE_INVALID)
    }

private fun admitJvmControlRoot(codeSource: Path): Refinement<Path, InstalledKastControlProductFailure> {
    val libraries = codeSource.parent
    return if (libraries == null || libraries.fileName.toString() != "lib")
        Refinement.Rejected(InstalledKastControlProductFailure.LIBRARY_DIRECTORY_INVALID)
    else
        when (val root = libraries.parent) {
            null -> Refinement.Rejected(InstalledKastControlProductFailure.PRODUCT_ROOT_UNAVAILABLE)
            else -> Refinement.Refined(root)
        }
}
