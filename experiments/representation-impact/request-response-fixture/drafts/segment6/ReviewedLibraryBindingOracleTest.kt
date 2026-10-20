package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** These cases assume issued metadata. Native compiler and artifact probes remain separate evidence. */
class ReviewedLibraryBindingOracleTest {
    @Test
    fun `reviewed model pins the independently supplied binary source version and overload`() {
        val model = ReviewedValueModel.FirstOrNull
        assertEquals(ReviewedModelOracleCase.BINARY_SHA, model.artifact.binaryDigest.value)
        assertEquals(ReviewedModelOracleCase.SOURCE_SHA, model.artifact.sourceDigest.value)
        assertEquals(ReviewedModelOracleCase.VERSION, model.artifact.version.value)
        assertEquals(ReviewedModelOracleCase.signature(), model.callable)
        assertEquals("source-sha256:" + ReviewedModelOracleCase.SOURCE_SHA, model.reference.model.provenance.value)
    }

    @Test
    fun `exact library binding keeps the issued invocation and observed artifact`() {
        val case = ReviewedModelOracleCase()
        val result = ReviewedLibraryBinding.fromCompiler(case.invocation, ReviewedValueModel.FirstOrNull, case.artifact)
        assertTrue(result is Refinement.Refined, "Exact assumed library metadata must bind")
        val bound = (result as Refinement.Refined).value
        assertSame(case.invocation, bound.invocation)
        assertSame(case.artifact, bound.artifact)
        assertSame(ReviewedValueModel.FirstOrNull, bound.model)
    }

    @Test
    fun `source origin cannot borrow the reviewed library rule`() {
        val case = ReviewedModelOracleCase()
        reject(
            case,
            case.artifact.copy(origin = LibraryModelDeclarationOrigin.SOURCE),
            LibraryModelBindingFailure.NOT_LIBRARY,
        )
    }

    @Test
    fun `unsupported origin cannot borrow the reviewed library rule`() {
        val case = ReviewedModelOracleCase()
        reject(
            case,
            case.artifact.copy(origin = LibraryModelDeclarationOrigin.UNSUPPORTED),
            LibraryModelBindingFailure.NOT_LIBRARY,
        )
    }

    @Test
    fun `artifact basis must match the exact invocation basis`() {
        val case = ReviewedModelOracleCase()
        val foreign = ReviewedModelOracleCase(generation = 2)
        reject(case, case.artifact.copy(basis = foreign.host.lease.identity), LibraryModelBindingFailure.BASIS_MISMATCH)
    }

    @Test
    fun `another valid binary digest does not identify the reviewed bytes`() {
        val case = ReviewedModelOracleCase()
        val changed = case.artifact.copy(binaryDigest = LibraryArtifactDigest.parse("f".repeat(64)).flowRefined())
        reject(case, changed, LibraryModelBindingFailure.ARTIFACT_MISMATCH)
    }

    @Test
    fun `the same binary digest does not erase a changed artifact version`() {
        val case = ReviewedModelOracleCase()
        val changed = case.artifact.copy(version = LibraryArtifactVersion.parse("2.3.21-release-1").flowRefined())
        reject(case, changed, LibraryModelBindingFailure.VERSION_MISMATCH)
    }

    @Test
    fun `a declaration outside the observed virtual artifact root cannot bind`() {
        val case = ReviewedModelOracleCase()
        val changed =
            case.artifact.copy(root = DetachedVirtualFileUrl.parse("jar:///other/kotlin-stdlib.jar!/").flowRefined())
        reject(case, changed, LibraryModelBindingFailure.DECLARATION_OUTSIDE_ARTIFACT)
    }

    @Test
    fun `the iterable overload cannot borrow the list overload review`() {
        val case = ReviewedModelOracleCase()
        val overload = case.endpoint(signature = ReviewedModelOracleCase.signature("kotlin/collections/Iterable<T>"))
        assertEquals(
            Refinement.Rejected(LibraryModelBindingFailure.CALLABLE_MISMATCH),
            ReviewedLibraryBinding.fromCompiler(
                case.call(endpoint = overload),
                ReviewedValueModel.FirstOrNull,
                case.artifact,
            ),
        )
    }

    @Test
    fun `a workspace declaration cannot borrow a library artifact flag`() {
        val case = ReviewedModelOracleCase()
        val source = case.endpoint(file = case.host.file)
        assertEquals(
            Refinement.Rejected(LibraryModelBindingFailure.DECLARATION_OUTSIDE_ARTIFACT),
            ReviewedLibraryBinding.fromCompiler(
                case.call(endpoint = source),
                ReviewedValueModel.FirstOrNull,
                case.artifact,
            ),
        )
    }

    @Test
    fun `fresh equivalent admissions keep semantic equality without shared object identity`() {
        val case = ReviewedModelOracleCase()
        val first = case.binding()
        val second =
            ReviewedLibraryBinding.fromCompiler(
                    case.call(),
                    ReviewedValueModel.FirstOrNull,
                    case.artifact.copy(),
                )
                .flowRefined()
        assertNotSame(first, second)
        assertNotSame(first.invocation, second.invocation)
        assertNotSame(first.artifact, second.artifact)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `a changed actual module name keeps its distinct compiler artifact evidence`() {
        val case = ReviewedModelOracleCase()
        val original = case.binding()
        val changedArtifact =
            case.artifact.copy(
                moduleName =
                    LibraryModelModuleName.parse("Gradle: org.jetbrains.kotlin:kotlin-stdlib:2.3.20").flowRefined()
            )
        val changed =
            ReviewedLibraryBinding.fromCompiler(
                    case.invocation,
                    ReviewedValueModel.FirstOrNull,
                    changedArtifact,
                )
                .flowRefined()
        assertNotEquals(original, changed)
        assertSame(changedArtifact, changed.artifact)
        assertEquals("Gradle: org.jetbrains.kotlin:kotlin-stdlib:2.3.20", changed.artifact.moduleName.value)
    }

    private fun reject(
        case: ReviewedModelOracleCase,
        artifact: LibraryArtifactObservation,
        failure: LibraryModelBindingFailure,
    ) {
        assertEquals(
            Refinement.Rejected(failure),
            ReviewedLibraryBinding.fromCompiler(case.invocation, ReviewedValueModel.FirstOrNull, artifact),
        )
    }
}
