@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.api.contract.ByteOffset
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SemanticInsertionTarget
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.backend.KastIndexerBackend
import io.github.amichne.kast.idea.backend.relationships.relationshipIdentity
import io.github.amichne.kast.idea.backend.workspace.isWorkspaceFile
import io.github.amichne.kast.idea.edit.IdeaLineSeparator
import io.github.amichne.kast.idea.edit.IdeaNormalizedTextEdit
import io.github.amichne.kast.idea.edit.IdeaTextImagePlanner
import io.github.amichne.kast.idea.edit.IdeaUtf16Offset
import io.github.amichne.kast.idea.snapshot.BuildClasspathFingerprintResolver
import io.github.amichne.kast.idea.timedReadAction
import io.github.amichne.kast.shared.analysis.SemanticInsertionPointResolver
import io.github.amichne.kast.shared.analysis.compilerContainingDeclarationName
import io.github.amichne.kast.shared.analysis.toSymbolModel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.projectStructure.copyOrigin
import org.jetbrains.kotlin.analysis.api.resolution.KaErrorCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaImplicitInvokeCall
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*

private data class AdditionGradleOwnerIdentity(
    val sourceRoot: AdditionSourceRoot,
    val gradleBuildRoot: AdditionGradleBuildRoot,
    val gradleProjectPath: AdditionGradleProjectPath,
    val sourceSetName: AdditionGradleSourceSetName,
)

private data class AdditionOwnerObservation(
    val gradleOwner: AdditionGradleOwnerIdentity,
    val sourceRoot: io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge.GradleSourceRoot,
    val ideaModuleName: AdditionIdeaModuleName,
)

/**
 * Proof transition: `Path -> AdditionOwnerSnapshot`.
 *
 * Establishes one deepest exact Gradle source-set owner, an admitted editable target, one indexed
 * IDEA module, and classified read-only proof roots for the exact workspace model. Expected
 * failures are closed by `AdditionProofIncompleteException` and `AdditionProofLimitation`; the raw
 * path enters only at this addition-planning boundary.
 */
