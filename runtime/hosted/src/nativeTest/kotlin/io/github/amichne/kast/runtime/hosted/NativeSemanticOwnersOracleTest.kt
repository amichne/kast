package io.github.amichne.kast.runtime.hosted

import junit.framework.TestCase

/** Each control changes one authored semantic fact before the public result exists. */
class NativeSemanticOwnersOracleTest : TestCase() {
    private val case = NativeSemanticOwnersCases.named("R2_depth_two_lambda_constructor")
    private val expected = case.expected()
    private val localCase = NativeSemanticOwnersCases.named("R2_local_function_function")
    private val localExpected = localCase.expected()
    private val localSignature = localExpected.supplierSignature as NativeSemanticOwnersSupplierSignature.LocalFunction

    fun testAcceptsTheAuthoredCompletePopulation() {
        assertEquals(REQUIRED_CASE_COUNT, NativeSemanticOwnersCases.required.size)
        assertEquals(REQUIRED_CASE_COUNT, NativeSemanticOwnersCases.required.map { it.id }.toSet().size)
        assertEquals(REQUIRED_CASE_COUNT, NativeSemanticOwnersCases.required.map { it.method }.toSet().size)
        NativeSemanticOwnersCases.required.forEach { entry ->
            assertTrue(entry.id, entry.accepts(entry.expected()))
            assertTrue(entry.id, entry.source().isNotEmpty())
        }
    }

    fun testRejectsAnOmittedTargetOccurrence() = assertFalse(case.accepts(expected.copy(targets = emptySet())))

    fun testRejectsASameNameTargetFromAnotherPackage() =
        assertFalse(
            case.accepts(expected.copy(targets = setOf(expected.targets.single().copy(identity = "unrelated.Target"))))
        )

    fun testRejectsAnInventedNamedCaller() =
        assertFalse(case.accepts(expected.copy(namedCallers = setOf("semanticowners.entry"))))

    fun testRejectsAnInventedExactlyOnceEffect() =
        assertFalse(case.accepts(expected.copy(effect = NativeSemanticOwnersEffect.EXACTLY_ONCE)))

    fun testRejectsQualifiedCompletion() =
        assertFalse(case.accepts(expected.copy(completion = NativeSemanticOwnersCompletion.QUALIFIED)))

    fun testRejectsAnUnresolvedObligation() {
        assertFalse(case.accepts(expected.copy(obligations = setOf("NESTED_CALLBACK_EXECUTION"))))
        assertFalse(case.accepts(expected.copy(scan = NativeSemanticOwnersScan.INCOMPLETE)))
        assertFalse(case.accepts(expected.copy(scan = NativeSemanticOwnersScan.NOT_APPLICABLE)))
    }

    fun testRejectsAMissingOuterInvocationRoute() {
        assertFalse(case.accepts(expected.copy(directOuterCalls = expected.directOuterCalls.drop(1).toSet())))
        assertFalse(case.accepts(expected.copy(executionRoute = expected.executionRoute.drop(1))))
        assertFalse(case.accepts(expected.copy(executionRoute = expected.executionRoute.reversed())))
        val first = expected.executionRoute.first()
        assertFalse(case.accepts(expected.copy(executionRoute = listOf(first, first))))
        val wrongBody = first.copy(body = NativeSemanticOwnersRange(0, "Target".length))
        assertFalse(case.accepts(expected.copy(executionRoute = listOf(wrongBody) + expected.executionRoute.drop(1))))
        val parent = first.owner as NativeSemanticOwnersExecutionParent.Named
        val wrongParent = first.copy(owner = parent.copy(identity = "semanticowners.unrelated"))
        assertFalse(case.accepts(expected.copy(executionRoute = listOf(wrongParent) + expected.executionRoute.drop(1))))
        val wrongIdentity =
            first.copy(
                owner = parent.copy(compilerIdentity = "canonical-signature-sha256-v1|" + "0".repeat(SHA256_HEX_WIDTH))
            )
        assertFalse(
            case.accepts(expected.copy(executionRoute = listOf(wrongIdentity) + expected.executionRoute.drop(1)))
        )
        assertFalse(case.accepts(expected.copy(executionOwner = case.supplierDeclaration)))
    }

    fun testRejectsAnotherWorkspaceGeneration() = assertFalse(case.accepts(expected.copy(generation = 2L)))

    fun testRejectsAnUnrelatedSupplier() {
        assertNamedSupplierMutations()
        assertFalse(case.accepts(expected.copy(supplierIdentity = "semanticowners.unrelated")))
        val wrongOwner =
            localSignature.copy(ownerIdentity = "canonical-signature-sha256-v1|" + "0".repeat(SHA256_HEX_WIDTH))
        assertFalse(localCase.accepts(localExpected.copy(supplierSignature = wrongOwner)))
        assertFalse(
            localCase.accepts(
                localExpected.copy(
                    supplierSignature =
                        localSignature.copy(ownerDeclaration = NativeSemanticOwnersRange(0, "Target".length))
                )
            )
        )
        assertFalse(
            localCase.accepts(localExpected.copy(supplierSignature = localSignature.copy(lexicalOwners = emptyList())))
        )
        assertFalse(
            localCase.accepts(
                localExpected.copy(
                    supplierSignature = localSignature.copy(valueParameterTypes = listOf("kotlin.String"))
                )
            )
        )
    }

    private fun assertNamedSupplierMutations() {
        val named = NativeSemanticOwnersCases.named("R2_named_function_function")
        val namedExpected = named.expected()
        val function = namedExpected.supplierSignature as NativeSemanticOwnersSupplierSignature.Function
        assertFalse(
            named.accepts(
                namedExpected.copy(
                    supplierSignature =
                        function.copy(
                            compilerIdentity = "canonical-signature-sha256-v1|" + "0".repeat(SHA256_HEX_WIDTH)
                        )
                )
            )
        )
        assertFalse(
            named.accepts(
                namedExpected.copy(supplierSignature = function.copy(valueParameterTypes = listOf("kotlin/Int")))
            )
        )
        val propertyCase = NativeSemanticOwnersCases.named("R2_top_property_constructor")
        val propertyExpected = propertyCase.expected()
        val property = propertyExpected.supplierSignature as NativeSemanticOwnersSupplierSignature.Property
        assertFalse(
            propertyCase.accepts(
                propertyExpected.copy(supplierSignature = property.copy(returnType = "kotlin/Lazy<kotlin/Unit>"))
            )
        )
        val initializer = NativeSemanticOwnersCases.named("R2_init_block_function")
        val initializerExpected = initializer.expected()
        val klass = initializerExpected.supplierSignature as NativeSemanticOwnersSupplierSignature.ClassLike
        assertFalse(
            initializer.accepts(initializerExpected.copy(supplierSignature = klass.copy(identity = "unrelated.Owner")))
        )
    }

    fun testRejectsAnOccurrenceOutsideTheExecutionOwner() =
        assertFalse(
            case.accepts(
                expected.copy(
                    targets =
                        setOf(
                            expected.targets.single().copy(occurrence = NativeSemanticOwnersRange(0, "Target".length))
                        )
                )
            )
        )
}

private const val REQUIRED_CASE_COUNT = 26

private const val SHA256_HEX_WIDTH = 64
