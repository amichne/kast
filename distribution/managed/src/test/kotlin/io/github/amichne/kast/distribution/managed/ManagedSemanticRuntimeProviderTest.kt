package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest
import io.github.amichne.kast.distribution.contract.SemanticRuntimeManifestAdmission
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSource
import io.github.amichne.kast.distribution.contract.SemanticRuntimeSourceSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManagedSemanticRuntimeProviderTest {
    @Test
    fun `preseeded runtime installs atomically and warm resolution needs no archive`(
        @TempDir temporary: Path,
    ) {
        val archive = runtimeArchive(temporary.resolve("runtime.zip"))
        val manifest = manifestFor(archive)
        val store = runtimeStore(temporary.resolve("store"))
        val provider = ManagedSemanticRuntimeProvider(store)
        val source = preseeded(archive)

        val cold = provider.resolve(manifest, source).installed()
        Files.delete(archive)
        val warm = provider.resolve(manifest, source).installed()

        assertEquals(cold.directory, warm.directory)
        assertTrue(Files.isExecutable(warm.executable))
        assertEquals(manifest.runtimeId, warm.runtimeId)
        assertFalse(Files.exists(store.path.resolve("${manifest.runtimeId.value}.download.partial")))
        assertFalse(
            Files.list(store.path).use { entries ->
                entries.anyMatch { it.fileName.toString().contains(".install.partial.") }
            },
        )
    }

    @Test
    fun `digest mismatch cannot become installed`(@TempDir temporary: Path) {
        val archive = runtimeArchive(temporary.resolve("runtime.zip"))
        val manifest = manifestFor(archive, archiveDigest = "sha256:${"0".repeat(64)}")
        val provider = ManagedSemanticRuntimeProvider(runtimeStore(temporary.resolve("store")))

        val resolution = provider.resolve(manifest, preseeded(archive))

        assertEquals(
            SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.DIGEST_MISMATCH),
            resolution,
        )
    }

    @Test
    fun `escaping archive entry cannot become installed`(@TempDir temporary: Path) {
        val archive = runtimeArchive(temporary.resolve("runtime.zip"), "../escape" to "bad")
        val manifest = manifestFor(archive)
        val provider = ManagedSemanticRuntimeProvider(runtimeStore(temporary.resolve("store")))

        val resolution = provider.resolve(manifest, preseeded(archive))

        assertEquals(
            SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.ARCHIVE_REJECTED),
            resolution,
        )
        assertFalse(Files.exists(temporary.resolve("escape")))
    }

    @Test
    fun `concurrent managed resolution downloads and publishes one exact runtime`(
        @TempDir temporary: Path,
    ) {
        val archive = runtimeArchive(temporary.resolve("runtime.zip"))
        val manifest = manifestFor(archive)
        val downloads = AtomicInteger()
        val downloadStarted = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        val downloader = RuntimeArtifactDownloader { _, target ->
            downloads.incrementAndGet()
            Files.copy(archive, target)
            downloadStarted.countDown()
            if (releaseDownload.await(5, TimeUnit.SECONDS)) {
                RuntimeArtifactAcquisition.Acquired
            } else {
                RuntimeArtifactAcquisition.Rejected(RuntimeStoreFailure.INTERRUPTED)
            }
        }
        val provider = ManagedSemanticRuntimeProvider(
            runtimeStore(temporary.resolve("store")),
            downloader,
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<SemanticRuntimeResolution> {
                provider.resolve(manifest, SemanticRuntimeSource.Managed)
            }
            assertTrue(downloadStarted.await(5, TimeUnit.SECONDS))
            val second = executor.submit<SemanticRuntimeResolution> {
                provider.resolve(manifest, SemanticRuntimeSource.Managed)
            }
            releaseDownload.countDown()

            val firstRuntime = first.get(5, TimeUnit.SECONDS).installed()
            val secondRuntime = second.get(5, TimeUnit.SECONDS).installed()
            assertEquals(1, downloads.get())
            assertEquals(firstRuntime.directory, secondRuntime.directory)
        } finally {
            releaseDownload.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `tampered visible runtime fails closed without reacquisition`(@TempDir temporary: Path) {
        val archive = runtimeArchive(temporary.resolve("runtime.zip"))
        val manifest = manifestFor(archive)
        val provider = ManagedSemanticRuntimeProvider(runtimeStore(temporary.resolve("store")))
        val installed = provider.resolve(manifest, preseeded(archive)).installed()
        Files.writeString(installed.executable, "tampered")
        Files.delete(archive)

        assertEquals(
            SemanticRuntimeResolution.Rejected(RuntimeStoreFailure.LAYOUT_INVALID),
            provider.resolve(manifest, preseeded(archive)),
        )
    }

    private fun runtimeStore(path: Path): RuntimeStore = when (val admitted = RuntimeStore.admit(path)) {
        is RuntimeStoreAdmission.Admitted -> admitted.store
        is RuntimeStoreAdmission.Rejected -> error("store rejected: ${admitted.failure}")
    }

    private fun preseeded(path: Path): SemanticRuntimeSource.PreseededArchive =
        when (val selected = SemanticRuntimeSource.select(path.toString())) {
            is SemanticRuntimeSourceSelection.Preseeded -> selected.source
            else -> error("preseeded source rejected: $selected")
        }

    private fun SemanticRuntimeResolution.installed(): InstalledSemanticRuntime =
        assertInstanceOf(SemanticRuntimeResolution.Installed::class.java, this).runtime

    private fun runtimeArchive(
        path: Path,
        vararg extras: Pair<String, String>,
    ): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val entries = listOf(
                "kast-indexer" to "#!/bin/sh\nexit 0\n",
                "runtime-libs/runtime.jar" to "runtime",
                "idea-home/product-info.json" to "{}",
                "idea-home/plugins/kast-indexer/plugin.jar" to "plugin",
            ) + extras
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return path
    }

    private fun manifestFor(
        archive: Path,
        archiveDigest: String = digest(archive),
    ): io.github.amichne.kast.distribution.contract.SemanticRuntimeManifest {
        val runtimeId = digestText(
            listOf(
                "macos",
                "aarch64",
                "261.25134.95",
                "2.4.10",
                "sha256:${"1".repeat(64)}",
                "kast-wire-v1",
                archiveDigest,
            ).joinToString("\n"),
        )
        val raw =
            "{\"schemaVersion\":1,\"runtimeId\":\"$runtimeId\",\"productVersion\":\"0.24.2\",\"platform\":\"macos\",\"architecture\":\"aarch64\",\"ideaBuild\":\"261.25134.95\",\"kotlinPluginBuild\":\"2.4.10\",\"kastPluginSha256\":\"sha256:${
                "1".repeat(
                    64
                )
            }\",\"wireSchemaId\":\"kast-wire-v1\",\"archive\":{\"fileName\":\"runtime.zip\",\"url\":\"https://example.invalid/runtime.zip\",\"sha256\":\"$archiveDigest\",\"bytes\":${
                Files.size(
                    archive
                )
            }},\"layout\":{\"executable\":\"kast-indexer\",\"requiredEntries\":[\"kast-indexer\",\"runtime-libs/\",\"idea-home/product-info.json\",\"idea-home/plugins/kast-indexer/\"],\"executableEntries\":[\"kast-indexer\"]}}"
        return when (val admitted = SemanticRuntimeManifest.admit(raw)) {
            is SemanticRuntimeManifestAdmission.Admitted -> admitted.manifest
            is SemanticRuntimeManifestAdmission.Rejected -> error("manifest rejected: ${admitted.failure}")
        }
    }

    private fun digest(path: Path): String = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)),
    )

    private fun digestText(value: String): String = "sha256:" + HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)),
    )
}
