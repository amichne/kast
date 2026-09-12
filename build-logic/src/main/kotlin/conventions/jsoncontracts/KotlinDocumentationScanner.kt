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
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtEnumEntry
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
    val kind: KnowledgeDeclarationKind,
    val name: String,
    val signature: String,
    val documentation: String,
)

sealed interface KotlinDocumentationScan {
    data class Accepted(val declarations: List<KotlinDocumentedDeclaration>) : KotlinDocumentationScan
    data class Rejected(val reason: KnowledgeDocsFailureCode) : KotlinDocumentationScan
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
            return KotlinDocumentationScan.Rejected(KnowledgeDocsFailureCode.INVALID_KOTLIN)
        }
        val declarations = mutableListOf<KotlinDocumentedDeclaration>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                    val header = declaration.knowledgeHeader()
                    if (header != null && declaration.isPubliclyReachable()) {
                        declarations += declaration.detach(header)
                    }
                    super.visitNamedDeclaration(declaration)
                }
            },
        )
        return KotlinDocumentationScan.Accepted(
            declarations.sortedWith(compareBy({ it.declarationPath }, { it.kind }, { it.signature })),
        )
    }

    override fun close() = Disposer.dispose(disposable)
}

private fun KtNamedDeclaration.isPubliclyReachable(): Boolean =
    name != null && !hasModifier(KtTokens.PRIVATE_KEYWORD) && !hasModifier(KtTokens.INTERNAL_KEYWORD) &&
        when (val container = parent) {
            is KtFile -> true
            is KtClassBody -> (container.parent as? KtNamedDeclaration)?.isPubliclyReachable() == true
            else -> false
        }

private data class KnowledgeHeader(val kind: KnowledgeDeclarationKind, val endOffset: Int)

private fun KtNamedDeclaration.knowledgeHeader(): KnowledgeHeader? = when (this) {
    is KtEnumEntry -> KnowledgeHeader(KnowledgeDeclarationKind.ENUM_ENTRY, body?.textRange?.startOffset ?: textRange.endOffset)
    is KtClass -> KnowledgeHeader(
        when {
            isInterface() -> KnowledgeDeclarationKind.INTERFACE
            isEnum() -> KnowledgeDeclarationKind.ENUM
            isAnnotation() -> KnowledgeDeclarationKind.ANNOTATION
            else -> KnowledgeDeclarationKind.CLASS
        },
        body?.textRange?.startOffset ?: textRange.endOffset,
    )
    is KtObjectDeclaration -> KnowledgeHeader(KnowledgeDeclarationKind.OBJECT, body?.textRange?.startOffset ?: textRange.endOffset)
    is KtNamedFunction -> KnowledgeHeader(
        KnowledgeDeclarationKind.FUNCTION,
        equalsToken?.textRange?.startOffset ?: bodyExpression?.textRange?.startOffset ?: textRange.endOffset,
    )
    is KtProperty -> KnowledgeHeader(
        if (isVar) KnowledgeDeclarationKind.VARIABLE else KnowledgeDeclarationKind.PROPERTY,
        listOfNotNull(equalsToken, delegate, getter, setter).minOfOrNull { it.textRange.startOffset } ?: textRange.endOffset,
    )
    is KtTypeAlias -> KnowledgeHeader(KnowledgeDeclarationKind.TYPEALIAS, textRange.endOffset)
    else -> null
}

private fun KtNamedDeclaration.detach(header: KnowledgeHeader): KotlinDocumentedDeclaration {
    val signature = text.substring(0, header.endOffset - textRange.startOffset)
    val documentationRange = docComment?.textRange
    return KotlinDocumentedDeclaration(
        declarationPath = syntacticPath(),
        kind = header.kind,
        name = requireNotNull(name),
        signature = if (documentationRange == null) signature.trim() else signature.removeRange(
            documentationRange.startOffset - textRange.startOffset,
            documentationRange.endOffset - textRange.startOffset,
        ).trim(),
        documentation = renderKDoc(docComment?.text.orEmpty()),
    )
}

private fun KtNamedDeclaration.syntacticPath(): String =
    (
        generateSequence(parent) { it.parent }
            .takeWhile { it !is KtFile }
            .filterIsInstance<KtNamedDeclaration>()
            .mapNotNull(KtNamedDeclaration::getName)
            .toList()
            .asReversed() + requireNotNull(name)
    ).joinToString(".")

private fun renderKDoc(raw: String): String {
    if (raw.isBlank()) return ""
    val lines = raw.lines()
    return lines.mapIndexed { index, source ->
        var line = source.trimStart()
        if (index == 0) {
            line = line.removePrefix("/**")
            if (line.startsWith(' ')) line = line.drop(1)
        }
        if (index == lines.lastIndex) line = line.removeSuffix("*/").trimEnd()
        if (line.startsWith('*')) {
            line = line.drop(1)
            if (line.startsWith(' ')) line = line.drop(1)
        }
        line
    }.dropWhile(String::isBlank)
        .dropLastWhile(String::isBlank)
        .joinToString("\n")
}
