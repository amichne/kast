package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContainerPlanTest {
    @TempDir lateinit var temporary: Path
    private val digest = "sha256:" + "a".repeat(64)

    @Test
    fun `image admission keeps native digest and rejects incompatible layouts`() {
        val document = imageDocument()
        val image = (ContainerImage.admit(document, ContainerArchitecture.ARM64) as Refinement.Refined).value
        assertEquals("registry.example:5000/kast@$digest", image.reference)
        assertEquals(digest, image.digest)
        for (configuration in
            listOf(
                document.configuration.copy(architecture = "amd64"),
                document.configuration.copy(os = "windows"),
                document.configuration.copy(config = ContainerConfig("root", mapOf("io.kast.dev-container" to "3"))),
                document.configuration.copy(
                    config = ContainerConfig("developer", mapOf("io.kast.dev-container" to "2"))
                ),
            )) {
            assertEquals(
                Refinement.Rejected(ContainerFailure.IMAGE_REJECTED),
                ContainerImage.admit(document.copy(configuration = configuration), ContainerArchitecture.ARM64),
            )
        }
    }

    @Test
    fun `image aliases keep the exact immutable name and reject conflicting content`() {
        val tag = imageDocument()
        val reference = "registry.example:5000/kast@$digest"
        val immutable = tag.copy(image = tag.image.copy(name = reference))
        val selection = (ContainerImageSelection.Digest.parse(reference) as Refinement.Refined).value
        val admitted =
            ContainerImage.admitAliases(listOf(tag, immutable), ContainerArchitecture.ARM64, selection, reference)
        assertEquals(reference, (admitted as Refinement.Refined).value.source)
        for (documents in
            listOf(
                emptyList(),
                listOf(tag, immutable.copy(configuration = immutable.configuration.copy(architecture = "amd64"))),
            )) {
            assertEquals(
                Refinement.Rejected(ContainerFailure.IMAGE_REJECTED),
                ContainerImage.admitAliases(documents, ContainerArchitecture.ARM64, selection, reference),
            )
        }
    }

    @Test
    fun `image admission binds both registry and local selections to the returned digest`() {
        for (reference in listOf(digest, "registry.example:5000/kast@$digest")) {
            val selected = (ContainerImageSelection.Digest.parse(reference) as Refinement.Refined).value
            assertTrue(
                ContainerImage.admit(imageDocument(), ContainerArchitecture.ARM64, selected) is Refinement.Refined
            )
            val different =
                imageDocument()
                    .copy(
                        image =
                            ContainerImageTarget(
                                "registry.example:5000/kast:arm64",
                                ContainerImageDescriptor("sha256:" + "c".repeat(64)),
                            )
                    )
            assertEquals(
                Refinement.Rejected(ContainerFailure.IMAGE_REJECTED),
                ContainerImage.admit(different, ContainerArchitecture.ARM64, selected),
            )
        }
    }

    @Test
    fun `existing scope rejects foreign ownership before it exposes an image selection`() {
        val plan = plan()
        val document = containerDocument(plan)
        val name = (ContainerWorkspaceName.parse("default") as Refinement.Refined).value
        val admitted =
            ContainerScope.admit(document, plan.workspace, name, ContainerArchitecture.ARM64, ContainerNetwork.OFFLINE)
        assertTrue(admitted is Refinement.Refined)
        val scope = (admitted as Refinement.Refined).value
        assertEquals(document.image, scope.imageSelection.reference)
        for (foreign in
            listOf(
                document.copy(name = "foreign-workspace"),
                document.copy(
                    config =
                        document.config.copy(labels = document.config.labels + ("io.kast.workspace.key" to "foreign"))
                ),
                document.copy(
                    config =
                        document.config.copy(labels = document.config.labels + ("io.kast.workspace.root" to "/foreign"))
                ),
                document.copy(config = document.config.copy(user = "root")),
                document.copy(image = "registry.example/kast:latest"),
            )) assertEquals(
            Refinement.Rejected(ContainerFailure.WORKSPACE_CONFLICT),
            ContainerScope.admit(foreign, plan.workspace, name, ContainerArchitecture.ARM64, ContainerNetwork.OFFLINE),
        )
    }

    @Test
    fun `scope snapshots input collections and final admission compares the selected image`() {
        val plan = plan()
        val document = containerDocument(plan)
        val labels = document.config.labels.toMutableMap()
        val mounts = document.mounts.toMutableList()
        val name = (ContainerWorkspaceName.parse("default") as Refinement.Refined).value
        val scope =
            (ContainerScope.admit(
                    document.copy(config = document.config.copy(labels = labels), mounts = mounts),
                    plan.workspace,
                    name,
                    ContainerArchitecture.ARM64,
                    ContainerNetwork.OFFLINE,
                ) as Refinement.Refined)
                .value
        labels.clear()
        mounts.clear()
        assertEquals(document, scope.document)
        assertTrue(ContainerPlan.RetainedContainer.admit(scope, plan.image) is Refinement.Refined)
        val differentDocument =
            imageDocument()
                .copy(
                    image =
                        ContainerImageTarget(
                            "registry.example:5000/kast:arm64",
                            ContainerImageDescriptor("sha256:" + "c".repeat(64)),
                        )
                )
        val different =
            (ContainerImage.admit(differentDocument, ContainerArchitecture.ARM64) as Refinement.Refined).value
        assertEquals(
            Refinement.Rejected(ContainerFailure.WORKSPACE_CONFLICT),
            ContainerPlan.RetainedContainer.admit(scope, different),
        )
    }

    @Test
    fun `retained workspaces reject changed images networks and mounts`() {
        val plan = plan()
        val document = containerDocument(plan)
        assertTrue(plan.admit(document) is Refinement.Refined)
        val wrongMount = document.mounts.first().copy(source = "/foreign")
        val wrongVolume = document.mounts.last().copy(name = "foreign-cache")
        for (changed in
            listOf(
                document.copy(image = "registry.example/kast:latest"),
                document.copy(host = ContainerHostConfig("bridge", false)),
                document.copy(host = ContainerHostConfig("none", true)),
                document.copy(mounts = listOf(wrongMount, document.mounts.last())),
                document.copy(mounts = listOf(document.mounts.first(), wrongVolume)),
                document.copy(mounts = document.mounts + document.mounts.first()),
                document.copy(config = document.config.copy(labels = emptyMap())),
                document.copy(state = ContainerState(true, true, false)),
            )) assertEquals(Refinement.Rejected(ContainerFailure.WORKSPACE_CONFLICT), plan.admit(changed))
        assertTrue(plan.createArguments.contains("type=bind,src=${plan.workspace.root},dst=${plan.workspace.root}"))
        assertTrue(plan.createArguments.contains("type=volume,src=${plan.cache},dst=/opt/kast/state/home/.gradle"))
        assertTrue(plan.createArguments.contains("GRADLE_USER_HOME=/opt/kast/state/home/.gradle"))
    }

    @Test
    fun `foreign runtime rejects before image or workspace effects`() {
        val plan = plan()
        val calls = mutableListOf<List<String>>()
        val runtime =
            ColimaContainerRuntime(
                Path.of("/fixture/colima"),
                temporary,
                ContainerArchitecture.ARM64,
                ContainerProcess { arguments ->
                    calls.add(arguments)
                    assertEquals(listOf("/fixture/colima", "list", "--json"), arguments)
                    Refinement.Refined(
                        managementJson.encodeToString(ColimaProfile("kast-native", "Running", "aarch64", "docker"))
                    )
                },
            )
        val name = (ContainerWorkspaceName.parse("default") as Refinement.Refined).value
        val command =
            ContainerCommand.Open(
                plan.workspace.root,
                name,
                ContainerImageSelection.Local,
                ContainerNetwork.OFFLINE,
                ContainerTask.Shell,
            )
        assertEquals(Refinement.Rejected(ContainerFailure.RUNTIME_REJECTED), runtime.prepare(command, plan.workspace))
        assertEquals(1, calls.size)
    }

    @Test
    fun `runtime selectors do not redirect the selected profile or Git worktree`() {
        assertEquals(
            mapOf("PATH" to "/bin", "HOME" to "/home/fixture"),
            containerEnvironment(
                mapOf(
                    "PATH" to "/bin",
                    "HOME" to "/home/fixture",
                    "COLIMA_HOME" to "/foreign",
                    "NERDCTL_NAMESPACE" to "foreign",
                    "CONTAINERD_ADDRESS" to "/foreign.sock",
                    "GIT_DIR" to "/foreign",
                    "SSH" to "/foreign/ssh",
                )
            ),
        )
    }

    @Test
    fun `busy preparation rejects without starting another effect`() {
        val result =
            withContainerLock(temporary) {
                withContainerLock<Unit>(temporary) { error("A second preparation must not start") }
            }
        assertEquals(Refinement.Rejected(ContainerFailure.LOCK_UNAVAILABLE), result)
        assertEquals(Refinement.Refined("released"), withContainerLock(temporary) { Refinement.Refined("released") })
    }

    private fun imageDocument() =
        ContainerImageDocument(
            ContainerImageTarget("registry.example:5000/kast:arm64", ContainerImageDescriptor(digest)),
            ContainerImageConfiguration(
                "arm64",
                "linux",
                ContainerConfig("developer", mapOf("io.kast.dev-container" to "3")),
            ),
        )

    private fun plan(): ContainerPlan {
        val root = temporary.toRealPath().resolve("repository")
        Files.createDirectories(root.resolve(".git/objects"))
        Files.writeString(root.resolve(".git/HEAD"), "ref: refs/heads/main\n")
        val workspace = (ContainerWorkspace.discover(root) as Refinement.Refined).value
        val name = (ContainerWorkspaceName.parse("default") as Refinement.Refined).value
        val image = (ContainerImage.admit(imageDocument(), ContainerArchitecture.ARM64) as Refinement.Refined).value
        return ContainerPlan(workspace, name, ContainerArchitecture.ARM64, image, ContainerNetwork.OFFLINE)
    }

    private fun containerDocument(plan: ContainerPlan) =
        ContainerDocument(
            "b".repeat(64),
            plan.container,
            "registry.example:5000/kast@$digest",
            ContainerConfig(
                "developer",
                mapOf(
                    "io.kast.workspace" to "1",
                    "io.kast.workspace.key" to plan.key,
                    "io.kast.workspace.root" to plan.workspace.root.toString(),
                    "io.kast.image" to digest,
                ),
            ),
            listOf(
                ContainerMount("bind", plan.workspace.root.toString(), plan.workspace.root.toString(), writable = true),
                ContainerMount(
                    "volume",
                    "/var/lib/nerdctl/volumes/cache",
                    "/opt/kast/state/home/.gradle",
                    plan.cache,
                    true,
                ),
            ),
            ContainerHostConfig("none", false),
            ContainerState(true, false, false),
        )
}
