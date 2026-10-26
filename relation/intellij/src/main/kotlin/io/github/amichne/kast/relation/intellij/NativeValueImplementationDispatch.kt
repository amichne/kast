@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationFact
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.ValueCallFrame
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueImplementationDispatch
import io.github.amichne.kast.relation.contract.ValueImplementationDispatchFailure
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOwnership
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.psi.KtNamedFunction

internal sealed interface NativeValueArgumentDispatch {
    data object Direct : NativeValueArgumentDispatch

    data class Implementations(
        val evidence: ValueImplementationDispatch,
        val declaredBody: NativeValueDeclaredBody,
    ) : NativeValueArgumentDispatch

    data class Unsupported(val cause: ValueFlowUnsupportedCause) : NativeValueArgumentDispatch

    data class Rejected(val cause: RelationCompilerRejection) : NativeValueArgumentDispatch
}

internal enum class NativeValueDeclaredBody {
    ABSTRACT,
    CONCRETE,
}

/** Existing definition inventory and K2 confirmation consume the enclosing flow's original allowance. */
internal class NativeValueImplementationDispatch(
    private val request: ValueFlowRequest,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val allowance: IntellijRelationAllowance,
) {
    fun read(): NativeValueArgumentDispatch {
        val invocation =
            when (val role = request.source.role) {
                is ValueRole.Argument -> role.call
                is ValueRole.Receiver ->
                    when (val formal = allowance.nativeValueReceiverFormal(request.source, scope, projection)) {
                        is Refinement.Refined -> role.call
                        is Refinement.Rejected -> return NativeValueArgumentDispatch.Unsupported(formal.failure)
                    }
                else -> return NativeValueArgumentDispatch.Unsupported(ValueFlowUnsupportedCause.UNSUPPORTED_EXPRESSION)
            }
        return forCallable(invocation.callable)
    }

    fun forCallable(callable: RelationEndpoint): NativeValueArgumentDispatch {
        if (callable.kind == CompilerSymbolKind.CONSTRUCTOR) return NativeValueArgumentDispatch.Direct
        val subject =
            when (val value = projection.subject(scope, callable)) {
                is IntellijRelationSubjectLookup.Found -> value
                is IntellijRelationSubjectLookup.Rejected -> return NativeValueArgumentDispatch.Direct
            }
        val function = subject.declaration as? KtNamedFunction ?: return NativeValueArgumentDispatch.Direct
        if (
            nativeValueBodySelection(
                function = function,
                source = request.source,
                callable = callable,
                scope = scope,
                projection = projection,
            ) == NativeValueBodySelection.DIRECT
        )
            return NativeValueArgumentDispatch.Direct
        val question =
            RelationRequest.start(
                subject = callable,
                meaning = RelationMeaning.Implementations,
                budget = request.budget,
                boundary = request.boundary,
            )
        val compiled = compile(question, subject)
        return when (val value = ValueImplementationDispatch.fromCompiler(compiled)) {
            is Refinement.Refined ->
                NativeValueArgumentDispatch.Implementations(
                    value.value,
                    if (function.bodyExpression == null) NativeValueDeclaredBody.ABSTRACT
                    else NativeValueDeclaredBody.CONCRETE,
                )
            is Refinement.Rejected ->
                when (val failure = value.failure) {
                    is ValueImplementationDispatchFailure.CompilerRejected ->
                        NativeValueArgumentDispatch.Rejected(failure.reason)
                    ValueImplementationDispatchFailure.MeaningMismatch ->
                        NativeValueArgumentDispatch.Rejected(RelationCompilerRejection.COMPILER_CONTRACT_VIOLATION)
                }
        }
    }

    private fun compile(
        question: RelationRequest,
        subject: IntellijRelationSubjectLookup.Found,
    ): RelationCompilation {
        val selected =
            when (val value = scope.forRequest(question)) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return RelationCompilation.Rejected(value.failure)
            }
        val definitions = selected.prepareFileEnumeration(allowance, ReadLimits.Default)
        val callbacks =
            when (
                val value =
                    CallbackEvidenceAccess.compile(definitions) { _, _ ->
                        Refinement.Rejected(RelationCompilerRejection.SCOPE_REJECTED)
                    }
            ) {
                is Refinement.Refined -> value.value
                is Refinement.Rejected -> return RelationCompilation.Rejected(value.failure)
            }
        val collector =
            IntellijRelationCollector(request = question, observation = projection.observation, allowance = allowance)
        val termination =
            IntellijK2RelationSearch(
                    project = subject.declaration.project,
                    scope = definitions,
                    projection = projection,
                    subjectSource = subject,
                    callbackEvidence = callbacks,
                    observation = projection.observation,
                )
                .read(question, subject.plan(question), collector)
        return collector.finish(termination)
    }
}

