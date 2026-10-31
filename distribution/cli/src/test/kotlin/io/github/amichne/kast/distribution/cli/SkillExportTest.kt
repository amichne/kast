package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

@EnabledOnOs(OS.MAC)
class SkillExportTest {
    @TempDir lateinit var temporary: Path

    @BeforeEach
    fun canonicalizeOwnedRoot() {
        temporary = temporary.toRealPath()
    }

    @Test
    fun `absent destination receives the complete skill and an identical retry does not write`() {
        val root = fixture()
        val parent = temporary.resolve("output with spaces")
        val sibling = parent.resolve("foreign")
        Files.createDirectories(sibling)
        Files.writeString(sibling.resolve("keep"), "foreign bytes")
        assertInstanceOf(ProvisionOutcome.Installed::class.java, exportCliSkill(root, parent))
        assertEquals("Read [Contract](references/contract.md).\n", Files.readString(parent.resolve("kast/SKILL.md")))
        assertEquals("independent fixture contract\n", Files.readString(parent.resolve("kast/references/contract.md")))
        val before = Files.getLastModifiedTime(parent.resolve("kast/SKILL.md"))
        assertInstanceOf(
            ProvisionOutcome.AlreadyInstalled::class.java,
            exportCliSkill(root, parent) { _, _ -> error("identical retry must not publish") },
        )
        assertEquals(before, Files.getLastModifiedTime(parent.resolve("kast/SKILL.md")))
        assertEquals("foreign bytes", Files.readString(sibling.resolve("keep")))
    }

    @Test
    fun `changed incomplete extra-file and extra-directory destinations preserve every byte`() {
        val root = fixture()
        val parent = temporary.resolve("output")
        assertInstanceOf(ProvisionOutcome.Installed::class.java, exportCliSkill(root, parent))
        Files.writeString(parent.resolve("kast/SKILL.md"), "user edit")
        assertConflict(root, parent)
        assertEquals("user edit", Files.readString(parent.resolve("kast/SKILL.md")))
        Files.copy(
            root.resolve("installation/$CLI_SKILL_RELATIVE_PATH/SKILL.md"),
            parent.resolve("kast/SKILL.md"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        Files.writeString(parent.resolve("kast/extra"), "keep extra")
        assertConflict(root, parent)
        assertEquals("keep extra", Files.readString(parent.resolve("kast/extra")))
        Files.delete(parent.resolve("kast/extra"))
        Files.createDirectory(parent.resolve("kast/empty-extra"))
        assertConflict(root, parent)
        Files.delete(parent.resolve("kast/empty-extra"))
        Files.delete(parent.resolve("kast/references/contract.md"))
        assertConflict(root, parent)
        assertFalse(Files.exists(parent.resolve("kast/references/contract.md")))
    }

    @Test
    fun `symlinks reject without a write through their target`() {
        val root = fixture()
        val foreign = temporary.resolve("foreign")
        Files.createDirectory(foreign)
        Files.writeString(foreign.resolve("keep"), "foreign")
        val parent = temporary.resolve("output")
        Files.createDirectory(parent)
        Files.createSymbolicLink(parent.resolve("kast"), foreign)
        val result = exportCliSkill(root, parent)
        assertEquals(ProvisionFailure.UNSUPPORTED_FILE_KIND, (result as ProvisionOutcome.Rejected).reason)
        assertEquals(listOf("keep"), Files.list(foreign).use { it.map { file -> file.fileName.toString() }.toList() })
    }

    @Test
    fun `a concurrent empty destination cannot be replaced`() {
        val root = fixture()
        val parent = temporary.resolve("output")
        val result =
            exportCliSkill(root, parent) { stage, destination ->
                Files.createDirectory(destination)
                publishDirectoryNoReplace(stage, destination)
            }
        assertEquals(ProvisionFailure.SKILL_EXISTS, (result as ProvisionOutcome.Rejected).reason)
        assertEquals(emptyList<Path>(), Files.list(parent.resolve("kast")).use { it.toList() })
        assertEquals(listOf("kast"), Files.list(parent).use { it.map { file -> file.fileName.toString() }.toList() })
    }

    @Test
    fun `publication failure and interruption leave no partial destination`() {
        val root = fixture()
        val parent = temporary.resolve("output")
        assertInstanceOf(
            ProvisionOutcome.Rejected::class.java,
            exportCliSkill(root, parent) { _, _ -> throw IOException("fixture publication failure") },
        )
        assertFalse(Files.exists(parent.resolve("kast")))
        assertThrows(InterruptedException::class.java) {
            exportCliSkill(root, parent) { _, _ -> throw InterruptedException("fixture interrupt") }
        }
        assertTrue(Files.list(parent).use { it.findAny().isEmpty })
    }

    @Test
    fun `manifest rejection precedes destination creation`() {
        val root = fixture()
        Files.writeString(root.resolve("installation/$CLI_SKILL_RELATIVE_PATH/SKILL.md"), "changed installed bytes")
        val parent = temporary.resolve("output")
        val result = exportCliSkill(root, parent)
        assertEquals(ProvisionStage.PAYLOAD_ADMISSION, (result as ProvisionOutcome.Rejected).stage)
        assertFalse(Files.exists(parent))
    }

    private fun assertConflict(root: Path, parent: Path) {
        val rejected = assertInstanceOf(ProvisionOutcome.Rejected::class.java, exportCliSkill(root, parent))
        assertEquals(ProvisionFailure.SKILL_EXISTS, rejected.reason)
    }

    private fun fixture(): Path {
        val fixture = createLifecycleFixture(temporary)
        val skill = fixture.installation.resolve(CLI_SKILL_RELATIVE_PATH)
        Files.createDirectories(skill.resolve("references"))
        Files.writeString(skill.resolve("SKILL.md"), "Read [Contract](references/contract.md).\n")
        Files.writeString(skill.resolve("references/contract.md"), "independent fixture contract\n")
        val manifest = (readBundledManifest(fixture.installation) as BundledManifestRead.Read).manifest
        val additions =
            Files.walk(skill).use {
                it.filter(Files::isRegularFile)
                    .map { file ->
                        BundledPayload(fixture.installation.relativize(file).toString(), "sha256:${sha256(file)}", 420)
                    }
                    .toList()
            }
        Files.writeString(
            fixture.installation.resolve("installation.json"),
            managementJson.encodeToString(manifest.copy(payloadFiles = manifest.payloadFiles + additions)),
        )
        return fixture.root
    }
}
