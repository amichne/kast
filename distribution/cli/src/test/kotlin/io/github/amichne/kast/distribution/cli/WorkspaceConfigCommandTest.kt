package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.kernel.Refinement
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceConfigCommandTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `inspection distinguishes recorded sharing from the creation default without a Host`() {
        val home = temporary.toRealPath()
        val share = Files.createDirectories(home.resolve(".local/share"))
        val product = productFixture(share)
        val workspace = Files.createDirectory(home.resolve("project"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val store = (containerStore(share.resolve("kast-workspaces")) as Refinement.Refined).value
        val creation =
            store.admitCreation(
                ContainerCommand.Create(null, null, null, false),
                workspace,
                WorkspaceDependencySharing.SHARED,
            )
        val recorded = store.create((creation as Refinement.Refined).value, product.runtime) as Refinement.Refined
        val generated = recorded.value.configuration
        val before = Files.readString(generated)
        val key = "KAST_WORKSPACE_DEPENDENCY_SHARING"
        val args = listOf("--workspace", workspace.toString(), "--json")
        fun invoke(vararg command: String) =
            managementChild(temporary, listOf("config", *command) + args).let { (code, output) ->
                assertEquals(0, code, output)
                Json.parseToJsonElement(output).jsonObject
            }
        val omitted = invoke("explain", key)
        val setting = omitted.getValue("settings").jsonArray.single().jsonObject
        assertEquals("PRIVATE", setting.getValue("value").jsonPrimitive.content)
        assertEquals("DEFAULT", setting.getValue("source").jsonPrimitive.content)
        val reuse = omitted.getValue("workspaceAccess").jsonObject
        assertEquals("REUSE_RECORDED", reuse.getValue("type").jsonPrimitive.content)
        assertEquals("SHARED", reuse.getValue("policy").jsonPrimitive.content)
        val conflict = invoke("set", key, "PRIVATE").getValue("workspaceAccess").jsonObject
        assertEquals("CONFIGURATION_CONFLICT", conflict.getValue("type").jsonPrimitive.content)
        assertEquals("SHARED", conflict.getValue("conflict").jsonObject.getValue("recorded").jsonPrimitive.content)
        val restored = invoke("unset", key).getValue("workspaceAccess").jsonObject
        assertEquals(reuse, restored)
        assertEquals(before, Files.readString(generated))
    }

    @Test
    fun `workspace config reports its source and edits only the workspace overlay`() {
        val home = temporary.toRealPath()
        val installation = Files.createDirectories(home.resolve(".local/share/kast/installation"))
        Files.writeString(
            installation.resolve("installation.json"),
            managementJson.encodeToString(BundledManifest(3, installation.toString(), emptyList())),
        )
        val workspace = Files.createDirectory(home.resolve("project"))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val id = MessageDigest.getInstance("SHA-256").digest(workspace.toString().toByteArray()).toHexString()
        val overlay = installation.resolve("config/workspaces/$id/environment")
        val args = listOf("--workspace", workspace.toString(), "--json")
        val key = "KAST_WORKSPACE_DEPENDENCY_SHARING"
        fun inspect(command: List<String>, source: String, value: String) {
            val (code, output) = managementChild(temporary, listOf("config") + command + args)
            assertEquals(0, code, output)
            val result = Json.parseToJsonElement(output).jsonObject
            assertEquals(overlay.toString(), result.getValue("workspaceFile").jsonPrimitive.content)
            val setting =
                result
                    .getValue("settings")
                    .jsonArray
                    .single { it.jsonObject["key"]?.jsonPrimitive?.content == key }
                    .jsonObject
            assertEquals(source, setting.getValue("source").jsonPrimitive.content)
            assertEquals(value, setting.getValue("value").jsonPrimitive.content)
        }
        inspect(listOf("show"), "DEFAULT", "PRIVATE")
        assertTrue(Files.notExists(overlay.parent))
        Files.createDirectories(overlay.parent)
        val other = "# keep this comment\nKAST_HOST_MAX_HEAP_MIB=2048\n"
        Files.writeString(overlay, other)
        inspect(listOf("set", key, "SHARED"), "SAVED_WORKSPACE", "SHARED")
        assertEquals(other + "$key=SHARED\n", Files.readString(overlay))
        inspect(listOf("explain", key), "SAVED_WORKSPACE", "SHARED")
        assertTrue(managementChild(temporary, listOf("config", "set", key, "typo") + args).first != 0)
        assertEquals(other + "$key=SHARED\n", Files.readString(overlay))
        inspect(listOf("unset", key), "DEFAULT", "PRIVATE")
        assertEquals(other, Files.readString(overlay))
        val installationFile = Files.writeString(installation.resolve("config/environment"), "$key=SHARED\n")
        inspect(listOf("set", key, "PRIVATE"), "SAVED_WORKSPACE", "PRIVATE")
        for (name in listOf("management.lock", "activation.lock")) {
            val lock = installation.parent.resolve(name)
            FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val (code, output) = managementChild(temporary, listOf("config", "unset", key) + args)
                    assertTrue(code != 0, "$name did not exclude a workspace edit: $output")
                }
            }
        }
        inspect(listOf("explain", key), "SAVED_WORKSPACE", "PRIVATE")
        inspect(listOf("unset", key), "SAVED_INSTALLATION", "SHARED")
        assertEquals("$key=SHARED\n", Files.readString(installationFile))
        Files.delete(overlay)
        val generated = Files.writeString(home.resolve("generated-environment"), "keep")
        Files.createSymbolicLink(overlay, generated)
        assertTrue(managementChild(temporary, listOf("config", "set", key, "SHARED") + args).first != 0)
        assertEquals("keep", Files.readString(generated))
    }
}