internal fun IntellijRelationAllowance.nativeValueImplementationEntry(
    source: ValueSite,
    dispatch: ValueImplementationDispatch,
    relation: RelationFact,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueTransfer, ValueFlowUnsupportedCause> {
    val invocation =
        when (val role = source.role) {
            is ValueRole.Argument -> role.call
            is ValueRole.Receiver -> role.call
            else -> return implementationRejected()
        }
    val frame =
        when (
            val value =
                nativeValueImplementationFrame(
                    invocation = invocation,
                    dispatch = dispatch,
                    relation = relation,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return implementationRejected()
        }
    val formal =
        when (
            val value =
                implementationFormal(
                    source = source,
                    callee = relation.source,
                    scope = scope,
                    projection = projection,
                )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    return nativeSourceEntry(source, frame, formal)
}

internal fun nativeValueImplementationFrame(
    invocation: ValueInvocation,
    dispatch: ValueImplementationDispatch,
    relation: RelationFact,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueCallFrame, ValueFlowUnsupportedCause> {
    val ownership =
        when (val value = nativeValueSourceOwnership(relation.source, scope, projection)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    return when (
        val value =
            ValueCallFrame.implementation(
                invocation = invocation,
                dispatch = dispatch,
                relation = relation,
                ownership = ownership,
            )
    ) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> implementationRejected()
    }
}

private fun IntellijRelationAllowance.implementationFormal(
    source: ValueSite,
    callee: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueFormalPosition, ValueFlowUnsupportedCause> {
    val function =
        when (val value = projection.subject(scope, callee)) {
            is IntellijRelationSubjectLookup.Found -> value.declaration as? KtNamedFunction
            is IntellijRelationSubjectLookup.Rejected -> null
        } ?: return implementationRejected()
    if (function.bodyExpression == null) return Refinement.Rejected(ValueFlowUnsupportedCause.EXTERNAL_CALL)
    return when (val role = source.role) {
        is ValueRole.Argument -> {
            val parameter = function.valueParameters.getOrNull(role.position.value) ?: return implementationRejected()
            when (val value = nativeValueFormalParameter(callee, role.position, parameter)) {
                is Refinement.Refined -> Refinement.Refined(ValueFormalPosition.Parameter(value.value))
                is Refinement.Rejected -> value
            }
        }
        is ValueRole.Receiver -> implementationReceiverFormal(source, function, scope, projection)
        else -> implementationRejected()
    }
}

private fun IntellijRelationAllowance.implementationReceiverFormal(
    source: ValueSite,
    function: KtNamedFunction,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueFormalPosition.Receiver, ValueFlowUnsupportedCause> {
    val formal =
        when (val value = nativeValueReceiverFormal(source, scope, projection)) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
    val present =
        projection.observation.observedAnalyze(function) {
            when (formal.kind) {
                ValueReceiverKind.EXTENSION -> function.symbol.receiverParameter != null
                ValueReceiverKind.DISPATCH -> function.symbol.containingDeclaration is KaClassSymbol
            }
        }
    return if (present) Refinement.Refined(formal) else implementationRejected()
}

internal fun nativeValueSourceOwnership(
    callee: RelationEndpoint,
    scope: CompiledRelationScope,
    projection: IntellijK2RelationProjection,
): Refinement<ValueSourceOwnership, ValueFlowUnsupportedCause> {
    val file = callee.file as? SymbolDiscoveryFileIdentity.Workspace ?: return implementationRejected()
    val roots = scope.sourceRoots
    val index = RelationSourceRootIndex(roots.map { Path.of(it.sourceRoot.value) })
    val owner = index.owner(Path.of(file.path.value), projection.observation)
    if (owner !is RelationPathOwner.Owned || owner.cardinality != RelationRootCardinality.UNIQUE)
        return implementationRejected()
    val root = roots.singleOrNull { Path.of(it.sourceRoot.value) == owner.path } ?: return implementationRejected()
    return when (val value = ValueSourceOwnership.fromCompiler(callee, root)) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> implementationRejected()
    }
}

private fun implementationRejected(): Refinement.Rejected<ValueFlowUnsupportedCause> =
    Refinement.Rejected(ValueFlowUnsupportedCause.UNRESOLVED_REFERENCE)
