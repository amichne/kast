package io.github.amichne.kast.shared.proofloss.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProofModelTest {
    private val predicateId = PredicateId.requireValid("zip.five-digits")
    private val boundaryId = BoundaryId.requireValid("address.submit")
    private val predicateCallable = callable("example.isZip", "kotlin.String")
    private val boundaryCallable = callable("example.AddressClient.submit", "kotlin.String")
    private val materializerCallable = callable("example.Zip.parse", "kotlin.String")

    @Test
    fun `valid model owns configuration and resolves every semantic role`() {
        val materializers = mutableSetOf<MaterializerDescriptor>(
            MaterializerDescriptor.NullableWithExit(materializerCallable),
        )
        val obligations = mutableListOf(Obligation(ArgumentIndex.requireValid(0), predicateId))
        val predicates = mutableListOf(
            PredicateDescriptor(predicateId, predicateCallable, ArgumentIndex.requireValid(0), materializers),
        )
        val boundaries = mutableListOf(BoundaryDescriptor(boundaryId, boundaryCallable, obligations))

        val model = assertInstanceOf(
            ModelBuildResult.Valid::class.java,
            ProofModel.build(predicates, boundaries),
        ).model

        predicates.clear()
        boundaries.clear()
        materializers.clear()
        obligations.clear()

        assertEquals(predicateId, model.predicateForCallable(predicateCallable)?.id)
        assertEquals(boundaryId, model.boundaryForCallable(boundaryCallable)?.id)
        assertEquals(predicateId, model.predicateForMaterializer(materializerCallable)?.id)
        assertEquals(1, model.predicates.size)
        assertEquals(1, model.boundaries.size)
    }

    @Test
    fun `invalid model accumulates typed violations`() {
        val unknown = PredicateId.requireValid("unknown")
        val duplicatePredicate = PredicateDescriptor(
            predicateId,
            predicateCallable,
            ArgumentIndex.requireValid(0),
            setOf(MaterializerDescriptor.Total(materializerCallable)),
        )
        val duplicateBoundary = BoundaryDescriptor(
            boundaryId,
            boundaryCallable,
            listOf(
                Obligation(ArgumentIndex.requireValid(0), unknown),
                Obligation(ArgumentIndex.requireValid(0), unknown),
            ),
        )

        val invalid = assertInstanceOf(
            ModelBuildResult.Invalid::class.java,
            ProofModel.build(
                predicates = listOf(duplicatePredicate, duplicatePredicate),
                boundaries = listOf(duplicateBoundary, duplicateBoundary),
            ),
        )

        assertTrue(invalid.violations.value.any { it is ModelViolation.DuplicatePredicateId })
        assertTrue(invalid.violations.value.any { it is ModelViolation.DuplicateBoundaryId })
        assertTrue(invalid.violations.value.any { it is ModelViolation.UnknownPredicate })
        assertTrue(invalid.violations.value.any { it is ModelViolation.DuplicateObligation })
    }

    @Test
    fun `one callable cannot acquire multiple semantic roles`() {
        val invalid = assertInstanceOf(
            ModelBuildResult.Invalid::class.java,
            ProofModel.build(
                predicates = listOf(
                    PredicateDescriptor(
                        predicateId,
                        predicateCallable,
                        ArgumentIndex.requireValid(0),
                        setOf(MaterializerDescriptor.Total(boundaryCallable)),
                    ),
                ),
                boundaries = listOf(
                    BoundaryDescriptor(
                        boundaryId,
                        boundaryCallable,
                        listOf(Obligation(ArgumentIndex.requireValid(0), predicateId)),
                    ),
                ),
            ),
        )

        val conflict = assertInstanceOf(
            ModelViolation.ConflictingCallableRoles::class.java,
            invalid.violations.value.singleOrNull { it is ModelViolation.ConflictingCallableRoles },
        )
        assertEquals(setOf(CallableRole.MATERIALIZER, CallableRole.BOUNDARY), conflict.roles)
    }

    @Test
    fun `constrained primitives reject blank and negative boundary input`() {
        assertInstanceOf(TextParseResult.Blank::class.java, PredicateId.parse("  "))
        assertInstanceOf(TextParseResult.Blank::class.java, KotlinTypeKey.parse(""))
        assertInstanceOf(ArgumentIndexParseResult.Negative::class.java, ArgumentIndex.parse(-1))
    }

    private fun callable(id: String, vararg parameters: String): CallableKey = CallableKey(
        callableId = CallableIdKey.requireValid(id),
        kind = CallableKind.FUNCTION,
        receiverType = null,
        contextParameterTypes = emptyList(),
        valueParameterTypes = parameters.map(KotlinTypeKey::requireValid),
        genericArity = 0,
    )
}

private fun PredicateId.Companion.requireValid(raw: String): PredicateId =
    (parse(raw) as TextParseResult.Valid).value

private fun BoundaryId.Companion.requireValid(raw: String): BoundaryId =
    (parse(raw) as TextParseResult.Valid).value

private fun CallableIdKey.Companion.requireValid(raw: String): CallableIdKey =
    (parse(raw) as TextParseResult.Valid).value

private fun KotlinTypeKey.Companion.requireValid(raw: String): KotlinTypeKey =
    (parse(raw) as TextParseResult.Valid).value

private fun ArgumentIndex.Companion.requireValid(raw: Int): ArgumentIndex =
    (parse(raw) as ArgumentIndexParseResult.Valid).value
