package io.github.amichne.kast.appserver

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkspaceRegistrationCommandTest {
    @Test
    fun `registration preserves desired roots without starting or configuring Codex`(@TempDir temporary: Path) {
        val installation = Files.createDirectories(temporary.resolve("installation/bin")).parent.toRealPath()
        val kast = Files.writeString(installation.resolve("bin/kast"), "fixture")
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val home = Files.createDirectory(temporary.resolve("home"))
        val manager = InstalledAppServerManager(kast, home, emptyMap())
        val result = manager.execute(AppServerAction.Register, workspace)
        assertTrue(result is AppServerManagementResult.Completed, result.toString())
        val document = (result as AppServerManagementResult.Completed).document
        assertEquals(workspace.toString(), document["root"]?.jsonPrimitive?.content)
        assertTrue(document["workspaceId"]?.jsonPrimitive?.content?.matches(Regex("[a-f0-9]{64}")) == true)
        assertEquals("1", document["revision"]?.jsonPrimitive?.content)
        assertTrue(Files.isRegularFile(installation.resolve("config/workspaces.json")))
        assertFalse(Files.exists(installation.resolve("state")))
        assertFalse(Files.exists(home.resolve("Library")))
        assertEquals(document, (manager.execute(AppServerAction.Register, workspace) as AppServerManagementResult.Completed).document)
    }
}
