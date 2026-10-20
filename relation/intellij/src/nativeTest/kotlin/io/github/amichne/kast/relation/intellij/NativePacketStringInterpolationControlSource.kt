package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueSite
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

/** Supplemental scalar positions use the public native position and read ports. Original S3 stays unchanged. */
internal class NativePacketStringInterpolationControlSource(
    private val project: Project,
    private val fixture: NativePacketValueFixture,
) {
    fun resolve(input: PacketExpression): ValueSite {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = input.enclosing,
                    anchor = input.anchor,
                    domain =
                        RelationRequest.start(
                            subject = RelationEndpoint.subject(input.enclosing),
                            meaning = RelationMeaning.References,
                            budget = fixture.budget,
                        ),
                    model = fixture.model,
                )
        }
        assertTrue(
            "The real scalar source position must resolve before interpolation assertions",
            result is ValueSiteResolution.Found,
        )
        return (result as ValueSiteResolution.Found).site.also { site ->
            assertEquals(input.anchor, site.range)
            assertEquals(input.enclosing.compilerIdentity, site.enclosing.compilerIdentity)
        }
    }

    fun read(source: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("The scalar control needs an actual native read", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step.also { step ->
            println(
                "INTERPOLATION_CONTROL source=${source.range} " +
                    "transfers=${step.transfers.map { transfer -> transfer.kind to transfer.target.range }} " +
                    "causes=${step.obligations.map { obligation -> obligation.cause }}"
            )
        }
    }

    companion object {
        const val PATH = "src/main/kotlin/fixture/controls/StringInterpolationControls.kt"
        const val OWNER = "StringInterpolationControls"
    }
}
