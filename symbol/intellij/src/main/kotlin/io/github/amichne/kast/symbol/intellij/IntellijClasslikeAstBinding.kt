package io.github.amichne.kast.symbol.intellij

import com.intellij.lang.ASTNode
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.ILazyParseableElementType
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.kotlin.KtNodeType
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.stubs.elements.KtStubElementType

internal enum class ClasslikeAstPsiAdmission {
    REQUIRED,
    UNPROVEN,
    OMITTED,
}

/** Immutable factory metadata only: no file, PSI, read epoch or absence result survives a read. */
internal sealed interface IntellijClasslikeAstBinding {
    data object Unsupported : IntellijClasslikeAstBinding

    data object UnsupportedParser : IntellijClasslikeAstBinding

    data object StartupAgent : IntellijClasslikeAstBinding

    data object Halted : IntellijClasslikeAstBinding

    class Supported private constructor(private val nonClasslike: Set<IElementType>) : IntellijClasslikeAstBinding {
        fun admit(node: ASTNode): ClasslikeAstPsiAdmission =
            when {
                node.javaClass == CompositeElement::class.java && node.elementType in CLASSLIKE_TYPES ->
                    ClasslikeAstPsiAdmission.REQUIRED
                node.javaClass == CompositeElement::class.java && node.elementType in nonClasslike ->
                    ClasslikeAstPsiAdmission.OMITTED
                node.javaClass in SELF_PSI_LEAVES -> ClasslikeAstPsiAdmission.OMITTED
                else -> ClasslikeAstPsiAdmission.UNPROVEN
            }

        companion object {
            private const val MAX_MAPPING_TYPES = 128

            internal fun inspect(
                definition: KotlinParserDefinition,
                observe: () -> Boolean,
                observation: IntellijReadObservation,
            ): IntellijClasslikeAstBinding {
                return try {
                    inspectMapping(definition, observe, observation)
                } catch (_: ReflectiveOperationException) {
                    Unsupported
                } catch (_: SecurityException) {
                    Unsupported
                }
            }

            private fun inspectMapping(
                definition: KotlinParserDefinition,
                observe: () -> Boolean,
                observation: IntellijReadObservation,
            ): IntellijClasslikeAstBinding {
                val negatives = linkedSetOf<IElementType>()
                val positives = linkedSetOf<IElementType>()
                val types = exportedTypes()
                if (types.size > MAX_MAPPING_TYPES) return Unsupported
                for (type in types) {
                    if (!observe()) return Halted
                    if (type is IFileElementType || type is ILazyParseableElementType) continue
                    when (classify(type, definition, observation)) {
                        ClasslikeAstPsiAdmission.REQUIRED -> positives += type
                        ClasslikeAstPsiAdmission.OMITTED -> negatives += type
                        ClasslikeAstPsiAdmission.UNPROVEN -> return Unsupported
                    }
                }
                if (positives != CLASSLIKE_TYPES || !selfPsiLeaves()) return Unsupported
                return Supported(negatives)
            }

            private fun exportedTypes(): List<IElementType> =
                KtNodeTypes::class
                    .java
                    .fields
                    .filter { field ->
                        Modifier.isStatic(field.modifiers) && IElementType::class.java.isAssignableFrom(field.type)
                    }
                    .map { it.get(null) as IElementType }
                    .distinct()

            private fun classify(
                type: IElementType,
                definition: KotlinParserDefinition,
                observation: IntellijReadObservation,
            ): ClasslikeAstPsiAdmission {
                if (!fixedFactory(type)) return ClasslikeAstPsiAdmission.UNPROVEN
                val psi = definition.createElement(CompositeElement(type))
                observation.count(IntellijReadCounter.DECLARATION_AST_MAPPING_PSI_PROBES)
                return if (psi is KtNamedDeclaration && psi.discoveryCompilerKind() == CompilerSymbolKind.CLASSLIKE)
                    ClasslikeAstPsiAdmission.REQUIRED
                else ClasslikeAstPsiAdmission.OMITTED
            }

            private fun fixedFactory(type: IElementType): Boolean =
                when (type) {
                    is KtStubElementType<*, *> ->
                        type.javaClass.getMethod("createPsiFromAst", ASTNode::class.java).declaringClass ==
                            KtStubElementType::class.java
                    is KtNodeType ->
                        type.javaClass.getMethod("createPsi", ASTNode::class.java).declaringClass ==
                            KtNodeType::class.java
                    else -> false
                }

            private fun selfPsiLeaves(): Boolean = SELF_PSI_LEAVES.all {
                it.getMethod("getPsi").declaringClass == LeafPsiElement::class.java
            }
        }
    }

    /** One metadata cache per loaded plugin; a cancelled or halted qualification establishes no cache entry. */
    class Owner {
        private val cached = ConcurrentHashMap<KotlinAstParserVariant, IntellijClasslikeAstBinding>()

        fun acquire(observe: () -> Boolean, observation: IntellijReadObservation): IntellijClasslikeAstBinding {
            if (!observe()) return Halted
            val definition = LanguageParserDefinitions.INSTANCE.forLanguage(KotlinLanguage.INSTANCE)
            return when (val parser = IntellijKotlinAstParserBinding.Qualified.inspect(definition)) {
                IntellijKotlinAstParserBinding.Unsupported -> UnsupportedParser
                is IntellijKotlinAstParserBinding.Qualified -> acquire(parser, observe, observation)
            }
        }

        private fun acquire(
            parser: IntellijKotlinAstParserBinding.Qualified,
            observe: () -> Boolean,
            observation: IntellijReadObservation,
        ): IntellijClasslikeAstBinding {
            cached[parser.variant]?.let {
                return it
            }
            val result =
                when (IntellijKotlinAstSourceBinding.inspect(observe, observation, parser.variant)) {
                    KotlinAstSourceBinding.HALTED -> Halted
                    KotlinAstSourceBinding.UNSUPPORTED -> Unsupported
                    KotlinAstSourceBinding.STARTUP_AGENT -> StartupAgent
                    KotlinAstSourceBinding.SUPPORTED -> Supported.inspect(parser.definition, observe, observation)
                }
            if (result != Halted) cached.putIfAbsent(parser.variant, result)
            return result
        }
    }

    companion object {
        private val CLASSLIKE_TYPES = setOf(KtNodeTypes.CLASS, KtNodeTypes.OBJECT_DECLARATION)
        private val SELF_PSI_LEAVES: Set<Class<*>> = setOf(LeafPsiElement::class.java, PsiWhiteSpaceImpl::class.java)
        private val owner = Owner()

        fun acquire(observe: () -> Boolean, observation: IntellijReadObservation): IntellijClasslikeAstBinding =
            owner.acquire(observe, observation)
    }
}
