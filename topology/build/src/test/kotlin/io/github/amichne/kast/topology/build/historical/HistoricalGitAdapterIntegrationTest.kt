package io.github.amichne.kast.topology.build.historical

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.GitObjectId
import io.github.amichne.kast.topology.contract.historical.HistoricalCapturePlan
import io.github.amichne.kast.topology.contract.historical.HistoricalContext
import io.github.amichne.kast.topology.contract.historical.HistoricalDigest
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalHeader
import io.github.amichne.kast.topology.contract.historical.HistoricalModuleId
import io.github.amichne.kast.topology.contract.historical.HistoricalName
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureFailure
import io.github.amichne.kast.topology.contract.historical.HistoricalNativeCaptureOutcome
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalRepositoryId
import io.github.amichne.kast.topology.contract.historical.HistoricalRevision
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class HistoricalGitAdapterIntegrationTest {
    @TempDir lateinit var checkout: Path

    @Test
    fun `real Git adapter result enters coordinator but cannot supply native authority`() {
        val fixture = prepareFixture()
        val output =
            command(
                listOf(
                    "python3",
                    System.getProperty("kast.historical.gitAdapter"),
                    "--checkout",
                    checkout.toString(),
                    "--request",
                    fixture.requestFile.toString(),
                )
            )
        var nativeCalls = 0
        val result =
            HistoricalCaptureCoordinator(
                    {
                        nativeCalls++
                        HistoricalNativeCaptureOutcome.Rejected(
                            HistoricalNativeCaptureFailure.EXTERNAL_INPUT_STABILITY_UNPROVEN
                        )
                    },
                    { error("A Git observation must never publish without native coherence") },
                )
                .capture(fixture.plan, output)
        val rejected = assertInstanceOf<HistoricalCaptureResult.NativeRejected>(result)
        assertEquals(HistoricalNativeCaptureFailure.EXTERNAL_INPUT_STABILITY_UNPROVEN, rejected.cause)
        assertEquals(1, nativeCalls)
        assertEquals(6L, rejected.work.gitCalls.value)
        assertEquals(fixture.sourceBytes * 2L, rejected.work.bytesExamined.value)
        assertEquals(fixture.commit, git("rev-parse", "HEAD").trim())
        assertEquals("", git("status", "--porcelain", "--untracked-files=no"))
    }

    private fun prepareFixture(): GitFixture {
        git("init", "--quiet")
        git("config", "user.name", "Fixture")
        git("config", "user.email", "fixture@example.invalid")
        val source = "package api\nfun chosen() = 1\n".toByteArray()
        Files.write(checkout.resolve("Api.kt"), source)
        git("add", "Api.kt")
        git("commit", "--quiet", "-m", "fixture A")
        val commit = git("rev-parse", "HEAD").trim()
        val tree = git("rev-parse", "HEAD^{tree}").trim()
        val blob = git("rev-parse", "HEAD:Api.kt").trim()
        val content = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
        val requestFile = checkout.resolve("request.json")
        Files.writeString(
            requestFile,
            Json.encodeToString(
                AttributionRequest(
                    "fixture:repository",
                    commit,
                    listOf(AttributionSource("Api.kt", content)),
                )
            ),
        )
        return GitFixture(commit, source.size, requestFile, capturePlan(commit, tree, blob, content))
    }

    private fun capturePlan(commit: String, tree: String, blob: String, content: String): HistoricalCapturePlan {
        val module = HistoricalModuleId.parse("api").value()
        val header =
            HistoricalHeader(
                HistoricalRevision(
                    HistoricalRepositoryId.parse("fixture:repository").value(),
                    GitObjectId.parse(commit).value(),
                    GitObjectId.parse(tree).value(),
                ),
                HistoricalContext(digest('1'), digest('2'), digest('3')),
                HistoricalProfile.STRUCTURE_V1,
                HistoricalName.parse("fixture-1").value(),
            )
        val file =
            HistoricalFile(
                WorkspaceSourcePath.parse("Api.kt").value(),
                WorkspaceSourceContentHash.parse(content).value(),
                module,
                HistoricalFileProvenance.Tracked(GitObjectId.parse(blob).value()),
            )
        return HistoricalCapturePlan.admit(header, setOf(module), listOf(file)).value()
    }

    private fun git(vararg args: String): String = command(listOf("git", "-C", checkout.toString()) + args)

    private fun command(args: List<String>): String {
        val builder = ProcessBuilder(args).redirectErrorStream(true)
        builder.environment().keys.removeIf { it.startsWith("GIT_") }
        val process = builder.start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            fail<Unit>("Fixture command exceeded its process limit")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), output)
        return output
    }

    private fun digest(character: Char) = HistoricalDigest.parse(character.toString().repeat(64)).value()

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value

    private data class GitFixture(
        val commit: String,
        val sourceBytes: Int,
        val requestFile: Path,
        val plan: HistoricalCapturePlan,
    )

    @Serializable
    private data class AttributionRequest(
        val repository: String,
        val commit: String,
        val files: List<AttributionSource>,
    )

    @Serializable private data class AttributionSource(val path: String, val content: String)
}
