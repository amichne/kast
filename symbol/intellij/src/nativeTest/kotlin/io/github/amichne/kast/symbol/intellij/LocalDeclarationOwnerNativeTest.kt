@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.LocalDeclarationAddress
import io.github.amichne.kast.symbol.contract.LocalDeclarationKind
import io.github.amichne.kast.symbol.contract.LocalPropertyMutability
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty

class LocalDeclarationOwnerNativeTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testLambdaLocalPropertyKeepsItsNamedCompilerOwner() =
        assertProperty("fun owner() { scope { val item = 1; item.hashCode() } }")

    fun testAnonymousFunctionLocalPropertyKeepsItsNamedCompilerOwner() =
        assertProperty("fun owner() { (fun() { val item = 1; item.hashCode() })() }")

    fun testNamedLocalFunctionRemainsTheNearestCompilerOwner() {
        val owner = "fun nested() { val item = 1; item.hashCode() }"
        assertProperty("fun owner() { $owner; nested() }", owner)
    }

    fun testConstructorRemainsTheNearestCompilerOwner() {
        val owner = "constructor() { val item = 1; item.hashCode() }"
        assertProperty("class Owner { $owner }", owner)
    }

    fun testAnonymousObjectRemainsTheNearestCompilerOwner() {
        val owner = "object { init { val item = 1; item.hashCode() } }"
        assertProperty("fun owner() { val holder = $owner; holder.hashCode() }", owner)
    }

    private fun assertProperty(declaration: String, owner: String = declaration) {
        val source = "package proof\nfun scope(block: () -> Unit) { block() }\n$declaration\n"
        val file = prepare(source)
        ReadAction.run<RuntimeException> {
            val psi = psiManager.findFile(file) as KtFile
            val property = PsiTreeUtil.findChildrenOfType(psi, KtProperty::class.java).single { it.name == "item" }
            val identity = LocalDeclarationAddress.restoreFile("workspace", file.path).refined()
            val result =
                analyze(property) { property.symbol.toCompilerProjection(this, IntellijReadObservation.None, identity) }
            assertTrue(
                "Expected the exact local property, received $result",
                result is IntellijCompilerSymbolProjectionResult.Projected,
            )
            val signature = (result as IntellijCompilerSymbolProjectionResult.Projected).projection.signature
            assertTrue(signature is CanonicalCompilerSignature.LocalProperty)
            val local = signature as CanonicalCompilerSignature.LocalProperty
            assertEquals(LocalPropertyMutability.VAL, local.mutability)
            assertEquals(LocalDeclarationKind.PROPERTY, local.address.kind)
            val propertyStart = source.indexOf("val item = 1")
            assertEquals(propertyStart, local.address.range.startInclusive)
            assertEquals(propertyStart + "val item = 1".length, local.address.range.endExclusive)
            val ownerStart = source.indexOf(owner)
            assertEquals(ownerStart, local.address.ownerRange.startInclusive)
            assertEquals(ownerStart + owner.length, local.address.ownerRange.endExclusive)
            assertTrue(local.address.lexicalOwners.isNotEmpty())
        }
    }

    private fun prepare(source: String): VirtualFile {
        lateinit var file: VirtualFile
        ApplicationManager.getApplication().invokeAndWait {
            val sdk = JavaSdk.getInstance().createJdk("local-owner-jdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
            WriteCommandAction.runWriteCommandAction(project) {
                val root = getOrCreateProjectBaseDir().createChildDirectory(this, "src")
                ModuleRootModificationUtil.updateModel(module) { model ->
                    model.sdk = sdk
                    model.addContentEntry(root).addSourceFolder(root, false)
                    val library = model.moduleLibraryTable.createLibrary("local-owner-stdlib")
                    val roots = library.modifiableModel
                    val stdlib = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
                    roots.addRoot("jar://$stdlib!/", OrderRootType.CLASSES)
                    roots.commit()
                }
                file = root.createChildData(this, "Owners.kt")
                file.setBinaryContent(source.toByteArray())
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return file
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Invalid native local-owner fixture: $failure")
        }
}
