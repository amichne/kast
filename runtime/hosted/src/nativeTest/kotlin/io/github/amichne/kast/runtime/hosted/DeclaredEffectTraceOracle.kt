package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.QueryDeclarationKindDocument
import junit.framework.TestCase

internal enum class DeclaredTraceEffect(val id: String, val api: String) {
    EXACTLY_ONCE("exactly_once", "exactlyOnce"),
    AT_MOST_ONCE("at_most_once", "atMostOnce"),
    AT_LEAST_ONCE("at_least_once", "atLeastOnce"),
    UNKNOWN("unknown", "unknown"),
}

internal enum class DeclaredTraceArgument(val id: String, val ownerSuffix: String) {
    LAMBDA("lambda", "Lambda"),
    ANONYMOUS("anonymous", "Anonymous"),
}

internal enum class DeclaredTraceTarget(val id: String, val ownerSuffix: String, val identity: String) {
    FUNCTION("function", "Function", "proof.target"),
    CONSTRUCTOR("constructor", "Constructor", "proof.Target"),
}

/** Expected values come from authored inputs, never from a Kast result. */
internal class DeclaredTraceCase(
    declaredEffect: DeclaredTraceEffect,
    argument: DeclaredTraceArgument,
    declaredTarget: DeclaredTraceTarget,
) {
    val id = "r1-${declaredEffect.id}-${argument.id}-${declaredTarget.id}"
    val owner = declaredEffect.api + argument.ownerSuffix + declaredTarget.ownerSuffix
    val api = declaredEffect.api
    val effect = declaredEffect.name
    val target = declaredTarget.identity
    val declarationKind =
        when (declaredTarget) {
            DeclaredTraceTarget.FUNCTION -> QueryDeclarationKindDocument.FUNCTION
            DeclaredTraceTarget.CONSTRUCTOR -> QueryDeclarationKindDocument.CLASS
        }
    val source = DeclaredEffectArchive.resource("$id.kt").toString(Charsets.UTF_8)
    val targetStart = source.indexOf("proof.") + "proof.".length
    val targetEnd = targetStart + target.substringAfterLast('.').length
    val bodyStart =
        source.indexOf(
            when (argument) {
                DeclaredTraceArgument.LAMBDA -> "{ proof."
                DeclaredTraceArgument.ANONYMOUS -> "fun() {"
            }
        )
    val bodyEnd = source.indexOf('}', bodyStart) + 1
    val supplyStart = source.indexOf("effects.")
    val supplyEnd = source.lastIndexOf(')') + 1

    fun edges(classDigest: String) =
        setOf(
            DeclaredTraceEdge.Contract(
                supplier = "consumer.$owner",
                target = "effects.$api",
                start = supplyStart,
                end = supplyEnd,
                effect = effect,
                formal = 1,
                classDigest = classDigest,
            ),
            DeclaredTraceEdge.Body(supplier = "consumer.$owner", target = target, start = targetStart, end = targetEnd),
        )

    fun accepts(actual: DeclaredTraceActual, classDigest: String): Boolean =
        actual.completion == DeclaredTraceCompletion.COMPLETE &&
            actual.namedCallers.isEmpty() &&
            actual.edges == edges(classDigest)

    companion object {
        val exactlyLambdaFunction =
            DeclaredTraceCase(
                DeclaredTraceEffect.EXACTLY_ONCE,
                DeclaredTraceArgument.LAMBDA,
                DeclaredTraceTarget.FUNCTION,
            )
        val exactlyLambdaConstructor =
            DeclaredTraceCase(
                DeclaredTraceEffect.EXACTLY_ONCE,
                DeclaredTraceArgument.LAMBDA,
                DeclaredTraceTarget.CONSTRUCTOR,
            )
        val exactlyAnonymousFunction =
            DeclaredTraceCase(
                DeclaredTraceEffect.EXACTLY_ONCE,
                DeclaredTraceArgument.ANONYMOUS,
                DeclaredTraceTarget.FUNCTION,
            )
        val exactlyAnonymousConstructor =
            DeclaredTraceCase(
                DeclaredTraceEffect.EXACTLY_ONCE,
                DeclaredTraceArgument.ANONYMOUS,
                DeclaredTraceTarget.CONSTRUCTOR,
            )
        val atMostLambdaFunction =
            DeclaredTraceCase(
                DeclaredTraceEffect.AT_MOST_ONCE,
                DeclaredTraceArgument.LAMBDA,
                DeclaredTraceTarget.FUNCTION,
            )
        val atMostLambdaConstructor =
            DeclaredTraceCase(
                DeclaredTraceEffect.AT_MOST_ONCE,
                DeclaredTraceArgument.LAMBDA,
                DeclaredTraceTarget.CONSTRUCTOR,
            )
        val atMostAnonymousFunction =
            DeclaredTraceCase(
                DeclaredTraceEffect.AT_MOST_ONCE,
                DeclaredTraceArgument.ANONYMOUS,
                DeclaredTraceTarget.FUNCTION,
            )
        val atMostAnonymousConstructor =
            DeclaredTraceCase(
                DeclaredTraceEffect.AT_MOST_ONCE,
                DeclaredTraceArgument.ANONYMOUS,
                DeclaredTraceTarget.CONSTRUCTOR,
            )
        val atLeastLambdaFunction =
            DeclaredTraceCase(
                DeclaredTraceEffect.AT_LEAST_ONCE,
                DeclaredTraceArgument.LAMBDA,
                DeclaredTraceTarget.FUNCTION,
            )
        val atLeastLambdaConstructor =
            DeclaredTraceCase(
                DeclaredTraceEffect.AT_LEAST_ONCE,
                DeclaredTraceArgument.LAMBDA,
                DeclaredTraceTarget.CONSTRUCTOR,
            )
        val atLeastAnonymousFunction =
            DeclaredTraceCase(
                DeclaredTraceEffect.AT_LEAST_ONCE,
                DeclaredTraceArgument.ANONYMOUS,
                DeclaredTraceTarget.FUNCTION,
            )
        val atLeastAnonymousConstructor =
            DeclaredTraceCase(
                DeclaredTraceEffect.AT_LEAST_ONCE,
                DeclaredTraceArgument.ANONYMOUS,
                DeclaredTraceTarget.CONSTRUCTOR,
            )
        val unknownLambdaFunction =
            DeclaredTraceCase(DeclaredTraceEffect.UNKNOWN, DeclaredTraceArgument.LAMBDA, DeclaredTraceTarget.FUNCTION)
        val unknownLambdaConstructor =
            DeclaredTraceCase(
                DeclaredTraceEffect.UNKNOWN,
                DeclaredTraceArgument.LAMBDA,
                DeclaredTraceTarget.CONSTRUCTOR,
            )
        val unknownAnonymousFunction =
            DeclaredTraceCase(
                DeclaredTraceEffect.UNKNOWN,
                DeclaredTraceArgument.ANONYMOUS,
                DeclaredTraceTarget.FUNCTION,
            )
        val unknownAnonymousConstructor =
            DeclaredTraceCase(
                DeclaredTraceEffect.UNKNOWN,
                DeclaredTraceArgument.ANONYMOUS,
                DeclaredTraceTarget.CONSTRUCTOR,
            )
    }
}

