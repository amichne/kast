package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerRuntimeTest {
    @TempDir lateinit var temporary: Path
    private val digest = "sha256:" + "a".repeat(64)
    private val image = "registry.example/kast@$digest"
    private val runner = listOf("/fixture/colima", "nerdctl", "--profile", "kast-native", "--")

    @Test
    fun `a foreign named container rejects before image lookup or pull`() {
        val fixture = fixture()
        val calls = ArrayDeque(commonCalls(fixture))
        calls.add(
            runner + listOf("inspect", fixture.container.name) to
                read(fixture.container.copy(config = ContainerConfig("developer")))
        )
        val result = runtime(calls).prepare(fixture.command, fixture.workspace)
        assertEquals(Refinement.Rejected(ContainerFailure.WORKSPACE_CONFLICT), result)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a retained image uses local digest inventory without a registry call`() {
        val fixture = fixture()
        val calls = ArrayDeque(commonCalls(fixture))
        calls.add(runner + listOf("inspect", fixture.container.name) to read(fixture.container))
        calls.add(runner + listOf("images", "--quiet", "--no-trunc") to Refinement.Refined("$digest\n"))
        calls.add(
            inspectImage(image) to
                Refinement.Refined(
                    nativeImage + "\n" + nativeImage.replace("registry.example/kast:arm64", image) + "\n"
                )
        )
        val result = runtime(calls).prepare(fixture.command, fixture.workspace)
        assertTrue(result is Refinement.Refined)
        assertEquals(fixture.container.id, (result as Refinement.Refined).value.id)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `local image pin rejects a changed target before container creation`() {
        val fixture = fixture()
        val changed =
            nativeImage.replace("registry.example/kast:arm64", image).replace(digest, "sha256:" + "c".repeat(64))
        val calls = ArrayDeque<Pair<List<String>, Refinement<String, ContainerFailure>>>()
        calls.add(profileCall())
        calls.add(runner + listOf("ps", "--all", "--format", "{{.Names}}") to Refinement.Refined(""))
        calls.add(inspectImage("kast-dev:arm64") to Refinement.Refined(nativeImage))
        calls.add(runner + listOf("tag", "registry.example/kast:arm64", image) to Refinement.Refined(""))
        calls.add(inspectImage(image) to Refinement.Refined(changed))
        assertEquals(
            Refinement.Rejected(ContainerFailure.IMAGE_REJECTED),
            runtime(calls).prepare(fixture.command, fixture.workspace),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `image process failures preserve their finite cause`() {
        val fixture = fixture()
        for (failure in
            listOf(
                ContainerFailure.PROCESS_START_FAILED,
                ContainerFailure.OUTPUT_LIMIT_EXCEEDED,
                ContainerFailure.OUTPUT_ENCODING_REJECTED,
                ContainerFailure.PROCESS_FAILED,
                ContainerFailure.TERMINATED,
            )) {
            val calls = ArrayDeque<Pair<List<String>, Refinement<String, ContainerFailure>>>()
            calls.add(profileCall())
            calls.add(runner + listOf("ps", "--all", "--format", "{{.Names}}") to Refinement.Refined(""))
            calls.add(inspectImage("kast-dev:arm64") to Refinement.Rejected(failure))
            assertEquals(Refinement.Rejected(failure), runtime(calls).prepare(fixture.command, fixture.workspace))
            assertTrue(calls.isEmpty())
        }
    }

    @Test
    fun `a successful image inspection without output reports an empty document`() {
        val result = prepareWithImageOutput("\n")
        assertEquals("IMAGE_INSPECT_EMPTY", result.failure.name)
    }

    @Test
    fun `invalid image inspection output rejects before image effects`() {
        val wrongShapes = providerFixture("container-image-inspect-wrong-shapes.ndjson").trimEnd().lines()
        for (output in
            wrongShapes +
                listOf("{", "banner\n$nativeImage", "\u001b[32m$nativeImage\u001b[0m", "$nativeImage $nativeImage")) {
            val result = prepareWithImageOutput(output)
            assertEquals("IMAGE_INSPECT_DOCUMENT_REJECTED", result.failure.name)
        }
    }

    @Test
    fun `image inspection rejects conflicting aliases before image effects`() {
        val foreign = nativeImage.replace(digest, "sha256:" + "b".repeat(64))
        assertEquals(ContainerFailure.IMAGE_REJECTED, prepareWithImageOutput("$nativeImage\n$foreign\n").failure)
    }

    // Native nerdctl fields with a synthetic image name, digest, and metadata.
    private val nativeImage = providerFixture("container-image-inspect.ndjson").trimEnd()

    private fun providerFixture(name: String): String =
        requireNotNull(javaClass.getResource("/management/$name")).readText()

    private fun inspectImage(reference: String): List<String> =
        runner + listOf("image", "inspect", "--mode", "native", "--format=json", reference)

    private fun prepareWithImageOutput(output: String): Refinement.Rejected<ContainerFailure> {
        val fixture = fixture()
        val calls = ArrayDeque<Pair<List<String>, Refinement<String, ContainerFailure>>>()
        calls.add(profileCall())
        calls.add(runner + listOf("ps", "--all", "--format", "{{.Names}}") to Refinement.Refined(""))
        calls.add(inspectImage("kast-dev:arm64") to Refinement.Refined(output))
        val result = runtime(calls).prepare(fixture.command, fixture.workspace)
        assertTrue(calls.isEmpty())
        return result as Refinement.Rejected
    }

    private fun runtime(
        calls: ArrayDeque<Pair<List<String>, Refinement<String, ContainerFailure>>>
    ): ColimaContainerRuntime =
        ColimaContainerRuntime(
            Path.of("/fixture/colima"),
            temporary,
            ContainerArchitecture.ARM64,
            ContainerProcess { arguments ->
                assertTrue(calls.isNotEmpty(), "Unexpected effect: $arguments")
                val expected = calls.removeFirst()
                assertEquals(expected.first, arguments)
                expected.second
            },
        )

    private fun commonCalls(fixture: Fixture): List<Pair<List<String>, Refinement<String, ContainerFailure>>> =
        listOf(
            profileCall(),
            runner + listOf("ps", "--all", "--format", "{{.Names}}") to
                Refinement.Refined(fixture.container.name + "\n"),
        )

    private fun profileCall(): Pair<List<String>, Refinement<String, ContainerFailure>> =
        listOf("/fixture/colima", "list", "--json") to
            Refinement.Refined(
                managementJson.encodeToString(ColimaProfile("kast-native", "Running", "aarch64", "containerd"))
            )

    private fun read(document: ContainerDocument): Refinement<String, ContainerFailure> =
        Refinement.Refined(managementJson.encodeToString(listOf(document)))

    private fun fixture(): Fixture {
        val root = temporary.toRealPath().resolve("repository")
        Files.createDirectories(root.resolve(".git/objects"))
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/main\n")
        val workspace = (ContainerWorkspace.discover(root) as Refinement.Refined).value
        val name = (ContainerWorkspaceName.parse("default") as Refinement.Refined).value
        val key = workspace.key(name, ContainerArchitecture.ARM64)
        val document =
            ContainerDocument(
                "b".repeat(64),
                "kast-work-$key",
                image,
                ContainerConfig(
                    "developer",
                    mapOf(
                        "io.kast.workspace" to "1",
                        "io.kast.workspace.key" to key,
                        "io.kast.workspace.root" to root.toString(),
                        "io.kast.image" to digest,
                    ),
                ),
                listOf(
                    ContainerMount("bind", root.toString(), root.toString(), writable = true),
                    ContainerMount("volume", "/volume/data", "/opt/kast/state/home/.gradle", "kast-gradle-$key", true),
                ),
                ContainerHostConfig("none", false),
                ContainerState(true, false, false),
            )
        return Fixture(
            workspace,
            ContainerCommand.Open(
                root,
                name,
                ContainerImageSelection.Local,
                ContainerNetwork.OFFLINE,
                ContainerTask.Shell,
            ),
            document,
        )
    }

    private data class Fixture(
        val workspace: ContainerWorkspace,
        val command: ContainerCommand.Open,
        val container: ContainerDocument,
    )
}
