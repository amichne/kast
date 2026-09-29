package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir

/** Real file-backed PSI proof; this does not claim K2 identity or installed-host qualification. */
class IntellijExactDeclarationPhysicalPsiTest {
    @Test
    fun `physical paths and declaration shapes preserve exact lookup`(@TempDir home: Path) {
        withParser(home) { project ->
            val root = home.toRealPath()
            val cases = pathCases() + kotlinDeclarationCases() + javaDeclarationCases()
            assertAll(
                "file-backed exact declaration lookup",
                cases.mapIndexed { index, case ->
                    Executable { assertPhysicalCase(project, root.resolve("case$index"), case) }
                },
            )
        }
    }

    private fun assertPhysicalCase(project: Project, root: Path, case: Case) {
        val path = root.resolve(case.path)
        Files.createDirectories(path.parent)
        Files.writeString(path, case.prefix + case.declaration + case.suffix)
        val virtualFile =
            requireNotNull(VirtualFileManager.getInstance().getFileSystem("file").findFileByPath(path.toString()))
        ApplicationManager.getApplication().runReadAction {
            val file = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            assertPhysicalFile(file, case)
            val key =
                IntellijExactDeclarationLookupKey(
                    file =
                        SymbolDiscoveryFileIdentity.fromBoundary(
                                workspaceRoot = CanonicalWorkspaceRoot.fromCanonicalPath(root).refined(),
                                nativePath = path,
                                virtualFileUrl = virtualFile.url,
                            )
                            .refined(),
                    offset = SymbolDiscoverySourceOffset.parse(case.prefix.length).refined(),
                    name = SymbolDiscoveryCandidateName.parse(case.name).refined(),
                )
            assertLookup(file, key, case)
        }
    }

    private fun assertPhysicalFile(file: PsiFile, case: Case) {
        if (case.path.endsWith(".java")) assertInstanceOf(PsiJavaFile::class.java, file)
        else assertInstanceOf(KtFile::class.java, file)
        assertTrue(file.isPhysical, case.path)
        assertTrue(file.isValid, case.path)
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java), case.path)
        val directory = requireNotNull(file.containingDirectory)
        assertNull(directory.textRange, "a real directory is named PSI without a source range")
        assertEquals(
            case.collision,
            generateSequence(directory) { it.parent }.any { it.name == case.name },
            case.path,
        )
    }

    private fun assertLookup(file: PsiFile, key: IntellijExactDeclarationLookupKey, case: Case) {
        val leaf = requireNotNull(file.findElementAt(case.prefix.length))
        val found =
            assertInstanceOf(
                IntellijLiveExactDeclarationLookupResult.Found::class.java,
                findExactDeclarationAncestor(leaf, key),
                case.path,
            )
        assertEquals(case.declaration, found.declaration.text, case.path)
        assertEquals(key.file, found.evidence.file)
        assertEquals(key.name, found.evidence.name)
        assertEquals(case.prefix.length, found.evidence.range.startInclusive)
        assertEquals(case.prefix.length + case.declaration.length, found.evidence.range.endExclusive)
        val declaration = assertInstanceOf(PsiNameIdentifierOwner::class.java, found.declaration)
        val fromIdentifier =
            assertInstanceOf(
                IntellijLiveExactDeclarationLookupResult.Found::class.java,
                findExactDeclarationAncestor(requireNotNull(declaration.nameIdentifier), key),
            )
        assertSame(declaration, fromIdentifier.declaration)
        assertEquals(found.evidence, fromIdentifier.evidence)
    }

    private fun pathCases(): List<Case> =
        listOf(
            Case("health", "src/main/kotlin/example/health/Functions.kt", "fun health() = 1"),
            Case(
                "inquiry",
                "src/main/kotlin/inquiry/impl/Functions.kt",
                "fun inquiry() = 1",
                prefix = "package unrelated\n\n",
            ),
            Case("main", "src/main/kotlin/example/Functions.kt", "fun main() = 1"),
            Case("test", "src/test/kotlin/example/Functions.kt", "fun test() = 1"),
            Case("paperless", "paperless/src/main/kotlin/example/Functions.kt", "fun paperless() = 1"),
            Case("health", "health/health/health/Functions.kt", "fun health() = 1"),
            Case("unrelated", "src/main/kotlin/example/Functions.kt", "fun unrelated() = 1", collision = false),
            Case(
                "health",
                "src/main/kotlin/example/Functions.kt",
                "fun health() = 1",
                prefix = "package health\n\n",
                collision = false,
            ),
        )

    private fun kotlinDeclarationCases(): List<Case> =
        listOf(
            Case(
                "authenticate",
                "authenticate/Functions.kt",
                "@Deprecated(\"fixture\")\nprivate fun authenticate() = 1",
            ),
            Case("format", "format/Declarations.kt", "val format: Int = 1"),
            Case("Model", "Model/Declarations.kt", "class Model"),
            Case("Registry", "Registry/Declarations.kt", "object Registry"),
            Case("Alias", "Alias/Declarations.kt", "typealias Alias = String"),
            Case(
                "messages",
                "messages/Functions.kt",
                "fun messages() = 1",
                prefix = "class Service {\n    companion object {\n        ",
                suffix = "\n    }\n}",
            ),
            Case("helper", "helper/Functions.kt", "fun helper() = 1", prefix = "fun outer() {\n    ", suffix = "\n}"),
            Case(
                "service",
                "service/Functions.kt",
                "fun service(value: String) = value",
                prefix = "fun service(value: Int) = value\n\n",
            ),
        )

    private fun javaDeclarationCases(): List<Case> =
        listOf(
            Case("Service", "Service/Service.java", "class Service {}"),
            Case(
                "service",
                "service/Service.java",
                "int service() { return 1; }",
                prefix = "class Service {\n    ",
                suffix = "\n}",
            ),
            Case(
                "Service",
                "Service/Service.java",
                "Service() {}",
                prefix = "class Service {\n    ",
                suffix = "\n}",
            ),
        )

    private data class Case(
        val name: String,
        val path: String,
        val declaration: String,
        val prefix: String = "",
        val suffix: String = "",
        val collision: Boolean = true,
    )

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
        org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    )
    private fun withParser(home: Path, assertion: (Project) -> Unit) {
        val properties =
            listOf("idea.home.path", "idea.config.path", "idea.system.path").associateWith(System::getProperty)
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("bin/idea.properties"), "")
        System.setProperty("idea.home.path", home.toString())
        System.setProperty("idea.config.path", home.resolve("config").toString())
        System.setProperty("idea.system.path", home.resolve("system").toString())
        val disposable = Disposer.newDisposable()
        try {
            val environment =
                KotlinCoreEnvironment.createForTests(
                    disposable,
                    CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )
            assertion(environment.project)
        } finally {
            val application = ApplicationManager.getApplication()
            if (application == null) Disposer.dispose(disposable)
            else application.runWriteAction { Disposer.dispose(disposable) }
            properties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
