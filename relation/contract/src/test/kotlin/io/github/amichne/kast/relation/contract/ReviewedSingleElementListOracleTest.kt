package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewedSingleElementListOracleTest {
    @Test
    fun `a first element selection review cannot authorize insertion`() {
        val case = SingleListModelOracleCase()
        val other = case.library.binding()
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.RULE_MISMATCH),
            ValueModelApplication.CollectionInsertion.insertSingle(case.argument(), other),
        )
    }

    @Test
    fun `insertion binding rejects wrong origin bytes version and virtual artifact membership`() {
        val case = SingleListModelOracleCase()
        val variants =
            listOf(
                case.artifact.copy(origin = LibraryModelDeclarationOrigin.SOURCE) to
                    LibraryModelBindingFailure.NOT_LIBRARY,
                case.artifact.copy(binaryDigest = LibraryArtifactDigest.parse("f".repeat(64)).flowRefined()) to
                    LibraryModelBindingFailure.ARTIFACT_MISMATCH,
                case.artifact.copy(version = LibraryArtifactVersion.parse("2.3.21-release-1").flowRefined()) to
                    LibraryModelBindingFailure.VERSION_MISMATCH,
                case.artifact.copy(
                    root = DetachedVirtualFileUrl.parse("jar:///other/kotlin-stdlib.jar!/").flowRefined()
                ) to LibraryModelBindingFailure.DECLARATION_OUTSIDE_ARTIFACT,
            )
        for ((artifact, failure) in variants) {
            assertEquals(
                Refinement.Rejected(failure),
                ReviewedLibraryBinding.fromCompiler(case.invocation, ReviewedValueModel.SingleElementListOf, artifact),
            )
        }
    }

    @Test
    fun `array receiver and different generic arity cannot borrow the fixed single T review`() {
        val case = SingleListModelOracleCase()
        val signatures =
            listOf(
                signature(parameters = listOf("kotlin/Array<out T>")),
                signature(receiver = "T"),
                signature(typeParameters = 2),
            )
        for (signature in signatures) {
            val callable = case.endpoint(signature = signature)
            assertEquals(
                Refinement.Rejected(LibraryModelBindingFailure.CALLABLE_MISMATCH),
                ReviewedLibraryBinding.fromCompiler(
                    case.call(callable = callable),
                    ReviewedValueModel.SingleElementListOf,
                    case.artifact,
                ),
            )
        }
    }

    private fun signature(
        parameters: List<String> = listOf("T"),
        receiver: String? = null,
        typeParameters: Int = 1,
    ) =
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity = "kotlin.collections.listOf",
                rawReceiverType = receiver,
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = parameters,
                rawTypeParameterCount = typeParameters,
            )
            .flowRefined() as CanonicalCompilerSignature.Function
}
