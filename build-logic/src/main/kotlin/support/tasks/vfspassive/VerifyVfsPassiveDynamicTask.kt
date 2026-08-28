package support.tasks.vfspassive

import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import kotlinx.serialization.Serializable
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.xml.sax.SAXException

@UntrackedTask(because = "KVP-033 is a non-cacheable dynamic system gate")
abstract class VerifyVfsPassiveDynamicTask : DefaultTask() {
    @get:InputDirectory abstract val runtimeTestResults: DirectoryProperty
    @get:InputDirectory abstract val workspaceTestResults: DirectoryProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val evidence = REQUIRED_DYNAMIC_TESTS.map { expected ->
            val root = when (expected.authority) {
                DynamicProofAuthority.VFS_EVENT_STORM -> workspaceTestResults.get().asFile.toPath()
                else -> runtimeTestResults.get().asFile.toPath()
            }
            when (val admitted = readTestClass(root, expected)) {
                is DynamicTestAdmission.Complete -> admitted.evidence
                is DynamicTestAdmission.Rejected -> reject(admitted.failure)
            }
        }
        val proof = baselineDynamicProof(evidence)
        when (val admitted = admitVfsPassiveDynamicProof(proof)) {
            is DynamicProofAdmission.Complete -> write(admitted.proof)
            is DynamicProofAdmission.Qualified -> reject(admitted.failure)
            is DynamicProofAdmission.Rejected -> reject(admitted.failure)
        }
        logger.lifecycle(
            "KVP-033 admitted {} dynamic cases across {} non-cacheable test processes",
            proof.testCaseCount,
            proof.testProcessCount,
        )
    }

    private fun write(proof: VfsPassiveDynamicProofDocument) {
        val target = reportFile.get().asFile.toPath()
        try {
            Files.createDirectories(target.parent)
            Files.writeString(
                target,
                KVP033_DYNAMIC_JSON.encodeToString(
                    VfsPassiveDynamicProofDocument.serializer(),
                    proof,
                ) + "\n",
                StandardCharsets.UTF_8,
            )
        } catch (_: IOException) {
            reject(DynamicProofFailure.REPORT_WRITE_REJECTED)
        } catch (_: SecurityException) {
            reject(DynamicProofFailure.REPORT_WRITE_REJECTED)
        }
    }

    private fun reject(failure: DynamicProofFailure): Nothing =
        throw GradleException("KVP-033 dynamic VFS-passive proof rejected: $failure")
}

@UntrackedTask(because = "Exercises every fixed KVP-033 misuse on each proof run")
abstract class VerifyVfsPassiveDynamicNegativeTask : DefaultTask() {
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val baseline = baselineDynamicProof(fixedDynamicEvidence())
        val mutations = dynamicMisuseFixtures(baseline)
        mutations.forEach { fixture ->
            val observed = admitVfsPassiveDynamicProof(fixture.document)
            if (observed !is DynamicProofAdmission.Rejected || observed.failure != fixture.failure) {
                throw GradleException(
                    "KVP-033 misuse ${fixture.name} was not rejected as ${fixture.failure}",
                )
            }
        }
        val target = evidenceFile.get().asFile.toPath()
        try {
            Files.createDirectories(target.parent)
            Files.writeString(
                target,
                KVP033_DYNAMIC_JSON.encodeToString(
                    DynamicNegativeEvidenceDocument.serializer(),
                    DynamicNegativeEvidenceDocument(
                        schemaVersion = 1,
                        taskId = "KVP-033",
                        outcome = DynamicProofOutcome.COMPLETE,
                        rejectedFixtureCount = mutations.size,
                    ),
                ) + "\n",
                StandardCharsets.UTF_8,
            )
        } catch (_: IOException) {
            throw GradleException("KVP-033 negative evidence write rejected")
        } catch (_: SecurityException) {
            throw GradleException("KVP-033 negative evidence write rejected")
        }
        logger.lifecycle("KVP-033 rejected all {} dynamic-safety misuses", mutations.size)
    }
}

@Serializable
internal data class DynamicNegativeEvidenceDocument(
    val schemaVersion: Int,
    val taskId: String,
    val outcome: DynamicProofOutcome,
    val rejectedFixtureCount: Int,
)

internal data class ExpectedDynamicTest(
    val authority: DynamicProofAuthority,
    val className: String,
    val testCount: Int,
)

private sealed interface DynamicTestAdmission {
    data class Complete(val evidence: DynamicTestClassDocument) : DynamicTestAdmission
    data class Rejected(val failure: DynamicProofFailure) : DynamicTestAdmission
}

private sealed interface XmlIntAttributeRefinement {
    data class Complete(val value: Int) : XmlIntAttributeRefinement
    data object Rejected : XmlIntAttributeRefinement
}

/**
 * Proof transition: exact JUnit XML path plus expected selector -> `DynamicTestAdmission`.
 *
 * Establishes one failure-free, non-skipped dynamic test-class execution with an exact case count.
 * Malformed, missing, failed, or incomplete test output remains closed [DynamicProofFailure] data;
 * XML attribute extraction is permitted only at this Gradle boundary.
 */
