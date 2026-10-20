package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.EmptyImpactValuePath
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePredicateDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import junit.framework.TestCase.assertTrue

/** This oracle assesses fixed retained paths. It performs no semantic read or graph expansion. */
internal class NativePacketS3QueryOracle(private val facts: NativePacketS3QueryFacts) {
    private val callbacks = NativePacketS3QueryCallbackOracle(facts)

    fun assertLogArgument(branch: S3LogBranch, paths: List<ImpactPathDocument>) {
        val assessments = paths.map { logRows(it, branch) }
        assertTrue(
            "C3.2 $branch needs one contiguous original callback/helper/field/template/Logger.info argument route. " +
                "FirstMissing=${firstMissing(assessments)}. " +
                "Typed string derivation and reviewed terminal interpretation remain NOT_ASSERTED",
            assessments.any { rows -> rows.all { it.passed } },
        )
        println(
            "S3_QUERY_LOG branch=$branch route_to_argument=ASSERTED typed_derivation,reviewed_terminal=NOT_ASSERTED"
        )
    }

    fun assertExecuteReturn(paths: List<ImpactPathDocument>) {
        val assessments = paths.map { path ->
            val rows = S3RetainedRows(path, facts.origin)
            rows.transfer("C3.3 result binding") {
                local(it, NativePacketS3QueryRanges.binding, ImpactValueRoleDocument.LocalBinding)
            }
            rows.transfer("C3.3 return read") {
                local(it, NativePacketS3QueryRanges.executeReturn, ImpactValueRoleDocument.LocalRead)
            }
            rows.transfer("C3.3 execute return") {
                it.kind == ImpactTransferKindDocument.RETURN && it.target == facts.executeReturn
            }
            rows.transfer("C3.3 matched original caller", ::matchedCallerReturn)
            rows.results
        }
        assertTrue(
            "C3.3 lacks its exact execute return and matched original caller: ${firstMissing(assessments)}",
            assessments.any { rows -> rows.all { it.passed } },
        )
    }

    fun assertNoHttpArgumentWitness(paths: List<ImpactPathDocument>) {
        assertTrue(
            "The forbidden control needs the actual original S3 producer",
            paths.any { it.producer == facts.origin },
        )
        val forbidden =
            paths
                .flatMap { it.compositeSites() }
                .filter { site ->
                    val argument = site.role as? ImpactValueRoleDocument.Argument
                    argument?.invocation?.callable == facts.api
                }
        assertTrue("C3.N1 emitted original HTTP arguments: ${forbidden.map { it.range }}", forbidden.isEmpty())
    }

    private fun logRows(path: ImpactPathDocument, selected: S3LogBranch): List<CompositeRequiredRow> {
        val branch = if (selected == S3LogBranch.SUCCESS) facts.success else facts.error
        val rows = S3RetainedRows(path, facts.origin)
        val helper = logPrefix(rows)
        payloadRows(rows = rows, branch = branch, helper = helper, selected = selected)
        logReturns(rows = rows, branch = branch, helper = helper)
        return rows.results
    }

    private fun logPrefix(rows: S3RetainedRows): ImpactCompilerTransferDocument? {
        rows.transfer("C3.2 result binding") {
            local(it, NativePacketS3QueryRanges.binding, ImpactValueRoleDocument.LocalBinding)
        }
        rows.transfer("C3.2 log result read") {
            local(it, NativePacketS3QueryRanges.logRead, ImpactValueRoleDocument.LocalRead)
        }
        rows.transfer("C3.2 actual log argument", ::logArgument)
        rows.transfer("C3.2 original supplied callback", callbacks::entry)
        rows.transfer("C3.2 genuine implicit it", ::implicitRead)
        return rows.transfer("C3.2 source toLogLine entry", callbacks::helperEntry)
    }

    private fun payloadRows(
        rows: S3RetainedRows,
        branch: S3LogBranchFacts,
        helper: ImpactCompilerTransferDocument?,
        selected: S3LogBranch,
    ) {
        val valueRange =
            if (selected == S3LogBranch.SUCCESS) NativePacketS3QueryRanges.successValue
            else NativePacketS3QueryRanges.errorValue
        val payloadRange =
            if (selected == S3LogBranch.SUCCESS) NativePacketS3QueryRanges.statusCode
            else NativePacketS3QueryRanges.errorCode
        rows.transfer("C3.2 exact guarded carrier value") { transfer ->
            reads(transfer, branch.carrierField) &&
                transfer.target.range == valueRange &&
                guarded(transfer.target, branch, helper)
        }
        rows.transfer("C3.2 exact guarded log field") { transfer ->
            reads(transfer, branch.payloadField) &&
                transfer.target.range == payloadRange &&
                guarded(transfer.target, branch, helper)
        }
        rows.site("C3.2 original template result") { site ->
            site.enclosing == facts.helper && site.range == branch.template && guarded(site, branch, helper)
        }
    }

    private fun logReturns(rows: S3RetainedRows, branch: S3LogBranchFacts, helper: ImpactCompilerTransferDocument?) {
        rows.transfer("C3.2 helper normal return") { transfer ->
            transfer.kind == ImpactTransferKindDocument.RETURN &&
                transfer.target.range == facts.helperReturn &&
                transfer.target.enclosing == facts.helper
        }
        rows.transfer("C3.2 matched helper return", callbacks::helperReturn)
        rows.transfer("C3.2 anonymous normal return") { transfer ->
            transfer.kind == ImpactTransferKindDocument.RETURN &&
                transfer.target.range == facts.helperInvocation.range &&
                transfer.target.executionOwner == facts.logBody
        }
        rows.transfer("C3.2 matched log callback return", callbacks::callbackReturn)
        rows.transfer("C3.2 exact Logger.info argument0") { transfer ->
            transfer.kind == ImpactTransferKindDocument.ARGUMENT &&
                loggerArgument(transfer.target) &&
                guarded(transfer.target, branch, helper)
        }
    }

