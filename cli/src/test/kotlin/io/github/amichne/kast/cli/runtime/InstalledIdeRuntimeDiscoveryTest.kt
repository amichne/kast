package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstalledIdeRuntimeDiscoveryTest {
    @Test
    fun `explicit home refines exact IDEA Kotlin JBR and platform evidence`(
        @TempDir temporary: Path,
    ) {
        val home = ideaHome(temporary.resolve("idea"))

        val discovered = InstalledIdeRuntimeDiscovery.discover(
            support = supportedPair(),
            kastPayloadDigest = digest('a'),
            selection = IdeHomeSelection.Explicit(home),
        ).discovered()

        assertEquals(home.toRealPath(), discovered.home)
        assertEquals(
            home.resolve("jbr/Contents/Home/bin/java").toRealPath(),
            discovered.javaExecutable,
        )
        assertEquals("jbr-25.0.3+9-b508.16-aarch64", discovered.identity.jbrIdentity)
        assertEquals(digest('a'), discovered.identity.kastPayloadDigest)
    }

    @Test
    fun `compatible patch builds retain their exact installed runtime identity`(
        @TempDir temporary: Path,
    ) {
        val home = ideaHome(
            temporary.resolve("patched-idea"),
            ideaBuild = "262.9999.41",
            kotlinBuild = "262.8888.17-IJ",
        )

        val discovered = InstalledIdeRuntimeDiscovery.discover(
            support = supportedPair(),
            kastPayloadDigest = digest('b'),
            selection = IdeHomeSelection.Explicit(home),
        ).discovered()

        assertEquals("262.9999.41", discovered.identity.supportedPair.ideaBuild)
        assertEquals(
            "262.8888.17-IJ",
            discovered.identity.supportedPair.kotlinPluginBuild,
        )
        assertEquals("jbr-25.0.3+9-b508.16-aarch64", discovered.identity.jbrIdentity)
        assertEquals(digest('b'), discovered.identity.kastPayloadDigest)
    }

    @Test
    fun `automatic discovery fails closed for missing and ambiguous matches`(
        @TempDir temporary: Path,
    ) {
        assertEquals(
            IndexSeedFailure.MissingInstallation,
            InstalledIdeRuntimeDiscovery.discover(
                supportedPair(),
                digest('a'),
                IdeHomeSelection.Standard(emptyList()),
            ).rejected(),
        )

        val first = ideaHome(temporary.resolve("first"))
        val second = ideaHome(temporary.resolve("second"))
        assertEquals(
            IndexSeedFailure.Ambiguity,
            InstalledIdeRuntimeDiscovery.discover(
                supportedPair(),
                digest('a'),
                IdeHomeSelection.Standard(listOf(first, second)),
            ).rejected(),
        )
    }

    @Test
    fun `incompatible build and incomplete layout retain finite rejection`(
        @TempDir temporary: Path,
    ) {
        val incompatible = ideaHome(temporary.resolve("incompatible"), ideaBuild = "261.1")
        val incompatibleFailure = InstalledIdeRuntimeDiscovery.discover(
            supportedPair(),
            digest('a'),
            IdeHomeSelection.Explicit(incompatible),
        ).rejected()
        assertTrue(incompatibleFailure is IndexSeedFailure.Incompatibility)

        val incomplete = ideaHome(temporary.resolve("incomplete"))
        Files.delete(incomplete.resolve("lib/intellij.platform.ide.core.jar"))
        assertEquals(
            IndexSeedFailure.ValidationFailure,
            InstalledIdeRuntimeDiscovery.discover(
                supportedPair(),
                digest('a'),
                IdeHomeSelection.Explicit(incomplete),
            ).rejected(),
        )

        val incompleteGradle = ideaHome(temporary.resolve("incomplete-gradle"))
        Files.delete(incompleteGradle.resolve("plugins/gradle-java-plugin/lib"))
        Files.delete(incompleteGradle.resolve("plugins/gradle-java-plugin"))
        assertEquals(
            IndexSeedFailure.ValidationFailure,
            InstalledIdeRuntimeDiscovery.discover(
                supportedPair(),
                digest('a'),
                IdeHomeSelection.Explicit(incompleteGradle),
            ).rejected(),
        )
    }

    private fun ideaHome(
        home: Path,
        ideaBuild: String = "262.9437.185",
        kotlinBuild: String = "262.9437.185-IJ",
    ): Path {
        Files.createDirectories(home.resolve("Resources"))
        Files.writeString(home.resolve("Resources/build.txt"), "IU-$ideaBuild\n")

        val kotlinJar = home.resolve("plugins/Kotlin/lib/kotlin-plugin.jar")
        writeJar(kotlinJar, "META-INF/plugin.xml", "<idea-plugin><version>$kotlinBuild</version></idea-plugin>")
        writeJar(
            home.resolve("lib/intellij.platform.bootstrap.jar"),
            "com/intellij/idea/Main.class",
            "main",
        )
        writeJar(
            home.resolve("lib/intellij.platform.ide.core.jar"),
            "com/intellij/openapi/application/ApplicationStarter.class",
            "starter",
        )
        Files.createDirectories(home.resolve("plugins/gradle-plugin/lib"))
        Files.createDirectories(home.resolve("plugins/gradle-java-plugin/lib"))

        val javaHome = home.resolve("jbr/Contents/Home")
        val java = javaHome.resolve("bin/java")
        Files.createDirectories(java.parent)
        Files.writeString(java, "#!/usr/bin/env bash\nexit 0\n")
        Files.setPosixFilePermissions(
            java,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        Files.writeString(
            javaHome.resolve("release"),
            "JAVA_RUNTIME_VERSION=\"25.0.3+9-b508.16\"\nOS_ARCH=\"aarch64\"\n",
        )
        return home.toRealPath()
    }

    private fun writeJar(path: Path, entry: String, content: String) {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { archive ->
            archive.putNextEntry(ZipEntry(entry))
            archive.write(content.toByteArray())
            archive.closeEntry()
        }
    }

    private fun supportedPair(): SupportedIdeRuntimePair = SupportedIdeRuntimePair.admit(
        "262.9437.185",
        "262.9437.185-IJ",
    ).let { (it as SupportedIdeRuntimePairAdmission.Admitted).pair }

    private fun digest(character: Char): String = "sha256:${character.toString().repeat(64)}"
}

private fun InstalledIdeRuntimeDiscoveryResult.discovered(): InstalledIdeRuntime =
    (this as InstalledIdeRuntimeDiscoveryResult.Discovered).runtime

private fun InstalledIdeRuntimeDiscoveryResult.rejected(): IndexSeedFailure =
    (this as InstalledIdeRuntimeDiscoveryResult.Rejected).failure
