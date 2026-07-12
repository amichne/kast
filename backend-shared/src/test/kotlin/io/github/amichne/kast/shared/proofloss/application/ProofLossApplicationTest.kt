package io.github.amichne.kast.shared.proofloss.application

import io.github.amichne.kast.api.contract.NonEmptyList
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.shared.proofloss.ir.*
import io.github.amichne.kast.shared.proofloss.model.ProofModel
import io.github.amichne.kast.shared.proofloss.model.ModelBuildResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class ProofLossApplicationTest {
    @Test fun `application preserves supported and unsupported coverage deterministically`() {
        val path = NormalizedPath.ofAbsolute(Path.of("/workspace/Test.kt"))
        fun id(offset: Int) = FunctionId(path, SourceOffset.valid(offset))
        val unsupported = ExtractionResult.Unsupported(
            id(2),
            NonEmptyList(listOf(UnsupportedReason.Loop(SourceSpan(path, SourceOffset.valid(2), SourceOffset.valid(3))))),
        )
        val supported = ExtractionResult.Supported(FunctionIr(id(1), emptySet(), Block()))
        val model = (ProofModel.build(emptyList(), emptyList()) as ModelBuildResult.Valid).model
        val run = ProofLossApplication(model, IrExtractor<ExtractionResult> { it }).run(listOf(unsupported, supported))
        assertEquals(listOf(id(1)), run.analyzedFunctionIds)
        assertEquals(listOf(id(2)), run.unsupported.map { it.functionId })
    }
}
