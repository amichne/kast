package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

enum class ImpactModelSyntaxFailure {
    MALFORMED_DOCUMENT,
    EMPTY_RULES,
    DUPLICATE_RULE_ID,
    INVALID_STATE_DOMAIN,
    UNDECLARED_STATE,
    INVALID_DECLARATION,
    INVALID_BASIS,
    INVALID_RANGE,
    INVALID_POSITION,
    INVALID_INVOCATION,
    INCOMPATIBLE_BOUNDARY_KIND,
    INVALID_ASSUMPTIONS,
    INVALID_TERMINAL,
}

/** Whole-document syntax proof. This conveys no current semantic authority or admitted model binding. */
class AdmittedImpactModelSyntax private constructor(val document: ImpactModelDocument) {
    companion object {
        fun admit(document: ImpactModelDocument): Refinement<AdmittedImpactModelSyntax, ImpactModelSyntaxFailure> {
            val failure =
                when (document) {
                    is ImpactModelDocument.Representation -> document.representationFailure()
                    is ImpactModelDocument.Boundary -> document.boundaryFailure()
                }
            return when (failure) {
                is Refinement.Refined -> Refinement.Refined(AdmittedImpactModelSyntax(document))
                is Refinement.Rejected -> failure
            }
        }
    }
}

object ImpactModelIngress {
    private val strict = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        encodeDefaults = true
    }

    fun decode(raw: JsonElement): Refinement<AdmittedImpactModelSyntax, ImpactModelSyntaxFailure> =
        try {
            AdmittedImpactModelSyntax.admit(strict.decodeFromJsonElement(ImpactModelDocument.serializer(), raw))
        } catch (_: SerializationException) {
            Refinement.Rejected(ImpactModelSyntaxFailure.MALFORMED_DOCUMENT)
        } catch (_: IllegalArgumentException) {
            Refinement.Rejected(ImpactModelSyntaxFailure.MALFORMED_DOCUMENT)
        }
}

private fun ImpactModelDocument.Representation.representationFailure(): Refinement<Unit, ImpactModelSyntaxFailure> {
    if (
        states.values.isEmpty() ||
            states.values.size > MAX_REPRESENTATION_STATES ||
            states.values.distinct().size != states.values.size
    )
        return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_STATE_DOMAIN)
    if (rules.values.isEmpty()) return Refinement.Rejected(ImpactModelSyntaxFailure.EMPTY_RULES)
    if (rules.values.map { it.id }.distinct().size != rules.values.size)
        return Refinement.Rejected(ImpactModelSyntaxFailure.DUPLICATE_RULE_ID)
    for (rule in rules.values) when (val admitted = rule.validateRepresentationRule(states.values)) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    return Refinement.Refined(Unit)
}

private fun ImpactRepresentationRuleDocument.validateRepresentationRule(
    states: List<ImpactModelIdentifierDocument>
): Refinement<Unit, ImpactModelSyntaxFailure> {
    val positions =
        when (this) {
            is ImpactRepresentationRuleDocument.Origin -> listOf(output)
            is ImpactRepresentationRuleDocument.Transfer -> listOf(input, output)
            is ImpactRepresentationRuleDocument.Transformation -> listOf(input, output)
            is ImpactRepresentationRuleDocument.ConsumerExpectation -> listOf(input)
        }
    for (position in positions) when (val admitted = position.declaration.declarationFailure()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    return when (this) {
        is ImpactRepresentationRuleDocument.Origin -> validateStatePositions(states)
        is ImpactRepresentationRuleDocument.Transfer -> validateStatePositions()
        is ImpactRepresentationRuleDocument.Transformation -> validateStatePositions(states)
        is ImpactRepresentationRuleDocument.ConsumerExpectation -> validateStatePositions(states)
    }
}

private fun sameCallablePositions(
    input: ImpactCallablePositionDocument,
    output: ImpactCallablePositionDocument,
): Boolean =
    input.position is ImpactModelValuePositionDocument.Argument &&
        output.position == ImpactModelValuePositionDocument.Result &&
        input.declaration == output.declaration

private fun ImpactModelDocument.Boundary.boundaryFailure(): Refinement<Unit, ImpactModelSyntaxFailure> {
    if (rules.values.isEmpty()) return Refinement.Rejected(ImpactModelSyntaxFailure.EMPTY_RULES)
    if (rules.values.map { it.id }.distinct().size != rules.values.size)
        return Refinement.Rejected(ImpactModelSyntaxFailure.DUPLICATE_RULE_ID)
    for (rule in rules.values) when (val admitted = rule.validateBoundaryRule()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admitted
    }
    return Refinement.Refined(Unit)
}

private fun ImpactBoundaryRuleDocument.validateBoundaryRule(): Refinement<Unit, ImpactModelSyntaxFailure> =
    when (this) {
        is ImpactBoundaryRuleDocument.Continuation -> validateBoundaryRule()
        is ImpactBoundaryRuleDocument.Terminal -> validateBoundaryRule()
    }

private fun ImpactDeclarationReferenceDocument.declarationFailure(): Refinement<Unit, ImpactModelSyntaxFailure> {
    when (val admission = basis.basisFailure()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admission
    }
    if (
        !file.hasDeclarationFileShape() ||
            !Regex("^canonical-signature-sha256-v1\\|[0-9a-f]{64}$").matches(compilerIdentity.value)
    )
        return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_DECLARATION)
    return range.rangeFailure()
}

