package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Path

/** Every Control entry runs on the JVM from `<installation>/lib`, so the code source locates the installation. */
internal fun discoverInstalledControlRoot(): Refinement<Path, InstalledKastControlProductFailure> = jvmControlRoot()

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
