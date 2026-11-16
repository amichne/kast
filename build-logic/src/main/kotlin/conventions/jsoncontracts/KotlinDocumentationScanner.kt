package conventions.jsoncontracts

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/** PSI-only declaration evidence for repository citations; performs no name or type resolution. */
class KotlinDocumentationScanner : AutoCloseable {
    private val disposable = Disposer.newDisposable("kast-api-knowledge")

    @OptIn(CoreEnvironmentDeprecation::class)
    private val environment =
        KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration.create(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )

    private val factory = KtPsiFactory(environment.project, false)

    /** All named declarations, including internal and private declarations cited by repository knowledge. */
    fun symbols(fileName: String, content: String): KotlinSymbolScan {
        val file = factory.createFile(fileName, content)
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) {
            return KotlinSymbolScan.Rejected(KnowledgeDocsFailureCode.INVALID_KOTLIN)
        }
        val names = mutableSetOf<String>()
        val declarations = mutableListOf<KotlinSymbolDeclaration>()
        file.accept(
            object : KtTreeVisitorVoid() {
                override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                    val name = declaration.name
                    name?.let(names::add)
                    val identifier = declaration.nameIdentifier
                    if (name != null && identifier != null) {
                        val line = content.substring(0, identifier.textRange.startOffset).count { it == '\n' } + 1
                        val endLine = line + identifier.text.count { it == '\n' }
                        val test = declaration is KtNamedFunction && declaration.hasBody() &&
                            declaration.annotationEntries.any { it.shortName?.asString() == "Test" }
                        declarations += KotlinSymbolDeclaration(name, line, endLine, test)
                    }
                    super.visitNamedDeclaration(declaration)
                }
            },
        )
        return KotlinSymbolScan.Accepted(names.sorted(), declarations)
    }

    override fun close() = Disposer.dispose(disposable)
}
