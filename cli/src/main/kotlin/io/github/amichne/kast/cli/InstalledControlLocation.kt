package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.InstallationLayout
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Path

/** Every Control entry runs on the JVM from `<installation>/lib`, so the code source locates the installation. */
internal fun discoverInstalledControlRoot(): Refinement<InstallationLayout, InstalledKastControlProductFailure> =
    jvmControlRoot()

internal fun admitNativeControlRoot(
    executable: Path
): Refinement<InstallationLayout, InstalledKastControlProductFailure> =
    if (
        !executable.isAbsolute ||
            executable.normalize() != executable ||
            !executable.endsWith(InstallationLayout.Relative.MANAGEMENT_EXECUTABLE.asPath())
    )
        Refinement.Rejected(InstalledKastControlProductFailure.NATIVE_LAYOUT_INVALID)
    else
        when (
            val layout = InstallationLayout.containing(executable, InstallationLayout.Relative.MANAGEMENT_EXECUTABLE)
        ) {
            null -> Refinement.Rejected(InstalledKastControlProductFailure.NATIVE_LAYOUT_INVALID)
            else -> Refinement.Refined(layout)
        }

private fun jvmControlRoot(): Refinement<InstallationLayout, InstalledKastControlProductFailure> =
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

private fun admitJvmControlRoot(codeSource: Path): Refinement<InstallationLayout, InstalledKastControlProductFailure> {
    val libraries = codeSource.parent
    return if (libraries == null || libraries.fileName.toString() != InstallationLayout.Relative.LIBRARIES.value)
        Refinement.Rejected(InstalledKastControlProductFailure.LIBRARY_DIRECTORY_INVALID)
    else
        when (
            val layout =
                InstallationLayout.containing(
                    codeSource,
                    InstallationLayout.Relative.library(codeSource.fileName.toString()),
                )
        ) {
            null -> Refinement.Rejected(InstalledKastControlProductFailure.PRODUCT_ROOT_UNAVAILABLE)
            else -> Refinement.Refined(layout)
        }
}
