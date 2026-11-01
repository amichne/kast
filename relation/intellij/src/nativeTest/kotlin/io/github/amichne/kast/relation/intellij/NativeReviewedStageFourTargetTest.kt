package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import org.jetbrains.kotlin.psi.KtFile

class NativeReviewedStageFourTargetTest : NativeReviewedValueFixture() {
    override val fixtureSdkName = "reviewed-stage-four-target-jdk"

    fun testStageFourLibraryTargetsUseExactCompilerSymbols() {
        fixture(paths = listOf(STAGE_FOUR_CONTROLS))
        ReadAction.run<RuntimeException> {
            val virtual =
                checkNotNull(
                    getOrCreateProjectBaseDir()
                        .findFileByRelativePath("src/fixture/controls/ReviewedStageFourControls.kt")
                )
            assertReviewedStageFourTargets(psiManager.findFile(virtual) as KtFile)
        }
    }

    private companion object {
        const val STAGE_FOUR_CONTROLS = "src/main/kotlin/fixture/controls/ReviewedStageFourControls.kt"
    }
}
