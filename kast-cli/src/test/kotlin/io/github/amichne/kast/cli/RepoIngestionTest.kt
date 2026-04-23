package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path

class RepoIngestionTest {

    /** Fake that clones successfully without creating Gradle files at the repo root. */
    private class RecordingGitRunner : GitRunner {
        val calls = mutableListOf<Pair<String, Path>>()
        override fun cloneShallow(url: String, dest: Path) {
            calls += url to dest
        }
    }

    @Test
    fun `clone with valid HTTPS GitHub URL invokes runner with correct args`() {
        val runner = RecordingGitRunner()
        val result = RepoIngestion.clone("https://github.com/amichne/kast", runner)

        assertEquals(1, runner.calls.size)
        assertEquals("https://github.com/amichne/kast", runner.calls[0].first)
        assertEquals(result, runner.calls[0].second)
        assertTrue(Files.isDirectory(result))
    }

    @Test
    fun `clone accepts HTTPS GitHub URL with dot-git suffix`() {
        val runner = RecordingGitRunner()
        val result = RepoIngestion.clone("https://github.com/amichne/kast.git", runner)
        assertEquals(1, runner.calls.size)
        assertTrue(Files.isDirectory(result))
    }

    @Test
    fun `clone accepts SSH GitHub URL`() {
        val runner = RecordingGitRunner()
        val result = RepoIngestion.clone("git@github.com:amichne/kast.git", runner)
        assertEquals(1, runner.calls.size)
        assertTrue(Files.isDirectory(result))
    }

    @Test
    fun `clone rejects non-GitHub URLs`() {
        val runner = RecordingGitRunner()
        val failure = assertThrows<CliFailure> {
            RepoIngestion.clone("https://gitlab.com/foo/bar", runner)
        }
        assertEquals("DEMO_GEN_INVALID_URL", failure.code)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `clone rejects blank URL`() {
        val runner = RecordingGitRunner()
        val failure = assertThrows<CliFailure> {
            RepoIngestion.clone("   ", runner)
        }
        assertEquals("DEMO_GEN_INVALID_URL", failure.code)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `clone rejects empty URL`() {
        val runner = RecordingGitRunner()
        val failure = assertThrows<CliFailure> {
            RepoIngestion.clone("", runner)
        }
        assertEquals("DEMO_GEN_INVALID_URL", failure.code)
    }

    @Test
    fun `clone rejects malformed GitHub URL`() {
        val runner = RecordingGitRunner()
        val failure = assertThrows<CliFailure> {
            RepoIngestion.clone("http://github.com/foo/bar", runner)
        }
        assertEquals("DEMO_GEN_INVALID_URL", failure.code)
    }

    @Test
    fun `clone propagates CliFailure from runner with DEMO_GEN_CLONE_FAILED`() {
        val failingRunner = GitRunner { _, _ ->
            throw CliFailure(
                code = "DEMO_GEN_CLONE_FAILED",
                message = "git clone failed with exit code 128",
            )
        }
        val failure = assertThrows<CliFailure> {
            RepoIngestion.clone("https://github.com/amichne/kast", failingRunner)
        }
        assertEquals("DEMO_GEN_CLONE_FAILED", failure.code)
    }

    @Test
    fun `clone returns existing directory after successful invocation`() {
        val runner = RecordingGitRunner()
        val result = RepoIngestion.clone("https://github.com/amichne/kast", runner)
        assertTrue(Files.exists(result))
        assertTrue(Files.isDirectory(result))
    }

    @Test
    fun `clone succeeds when cloned repository has no settings-gradle-kts at root`() {
        val runner = RecordingGitRunner()
        val result = RepoIngestion.clone("https://github.com/amichne/kast", runner)

        assertEquals(1, runner.calls.size)
        assertTrue(Files.isDirectory(result))
        assertTrue(Files.notExists(result.resolve("settings.gradle.kts")))
    }
}
