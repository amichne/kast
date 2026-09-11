import conventions.VerifyJsonContractsTask
import conventions.jsoncontracts.JsonContractAllowance
import conventions.jsoncontracts.JsonContractBaselineDocument
import conventions.jsoncontracts.JsonContractEvidence
import conventions.jsoncontracts.JsonContractReport
import conventions.jsoncontracts.JsonContractScanRequest
import conventions.jsoncontracts.JsonContractViolation
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JsonContractsTaskTest {
    @TempDir lateinit var root: Path

    @Test
    fun `both root gates run isolated syntax verification and reject changed contracts`() {
        val runner = fixture()
        val source = root.resolve("src/main/kotlin/Contract.kt")
        source.parent.createDirectories()
        source.writeText(
            """
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.encodeToString
            import kotlinx.serialization.json.Json
            @Serializable data class Contract(val status: String)
            fun encode() = Json.encodeToString(Contract("ready"))
            """
                .trimIndent()
        )
        val ignored = root.resolve("build-logic/.kotlin/Unparsed.kt")
        ignored.parent.createDirectories()
        ignored.writeText("fun broken( {")
        baseline(emptyList())
        val accepted = runner.withArguments("check", "--configuration-cache", "--console=plain").build()
        assertEquals(TaskOutcome.SUCCESS, accepted.task(":verifyJsonContracts")?.outcome)
        assertEquals(JsonContractEvidence.KOTLIN_PSI_SYNTAX, report().evidence)
        assertTrue(report().violations.isEmpty())

        source.writeText(
            "import kotlinx.serialization.json.buildJsonObject\nfun encode() = buildJsonObject { put(\"status\", \"ready\") }"
        )
        runner.withArguments("check", "--configuration-cache", "--console=plain").buildAndFail()
        val finding = report().findings.single()
        assertTrue(report().violations.single() is JsonContractViolation.Unapproved)
        baseline(
            listOf(JsonContractAllowance(finding.fingerprint, finding.count, "Exact isolated negative test fixture."))
        )
        runner.withArguments("check", "--configuration-cache", "--console=plain").build()
        val before = root.resolve("config/json-contracts/baseline.json").readText()
        source.writeText(
            source.readText().replace("put(\"status\", \"ready\")", "put(\"status\", \"ready\"); put(\"extra\", true)")
        )
        val rejected =
            runner.withArguments("productBuildGate", "--configuration-cache", "--console=plain").buildAndFail()
        assertEquals(TaskOutcome.FAILED, rejected.task(":verifyJsonContracts")?.outcome)
        assertTrue(report().violations.any { it is JsonContractViolation.Unapproved })
        assertTrue(report().violations.any { it is JsonContractViolation.StaleAllowance })
        assertEquals(before, root.resolve("config/json-contracts/baseline.json").readText())
        assertFalse(root.resolve("build/reports/json-contracts/report.json").readText().contains("ready"))
    }

    private fun fixture(): GradleRunner {
        val taskClasspath =
            listOf(
                    VerifyJsonContractsTask::class.java,
                    JsonContractScanRequest::class.java,
                    Json::class.java,
                    KSerializer::class.java,
                )
                .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
        val parserClasspath = System.getProperty("kast.jsonContractParserClasspath").split(java.io.File.pathSeparator)
        root.resolve("settings.gradle").writeText("rootProject.name = 'json-contract-guard-fixture'\n")
        root
            .resolve("build.gradle")
            .writeText(
                """
            buildscript { dependencies { classpath files(${groovyPaths(taskClasspath)}) } }
            apply plugin: 'base'
            conventions.JsonContractVerificationKt.registerJsonContractVerification(project, files(${groovyPaths(parserClasspath)}))
            tasks.register('productBuildGate')
        """
                    .trimIndent()
            )
        return GradleRunner.create().withProjectDir(root.toFile())
    }

    private fun groovyPaths(paths: List<String>): String =
        paths.joinToString(",") {
            "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'"
        }

    private fun baseline(allowances: List<JsonContractAllowance>) {
        val file = root.resolve("config/json-contracts/baseline.json")
        file.parent.createDirectories()
        file.writeText(Json.encodeToString(JsonContractBaselineDocument(1, allowances)))
    }

    private fun report(): JsonContractReport =
        Json.decodeFromString(root.resolve("build/reports/json-contracts/report.json").readText())
}
