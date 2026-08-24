package support.pr633

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.gradle.api.tasks.UntrackedTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class VerifyPr633StackTaskTest {
    @TempDir
    lateinit var repository: Path

    @Test
    fun `missing required tasks field is rejected by generated decoding`() {
        val incomplete = pr633AuthorityJson.encodeToString(
            Pr633ProgramWithoutTasksDocument.serializer(),
            Pr633ProgramWithoutTasksDocument(
                schemaVersion = 2,
                programId = PROGRAM_ID,
                gates = emptyList(),
            ),
        )

        assertThrows<SerializationException> {
            pr633AuthorityJson.decodeFromString(Pr633ProgramDocument.serializer(), incomplete)
        }
    }

    @Test
    fun `missing required gates field is rejected by generated decoding`() {
        val incomplete = pr633AuthorityJson.encodeToString(
            Pr633ProgramWithoutGatesDocument.serializer(),
            Pr633ProgramWithoutGatesDocument(
                schemaVersion = 2,
                programId = PROGRAM_ID,
                tasks = emptyList(),
            ),
        )

        assertThrows<SerializationException> {
            pr633AuthorityJson.decodeFromString(Pr633ProgramDocument.serializer(), incomplete)
        }
    }

    @Test
    fun `refined repository locations cannot be reconstructed from raw strings`() {
        val refinedConstructorInputs = listOf(
            RepositoryPath::class.java,
            Class.forName("support.pr633.RepositoryPrefix"),
        ).flatMap { type ->
            type.declaredConstructors.flatMap { constructor ->
                constructor.parameterTypes.filterNot { parameter ->
                    parameter.name == "kotlin.jvm.internal.DefaultConstructorMarker"
                }
            }
        }.map(Class<*>::getSimpleName).toSet()

        assertTrue(refinedConstructorInputs == setOf("NormalizedRepositoryLocation"))
    }

    @Test
    fun `aggregate stack failures always retain first causal evidence`() {
        val parsed = RepositoryPath.parse("scope/file.kt")
        val path = (parsed as RepositoryLocationResult.Parsed<RepositoryPath>).value
        val missing = StackVerificationFailure.MissingScopedTasks("KTP633-010", emptySet())
        val duplicate = StackVerificationFailure.DuplicateScopedTasks("KTP633-020", emptySet())
        val forbidden = StackVerificationFailure.ForbiddenChangedPaths(path, emptyList())
        val outside = StackVerificationFailure.OutsideTaskScopes(path, emptyList())

        assertTrue(missing.taskIds == setOf("KTP633-010"))
        assertTrue(duplicate.taskIds == setOf("KTP633-020"))
        assertTrue(forbidden.paths == listOf(path))
        assertTrue(outside.paths == listOf(path))
    }

    @Test
    fun `Git-reading tasks cannot reuse up-to-date repository proof`() {
        assertTrue(VerifyPr633StackTask::class.java.isAnnotationPresent(UntrackedTask::class.java))
        assertTrue(VerifyPr633GitDiffTask::class.java.isAnnotationPresent(UntrackedTask::class.java))
    }

    @Test
    fun `deleted forbidden path is rejected even when a task scope allows it`() {
        initializeRepository()
        write("release/deleted.txt", "legacy\n")
        commit("base", "release/deleted.txt")
        git("switch", "-c", "feature")
        Files.delete(repository.resolve("release/deleted.txt"))
        git("add", "-u")
        git("commit", "-m", "delete forbidden path")

        val failure = assertThrows<IllegalStateException> {
            configuredTask(
                program = program(scopes = mapOf("KTP633-010" to listOf("release/"))),
                forbiddenPrefixes = listOf("release/"),
            ).verify()
        }

        assertTrue(failure.message.orEmpty().contains("release/deleted.txt"))
        assertTrue(failure.message.orEmpty().contains("forbidden"))
    }

    @Test
    fun `deleted path outside every task scope is rejected`() {
        initializeRepository()
        write("outside/deleted.txt", "outside\n")
        commit("base", "outside/deleted.txt")
        git("switch", "-c", "feature")
        Files.delete(repository.resolve("outside/deleted.txt"))
        git("add", "-u")
        git("commit", "-m", "delete out-of-scope path")

        val failure = assertThrows<IllegalStateException> {
            configuredTask(program = program()).verify()
        }

        assertTrue(failure.message.orEmpty().contains("outside/deleted.txt"))
        assertTrue(failure.message.orEmpty().contains("outside KTP633-010 through KTP633-070"))
    }

    @Test
    fun `whitespace-only Git pathname reaches admission and is rejected`() {
        initializeRepository()
        git("commit", "--allow-empty", "-m", "base")
        git("switch", "-c", "feature")
        write("   ", "content\n")
        commit("add whitespace-only pathname", "   ")

        val failure = assertThrows<IllegalStateException> {
            configuredTask(program = program()).verify()
        }

        assertTrue(failure.message.orEmpty().contains("Git reported invalid path"))
    }

    @Test
    fun `exact and prefix scopes are derived from program tasks`() {
        initializeRepository()
        git("commit", "--allow-empty", "-m", "base")
        git("switch", "-c", "feature")
        write("exact.txt", "exact\n")
        write("scope/nested.txt", "prefix\n")
        commit("add task-scoped files", "exact.txt", "scope/nested.txt")

        val task = configuredTask(
            program = program(
                scopes = mapOf(
                    "KTP633-010" to listOf("exact.txt"),
                    "KTP633-020" to listOf("scope/"),
                ),
            ),
        )

        assertDoesNotThrow(task::verify)
        val report = repository.resolve("report.json").toFile().readText()
        assertTrue(report.contains("exact.txt"))
        assertTrue(report.contains("scope/nested.txt"))
    }

    @Test
    fun `exact scope does not admit paths that merely start with its value`() {
        initializeRepository()
        git("commit", "--allow-empty", "-m", "base")
        git("switch", "-c", "feature")
        write("exact.txt.more", "outside exact scope\n")
        commit("add similarly named file", "exact.txt.more")

        val failure = assertThrows<IllegalStateException> {
            configuredTask(
                program = program(scopes = mapOf("KTP633-010" to listOf("exact.txt"))),
            ).verify()
        }

        assertTrue(failure.message.orEmpty().contains("exact.txt.more"))
        assertTrue(failure.message.orEmpty().contains("outside KTP633-010 through KTP633-070"))
    }

    @Test
    fun `ancestor guides are admitted only when they cover an admitted non-guide path`() {
        initializeRepository()
        git("commit", "--allow-empty", "-m", "base")
        git("switch", "-c", "feature")
        write("src/main/App.kt", "class App\n")
        write("src/AGENTS.md", "guide\n")
        write("AGENTS.md", "root guide\n")
        commit("add scoped source and ancestor guides", "src/main/App.kt", "src/AGENTS.md", "AGENTS.md")

        val task = configuredTask(
            program = program(
                scopes = mapOf(
                    "KTP633-010" to listOf("src/main/"),
                    "KTP633-050" to listOf(ANCESTOR_GUIDE_AUTHORITY),
                ),
            ),
        )

        assertDoesNotThrow(task::verify)
    }

    @Test
    fun `guide outside admitted path ancestry is rejected`() {
        initializeRepository()
        git("commit", "--allow-empty", "-m", "base")
        git("switch", "-c", "feature")
        write("src/main/App.kt", "class App\n")
        write("docs/AGENTS.md", "unrelated guide\n")
        commit("add source and unrelated guide", "src/main/App.kt", "docs/AGENTS.md")

        val failure = assertThrows<IllegalStateException> {
            configuredTask(
                program = program(
                    scopes = mapOf(
                        "KTP633-010" to listOf("src/main/"),
                        "KTP633-050" to listOf(ANCESTOR_GUIDE_AUTHORITY),
                    ),
                ),
            ).verify()
        }

        assertTrue(failure.message.orEmpty().contains("docs/AGENTS.md"))
    }

    @Test
    fun `PR range diff rejects committed whitespace error in a clean worktree`() {
        initializeRepository()
        git("commit", "--allow-empty", "-m", "base")
        git("switch", "-c", "feature")
        write("bad.txt", "trailing whitespace \n")
        commit("commit whitespace error", "bad.txt")
        assertTrue(git("status", "--porcelain").isEmpty())

        val task = configuredGitDiffTask()

        val failure = assertThrows<IllegalStateException>(task::verify)
        assertTrue(failure.message.orEmpty().contains("bad.txt"))
    }

    @Test
    fun `successful PR diff report carries resolved commit identities`() {
        initializeRepository()
        git("commit", "--allow-empty", "-m", "base")
        val mainSha = git("rev-parse", "main")
        git("switch", "-c", "feature")
        write("clean.txt", "clean\n")
        commit("commit clean file", "clean.txt")
        val headSha = git("rev-parse", "HEAD")

        configuredGitDiffTask().verify()

        val report = repository.resolve("git-diff-report.json").toFile().readText()
        assertTrue(report.contains(mainSha))
        assertTrue(report.contains(headSha))
        assertTrue(!report.contains("mainRef") && !report.contains("headRef"))
    }

    private fun configuredTask(
        program: String,
        forbiddenPrefixes: List<String> = emptyList(),
    ): VerifyPr633StackTask {
        val head = git("rev-parse", "HEAD")
        val event = write(
            "event.json",
            pr633AuthorityJson.encodeToString(
                Pr633EventDocument.serializer(),
                Pr633EventDocument(
                    number = 633,
                    pullRequest = Pr633EventDocument.PullRequestDocument(
                        base = Pr633EventDocument.BaseDocument("main"),
                        head = Pr633EventDocument.HeadDocument(head),
                    ),
                ),
            ),
        )
        val programFile = write("program.json", program)
        val policy = write(
            "policy.json",
            pr633AuthorityJson.encodeToString(
                Pr633PathPolicyDocument.serializer(),
                Pr633PathPolicyDocument(
                    schemaVersion = 1,
                    programId = PROGRAM_ID,
                    policy = "Test-only forbidden-prefix authority.",
                    forbiddenPrefixes = forbiddenPrefixes,
                ),
            ),
        )
        val project = ProjectBuilder.builder().withProjectDir(repository.toFile()).build()
        return project.tasks.register("verifyStackUnderTest", VerifyPr633StackTask::class.java).get().apply {
            eventFile.set(event.toFile())
            this.programFile.set(programFile.toFile())
            pathPolicyFile.set(policy.toFile())
            expectedPullRequest.set(633)
            expectedBaseRef.set("main")
            mainGitRef.set("main")
            headGitRef.set("HEAD")
            repositoryDirectory.set(repository.toFile())
            reportFile.set(repository.resolve("report.json").toFile())
        }
    }

    private fun configuredGitDiffTask(): VerifyPr633GitDiffTask {
        val project = ProjectBuilder.builder().withProjectDir(repository.toFile()).build()
        return project.tasks.register(
            "verifyPr633GitDiffUnderTest",
            VerifyPr633GitDiffTask::class.java,
        ).get().apply {
            mainGitRef.set("main")
            headGitRef.set("HEAD")
            repositoryDirectory.set(repository.toFile())
            reportFile.set(repository.resolve("git-diff-report.json").toFile())
        }
    }

    private fun program(scopes: Map<String, List<String>> = emptyMap()): String =
        pr633AuthorityJson.encodeToString(
            Pr633ProgramDocument.serializer(),
            Pr633ProgramDocument(
                schemaVersion = 2,
                programId = PROGRAM_ID,
                tasks = REQUIRED_TASK_IDS.map { taskId ->
                    Pr633ProgramDocument.TaskDocument(
                        id = taskId,
                        allowedWrites = scopes.getOrElse(taskId, ::emptyList),
                    )
                },
                gates = emptyList(),
            ),
        )

    private fun initializeRepository() {
        git("init", "--initial-branch=main")
        git("config", "user.name", "Kast Test")
        git("config", "user.email", "kast-test@example.invalid")
    }

    private fun commit(message: String, vararg paths: String) {
        git("add", *paths)
        git("commit", "-m", message)
    }

    private fun write(relative: String, content: String): Path = repository.resolve(relative).also { path ->
        Files.createDirectories(path.parent)
        path.writeText(content)
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    private companion object {
        const val PROGRAM_ID = "kast-pr633-durable-topology"
        const val ANCESTOR_GUIDE_AUTHORITY =
            "ancestor AGENTS.md files required for every admitted changed path"
        val REQUIRED_TASK_IDS = (1..7).map { index -> "KTP633-0${index}0" }
    }
}

@Serializable
private data class Pr633ProgramWithoutTasksDocument(
    val schemaVersion: Int,
    val programId: String,
    val gates: List<Pr633ProgramDocument.GateDocument>,
)

@Serializable
private data class Pr633ProgramWithoutGatesDocument(
    val schemaVersion: Int,
    val programId: String,
    val tasks: List<Pr633ProgramDocument.TaskDocument>,
)
