@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.change.verify.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.AddDeclarationOutboundReferenceCount
import io.github.amichne.kast.change.verify.spi.AddDeclarationCollisionObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationCompilerDiagnosticsObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationExistingBindingsObservation
import io.github.amichne.kast.change.verify.spi.AddDeclarationOutboundBindingsObservation
import io.github.amichne.kast.kernel.Refinement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.components.semanticallyEquals
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDestructuringDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.util.concurrent.CancellationException

internal enum class IntellijAddDeclarationSemanticProofFailure {
    DIAGNOSTICS_INCOMPLETE,
    DIAGNOSTICS_REJECTED,
    COLLISION_SCOPE_INCOMPLETE,
    COLLISION_OBSERVED,
    OUTBOUND_SCOPE_INCOMPLETE,
    OUTBOUND_COUNT_INVALID,
    EXISTING_BINDINGS_CHANGED,
}

internal data class IntellijAddDeclarationSemanticProof(
    val diagnostics: AddDeclarationCompilerDiagnosticsObservation,
    val collision: AddDeclarationCollisionObservation,
    val outboundBindings: AddDeclarationOutboundBindingsObservation,
    val existingBindings: AddDeclarationExistingBindingsObservation,
    val outboundReferenceCount: AddDeclarationOutboundReferenceCount,
)

/**
 * Proof transition: one exact appended declaration under a scoped K2 read to
 * `Refinement<IntellijAddDeclarationSemanticProof,
 * IntellijAddDeclarationSemanticProofFailure>`.
 *
 * Refined proves bounded error diagnostics are clear, no compiler collision exists, every outbound
 * occurrence resolves uniquely, and the new declaration acquired no pre-existing bindings. The
 * failure set is closed. PSI, K2 symbols, types, and references remain inside the scoped read.
 */
internal fun proveAddDeclarationSemantics(
    target: KtFile,
    declaration: KtNamedDeclaration,
    declarationRange: VerifiedDeclarationRange,
    expectedOutboundReferenceCount: AddDeclarationOutboundReferenceCount,
): Refinement<IntellijAddDeclarationSemanticProof, IntellijAddDeclarationSemanticProofFailure> {
    val diagnostics = try {
        analyze(target) {
            target.collectDiagnostics(KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS)
                .any { diagnostic ->
                    diagnostic.severity == KaSeverity.ERROR && diagnostic.textRanges.any { range ->
                        range.intersects(TextRange(declarationRange.startOffset, declarationRange.endOffset))
                    }
                }
        }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        return rejected(IntellijAddDeclarationSemanticProofFailure.DIAGNOSTICS_INCOMPLETE)
    }
    if (diagnostics) return rejected(IntellijAddDeclarationSemanticProofFailure.DIAGNOSTICS_REJECTED)

    val semantic = try {
        analyze(target) {
            when (hasCompilerCollision(target, declaration)) {
                CompilerCollisionRead.Present -> return@analyze SemanticRead.Collision
                CompilerCollisionRead.Incomplete -> return@analyze SemanticRead.CollisionIncomplete
                CompilerCollisionRead.Absent -> Unit
            }
            when (val outbound = outboundReferenceCount(declaration)) {
                OutboundReferenceRead.Incomplete -> SemanticRead.OutboundIncomplete
                is OutboundReferenceRead.Complete -> SemanticRead.Proven(outbound.count)
            }
        }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        return rejected(IntellijAddDeclarationSemanticProofFailure.COLLISION_SCOPE_INCOMPLETE)
    }
    val outbound = when (semantic) {
        SemanticRead.Collision ->
            return rejected(IntellijAddDeclarationSemanticProofFailure.COLLISION_OBSERVED)
        SemanticRead.CollisionIncomplete ->
            return rejected(IntellijAddDeclarationSemanticProofFailure.COLLISION_SCOPE_INCOMPLETE)
        SemanticRead.OutboundIncomplete ->
            return rejected(IntellijAddDeclarationSemanticProofFailure.OUTBOUND_SCOPE_INCOMPLETE)
        is SemanticRead.Proven -> semantic.count
    }
    val outboundCount = when (val parsed = AddDeclarationOutboundReferenceCount.parse(outbound)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected ->
            return rejected(IntellijAddDeclarationSemanticProofFailure.OUTBOUND_COUNT_INVALID)
    }
    val outboundBindings = when (val admitted = admitVacuousOutboundBindingProof(
        expectedOutboundReferenceCount,
        outboundCount,
    )) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> return rejected(admitted.failure)
    }
    val rebound = try {
        ReferencesSearch.search(declaration, GlobalSearchScope.projectScope(target.project))
            .findAll()
            .any { reference -> !PsiTreeUtil.isAncestor(declaration, reference.element, false) }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        return rejected(IntellijAddDeclarationSemanticProofFailure.EXISTING_BINDINGS_CHANGED)
    }
    if (rebound) {
        return rejected(IntellijAddDeclarationSemanticProofFailure.EXISTING_BINDINGS_CHANGED)
    }
    return Refinement.Refined(
        IntellijAddDeclarationSemanticProof(
            AddDeclarationCompilerDiagnosticsObservation.CLEAR,
            AddDeclarationCollisionObservation.ABSENT_COMPLETE,
            outboundBindings,
            AddDeclarationExistingBindingsObservation.PRESERVED_NO_CANDIDATES,
            outboundCount,
        ),
    )
}

private sealed interface SemanticRead {
    data object Collision : SemanticRead
    data object CollisionIncomplete : SemanticRead
    data object OutboundIncomplete : SemanticRead
    data class Proven(val count: Int) : SemanticRead
}

private enum class CompilerCollisionRead {
    Absent,
    Present,
    Incomplete,
}