internal fun KastIndexerBackend.exactAdditionOwner(target: Path): AdditionOwnerSnapshot {
    val model = workspaceModelReader()
    if (!model.importedModelComplete()) failAddition(
        AdditionProofLimitation.PROJECT_MODEL_INCOMPLETE,
        "The imported Gradle project model is incomplete",
    )
    val normalizedTarget = target.toAbsolutePath().normalize()
    val candidates = model.moduleAssociations().flatMap { module ->
        module.sourceSets().flatMap { sourceSet ->
            sourceSet.sourceRoots().mapNotNull { rawRoot ->
                val sourceRoot = rawRoot.path()
                sourceRoot.takeIf { normalizedTarget != it && normalizedTarget.startsWith(it) }?.let {
                    AdditionOwnerObservation(
                        gradleOwner = AdditionGradleOwnerIdentity(
                            sourceRoot = AdditionSourceRoot.parse(sourceRoot.toString()),
                            gradleBuildRoot = AdditionGradleBuildRoot.parse(
                                module.linkedBuildRoot().toAbsolutePath().normalize().toString(),
                            ),
                            gradleProjectPath = AdditionGradleProjectPath.parse(module.gradleProjectPath()),
                            sourceSetName = AdditionGradleSourceSetName.of(sourceSet.sourceSetName()),
                        ),
                        sourceRoot = rawRoot,
                        ideaModuleName = AdditionIdeaModuleName.of(module.ideaModuleName()),
                    )
                }
            }
        }
    }
    val mostSpecificDepth = candidates.maxOfOrNull { Path.of(it.gradleOwner.sourceRoot.value).nameCount } ?: failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The target has no exact Gradle source-set owner",
    )
    val exactGradleOwners = candidates
        .filter { Path.of(it.gradleOwner.sourceRoot.value).nameCount == mostSpecificDepth }
        .groupBy(AdditionOwnerObservation::gradleOwner)
    if (exactGradleOwners.size != 1) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_AMBIGUOUS,
        "The target has more than one exact Gradle source-set owner",
    )
    val (gradleOwner, observations) = exactGradleOwners.entries.single()
    val editableTarget = EditableAdditionTarget.admit(
        backend = this,
        target = normalizedTarget,
        exactSourceRoots = observations.map(AdditionOwnerObservation::sourceRoot),
    )
    val sourceRoot = editableTarget.sourceRootPath
    val ideaModuleName = exactAdditionIdeaModule(
        target = normalizedTarget,
        sourceRoot = sourceRoot,
        observations = observations,
    )
    val proofRoots = model.moduleAssociations().flatMap { association ->
        association.sourceSets().flatMap { it.sourceRoots() }
    }
        .map(AdditionProofRoot::from)
        .distinctBy { it.sourceRoot.stableIdentity() }
        .sortedWith(
            compareByDescending<AdditionProofRoot> { it.path.nameCount }
                .thenBy { it.sourceRoot.stableIdentity() },
        )
    val sourceFiles = proofRoots
        .flatMap(::sourceFilesUnder)
        .distinctBy(AdditionProofFile::path)
        .sortedBy { it.path.toString() }
    val anchorSourceFiles = sourceFilesUnder(editableTarget.asProofRoot())
    return AdditionOwnerSnapshot(
        editableTarget = editableTarget,
        owner = AdditionSourceOwner.of(
            sourceRoot = editableTarget.additionSourceRoot,
            ideaModuleName = ideaModuleName,
            gradleBuildRoot = gradleOwner.gradleBuildRoot,
            gradleProjectPath = gradleOwner.gradleProjectPath,
            sourceSetName = gradleOwner.sourceSetName,
        ),
        modelFingerprint = projectModelFingerprint(model),
        classpathFingerprint = AdditionClasspathFingerprint.of(
            BuildClasspathFingerprintResolver.resolve(project, sharedWorkspaceIdentity).value,
        ),
        sourceFiles = sourceFiles,
        anchorSourceFiles = anchorSourceFiles,
    )
}

/**
 * Proof transition: `(Path, Path, List<AdditionOwnerObservation>) -> AdditionIdeaModuleName`.
 *
 * Establishes one IDEA module that agrees with the exact Gradle source-set observations. Expected
 * ambiguity or missing indexed ownership is closed by `AdditionProofIncompleteException` and
 * `AdditionProofLimitation`; raw paths are used only at the IDEA file-index boundary.
 */
private fun KastIndexerBackend.exactAdditionIdeaModule(
    target: Path,
    sourceRoot: Path,
    observations: List<AdditionOwnerObservation>,
): AdditionIdeaModuleName {
    val observedModuleNames = observations
        .map(AdditionOwnerObservation::ideaModuleName)
        .distinct()
        .sortedBy(AdditionIdeaModuleName::value)
    if (observedModuleNames.size == 1) return observedModuleNames.single()

    val indexedModuleNames = sequenceOf(
        target.takeIf { Files.exists(it, NOFOLLOW_LINKS) },
        target.parent,
        sourceRoot,
    ).filterNotNull()
        .distinct()
        .mapNotNull(LocalFileSystem.getInstance()::findFileByNioFile)
        .mapNotNull { ProjectFileIndex.getInstance(project).getModuleForFile(it)?.name }
        .distinct()
        .toList()
    if (indexedModuleNames.size != 1) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The exact Gradle source-set owner has no unique indexed IDEA module",
    )
    return observedModuleNames.singleOrNull { it.value == indexedModuleNames.single() } ?: failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The indexed IDEA module does not observe the exact Gradle source-set owner",
    )
}

/**
 * Proof transition: `AdditionProofRoot -> List<AdditionProofFile>`.
 *
 * Establishes a deterministic set of regular Kotlin and Java descendants with no symbolic links.
 * Expected unsafe source trees are closed by `AdditionProofIncompleteException` and
 * `AdditionProofLimitation`; raw paths are used only at the no-follow filesystem boundary.
 */
