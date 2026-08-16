package io.github.amichne.kast.cli

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

private const val RUNTIME_DIRECTORY_ENVIRONMENT = "KAST_RUNTIME_DIRECTORY"

/** The sole service-loaded composition for an installed Kotlin `kast` executable. */
class InstalledKastCliComposition : KastCliComposition {
    override fun create(): KastCli {
        val installation = InstalledKastProduct.discover()
        val projections = when (val construction = CliProjectionTable.create(canonicalCliProjections())) {
            is CliProjectionTableConstruction.Created -> construction.table
            is CliProjectionTableConstruction.Rejected -> error(
                "canonical CLI projection table is incomplete: ${construction.failures}",
            )
        }
        val executable = when (val admitted = IndexerExecutable.admit(installation.indexer)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error("installed indexer is unavailable: ${admitted.failure}")
        }
        val runtimeDirectory = InstalledRuntimeDirectory.prepare()
        return KastCli(
            projections,
            FilesystemCanonicalRootDiscovery,
            Sha256RuntimeEndpointLocator(runtimeDirectory.path),
            ExactRootProcessRuntimeDemander(executable),
            UnixDomainWireClient(),
        )
    }
}

/** One installation root proven by the CLI jar and its exact packaged indexer path. */
private class InstalledKastProduct private constructor(
    val indexer: Path,
) {
    companion object {
        /**
         * Proof transition: `InstalledKastCliComposition code source -> InstalledKastProduct`.
         *
         * Establishes that the provider was loaded from the installation's `lib` directory and
         * derives only its sibling packaged indexer. Unexpected layouts fail the service-provider
         * construction boundary. Raw URI and paths remain inside this installed-product adapter.
         */
        fun discover(): InstalledKastProduct {
            val codeSource = try {
                Path.of(
                    InstalledKastCliComposition::class.java.protectionDomain.codeSource.location
                        .toURI(),
                ).toRealPath()
            } catch (failure: IOException) {
                throw IllegalStateException("installed CLI code source is unavailable", failure)
            } catch (failure: URISyntaxException) {
                throw IllegalStateException("installed CLI code source is invalid", failure)
            }
            val libraryDirectory = codeSource.parent
                ?.takeIf { it.fileName.toString() == "lib" }
                ?: error("installed CLI must be loaded from its lib directory")
            val root = libraryDirectory.parent
                ?: error("installed CLI lib directory has no product root")
            return InstalledKastProduct(
                root.resolve("libexec/kast-indexer/kast-indexer").normalize(),
            )
        }
    }
}

/** An absolute runtime directory created before exact-root socket derivation. */
private class InstalledRuntimeDirectory private constructor(
    val path: Path,
) {
    companion object {
        /**
         * Proof transition: `KAST_RUNTIME_DIRECTORY | java.io.tmpdir -> InstalledRuntimeDirectory`.
         *
         * Establishes an absolute, normalized, physically available directory owned by the CLI
         * runtime boundary. Filesystem or malformed-path failures reject provider construction.
         * Raw environment and system-property text are extracted only here.
         */
        fun prepare(): InstalledRuntimeDirectory {
            val configured = System.getenv(RUNTIME_DIRECTORY_ENVIRONMENT)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: Path.of(System.getProperty("java.io.tmpdir")).resolve("kast-runtime")
            val normalized = configured.toAbsolutePath().normalize()
            val physical = try {
                Files.createDirectories(normalized).toRealPath()
            } catch (failure: IOException) {
                throw IllegalStateException("installed runtime directory is unavailable", failure)
            } catch (failure: SecurityException) {
                throw IllegalStateException("installed runtime directory is unavailable", failure)
            }
            return InstalledRuntimeDirectory(physical)
        }
    }
}
