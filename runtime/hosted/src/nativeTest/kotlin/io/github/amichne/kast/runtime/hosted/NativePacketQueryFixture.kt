package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
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

/** Original packet bytes enter a case-owned indexed project, with an authored source-root model. */
internal class NativePacketQueryFixture(
    private val project: Project,
    private val base: VirtualFile,
    private val module: com.intellij.openapi.module.Module,
    private val sdk: com.intellij.openapi.projectRoots.Sdk,
    private val configureCompiler: () -> Unit = {},
) {
    fun prepare(variant: NativePacketQueryVariant = NativePacketQueryVariant.V0): NativePacketQueryCase {
        val prepared = prepareFiles(listOf(PacketQuerySourceInput(variant.sourcePath, variant.manifest)))
        val original =
            NativePacketQuerySource(
                project = project,
                file = prepared.files.values.single(),
                services = prepared.services,
                variant = variant,
                ownedRoot = prepared.model.sourceRoots.single(),
            )
        original.assertNoErrors()
        return NativePacketQueryCase(prepared.services, original)
    }

    private fun prepareFiles(inputs: List<PacketQuerySourceInput>): PreparedPacketQuerySources {
        lateinit var source: VirtualFile
        var testSource: VirtualFile? = null
        val hasTestSources = inputs.any { it.path.startsWith("src/test/kotlin/") }
        val files = linkedMapOf<String, VirtualFile>()
        ApplicationManager.getApplication().invokeAndWait {
            source =
                WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                    val container = base.createChildDirectory(this, "src")
                    if (hasTestSources) {
                        testSource = container.createChildDirectory(this, "test")
                        container.createChildDirectory(this, "main")
                    } else container
                }
            prepareModule(source, testSource)
            WriteCommandAction.runWriteCommandAction(project) {
                configureCompiler()
                inputs.forEach { input -> files[input.path] = copyFrozenSource(source, testSource, input) }
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        val model = model(source, testSource)
        val services =
            NativePacketQueryServices(
                project = project,
                root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).packetQueryRefined(),
                model = model,
                files = files.values.toList(),
            )
        return PreparedPacketQuerySources(files, model, services)
    }

    private fun prepareModule(source: VirtualFile, testSource: VirtualFile?) {
        ModuleRootModificationUtil.updateModel(module) { roots ->
            roots.sdk = sdk
            val content = roots.addContentEntry(base)
            content.addSourceFolder(source, false)
            testSource?.let { content.addSourceFolder(it, true) }
            val library = roots.moduleLibraryTable.createLibrary("packet-query-stdlib")
            val libraryRoots = library.modifiableModel
            val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            check(Files.isRegularFile(jar)) { "Pinned Kotlin standard library is absent: $jar" }
            libraryRoots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
            libraryRoots.commit()
        }
    }

    private fun copyFrozenSource(
        source: VirtualFile,
        testSource: VirtualFile?,
        input: PacketQuerySourceInput,
    ): VirtualFile {
        val isTest = input.path.startsWith("src/test/kotlin/")
        var directory = if (isTest) checkNotNull(testSource) else source
        val relative = input.path.removePrefix(if (isTest) "src/test/kotlin/" else "src/main/kotlin/")
        relative.substringBeforeLast('/').split('/').forEach { name ->
            directory = directory.findChild(name) ?: directory.createChildDirectory(this, name)
        }
        return directory.createChildData(this, relative.substringAfterLast('/')).also {
            it.setBinaryContent(frozenSource(input))
        }
    }

    private fun model(source: VirtualFile, testSource: VirtualFile?): WorkspaceSearchScopeModel {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(base.toNioPath()).packetQueryRefined()
        val compiled =
            WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOfNotNull(
                    sourceRoot(source, "main", WorkspaceSourceRootKind.PRODUCTION),
                    testSource?.let { sourceRoot(it, "test", WorkspaceSourceRootKind.TEST) },
                ),
            )
        check(compiled is WorkspaceSearchScopeModelCompilation.Compiled) {
            "Packet query source model failed: $compiled"
        }
        return compiled.model
    }

    private fun sourceRoot(source: VirtualFile, sourceSet: String, kind: WorkspaceSourceRootKind) =
        WorkspaceSourceRootBoundary(
            ideaModuleName = module.name,
            linkedBuildRoot = base.toNioPath(),
            gradleProjectPath = ":packet",
            sourceSetName = sourceSet,
            sourceRoot = source.toNioPath(),
            sourceKind = kind,
            provenance = WorkspaceSourceRootProvenance.AUTHORED,
        )

    private fun frozenSource(input: PacketQuerySourceInput): ByteArray {
        val packet = Path.of("../../relation/intellij/testData/request-response").toAbsolutePath().normalize()
        val path = input.path
        val receipt = Files.readAllLines(packet.resolve("frozen-sources.sha256")).single { it.endsWith("  $path") }
        val expected = receipt.substringBefore("  ")
        val bytes = Files.readAllBytes(packet.resolve(path))
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        check(expected == actual) { "Original packet source differs from the immutable freeze: $path" }
        return bytes
    }
}

internal data class NativePacketQueryCase(val services: NativePacketQueryServices, val source: NativePacketQuerySource)

internal enum class NativePacketQueryVariant(val directory: String, val concreteClient: String, val manifest: String) {
    V0("v0", "BackendClient", "initial-freeze.sha256"),
    V1("v1", "BackendClientImpl", "derived-sources.sha256"),
    V2("v2", "BackendClient", "derived-sources.sha256"),
    V3("v3", "BackendClient", "derived-sources.sha256"),
    V8("v8", "BackendClient", "derived-sources.sha256"),
    COVERAGE("coverage", "BindingScope", "frozen-sources.sha256"),
    CONFIGURED("configured", "Box", "frozen-sources.sha256");

    val sourcePath = "src/main/kotlin/fixture/variants/$directory/${directory.uppercase()}.kt"
}

private data class PacketQuerySourceInput(val path: String, val manifest: String)

private data class PreparedPacketQuerySources(
    val files: Map<String, VirtualFile>,
    val model: WorkspaceSearchScopeModel,
    val services: NativePacketQueryServices,
)
