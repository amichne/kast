package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class DeclaredHostCompatibilityPolicyTest {
    private val targets =
        assertInstanceOf<Refinement.Refined<DeclaredIntellijTargets>>(DeclaredIntellijTargets.packaged).value
    private val contract =
        HostedContractDocument(
            runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
            operationRegistryDigest = "sha256:" + "1".repeat(64),
            wireSchemaDigest = "sha256:" + "2".repeat(64),
            capabilities = emptyList(),
        )

    @Test
    fun `each compiled target admits its running line and retains exact actual identities`() {
        for (target in targets.targets) {
            val compiled = compiled(target)
            val policy =
                assertInstanceOf<Refinement.Refined<IdeHostCompatibilityPolicy>>(
                        DeclaredHostCompatibilityPolicy.define(targets, compiled)
                    )
                    .value
            val actual =
                compiled.copy(
                    ideBuild = "${target.releaseLine.value}.30000.200",
                    kotlinPluginBuild = "${target.releaseLine.value}.30001.201-IJ",
                )
            val admitted = assertInstanceOf<IdeHostCompatibilityAdmission.Admitted>(policy.admit(actual.candidate()))
            assertEquals(actual.ideBuild, admitted.compatibility.ideBuild.value)
            assertEquals(actual.kotlinPluginBuild, admitted.compatibility.kotlinPluginBuild.value)
            val other = targets.targets.first { it.releaseLine != target.releaseLine }
            assertInstanceOf<IdeHostCompatibilityAdmission.Rejected>(policy.admit(compiled(other).candidate()))
        }
    }

    @Test
    fun `forged compile pins mixed families and unknown future target cannot create a policy`() {
        val target = targets.targets.last()
        for (invalid in
            listOf(
                compiled(target).copy(ideBuild = "263.1.2"),
                compiled(target).copy(kotlinPluginBuild = "263.1.2-IJ"),
                compiled(target).copy(kotlinPluginBuild = "262.1.2-IJ"),
                compiled(target).copy(ideBuild = "264.1.2", kotlinPluginBuild = "264.1.2-IJ"),
            )) assertInstanceOf<Refinement.Rejected<IdeHostCompatibilityFailure>>(
            DeclaredHostCompatibilityPolicy.define(targets, invalid)
        )
    }

    private fun compiled(target: DeclaredIntellijTarget) =
        HostedCompatibilityDocument(target.ideBuild.value, target.kotlinPluginBuild.value, "0.0.0", contract)
}