internal enum class DeclaredTraceCompletion {
    COMPLETE,
    QUALIFIED,
}

internal sealed interface DeclaredTraceEdge {
    data class Contract(
        val supplier: String,
        val target: String,
        val start: Int,
        val end: Int,
        val effect: String,
        val formal: Int,
        val classDigest: String,
    ) : DeclaredTraceEdge

    data class Body(
        val supplier: String,
        val target: String,
        val start: Int,
        val end: Int,
    ) : DeclaredTraceEdge
}

internal data class DeclaredTraceActual(
    val completion: DeclaredTraceCompletion,
    val namedCallers: Set<String>,
    val edges: Set<DeclaredTraceEdge>,
)

/** Mutated evidence proves that the independent predicate rejects missing or upgraded facts. */
class DeclaredEffectTraceOracleTest : TestCase() {
    fun testIndependentPredicateRejectsNegativeControls() {
        val case = DeclaredTraceCase.atMostAnonymousConstructor
        val digest = "0".repeat(R1_ORACLE_DIGEST_HEX_LENGTH)
        val expected = case.edges(digest)
        val actual = DeclaredTraceActual(DeclaredTraceCompletion.COMPLETE, emptySet(), expected)
        assertTrue(case.accepts(actual, digest))
        assertFalse(case.accepts(actual.copy(edges = emptySet()), digest))
        assertFalse(
            case.accepts(actual.copy(edges = expected.filterNot { it is DeclaredTraceEdge.Body }.toSet()), digest)
        )
        val sameName =
            (expected.single { it is DeclaredTraceEdge.Body } as DeclaredTraceEdge.Body).copy(
                target = "unrelated.Target"
            )
        assertFalse(case.accepts(actual.copy(edges = expected + sameName), digest))
        val changedEffect =
            expected.map { if (it is DeclaredTraceEdge.Contract) it.copy(effect = "EXACTLY_ONCE") else it }.toSet()
        assertFalse(case.accepts(actual.copy(edges = changedEffect), digest))
        assertFalse(case.accepts(actual.copy(completion = DeclaredTraceCompletion.QUALIFIED), digest))
        assertFalse(case.accepts(actual.copy(namedCallers = setOf("consumer.${case.owner}")), digest))
        assertFalse(case.accepts(actual, "1".repeat(R1_ORACLE_DIGEST_HEX_LENGTH)))
    }
}

private const val R1_ORACLE_DIGEST_HEX_LENGTH = 64
