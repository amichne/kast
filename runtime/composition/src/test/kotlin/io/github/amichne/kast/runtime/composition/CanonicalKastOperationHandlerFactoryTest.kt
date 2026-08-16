package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.change.apply.AddDeclarationApplyFailure
import io.github.amichne.kast.change.apply.AddDeclarationApplyResult
import io.github.amichne.kast.change.apply.MutationAdmissionFailure
import io.github.amichne.kast.change.plan.PureAddDeclarationPlanningService
import io.github.amichne.kast.change.plan.PureAddFilePlanningService
import io.github.amichne.kast.change.plan.PureRenameSymbolPlanningService
import io.github.amichne.kast.change.plan.PureReplaceDeclarationPlanningService
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ChangeApplyRejection
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangeIntentDocument
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.runtime.composition.protocol.CanonicalKastOperationHandlerFactory
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmission
import io.github.amichne.kast.runtime.composition.protocol.ChangePlanAdmissionOperations
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.startCoroutine

class CanonicalKastOperationHandlerFactoryTest {
    @Test
    fun `canonical factory shares retained change authority across handlers`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val fixture = InstalledChangeProtocolFixture.create(root)
        val installed = InstalledWorkspaceRoot.admit(root).required()
        val factory = CanonicalKastOperationHandlerFactory.create(
            installed,
            fixture.workspace,
            ChangePlanAdmissionOperations { ChangePlanAdmission.AddFile(fixture.addFile) },
        ).required()
        val planning = ChangePlanningOperations(
            PureAddFilePlanningService(),
            PureAddDeclarationPlanningService(),
            PureReplaceDeclarationPlanningService(),
            PureRenameSymbolPlanningService(),
        )
        val plan = factory.changePlan(planning)
        val apply = factory.changeApply {
            AddDeclarationApplyResult.Rejected(
                AddDeclarationApplyFailure.Admission(MutationAdmissionFailure.WRONG_ROOT),
            )
        }
        val planned = immediate {
            plan.execute(
                ChangePlanRequest(
                    ChangeIntentDocument.AddFile(
                        ProtocolText.parse("src/main/kotlin/sample/Added.kt").required(),
                        ProtocolText.parse("package sample\n\nclass Added\n").required(),
                    ),
                ),
            )
        } as OperationOutcome.Complete

        assertEquals(
            OperationOutcome.Rejected(ChangeApplyRejection.ROOT_MISMATCH),
            immediate {
                apply.execute(ChangeApplyRequest(planned.evidence.payload.planIdentity))
            },
        )
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.required(): Value = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> error("unexpected factory rejection: $failure")
}

private fun <Value> immediate(block: suspend () -> Value): Value {
    var completed: Result<Value>? = null
    block.startCoroutine(
        object : kotlin.coroutines.Continuation<Value> {
            override val context = kotlin.coroutines.EmptyCoroutineContext

            override fun resumeWith(result: Result<Value>) {
                completed = result
            }
        },
    )
    return checkNotNull(completed).getOrThrow()
}
