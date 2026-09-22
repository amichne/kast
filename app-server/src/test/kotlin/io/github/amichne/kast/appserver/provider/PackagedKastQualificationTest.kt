package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackagedKastQualificationTest {
    @Test
    fun `qualification reads packaged catalog without executing Kast`(@TempDir root: Path) = runTest {
        val executable = Files.createDirectories(root.resolve("bin")).resolve("kast")
        val marker = root.resolve("executed")
        Files.writeString(executable, "#!/bin/sh\ntouch '${marker}'\nexit 97\n")
        check(executable.toFile().setExecutable(true))
        val catalog = Files.createDirectories(root.resolve("share/kast")).resolve("provider-catalog.json")
        Files.writeString(catalog, installedKastCatalogFixture())
        val options =
            KastProviderOptions(
                catalogSource =
                    io.github.amichne.kast.appserver.provider.PackagedKastCatalog(
                        executable.parent.parent.resolve("share/kast/provider-catalog.json")
                    )
            )
        assertInstanceOf(KastProviderQualification.Qualified::class.java, KastProviderQualifier.qualify(options))
        assertFalse(Files.exists(marker))
    }

    @Test
    fun `packaged catalog bounds bytes and rejects absent symlink and invalid UTF8`(@TempDir root: Path) {
        val catalog = root.resolve("catalog.json")
        val source = PackagedKastCatalog(catalog)
        assertEquals(Refinement.Rejected(KastQualificationFailure.SCHEMA_UNAVAILABLE), source.read())
        Files.createDirectory(catalog)
        assertEquals(Refinement.Rejected(KastQualificationFailure.SCHEMA_UNAVAILABLE), source.read())
        Files.delete(catalog)
        val target = Files.writeString(root.resolve("target"), "irrelevant target")
        Files.createSymbolicLink(catalog, target)
        assertEquals(Refinement.Rejected(KastQualificationFailure.SCHEMA_UNAVAILABLE), source.read())
        Files.delete(catalog)
        Files.write(catalog, byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals(Refinement.Rejected(KastQualificationFailure.SCHEMA_INVALID), source.read())
        val maximum = io.github.amichne.kast.appserver.BrokerOperationalLimits.maximumKastSchemaBytes
        Files.writeString(catalog, " ".repeat(maximum))
        assertEquals(maximum, (source.read() as Refinement.Refined).value.length)
        Files.writeString(catalog, " ".repeat(maximum + 1))
        assertEquals(Refinement.Rejected(KastQualificationFailure.SCHEMA_SIZE_LIMIT), source.read())
    }
}
