package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerCommandTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `container help needs no installed payload or external process`() {
        val parsed = parseManagementCommand(listOf("container", "--help"))
        assertTrue(parsed is ManagementParsing.Print)
        val help = parsed as ManagementParsing.Print
        assertEquals(false, help.error)
        assertTrue(help.text.contains("Colima"))
        for (flag in listOf("--workspace", "--name", "--image", "--offline")) {
            assertTrue(help.text.contains(flag), flag)
        }
    }

    @Test
    fun `bare container opens the current worktree with defaults`() {
        val parsed = parseManagementCommand(listOf("container")) as ManagementParsing.Selected
        val command = (parsed.command as ManagementCommand.Container).command as ContainerCommand.Open
        assertEquals(Path.of("."), command.directory)
        assertEquals("default", command.name.value)
        assertEquals(ContainerImageSelection.Local, command.image)
        assertEquals(ContainerNetwork.ONLINE, command.network)
        assertEquals(ContainerTask.Shell, command.task)
    }

    @Test
    fun `both container status forms select the same passive operation`() {
        val expected = ManagementParsing.Selected(ManagementCommand.Container(ContainerCommand.Status))
        assertEquals(expected, parseManagementCommand(listOf("status", "container")))
        assertEquals(expected, parseManagementCommand(listOf("container", "status")))
        assertEquals(
            ManagementParsing.Selected(ManagementCommand.Status(true)),
            parseManagementCommand(listOf("status", "--json")),
        )
        for (args in listOf(listOf("status", "container"), listOf("container", "status"))) {
            val help = parseManagementCommand(args + "--help") as ManagementParsing.Print
            assertEquals(false, help.error)
            assertTrue(help.text.contains("without starting Colima"))
            val invalid = parseManagementCommand(args + listOf("--workspace", "/fixture")) as ManagementParsing.Print
            assertTrue(invalid.error)
        }
        val ignoredFlag = parseManagementCommand(listOf("status", "--json", "container")) as ManagementParsing.Print
        assertTrue(ignoredFlag.error)
    }

    @Test
    fun `run preserves literal arguments including option names and empty values`() {
        val literal = listOf("printf", "%s", "\$HOME; literal", "", "--image", "latest")
        val parsed = parseManagementCommand(listOf("container", "run", "--") + literal) as ManagementParsing.Selected
        val command = (parsed.command as ManagementCommand.Container).command as ContainerCommand.Open
        val task = command.task as ContainerTask.Run
        assertEquals(literal, listOf(task.executable) + task.arguments)
    }

    @Test
    fun `invalid commands and mutable image references reject during parsing`() {
        for (args in
            listOf(
                listOf("run"),
                listOf("export"),
                listOf("shell", "--image", "latest"),
                listOf("shell", "--name", "../foreign"),
                listOf("shell", "--unknown"),
                listOf("status", "--offline"),
            )) {
            val result = parseManagementCommand(listOf("container") + args)
            assertTrue(result is ManagementParsing.Print && result.error, args.toString())
        }
        assertTrue(ContainerImageSelection.Digest.parse("sha256:" + "a".repeat(64)) is Refinement.Refined)
        assertTrue(
            ContainerImageSelection.Digest.parse("registry.example/kast@sha256:" + "b".repeat(64)) is Refinement.Refined
        )
    }

    @Test
    fun `linked Git worktree preserves external metadata and distinct cache identity`() {
        val repository = temporary.toRealPath().resolve("source repository")
        Files.createDirectory(repository)
        git(repository, "init")
        git(
            repository,
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=fixture@example.invalid",
            "commit",
            "--allow-empty",
            "-m",
            "initial",
        )
        val linked = temporary.toRealPath().resolve("linked task")
        git(repository, "worktree", "add", "-b", "task", linked.toString())
        val nested = Files.createDirectory(linked.resolve("nested"))
        val workspace = (ContainerWorkspace.discover(nested) as Refinement.Refined).value
        assertEquals(linked, workspace.root)
        assertEquals(repository.resolve(".git"), workspace.commonDirectory)
        assertEquals(listOf(linked, repository.resolve(".git")), workspace.mounts)
        val name = (ContainerWorkspaceName.parse("default") as Refinement.Refined).value
        val original = (ContainerWorkspace.discover(repository) as Refinement.Refined).value
        assertNotEquals(
            original.key(name, ContainerArchitecture.ARM64),
            workspace.key(name, ContainerArchitecture.ARM64),
        )
        assertEquals(
            workspace.key(name, ContainerArchitecture.ARM64),
            (ContainerWorkspace.discover(linked) as Refinement.Refined).value.key(name, ContainerArchitecture.ARM64),
        )
        Files.writeString(linked.resolve(".git"), "gitdir: /missing/metadata\n")
        assertTrue(ContainerWorkspace.discover(linked) is Refinement.Rejected)
    }

    @Test
    fun `present invalid common metadata never becomes the private Git directory`() {
        val root = temporary.toRealPath().resolve("repository")
        Files.createDirectories(root.resolve(".git/objects"))
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/main\n")
        val common = root.resolve(".git/commondir")
        Files.writeString(common, ".\n")
        assertTrue(ContainerWorkspace.discover(root) is Refinement.Refined)
        for (content in listOf("", "\n", "x".repeat(4097))) {
            Files.writeString(common, content)
            assertEquals(Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE), ContainerWorkspace.discover(root))
        }
        Files.delete(common)
        val target = Files.writeString(temporary.resolve("common-path"), ".\n")
        Files.createSymbolicLink(common, target)
        assertEquals(Refinement.Rejected(ContainerFailure.INVALID_WORKSPACE), ContainerWorkspace.discover(root))
    }

    @Test
    fun `root help exposes the container command`() {
        val help = parseManagementCommand(listOf("--help")) as ManagementParsing.Print
        assertTrue(help.text.contains("container"))
    }

    private fun git(directory: Path, vararg arguments: String) {
        val child =
            ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments).redirectErrorStream(true).start()
        val output = child.inputStream.readAllBytes().decodeToString()
        assertEquals(0, child.waitFor(), output)
    }
}