private fun sourceFilesUnder(root: AdditionProofRoot): List<AdditionProofFile> {
    if (Files.isSymbolicLink(root.path)) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "A model-owned source root must not be a symbolic link",
    )
    if (!Files.isDirectory(root.path, NOFOLLOW_LINKS)) return emptyList()
    return Files.walk(root.path).use { paths ->
        val entries = paths.toList()
        if (entries.any(Files::isSymbolicLink)) failAddition(
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            "Model-owned Kotlin and Java source context must not contain symbolic links",
        )
        entries.asSequence().filter { path -> Files.isRegularFile(path, NOFOLLOW_LINKS) }
            .filter { path -> path.toString().endsWith(".kt") || path.toString().endsWith(".java") }
            .map { it.toAbsolutePath().normalize() }
            .map(root::file)
            .toList()
    }
}

/**
 * Proof transition: `GradleWorkspaceModel -> AdditionProjectModelFingerprint`.
 *
 * Binds completeness, module identities, source sets, and classified source-root provenance into
 * one deterministic fingerprint. The serialized model image exists only at this hashing boundary.
 */
private fun projectModelFingerprint(
    model: io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge.GradleWorkspaceModel,
): AdditionProjectModelFingerprint =
    AdditionProjectModelFingerprint.of(FileHashing.sha256(
        buildString {
            append("complete=").append(model.importedModelComplete()).append('\n')
            model.moduleAssociations().sortedWith(
                compareBy(
                    { it.ideaModuleName() },
                    { it.linkedBuildRoot().toAbsolutePath().normalize().toString() },
                    { it.gradleProjectPath() },
                ),
            ).forEach { module ->
                append(module.ideaModuleName()).append('|')
                    .append(module.linkedBuildRoot().toAbsolutePath().normalize()).append('|')
                    .append(module.gradleProjectPath()).append('\n')
                module.sourceSets().sortedBy { it.sourceSetName() }.forEach { sourceSet ->
                    append(sourceSet.sourceSetName()).append('|')
                    append(sourceSet.sourceRoots().map { it.stableIdentity() }.sorted())
                    append('\n')
                }
            }
        },
    ))

/**
 * Proof transition: `AdditionOwnerSnapshot -> ExactAdditionProofContext`.
 *
 * Binds the current semantic generation, project model, classpath, classified proof-file set, and
 * exact no-follow content hashes into the persisted addition proof context. Expected read failures
 * are closed by `AdditionProofIncompleteException` and `AdditionProofLimitation`.
 */
internal fun KastIndexerBackend.exactAdditionContext(
    owner: AdditionOwnerSnapshot,
    generation: Long,
): ExactAdditionProofContext {
    return ExactAdditionProofContext.of(
        requiredGeneration = MutationSemanticGeneration(generation),
        projectModelFingerprint = owner.modelFingerprint,
        classpathFingerprint = owner.classpathFingerprint,
        contextFileHashes = owner.sourceFiles.map { file ->
            ExactAdditionContextFileHash.of(file.path.toString(), file.readExactHash().sha256)
        },
    )
}

internal fun KastIndexerBackend.findKtFileOrNull(path: Path): KtFile? {
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
    return PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
}


internal fun strictAdditionPlannerUtf8Bytes(value: String): ByteArray {
    val encoded = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .encode(java.nio.CharBuffer.wrap(value))
    return ByteArray(encoded.remaining()).also(encoded::get)
}

internal fun org.jetbrains.kotlin.name.FqName.toAdditionPackage(): AdditionKotlinPackage =
    if (isRoot) AdditionKotlinPackage.Root else AdditionKotlinPackage.Named.of(*pathSegments().map { it.asString() }.toTypedArray())

internal fun AdditionTargetPath.toJavaPath(): Path = Path.of(value)
