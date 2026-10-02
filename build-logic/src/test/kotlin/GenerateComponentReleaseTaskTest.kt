import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import support.tasks.GenerateComponentReleaseTask
import support.tasks.ReleaseComponent
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class GenerateComponentReleaseTaskTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `control release needs no host package and owns required contract`() {
        assertRecord(ReleaseComponent.CONTROL, "CONTROL_RELEASE", "controlVersion", "requiredHostedContract")
    }

    @Test
    fun `host release needs no control package and owns provided contract`() {
        assertRecord(ReleaseComponent.HOST, "HOST_RELEASE", "hostedPluginVersion", "providedHostedContract")
    }

    private fun assertRecord(component: ReleaseComponent, type: String, versionField: String, contractField: String) {
        val root = directory.toFile()
        val archive = root.resolve("component.zip").apply { writeText("owned component payload") }
        val contract = root.resolve("contract.json").apply { writeText(Json.encodeToString(ContractFixture(
            "HOSTED_CONTRACT", "kast.ide-hosted.runtime.v2", "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64), listOf("query.run"),
        ))) }
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task = project.tasks.register("release", GenerateComponentReleaseTask::class.java).get().apply {
            this.component.set(component)
            releaseVersion.set("2.1.0")
            sourceRevision.set("a".repeat(40))
            this.archive.set(archive)
            hostedContractFile.set(contract)
            if (component == ReleaseComponent.HOST) ideaReleaseLine.set("262")
            outputDirectory.set(root.resolve("release"))
        }
        task.generate()
        val output = task.outputDirectory.get().asFile
        val recordFile = output.resolve("${component.recordName}-v2.1.0.json")
        val record = Json.parseToJsonElement(recordFile.readText()).jsonObject
        assertEquals(type, record.getValue("type").jsonPrimitive.content)
        assertEquals("2.1.0", record.getValue(versionField).jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(contract.readText()), record.getValue(contractField))
        assertEquals("sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive.readBytes())),
            record.getValue("artifact").jsonObject.getValue("sha256").jsonPrimitive.content)
        assertTrue(output.resolve("component.zip.sha256").isFile)
        assertTrue(output.resolve("${recordFile.name}.sha256").isFile)
        if (component == ReleaseComponent.CONTROL) {
            assertFalse(record.containsKey("hostedPluginVersion"))
            assertFalse(record.containsKey("supportedIntellijReleaseLine"))
        } else {
            assertFalse(record.containsKey("controlVersion"))
            assertEquals("262", record.getValue("supportedIntellijReleaseLine").jsonPrimitive.content)
        }
    }

    @Serializable
    private data class ContractFixture(val type: String, val runtimeProtocolIdentity: String,
        val operationRegistryDigest: String, val wireSchemaDigest: String, val capabilities: List<String>)
}