private fun readTestClass(root: Path, expected: ExpectedDynamicTest): DynamicTestAdmission {
    val file = root.resolve("TEST-${expected.className}.xml")
    val raw = try {
        if (!Files.isRegularFile(file) || Files.size(file) > 1_048_576L) return testRejected(
            DynamicProofFailure.INPUT_UNREADABLE,
        )
        Files.readAllBytes(file)
    } catch (_: IOException) {
        return testRejected(DynamicProofFailure.INPUT_UNREADABLE)
    } catch (_: SecurityException) {
        return testRejected(DynamicProofFailure.INPUT_UNREADABLE)
    }
    val suite = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        factory.isExpandEntityReferences = false
        factory.isXIncludeAware = false
        factory.newDocumentBuilder().parse(raw.inputStream()).documentElement
    } catch (_: ParserConfigurationException) {
        return testRejected(DynamicProofFailure.MALFORMED_TEST_RESULT)
    } catch (_: SAXException) {
        return testRejected(DynamicProofFailure.MALFORMED_TEST_RESULT)
    } catch (_: IOException) {
        return testRejected(DynamicProofFailure.MALFORMED_TEST_RESULT)
    }
    val className = suite.getAttribute("name")
    val tests = when (val refined = suite.refineIntAttribute("tests")) {
        is XmlIntAttributeRefinement.Complete -> refined.value
        XmlIntAttributeRefinement.Rejected -> return testRejected(
            DynamicProofFailure.MALFORMED_TEST_RESULT,
        )
    }
    val failures = when (val refined = suite.refineIntAttribute("failures")) {
        is XmlIntAttributeRefinement.Complete -> refined.value
        XmlIntAttributeRefinement.Rejected -> return testRejected(
            DynamicProofFailure.MALFORMED_TEST_RESULT,
        )
    }
    val errors = when (val refined = suite.refineIntAttribute("errors")) {
        is XmlIntAttributeRefinement.Complete -> refined.value
        XmlIntAttributeRefinement.Rejected -> return testRejected(
            DynamicProofFailure.MALFORMED_TEST_RESULT,
        )
    }
    val skipped = when (val refined = suite.refineIntAttribute("skipped")) {
        is XmlIntAttributeRefinement.Complete -> refined.value
        XmlIntAttributeRefinement.Rejected -> return testRejected(
            DynamicProofFailure.MALFORMED_TEST_RESULT,
        )
    }
    val duration = try {
        BigDecimal(suite.getAttribute("time")).multiply(BigDecimal(1_000))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
    } catch (_: NumberFormatException) {
        return testRejected(DynamicProofFailure.MALFORMED_TEST_RESULT)
    } catch (_: ArithmeticException) {
        return testRejected(DynamicProofFailure.MALFORMED_TEST_RESULT)
    }
    if (className != expected.className) return testRejected(
        DynamicProofFailure.TEST_CLASS_SET_MISMATCH,
    )
    if (failures != 0 || errors != 0 || skipped != 0) return testRejected(
        DynamicProofFailure.TEST_FAILURE_OBSERVED,
    )
    if (tests != expected.testCount) return testRejected(DynamicProofFailure.TEST_COUNT_MISMATCH)
    return DynamicTestAdmission.Complete(DynamicTestClassDocument(
        expected.authority,
        expected.className,
        tests,
        duration,
        sha256(raw),
        DynamicProofOutcome.COMPLETE,
    ))
}

/**
 * Proof transition: XML attribute text -> `XmlIntAttributeRefinement`.
 *
 * Establishes one present, non-negative integer attribute. Missing, malformed, or negative text is
 * closed rejection; raw XML text may be extracted only in the JUnit boundary.
 */
private fun org.w3c.dom.Element.refineIntAttribute(
    name: String,
): XmlIntAttributeRefinement {
    val value = getAttribute(name).toIntOrNull() ?: return XmlIntAttributeRefinement.Rejected
    return if (value >= 0) XmlIntAttributeRefinement.Complete(value)
    else XmlIntAttributeRefinement.Rejected
}

private data class DynamicMisuseFixture(
    val name: String,
    val document: VfsPassiveDynamicProofDocument,
    val failure: DynamicProofFailure,
)

private fun dynamicMisuseFixtures(
    baseline: VfsPassiveDynamicProofDocument,
): List<DynamicMisuseFixture> {
    val effectMutations = listOf(
        "refresh" to baseline.prohibitedEffects.copy(refresh = 1, total = 1),
        "import" to baseline.prohibitedEffects.copy(gradleImport = 1, total = 1),
        "walk" to baseline.prohibitedEffects.copy(repositoryWalk = 1, total = 1),
        "blocking-read" to baseline.prohibitedEffects.copy(blockingRead = 1, total = 1),
        "listener-work" to baseline.prohibitedEffects.copy(listenerSemanticWork = 1, total = 1),
        "edt-work" to baseline.prohibitedEffects.copy(edtSemanticWork = 1, total = 1),
    ).map { (name, effects) -> DynamicMisuseFixture(
        name,
        baseline.copy(prohibitedEffects = effects),
        DynamicProofFailure.PROHIBITED_EFFECT_OBSERVED,
    ) }
    return effectMutations + listOf(
        DynamicMisuseFixture(
            "concurrent-read",
            baseline.copy(maximumConcurrentReads = 2),
            DynamicProofFailure.CONCURRENCY_BOUND_REJECTED,
        ),
        DynamicMisuseFixture(
            "stale-acceptance",
            baseline.copy(staleAcceptedCount = 1),
            DynamicProofFailure.STALE_RESULT_ACCEPTED,
        ),
    )
}

private fun testRejected(failure: DynamicProofFailure) =
    DynamicTestAdmission.Rejected(failure)
