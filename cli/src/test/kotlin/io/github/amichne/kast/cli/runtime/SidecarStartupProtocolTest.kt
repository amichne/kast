package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SidecarStartupProtocolTest {
    @Test
    fun `start options refine explicit runtime and seed intent`(@TempDir temporary: Path) {
        val ideaHome = Files.createDirectory(temporary.resolve("idea")).toRealPath()
        val sourceSystem = Files.createDirectory(temporary.resolve("source")).toRealPath()

        val parsed = commandGraph().parse(
            listOf(
                "start",
                "--idea-home=$ideaHome",
                "--seed-from-idea",
                "--source-idea-system=$sourceSystem",
                "--accept-global-index-copy",
            ),
        )

        assertTrue(parsed is CliCommandParsing.Parsed)
        val action = (parsed as CliCommandParsing.Parsed).action as CliAction.Lifecycle.Start
        assertEquals(StartupIdeHome.Explicit(ideaHome), action.startup.ideHome)
        assertEquals(
            StartupCacheIntent.Seed(
                StartupIdeaSystem.Explicit(sourceSystem),
                IndexSeedConsentRequest.PREGRANTED,
            ),
            action.startup.cacheIntent,
        )
    }

    @Test
    fun `seed without noninteractive acceptance requests interactive disclosure`() {
        val parsed = commandGraph().parse(
            listOf("start", "--seed-from-idea"),
        ) as CliCommandParsing.Parsed
        val action = parsed.action as CliAction.Lifecycle.Start

        assertEquals(
            StartupCacheIntent.Seed(
                StartupIdeaSystem.Standard,
                IndexSeedConsentRequest.INTERACTIVE,
            ),
            action.startup.cacheIntent,
        )
    }

    @Test
    fun `ordinary start selects local discovery and never reads IDEA cache`() {
        val parsed = commandGraph().parse(listOf("start")) as CliCommandParsing.Parsed
        val action = parsed.action as CliAction.Lifecycle.Start

        assertEquals(RuntimeStartupRequest.Default, action.startup)
        assertTrue(
            commandGraph().parse(listOf("start", "--source-idea-system=/tmp/idea")) is
                CliCommandParsing.Rejected,
        )
    }

    @Test
    fun `launch command retains every installed runtime and private cache proof`(
        @TempDir temporary: Path,
    ) {
        val project = Files.createDirectory(temporary.resolve("project")).toRealPath()
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val root = FilesystemCanonicalRootDiscovery.discover(project).let {
            (it as CanonicalRootDiscovery.Discovered).root
        }
        val executablePath = Files.writeString(temporary.resolve("kast-indexer"), "#!/bin/sh\n")
        assertTrue(executablePath.toFile().setExecutable(true))
        val executable = when (val admitted = IndexerExecutable.admit(executablePath)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error(admitted.failure.toString())
        }
        val runtimeId = when (
            val admitted = SemanticRuntimeId.parse("sha256:${"b".repeat(64)}")
        ) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error(admitted.failure.toString())
        }
        val endpoint = RuntimeEndpoint.at(
            root,
            runtimeId,
            temporary.resolve("runtime.sock"),
        ).let { (it as RuntimeEndpointResolution.Resolved).endpoint }
        val ideaHome = Files.createDirectory(temporary.resolve("idea-home")).toRealPath()
        val java = Files.createFile(ideaHome.resolve("java")).toRealPath()
        assertTrue(java.toFile().setExecutable(true))
        val installed = InstalledIdeRuntime(ideaHome, java, runtimeIdentity())
        val cacheRoot = Files.createDirectory(temporary.resolve("cache")).toRealPath()
        val system = Files.createDirectory(cacheRoot.resolve("system")).toRealPath()
        val config = Files.createDirectory(cacheRoot.resolve("config")).toRealPath()
        val log = Files.createDirectory(cacheRoot.resolve("log")).toRealPath()
        val privatePlugins = Files.createDirectory(temporary.resolve("private-plugins")).toRealPath()
        val context = SidecarLaunchContext.admit(
            installed,
            cacheRoot,
            system,
            config,
            log,
            privatePlugins,
        ).let { (it as SidecarLaunchContextAdmission.Admitted).context }

        val command = IndexerLaunchCommand.create(executable, root, endpoint, context).let {
            (it as IndexerLaunchCommandConstruction.Created).command
        }

        assertEquals(
            listOf(
                executablePath.toRealPath().toString(),
                "--workspace-root=${root.path}",
                "--socket-path=${temporary.resolve("runtime.sock")}",
                "--runtime-id=${runtimeId.value}",
                "--idea-home=$ideaHome",
                "--java-executable=$java",
                "--idea-system-path=$system",
                "--idea-config-path=$config",
                "--idea-log-path=$log",
                "--private-plugins-path=$privatePlugins",
                "--cache-state-path=${cacheRoot.resolve("cache-state")}",
            ),
            command.arguments,
        )
    }

    private fun commandGraph(): CliCommandGraphFactory = when (
        val result = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> result.factory
        is CliCommandGraphConstruction.Rejected -> error(result.failures.toString())
    }

    private fun runtimeIdentity(): IdeRuntimeIdentity {
        val pair = SupportedIdeRuntimePair.admit(
            "262.9437.185",
            "262.9437.185-IJ",
        ).let { (it as SupportedIdeRuntimePairAdmission.Admitted).pair }
        return IdeRuntimeIdentity.admit(
            pair,
            IdeRuntimeIdentityCandidate(
                pair.ideaBuild,
                pair.kotlinPluginBuild,
                "jbr-25.0.3+9-b508.16-aarch64",
                "sha256:${"a".repeat(64)}",
            ),
        ).let { (it as IdeRuntimeIdentityAdmission.Admitted).identity }
    }
}
