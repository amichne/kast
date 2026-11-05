package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class HostedSupportedTargetsTest {
    @Test
    fun `packaged Control admits each declared live SDK pair and retains actual patch identities`() {
        val required = (requiredHostedCompatibilityPolicy() as Refinement.Refined).value
        for (line in listOf("262", "263")) {
            val document = document("$line.20000.200", "$line.20001.201-IJ")
            val admitted =
                assertInstanceOf(IdeHostCompatibilityAdmission.Admitted::class.java, required.admit(document))
            assertEquals(document.ideBuild, admitted.compatibility.ideBuild.value)
            assertEquals(document.kotlinPluginBuild, admitted.compatibility.kotlinPluginBuild.value)
            assertEquals("0.0.999", admitted.compatibility.kastPluginVersion.value)
        }
    }

    @Test
    fun `unknown lines mixed SDK pairs and changed contracts retain finite mismatches`() {
        val required = (requiredHostedCompatibilityPolicy() as Refinement.Refined).value
        fun rejected(document: HostedCompatibilityDocument, field: IdeHostCompatibilityField) {
            val result = assertInstanceOf(IdeHostCompatibilityAdmission.Rejected::class.java, required.admit(document))
            val failure = assertInstanceOf(IdeHostCompatibilityFailure.Mismatch::class.java, result.failure)
            assertEquals(field, failure.mismatch.field)
        }
        rejected(document("264.1.2", "264.1.2-IJ"), IdeHostCompatibilityField.IDE_BUILD)
        rejected(document("263.1.2", "262.1.2-IJ"), IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD)
        rejected(document("262.1.2", "263.1.2-IJ"), IdeHostCompatibilityField.KOTLIN_PLUGIN_BUILD)
        rejected(
            document("263.1.2", "263.1.2-IJ")
                .copy(
                    hostedContract =
                        CanonicalHostedContract.document.copy(wireSchemaDigest = "sha256:" + "0".repeat(64))
                ),
            IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
        )
    }

    private fun document(ide: String, kotlin: String) =
        HostedCompatibilityDocument(ide, kotlin, "0.0.999", CanonicalHostedContract.document)
}
