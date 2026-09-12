package conventions.jsoncontracts

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtTypeAlias

/** Detached syntactic documentation extracted from one parsed Kotlin declaration. */
data class KotlinDocumentedDeclaration(
    val declarationPath: String,
    val kind: String,
    val name: String,
    val signature: String,
    val documentation: String,
)

sealed interface KotlinDocumentationScan {
    data class Accepted(val declarations: List<KotlinDocumentedDeclaration>) : KotlinDocumentationScan
    data class Rejected(val reason: String) : KotlinDocumentationScan
}

/**
 * PSI-only Kotlin documentation extractor used by build tooling.
 *
 * This parser deliberately performs no name or type resolution. Its signature and owner path are source syntax, not
 * compiler identity; the owning Gradle project and source path remain part of the downstream declaration identity.
 */
class KotlinDocumentationScanner : AutoCloseable {
    private val disposable = Disposer.newDisposable("kast-api-knowledge")

    @OptIn(K1Deprecation::class)
    private val environment =
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration.create(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )

    private val factory = KtPsiFactory(environment.project, false)

    fun scan(fileName: String, content: String): KotlinDocumentationScan {
        val file = factory.createFile(fileName, content)
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) {
            return KotlinDocumentationScan.Rejected("invalid-kotlin")
        }
        val declarations = mutableListOf<KotlinDocumentedDeclaration>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                    if (declaration.isKnowledgeDeclaration()) {
                        declarations += declaration.detach()
                    }
                    super.visitNamedDeclaration(declaration)
                }
            },
        )
        return KotlinDocumentationScan.Accepted(
            declarations.distinct().sortedWith(compareBy({ it.declarationPath }, { it.kind }, { it.signature })),
        )
    }

    override fun close() = Disposer.dispose(disposable)
}

private fun KtNamedDeclaration.isKnowledgeDeclaration(): Boolean =
    name != null &&
        (this is KtClassOrObject || this is KtNamedFunction || this is KtProperty || this is KtTypeAlias) &&
        !hasModifier(KtTokens.PRIVATE_KEYWORD) &&
        !hasModifier(KtTokens.INTERNAL_KEYWORD) &&
        !hasHiddenOwner() &&
        !isLocalDeclaration()

private fun KtNamedDeclaration.hasHiddenOwner(): Boolean =
    generateSequence(parent) { it.parent }
        .takeWhile { it !is KtFile }
        .filterIsInstance<KtNamedDeclaration>()
        .any { owner ->
            owner.hasModifier(KtTokens.PRIVATE_KEYWORD) || owner.hasModifier(KtTokens.INTERNAL_KEYWORD)
        }

private fun KtNamedDeclaration.isLocalDeclaration(): Boolean =
    generateSequence(parent) { it.parent }
        .takeWhile { it !is KtFile }
        .any { it is KtNamedFunction }

private fun KtNamedDeclaration.detach(): KotlinDocumentedDeclaration =
    KotlinDocumentedDeclaration(
        declarationPath = syntacticPath(),
        kind = declarationKind(),
        name = requireNotNull(name),
        signature = declarationSignature(),
        documentation = renderKDoc(docComment?.text.orEmpty()),
    )

private fun KtNamedDeclaration.syntacticPath(): String =
    (
        generateSequence(parent) { it.parent }
            .takeWhile { it !is KtFile }
            .filterIsInstance<KtNamedDeclaration>()
            .mapNotNull(KtNamedDeclaration::getName)
            .toList()
            .asReversed() + requireNotNull(name)
    ).joinToString(".")

private fun KtNamedDeclaration.declarationKind(): String =
    when (this) {
        is KtObjectDeclaration -> "object"
        is KtClass ->
            when {
                isInterface() -> "interface"
                isEnum() -> "enum"
                isAnnotation() -> "annotation"
                else -> "class"
            }
        is KtNamedFunction -> "function"
        is KtProperty -> if (isVar) "variable" else "property"
        is KtTypeAlias -> "typealias"
        else -> error("unsupported documented declaration ${this::class.simpleName}")
    }

private fun KtNamedDeclaration.declarationSignature(): String =
    when (this) {
        is KtClassOrObject -> buildString {
            append(modifierPrefix())
            append(declarationKind()).append(' ').append(requireNotNull(name))
            typeParameterList?.text?.let { append(it) }
            if (this@declarationSignature is KtClass) {
                primaryConstructor?.valueParameterList?.text?.let { append(it) }
            }
            if (superTypeListEntries.isNotEmpty()) {
                append(" : ")
                append(superTypeListEntries.joinToString(", ") { it.text })
            }
        }
        is KtNamedFunction -> buildString {
            append(modifierPrefix())
            append("fun ")
            typeParameterList?.text?.let { append(it).append(' ') }
            receiverTypeReference?.text?.let { append(it).append('.') }
            append(requireNotNull(name))
            append(valueParameterList?.text ?: "()")
            typeReference?.text?.let { append(": ").append(it) }
        }
        is KtProperty -> buildString {
            append(modifierPrefix())
            append(if (isVar) "var " else "val ")
            receiverTypeReference?.text?.let { append(it).append('.') }
            append(requireNotNull(name))
            typeReference?.text?.let { append(": ").append(it) }
        }
        is KtTypeAlias -> buildString {
            append(modifierPrefix())
            append("typealias ").append(requireNotNull(name))
            typeParameterList?.text?.let { append(it) }
            getTypeReference()?.text?.let { append(" = ").append(it) }
        }
        else -> error("unsupported documented declaration ${this::class.simpleName}")
    }.replace(Regex("\\s+"), " ").trim()

private fun KtNamedDeclaration.modifierPrefix(): String =
    modifierList?.text?.trim()?.takeIf(String::isNotEmpty)?.let { "$it " }.orEmpty()

private fun renderKDoc(raw: String): String {
    if (raw.isBlank()) return ""
    return raw.lineSequence()
        .map { line -> line.trim().removePrefix("/**").removeSuffix("*/").trim().removePrefix("*").trimStart() }
        .dropWhile(String::isBlank)
        .toList()
        .dropLastWhile(String::isBlank)
        .joinToString("\n")
}
