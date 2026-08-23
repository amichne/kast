package support.pr633

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/**
 * Verifies the PR #633 GitHub event and local Git graph without calling the network.
 *
 * CI must check out `github.event.pull_request.head.sha` with full history before this task runs.
 * Local runs pass an equivalent event document with `-Ppr633EventFile=...`.
 */
@DisableCachingByDefault(because = "Reads the local Git graph and GitHub event")
abstract class VerifyPr633StackTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val eventFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pathPolicyFile: RegularFileProperty

    @get:Input
    abstract val expectedPullRequest: Property<Int>

    @get:Input
    abstract val expectedBaseRef: Property<String>

    @get:Input
    abstract val mainGitRef: Property<String>

    @get:Input
    abstract val headGitRef: Property<String>

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val event = Json.parseToJsonElement(eventFile.get().asFile.readText()).jsonObject
        val pullRequest = event.getValue("pull_request").jsonObject
        val number = event.getValue("number").jsonPrimitive.content.toInt()
        val baseRef = pullRequest.getValue("base").jsonObject.getValue("ref").jsonPrimitive.content
        val eventHead = pullRequest.getValue("head").jsonObject.getValue("sha").jsonPrimitive.content
        check(number == expectedPullRequest.get()) {
            "Expected PR #${expectedPullRequest.get()}, received #$number"
        }
        check(baseRef == expectedBaseRef.get()) {
            "PR #$number targets '$baseRef', not '${expectedBaseRef.get()}'"
        }

        val localHead = git("rev-parse", headGitRef.get())
        check(localHead == eventHead) {
            "Checked-out head $localHead differs from GitHub event head $eventHead"
        }

        val ancestry = execOperations.exec {
            workingDir(repositoryDirectory.get().asFile)
            commandLine(
                "git",
                "merge-base",
                "--is-ancestor",
                mainGitRef.get(),
                headGitRef.get(),
            )
            isIgnoreExitValue = true
        }.exitValue
        check(ancestry == 0) {
            "${mainGitRef.get()} is not an ancestor of ${headGitRef.get()}"
        }

        val changedPaths = git(
            "diff",
            "--name-only",
            "--diff-filter=ACMRTUXB",
            "${mainGitRef.get()}...${headGitRef.get()}",
        ).lineSequence().filter(String::isNotBlank).toList()

        val policy = Json.parseToJsonElement(pathPolicyFile.get().asFile.readText()).jsonObject
        val exact = policy.stringSet("allowedExact")
        val prefixes = policy.stringSet("allowedPrefixes")
        val guidePrefixes = policy.stringSet("allowedGuidePrefixes")
        val forbidden = policy.stringSet("forbiddenPrefixes")
        val nonGuidePaths = changedPaths.filterNot(::isAgentGuide)
        val forbiddenPaths = changedPaths.filter { path -> forbidden.any(path::startsWith) }
        val outside = changedPaths.filterNot { path ->
            path in exact || prefixes.any(path::startsWith) || (
                isAgentGuide(path) &&
                    guidePrefixes.any(path::startsWith) &&
                    nonGuidePaths.any { changed -> changed.startsWith(path.removeSuffix("AGENTS.md")) }
            )
        }

        check(forbiddenPaths.isEmpty()) {
            "PR #$number contains forbidden cleanup or legacy paths: $forbiddenPaths"
        }
        check(outside.isEmpty()) {
            "PR #$number changed paths outside the declared topology task scopes: $outside"
        }

        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        val report = buildJsonObject {
            put("schemaVersion", 1)
            put("pullRequest", number)
            put("baseRef", baseRef)
            put("headSha", localHead)
            put("mainRef", mainGitRef.get())
            put(
                "changedPaths",
                buildJsonArray { changedPaths.sorted().forEach { path -> add(JsonPrimitive(path)) } },
            )
            put("status", "passed")
        }
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report) + "\n")
    }

    private fun git(vararg arguments: String): String {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            workingDir(repositoryDirectory.get().asFile)
            commandLine(listOf("git") + arguments)
            standardOutput = output
        }
        return output.toString(Charsets.UTF_8).trim()
    }
}

private fun JsonObject.stringSet(name: String): Set<String> =
    getValue(name).jsonArray.mapTo(linkedSetOf()) { it.jsonPrimitive.content }

private fun isAgentGuide(path: String): Boolean =
    path == "AGENTS.md" || path.endsWith("/AGENTS.md")