    private fun logArgument(transfer: ImpactCompilerTransferDocument): Boolean {
        if (transfer.kind != ImpactTransferKindDocument.ARGUMENT) return false
        if (transfer.target.range != NativePacketS3QueryRanges.logRead) return false
        if (transfer.target.valuePath != EmptyImpactValuePath) return false
        val role = transfer.target.role as? ImpactValueRoleDocument.FunctionArgument ?: return false
        return role.invocation.range == NativePacketS3QueryRanges.logInvoke && role.index.value == 0
    }

    private fun implicitRead(transfer: ImpactCompilerTransferDocument): Boolean {
        if (transfer.kind != ImpactTransferKindDocument.LOCAL_READ) return false
        if (transfer.target.role != ImpactValueRoleDocument.LocalRead) return false
        return transfer.target.range == NativePacketS3QueryRanges.logIt &&
            transfer.target.executionOwner == facts.logBody
    }

    private fun firstMissing(assessments: List<List<CompositeRequiredRow>>) = assessments.map { rows ->
        rows.firstOrNull { !it.passed }?.id
    }

    private fun loggerArgument(site: ImpactValueSiteReferenceDocument): Boolean {
        if (site.role != facts.loggerArgument.role || site.range != facts.loggerArgument.range) return false
        if (site.executionOwner != facts.loggerArgument.executionOwner) return false
        if (site.callStack.values.size != 1) return false
        if (!facts.callerFrame.matches(site.callStack.values.single())) return false
        return site.enclosing == facts.loggerArgument.enclosing && site.valuePath == EmptyImpactValuePath
    }

    private fun guarded(
        site: ImpactValueSiteReferenceDocument,
        branch: S3LogBranchFacts,
        helper: ImpactCompilerTransferDocument?,
    ): Boolean {
        val source = helper?.source ?: return false
        val tested = source.copy(valuePath = EmptyImpactValuePath).compositeSubject()
        return site.predicates.values.any { predicate ->
            predicate is ImpactValuePredicateDocument.Subtype &&
                predicate.subject == tested &&
                predicate.resolvedType == branch.type
        }
    }

    private fun reads(transfer: ImpactCompilerTransferDocument, field: ImpactDeclarationReferenceDocument) =
        transfer.kind == ImpactTransferKindDocument.FIELD_READ &&
            (transfer.compositeOperation() as? ImpactSourceOperationDocument.ReadField)?.field == field

    private fun local(
        transfer: ImpactCompilerTransferDocument,
        range: ImpactSourceRangeDocument,
        role: ImpactValueRoleDocument,
    ): Boolean {
        val kind =
            if (role == ImpactValueRoleDocument.LocalBinding) ImpactTransferKindDocument.LOCAL_BINDING
            else ImpactTransferKindDocument.LOCAL_READ
        if (transfer.kind != kind) return false
        if (transfer.target.enclosing != facts.origin.enclosing || transfer.target.range != range) return false
        return transfer.target.role == role && transfer.target.valuePath == EmptyImpactValuePath
    }

    private fun matchedCallerReturn(transfer: ImpactCompilerTransferDocument): Boolean {
        if (transfer.kind != ImpactTransferKindDocument.SOURCE_CALL_RETURN) return false
        val frame =
            when (val operation = transfer.compositeOperation()) {
                is ImpactSourceOperationDocument.Return -> operation.frame
                is ImpactSourceOperationDocument.SeedReturn -> operation.frame
                else -> return false
            }
        return facts.callerFrame.matches(frame) && transfer.target == facts.callerResult
    }
}

/** A cursor assesses one already emitted finite list and stops at its first absent stage. */
private class S3RetainedRows(private val path: ImpactPathDocument, origin: ImpactValueSiteReferenceDocument) {
    private var cursor = 0
    private var reached = path.producer == origin && path.compositeContiguous()
    val results = mutableListOf(CompositeRequiredRow("C3 original producer and contiguous retained path", reached))

    fun transfer(name: String, matches: (ImpactCompilerTransferDocument) -> Boolean): ImpactCompilerTransferDocument? {
        val index =
            select(name) { step ->
                (step as? ImpactPathStepDocument.Compiler)?.transfer?.let(matches) == true
            } ?: return null
        return (path.steps.values[index] as ImpactPathStepDocument.Compiler).transfer
    }

    fun site(name: String, matches: (ImpactValueSiteReferenceDocument) -> Boolean) {
        select(name) { step -> matches(step.compositeTarget()) }
    }

    private fun select(name: String, matches: (ImpactPathStepDocument) -> Boolean): Int? {
        if (!reached) {
            results += CompositeRequiredRow("$name NOT_REACHED", false)
            return null
        }
        val found = (cursor until path.steps.values.size).firstOrNull { index -> matches(path.steps.values[index]) }
        reached = found != null
        results += CompositeRequiredRow(name, reached)
        if (found != null) cursor = found + 1
        return found
    }
}
