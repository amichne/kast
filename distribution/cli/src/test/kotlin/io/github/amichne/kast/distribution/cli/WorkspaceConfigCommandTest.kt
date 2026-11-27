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
                WorkspaceDependencySharing.PRIVATE,
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
        assertEquals("SHARED", setting.getValue("value").jsonPrimitive.content)
        assertEquals("DEFAULT", setting.getValue("source").jsonPrimitive.content)
        val reuse = omitted.getValue("workspaceAccess").jsonObject
        assertEquals("REUSE_RECORDED", reuse.getValue("type").jsonPrimitive.content)
        assertEquals("PRIVATE", reuse.getValue("policy").jsonPrimitive.content)
        val conflict = invoke("set", key, "SHARED").getValue("workspaceAccess").jsonObject
        assertEquals("CONFIGURATION_CONFLICT", conflict.getValue("type").jsonPrimitive.content)
        assertEquals("PRIVATE", conflict.getValue("conflict").jsonObject.getValue("recorded").jsonPrimitive.content)
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
        inspect(listOf("show"), "DEFAULT", "SHARED")
        assertTrue(Files.notExists(overlay.parent))
        Files.createDirectories(overlay.parent)
        val other = "# keep this comment\nKAST_HOST_MAX_HEAP_MIB=2048\n"
        Files.writeString(overlay, other)
        inspect(listOf("set", key, "SHARED"), "SAVED_WORKSPACE", "SHARED")
        assertEquals(other + "$key=SHARED\n", Files.readString(overlay))
        inspect(listOf("explain", key), "SAVED_WORKSPACE", "SHARED")
        assertTrue(managementChild(temporary, listOf("config", "set", key, "typo") + args).first != 0)
        assertEquals(other + "$key=SHARED\n", Files.readString(overlay))
        inspect(listOf("unset", key), "DEFAULT", "SHARED")
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

    @Test
    fun `the Host heap is edited in place, admitted by its owner, and explains how to apply it`() {
        val (workspace, overlay) = installedWorkspace("my project")
        val args = listOf("--workspace", workspace.toString(), "--json")
        val heap = "KAST_HOST_MAX_HEAP_MIB"
        fun config(vararg command: String) =
            managementChild(temporary, listOf("config", *command) + args).let { (code, output) ->
                assertEquals(0, code, output)
                Json.parseToJsonElement(output).jsonObject
            }
        val unset = config("explain", heap).getValue("explanation").jsonObject
        assertEquals("null", unset.getValue("effective").toString())
        assertEquals("1024", unset.getValue("admittedRange").jsonObject.getValue("minimum").jsonPrimitive.content)
        assertEquals("NEXT_WORKER_LAUNCH", unset.getValue("applicationBoundary").jsonPrimitive.content)
        val quoted = "'$workspace'"
        assertEquals(
            "kast config set $heap <value> --workspace $quoted",
            unset.getValue("edit").jsonObject.getValue("set").jsonPrimitive.content,
        )
        assertTrue(unset.getValue("apply").jsonPrimitive.content.contains("kast stop --workspace $quoted"))

        Files.createDirectories(overlay.parent)
        Files.writeString(overlay, "# keep\n$heap=2048\nKAST_HOST_IDLE_SHUTDOWN=30\n")
        val raised = config("set", heap, "4096").getValue("explanation").jsonObject.getValue("effective").jsonObject
        assertEquals("4096", raised.getValue("value").jsonPrimitive.content)
        assertEquals("SAVED_WORKSPACE", raised.getValue("source").jsonPrimitive.content)
        assertEquals("# keep\n$heap=4096\nKAST_HOST_IDLE_SHUTDOWN=30\n", Files.readString(overlay))

        for (rejected in listOf("512", "40000", "4g", "")) {
            val (code, output) = managementChild(temporary, listOf("config", "set", heap, rejected) + args)
            assertTrue(code != 0, "$rejected was admitted: $output")
        }
        assertEquals("# keep\n$heap=4096\nKAST_HOST_IDLE_SHUTDOWN=30\n", Files.readString(overlay))

        config("unset", heap)
        assertEquals("# keep\nKAST_HOST_IDLE_SHUTDOWN=30\n", Files.readString(overlay))
        val (code, output) = managementChild(temporary, listOf("config", "set", "KAST_GRADLE_JAVA_HOME", "/jdk") + args)
        assertTrue(code != 0, output)
        assertEquals("# keep\nKAST_HOST_IDLE_SHUTDOWN=30\n", Files.readString(overlay))
    }

    @Test
    fun `each editable setting saves only the value its owner admits, in the owner's form`() {
        fun saved(setting: EditableWorkspaceSetting, raw: String) =
            when (val admitted = setting.admit(raw)) {
                is Refinement.Refined -> admitted.value.line
                is Refinement.Rejected -> admitted.failure.name
            }
        assertEquals("KAST_HOST_MAX_HEAP_MIB=8192", saved(EditableWorkspaceSetting.HOST_MAX_HEAP_MIB, "8192"))
        assertEquals("VALUE_REJECTED", saved(EditableWorkspaceSetting.HOST_MAX_HEAP_MIB, "1023"))
        assertEquals("VALUE_REJECTED", saved(EditableWorkspaceSetting.HOST_MAX_HEAP_MIB, "8g"))
        assertEquals("KAST_HOST_IDLE_SHUTDOWN=off", saved(EditableWorkspaceSetting.HOST_IDLE_SHUTDOWN, "off"))
        assertEquals("KAST_HOST_IDLE_SHUTDOWN=30", saved(EditableWorkspaceSetting.HOST_IDLE_SHUTDOWN, "30"))
        assertEquals("VALUE_REJECTED", saved(EditableWorkspaceSetting.HOST_IDLE_SHUTDOWN, "4"))
        assertEquals(
            "KAST_WORKSPACE_DEPENDENCY_SHARING=PRIVATE",
            saved(EditableWorkspaceSetting.WORKSPACE_DEPENDENCY_SHARING, "PRIVATE"),
        )
        assertEquals("VALUE_REJECTED", saved(EditableWorkspaceSetting.WORKSPACE_DEPENDENCY_SHARING, "private-ish"))
    }

    @Test
    fun `a setting the command cannot edit names where it can be set`() {
        val (workspace, _) = installedWorkspace("project")
        val (code, output) =
            managementChild(
                temporary,
                listOf("config", "explain", "KAST_GRADLE_JAVA_HOME", "--workspace", workspace.toString(), "--json"),
            )
        assertEquals(0, code, output)
        val explanation = Json.parseToJsonElement(output).jsonObject.getValue("explanation").jsonObject
        assertEquals("null", explanation.getValue("edit").toString())
        assertTrue(explanation.getValue("sources").jsonArray.any { it.jsonPrimitive.content == "SAVED_WORKSPACE" })
    }

    private fun installedWorkspace(name: String): Pair<Path, Path> {
        val home = temporary.toRealPath()
        val installation = Files.createDirectories(home.resolve(".local/share/kast/installation"))
        Files.writeString(
            installation.resolve("installation.json"),
            managementJson.encodeToString(BundledManifest(3, installation.toString(), emptyList())),
        )
        val workspace = Files.createDirectory(home.resolve(name))
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val id = MessageDigest.getInstance("SHA-256").digest(workspace.toString().toByteArray()).toHexString()
        return workspace to installation.resolve("config/workspaces/$id/environment")
    }
}