private fun ImpactSemanticBasisDocument.basisFailure(): Refinement<Unit, ImpactModelSyntaxFailure> {
    if (!root.value.startsWith('/') || root.value.any(Char::isISOControl) || root.value.hasDotRootSegment())
        return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_BASIS)
    when (this) {
        is ImpactSemanticBasisDocument.Published -> Unit
        is ImpactSemanticBasisDocument.Live ->
            if (!Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(host.value))
                return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_BASIS)
    }
    return Refinement.Refined(Unit)
}

private fun ImpactSourceRangeDocument.rangeFailure(): Refinement<Unit, ImpactModelSyntaxFailure> =
    if (end.value > start.value) Refinement.Refined(Unit)
    else Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_RANGE)

private fun ImpactValueSiteReferenceDocument.siteFailure(): Refinement<Unit, ImpactModelSyntaxFailure> {
    when (val admission = enclosing.declarationFailure()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admission
    }
    when (val admission = range.rangeFailure()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admission
    }
    if (!enclosing.range.contains(range)) return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_RANGE)
    when (val role = role) {
        is ImpactValueRoleDocument.Argument -> {
            when (val admission = role.invocation.callable.declarationFailure()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admission
            }
            when (val admission = role.invocation.range.rangeFailure()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admission
            }
            if (
                role.invocation.callable.basis != enclosing.basis ||
                    !enclosing.range.contains(role.invocation.range) ||
                    !role.invocation.range.contains(range)
            )
                return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_INVOCATION)
        }
        ImpactValueRoleDocument.ExpressionResult,
        ImpactValueRoleDocument.LocalBinding,
        ImpactValueRoleDocument.LocalRead,
        ImpactValueRoleDocument.Return,
        ImpactValueRoleDocument.PropertyAssignment -> Unit
    }
    return Refinement.Refined(Unit)
}

private fun ImpactSourceRangeDocument.contains(other: ImpactSourceRangeDocument): Boolean =
    other.start.value >= start.value && other.end.value <= end.value

private fun ImpactRepresentationRuleDocument.Origin.validateStatePositions(
    states: List<ImpactModelIdentifierDocument>
): Refinement<Unit, ImpactModelSyntaxFailure> {
    if (output.position != ImpactModelValuePositionDocument.Result)
        return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_POSITION)
    if (state !in states) return Refinement.Rejected(ImpactModelSyntaxFailure.UNDECLARED_STATE)
    return Refinement.Refined(Unit)
}

private fun ImpactRepresentationRuleDocument.Transfer.validateStatePositions():
    Refinement<Unit, ImpactModelSyntaxFailure> {
    if (!sameCallablePositions(input, output)) return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_POSITION)
    return Refinement.Refined(Unit)
}

private fun ImpactRepresentationRuleDocument.Transformation.validateStatePositions(
    states: List<ImpactModelIdentifierDocument>
): Refinement<Unit, ImpactModelSyntaxFailure> {
    if (!sameCallablePositions(input, output)) return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_POSITION)
    if (from !in states || to !in states) return Refinement.Rejected(ImpactModelSyntaxFailure.UNDECLARED_STATE)
    return Refinement.Refined(Unit)
}

private fun ImpactRepresentationRuleDocument.ConsumerExpectation.validateStatePositions(
    states: List<ImpactModelIdentifierDocument>
): Refinement<Unit, ImpactModelSyntaxFailure> {
    if (input.position !is ImpactModelValuePositionDocument.Argument)
        return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_POSITION)
    if (state !in states) return Refinement.Rejected(ImpactModelSyntaxFailure.UNDECLARED_STATE)
    return Refinement.Refined(Unit)
}

private const val MAX_REPRESENTATION_STATES = 32

private fun ImpactBoundaryRuleDocument.Continuation.validateBoundaryRule(): Refinement<Unit, ImpactModelSyntaxFailure> {

    when (val admission = source.site.siteFailure()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admission
    }
    when (val admission = target.site.siteFailure()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admission
    }
    if (source.kind != target.kind) return Refinement.Rejected(ImpactModelSyntaxFailure.INCOMPATIBLE_BOUNDARY_KIND)
    if (assumptions.values.size > 2 || assumptions.values.distinct().size != assumptions.values.size)
        return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_ASSUMPTIONS)

    return Refinement.Refined(Unit)
}

private fun ImpactBoundaryRuleDocument.Terminal.validateBoundaryRule(): Refinement<Unit, ImpactModelSyntaxFailure> {

    when (val admission = source.site.siteFailure()) {
        is Refinement.Refined -> Unit
        is Refinement.Rejected -> return admission
    }
    when (meaning) {
        ImpactBoundaryTerminalDocument.REVIEWED_DISPOSAL -> Unit
        ImpactBoundaryTerminalDocument.REVIEWED_EXTERNAL_SINK ->
            if (source.kind != ImpactBoundaryKindDocument.EXTERNAL_SYSTEM)
                return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_TERMINAL)
        ImpactBoundaryTerminalDocument.REVIEWED_RETENTION ->
            if (source.kind != ImpactBoundaryKindDocument.PERSISTENCE)
                return Refinement.Rejected(ImpactModelSyntaxFailure.INVALID_TERMINAL)
    }

    return Refinement.Refined(Unit)
}

private fun ProtocolText.hasDeclarationFileShape(): Boolean = value.isNotBlank() && !value.any(Char::isISOControl)

private fun String.hasDotRootSegment(): Boolean = split('/').any { it == "." || it == ".." }
