package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Frozen inputs enter one indexed test root with the exact reviewed binary and source artifacts. */
internal class NativeReviewedQueryFixture(
    private val project: Project,
    private val base: VirtualFile,
    private val module: Module,
    private val sdk: Sdk,
) {
    fun prepare(variant: NativeReviewedQueryVariant): NativeReviewedQueryCase {
        val bytes = resource("/r1-value-flow/${variant.name}.kt")
        check(sha256(bytes) == variant.sha256) { "Frozen source changed: ${variant.name}" }
        check(
            bytes.toString(Charsets.UTF_8).substring(REVIEWED_QUERY_SEED_START, REVIEWED_QUERY_SEED_END) ==
                "ClientValue(id = id)"
        )
        lateinit var root: VirtualFile
        lateinit var file: VirtualFile
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                root =
                    base
                        .createChildDirectory(this, "src")
                        .createChildDirectory(this, "test")
                        .createChildDirectory(this, "kotlin")
                file =
                    root
                        .createChildDirectory(this, "kast")
                        .createChildDirectory(this, "repro")
                        .createChildData(this, "Repro.kt")
                file.setBinaryContent(bytes)
            }
            prepareModule(root)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        val model = model(root)
        val services =
            NativePacketQueryServices(
                project = project,
                root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).packetQueryRefined(),
                model = model,
                file = file,
            )
        val source = NativeReviewedQuerySource(project, file, services)
        source.assertNoErrors()
        return NativeReviewedQueryCase(variant, services, source)
    }

    private fun prepareModule(root: VirtualFile) {
        val binary =
            System.getProperty("java.class.path").split(java.io.File.pathSeparator).map(Path::of).single {
                it.fileName.toString() == "kotlin-stdlib-2.4.21.jar"
            }
        check(sha256(Files.readAllBytes(binary)) == BINARY_SHA256) { "Reviewed binary artifact changed" }
        val sourceBytes = resource("/reviewed-libraries/kotlin-stdlib-2.4.21-sources.jar")
        check(sha256(sourceBytes) == SOURCES_SHA256) { "Reviewed source artifact changed" }
        val sources = base.toNioPath().resolve("reviewed-stdlib-sources.jar")
        Files.write(sources, sourceBytes)
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sources))
        ModuleRootModificationUtil.updateModel(module) { roots ->
            roots.sdk = sdk
            roots.addContentEntry(base).addSourceFolder(root, true)
            val library = roots.moduleLibraryTable.createLibrary("r1-reviewed-stdlib").modifiableModel
            library.addRoot("jar://$binary!/", OrderRootType.CLASSES)
            library.addRoot("jar://$sources!/", OrderRootType.SOURCES)
            library.commit()
        }
    }

    private fun model(root: VirtualFile): WorkspaceSearchScopeModel {
        val model =
            WorkspaceSearchScopeModel.compile(
                CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).packetQueryRefined(),
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = module.name,
                        linkedBuildRoot = base.toNioPath(),
                        gradleProjectPath = ":",
                        sourceSetName = "test",
                        sourceRoot = root.toNioPath(),
                        sourceKind = WorkspaceSourceRootKind.TEST,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            )
        check(model is WorkspaceSearchScopeModelCompilation.Compiled) { "Test source model failed: $model" }
        return model.model
    }

    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)).use {
            it.readBytes()
        }

    private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    companion object {
        const val BINARY_SHA256 = "bd8250210584cb659847dce9cda660f8e9906e5def7fbebcea93dc6dcb6a88a3"
        const val SOURCES_SHA256 = "1e29c0668b0aa555e7b154e5da2e8094e1b755cfee25737839195bbbdad8a4a2"
    }
}

internal data class NativeReviewedQueryCase(
    val variant: NativeReviewedQueryVariant,
    val services: NativePacketQueryServices,
    val source: NativeReviewedQuerySource,
)

internal enum class NativeReviewedQueryVariant(val sha256: String) {
    E0("babe9037f525c8c3102305bd5d34ce4d5455faccb37f32a52c87d335302780d2"),
    E1("9a7ae785438f45cc6fd1580bedd6b2476dc8018735c8b39414be0bd04ffc08bb"),
    E4("4d98aed3d4e9b6c46e1ce5f6cafc350d88d475cd85d2f1fbec3199a11a615035"),
    E5("bcc02012a91a3e651e3d1dc35cd4e19b94abf11004b608679e9ac6ffed00c044"),
    E6("b4b969c5cf9dd73195bc13e7fa348eb62a74da0427b9ac4e7a35075333737223"),
}

internal const val REVIEWED_QUERY_SEED_START = 232
internal const val REVIEWED_QUERY_SEED_END = 252
