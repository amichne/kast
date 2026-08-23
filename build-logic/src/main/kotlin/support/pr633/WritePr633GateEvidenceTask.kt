package support.pr633

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes one deterministic proof record after all checks for a program gate pass.
 *
 * The task refuses dependency reports from another program projection. SAME_HEAD gates also
 * refuse another repository head. Cross-pull-request gates admit a different head only through the
 * program's explicit merged-predecessor policy and its separate ancestry verifier.
 */
@CacheableTask
abstract class WritePr633GateEvidenceTask : DefaultTask() {
    init {
        facts.convention(emptyMap())
        expectedExternalFacts.convention(emptyMap())
        externalEvidenceBindsHead.convention(true)
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val programFile: RegularFileProperty

    @get:Input
    abstract val gateId: Property<String>

    @get:Input
    @get:Optional
    abstract val headSha: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val externalEvidenceFile: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val expectedExternalKind: Property<String>

    @get:Input
    abstract val expectedExternalFacts: MapProperty<String, String>

    @get:Input
    abstract val externalEvidenceBindsHead: Property<Boolean>

    @get:Input
    abstract val checkIds: ListProperty<String>

    @get:Input
    abstract val facts: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyReports: ConfigurableFileCollection

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun writeEvidence() {
        val programBytes = programFile.get().asFile.readBytes()
        val program = Json.parseToJsonElement(programBytes.decodeToString()).jsonObject
        val programId = program.getValue("programId").jsonPrimitive.content
        val programFingerprint = sha256(programBytes)
        val externalEvidence = if (externalEvidenceFile.isPresent) {
            Json.parseToJsonElement(
                externalEvidenceFile.get().asFile.readText(),
            ).jsonObject.also { evidence ->
                check(evidence.requiredText("status") == "passed") {
                    "External evidence is not passed"
                }
                if (expectedExternalKind.isPresent) {
                    check(evidence.requiredText("kind") == expectedExternalKind.get()) {
                        "Expected external evidence kind ${expectedExternalKind.get()}, " +
                            "received ${evidence.requiredText("kind")}"
                    }
                }
                val observedFacts = evidence.getValue("facts").jsonObject
                    .mapValues { (_, value) -> value.jsonPrimitive.content }
                expectedExternalFacts.get().forEach { (name, expected) ->
                    check(observedFacts[name] == expected) {
                        "External evidence fact '$name' is ${observedFacts[name]}, not $expected"
                    }
                }
            }
        } else {
            null
        }
        val externalHead = externalEvidence?.requiredText("headSha")
        val currentHead = (
            if (externalEvidence != null && externalEvidenceBindsHead.get()) {
                externalHead
            } else {
                headSha.orNull
            }
        )?.also(::requireGitSha)
            ?: error("Gate ${gateId.get()} has no exact head SHA")
        if (externalEvidence != null && externalEvidenceBindsHead.get() && headSha.isPresent) {
            check(headSha.get() == currentHead) {
                "Configured head ${headSha.get()} differs from external evidence $currentHead"
            }
        }
        val declaredGate = program.getValue("gates")
            .let { it as JsonArray }
            .map { it.jsonObject }
            .singleOrNull { it.getValue("id").jsonPrimitive.content == gateId.get() }
            ?: error("Gate ${gateId.get()} is absent from ${programFile.get().asFile}")

        val declaredDependencies = declaredGate.getValue("dependsOn")
            .let { it as JsonArray }
            .map { it.jsonPrimitive.content }
            .sorted()
        val dependencyRevisionPolicy = declaredGate
            .getValue("dependencyRevisionPolicy")
            .jsonPrimitive
            .content

        val dependencyEvidence = linkedMapOf<String, String>()
        dependencyReports.files.sortedBy { it.path }.forEach { file ->
            val bytes = file.readBytes()
            val report = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val dependencyGate = report.requiredText("gateId")
            check(report.requiredText("programId") == programId) {
                "$file belongs to another program"
            }
            check(report.requiredText("programFingerprint") == programFingerprint) {
                "$file belongs to another program projection"
            }
            if (dependencyRevisionPolicy == "SAME_HEAD") {
                check(report.requiredText("headSha") == currentHead) {
                    "$file belongs to ${report.requiredText("headSha")}, not $currentHead"
                }
            }
            check(report.requiredText("status") == "passed") {
                "$file does not contain passed evidence"
            }
            check(dependencyGate !in dependencyEvidence) {
                "Duplicate dependency evidence for $dependencyGate"
            }
            dependencyEvidence[dependencyGate] = sha256(bytes)
        }
        check(dependencyEvidence.keys.sorted() == declaredDependencies) {
            "Gate ${gateId.get()} requires $declaredDependencies but received ${dependencyEvidence.keys.sorted()}"
        }

        val checks = checkIds.get().distinct().sorted()
        check(checks.isNotEmpty()) { "Gate ${gateId.get()} has no checks" }
        val externalFacts = externalEvidence?.get("facts")?.jsonObject
            ?.mapValues { (_, value) -> value.jsonPrimitive.content }
            .orEmpty()
        val factValues = (externalFacts + facts.getOrElse(emptyMap())).toSortedMap()
        check(externalFacts.keys.intersect(facts.getOrElse(emptyMap()).keys).isEmpty()) {
            "Configured facts duplicate external evidence facts"
        }
        factValues.forEach { (name, value) ->
            check(name.isNotBlank()) { "Gate ${gateId.get()} has a blank fact name" }
            check(value.isNotBlank()) { "Gate ${gateId.get()} fact '$name' is blank" }
        }

        val document = buildJsonObject {
            put("schemaVersion", 1)
            put("programId", programId)
            put("programFingerprint", programFingerprint)
            put("gateId", gateId.get())
            put("headSha", currentHead)
            put("status", "passed")
            put(
                "dependencyEvidence",
                buildJsonObject {
                    dependencyEvidence.toSortedMap().forEach(::put)
                },
            )
            put(
                "facts",
                buildJsonObject { factValues.forEach(::put) },
            )
            put(
                "checks",
                buildJsonArray {
                    checks.forEach { checkId ->
                        add(
                            buildJsonObject {
                                put("id", checkId)
                                put("status", "passed")
                            },
                        )
                    }
                },
            )
        }
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), document) + "\n")
    }
}

private fun JsonObject.requiredText(name: String): String =
    getValue(name).jsonPrimitive.content

private fun requireGitSha(value: String) {
    require(value.matches(Regex("[0-9a-f]{40}"))) {
        "Expected a full lowercase Git SHA, received '$value'"
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