private sealed interface OutboundReferenceRead {
    data object Incomplete : OutboundReferenceRead
    data class Complete(val count: Int) : OutboundReferenceRead
}

private fun KaSession.hasCompilerCollision(
    target: KtFile,
    declaration: KtNamedDeclaration,
): CompilerCollisionRead {
    ProgressManager.checkCanceled()
    val symbol = with(this) { declaration.symbol }
    val name = declaration.name ?: return CompilerCollisionRead.Incomplete
    val packageName = target.packageFqName
    val collision = when (declaration.declarationKind()) {
        AddDeclarationKind.FUNCTION,
        AddDeclarationKind.PROPERTY,
        -> findTopLevelCallables(packageName, Name.identifier(name))
            .filter { candidate -> !candidate.isDeclarationSymbol(declaration) }
            .any { candidate -> callablesCollide(symbol, candidate) }

        else -> {
            val classId = ClassId.topLevel(packageName.child(Name.identifier(name)))
            val kotlinCollision = findClassLike(classId)
                ?.takeUnless { candidate -> candidate.isDeclarationSymbol(declaration) } != null
            val javaCollision = JavaPsiFacade.getInstance(target.project)
                .findClasses(classId.asSingleFqName().asString(), GlobalSearchScope.allScope(target.project))
                .any { candidate -> !candidate.isDeclarationElement(declaration) }
            kotlinCollision || javaCollision
        }
    }
    return if (collision) CompilerCollisionRead.Present else CompilerCollisionRead.Absent
}

private fun KaSession.outboundReferenceCount(
    declaration: KtNamedDeclaration,
): OutboundReferenceRead {
    if (declaration.hasImplicitResolutionSyntax()) return OutboundReferenceRead.Incomplete
    var count = 0
    for (expression in PsiTreeUtil.findChildrenOfType(declaration, KtReferenceExpression::class.java)) {
        ProgressManager.checkCanceled()
        val references = expression.references.filterIsInstance<KtReference>()
        if (references.isEmpty()) continue
        val targets = references.map { reference ->
            with(this) { reference.resolveToSymbol() }
                ?: return OutboundReferenceRead.Incomplete
        }.distinct()
        if (targets.size != 1) return OutboundReferenceRead.Incomplete
        val target = targets.single()
        if (target is KaPackageSymbol || target.isInside(declaration)) continue
        count = try {
            Math.addExact(count, 1)
        } catch (_: ArithmeticException) {
            return OutboundReferenceRead.Incomplete
        }
    }
    return OutboundReferenceRead.Complete(count)
}

private fun KtNamedDeclaration.hasImplicitResolutionSyntax(): Boolean =
    PsiTreeUtil.findChildOfType(this, KtForExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtArrayAccessExpression::class.java) != null ||
        PsiTreeUtil.findChildOfType(this, KtDestructuringDeclaration::class.java) != null ||
        (this is KtProperty && delegateExpression != null)

private fun KtNamedDeclaration.declarationKind(): AddDeclarationKind = when (this) {
    is KtClass -> when {
        isInterface() -> AddDeclarationKind.INTERFACE
        isEnum() -> AddDeclarationKind.ENUM_CLASS
        isAnnotation() -> AddDeclarationKind.ANNOTATION_CLASS
        else -> AddDeclarationKind.CLASS
    }
    is KtObjectDeclaration -> AddDeclarationKind.OBJECT
    is KtNamedFunction -> AddDeclarationKind.FUNCTION
    is KtProperty -> AddDeclarationKind.PROPERTY
    is KtTypeAlias -> AddDeclarationKind.TYPE_ALIAS
    else -> AddDeclarationKind.CLASS
}

private fun KaSession.callablesCollide(first: KaSymbol, second: KaCallableSymbol): Boolean = when {
    first is KaFunctionSymbol && second is KaFunctionSymbol ->
        sameReceiver(first, second) && sameContextReceivers(first, second) &&
            first.valueParameters.size == second.valueParameters.size &&
            first.valueParameters.zip(second.valueParameters).all { (left, right) ->
                sameType(left.returnType, right.returnType)
            }
    first is KaVariableSymbol && second is KaVariableSymbol ->
        sameReceiver(first, second) && sameContextReceivers(first, second)
    else -> false
}

private fun KaSession.sameReceiver(first: KaCallableSymbol, second: KaCallableSymbol): Boolean {
    val left = first.receiverParameter?.returnType
    val right = second.receiverParameter?.returnType
    return when {
        left == null -> right == null
        right == null -> false
        else -> sameType(left, right)
    }
}

private fun KaSession.sameContextReceivers(
    first: KaCallableSymbol,
    second: KaCallableSymbol,
): Boolean =
    first.contextReceivers.size == second.contextReceivers.size &&
        first.contextReceivers.zip(second.contextReceivers).all { (left, right) ->
            sameType(left.type, right.type)
        }

private fun KaSession.sameType(
    first: org.jetbrains.kotlin.analysis.api.types.KaType,
    second: org.jetbrains.kotlin.analysis.api.types.KaType,
): Boolean = with(this) { first.semanticallyEquals(second) }

private fun KaSymbol.isDeclarationSymbol(declaration: KtNamedDeclaration): Boolean =
    psi.isDeclarationElement(declaration)

private fun PsiElement?.isDeclarationElement(declaration: KtNamedDeclaration): Boolean =
    this != null && (this === declaration || navigationElement === declaration)

private fun KaSymbol.isInside(declaration: KtNamedDeclaration): Boolean =
    psi?.let { element -> PsiTreeUtil.isAncestor(declaration, element, false) } == true

private fun rejected(
    failure: IntellijAddDeclarationSemanticProofFailure,
): Refinement.Rejected<IntellijAddDeclarationSemanticProofFailure> = Refinement.Rejected(failure)
