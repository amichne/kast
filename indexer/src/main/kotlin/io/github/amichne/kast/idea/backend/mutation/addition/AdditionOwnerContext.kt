@file:OptIn(
    org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class,
    org.jetbrains.kotlin.analysis.api.KaIdeApi::class,
)

package io.github.amichne.kast.idea.backend.mutation

import com.intellij.openapi.progress.ProcessCanceledException
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
import io.github.amichne.kast.api.client.WorkspacePathPolicy
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import io.github.amichne.kast.api.validation.*
import io.github.amichne.kast.idea.IdeaTelemetryScope
import io.github.amichne.kast.idea.IdeaWorkspaceMutation
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
import java.util.concurrent.CancellationException
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
    val ideaModuleName: AdditionIdeaModuleName,
)

internal fun KastIndexerBackend.exactAdditionOwner(target: Path): AdditionOwnerSnapshot {
    val model = workspaceModelReader()
    if (!model.importedModelComplete()) failAddition(
        AdditionProofLimitation.PROJECT_MODEL_INCOMPLETE,
        "The imported Gradle project model is incomplete",
    )
    val normalizedTarget = target.toAbsolutePath().normalize()
    requireAdditionPathAuthority(normalizedTarget, "addition target")
    val candidates = model.moduleAssociations().flatMap { module ->
        module.sourceSets().flatMap { sourceSet ->
            sourceSet.sourceRoots().mapNotNull { rawRoot ->
                val sourceRoot = rawRoot.toAbsolutePath().normalize()
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
    val sourceRoot = Path.of(gradleOwner.sourceRoot.value)
    requireAdditionPathAuthority(sourceRoot, "model-owned source root")
    val ideaModuleName = exactAdditionIdeaModule(
        target = normalizedTarget,
        sourceRoot = sourceRoot,
        observations = observations,
    )
    val modelSourceRoots = model.moduleAssociations().flatMap { association ->
        association.sourceSets().flatMap { it.sourceRoots() }
    }
        .map { it.toAbsolutePath().normalize() }
        .distinct()
        .sortedBy(Path::toString)
    modelSourceRoots.forEach { modelSourceRoot ->
        requireAdditionPathAuthority(modelSourceRoot, "model-owned proof source root")
    }
    val sourceFiles = modelSourceRoots
        .flatMap(::sourceFilesUnder)
        .distinct()
        .sortedBy(Path::toString)
    val anchorSourceFiles = sourceFilesUnder(sourceRoot)
    return AdditionOwnerSnapshot(
        owner = AdditionSourceOwner.of(
            sourceRoot = gradleOwner.sourceRoot,
            ideaModuleName = ideaModuleName,
            gradleBuildRoot = gradleOwner.gradleBuildRoot,
            gradleProjectPath = gradleOwner.gradleProjectPath,
            sourceSetName = gradleOwner.sourceSetName,
        ),
        modelFingerprint = AdditionProjectModelFingerprint.of(projectModelFingerprint(model)),
        classpathFingerprint = AdditionClasspathFingerprint.of(
            BuildClasspathFingerprintResolver.resolve(project, sharedWorkspaceIdentity).value,
        ),
        sourceFiles = sourceFiles,
        anchorSourceFiles = anchorSourceFiles,
    )
}

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

internal fun KastIndexerBackend.requireAdditionPathAuthority(path: Path, subject: String) {
    val normalizedPath = path.toAbsolutePath().normalize()
    val relativePath = sharedWorkspaceIdentity.relativizeIfContained(normalizedPath) ?: failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The $subject is outside the exact workspace authority",
    )
    if (WorkspacePathPolicy.isHardExcluded(relativePath)) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The $subject is inside a hard-excluded workspace directory",
    )
}

private fun sourceFilesUnder(root: Path): List<Path> {
    if (Files.isSymbolicLink(root)) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "A model-owned source root must not be a symbolic link",
    )
    if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return emptyList()
    return Files.walk(root).use { paths ->
        val entries = paths.toList()
        if (entries.any(Files::isSymbolicLink)) failAddition(
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            "Model-owned Kotlin and Java source context must not contain symbolic links",
        )
        entries.asSequence().filter { path -> Files.isRegularFile(path, NOFOLLOW_LINKS) }
            .filter { path -> path.toString().endsWith(".kt") || path.toString().endsWith(".java") }
            .map { it.toAbsolutePath().normalize() }
            .toList()
    }
}

private fun projectModelFingerprint(model: io.github.amichne.kast.idea.IdeaGradleProjectLoadBridge.GradleWorkspaceModel): String =
    FileHashing.sha256(
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
                    append(sourceSet.sourceRoots().map { it.toAbsolutePath().normalize().toString() }.sorted())
                    append('\n')
                }
            }
        },
    )

internal fun KastIndexerBackend.exactAdditionContext(
    owner: AdditionOwnerSnapshot,
    generation: Long,
    targetToInclude: Path?,
): ExactAdditionProofContext {
    val files = (owner.sourceFiles + listOfNotNull(targetToInclude)).distinct().sortedBy(Path::toString)
    return ExactAdditionProofContext.of(
        requiredGeneration = MutationSemanticGeneration(generation),
        projectModelFingerprint = owner.modelFingerprint,
        classpathFingerprint = owner.classpathFingerprint,
        contextFileHashes = files.map { path ->
            ExactAdditionContextFileHash.of(path.toString(), FileHashing.sha256(secureAdditionRead(path)))
        },
    )
}

internal fun KastIndexerBackend.revalidateAdditionContext(
    owner: AdditionOwnerSnapshot,
    generation: Long,
    context: ExactAdditionProofContext,
    target: Path,
    mustExist: Boolean,
) {
    if (psiGeneration() != generation) failAddition(
        AdditionProofLimitation.GENERATION_CHANGED,
        "The semantic generation changed during addition planning",
    )
    val currentOwner = exactAdditionOwner(target)
    if (currentOwner.modelFingerprint != owner.modelFingerprint) failAddition(
        AdditionProofLimitation.PROJECT_MODEL_CHANGED,
        "The Gradle project model changed during addition planning",
    )
    if (currentOwner.classpathFingerprint != owner.classpathFingerprint) failAddition(
        AdditionProofLimitation.CLASSPATH_CHANGED,
        "The compiler classpath changed during addition planning",
    )
    if (currentOwner.sourceFiles != owner.sourceFiles) failAddition(
        AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
        "The model-owned Kotlin and Java source-file set changed during addition planning",
    )
    if (Files.exists(target, NOFOLLOW_LINKS) != mustExist) failAddition(
        if (mustExist) AdditionProofLimitation.TARGET_FILE_MISSING else AdditionProofLimitation.TARGET_ALREADY_EXISTS,
        "The addition target state changed during planning",
    )
    context.contextFileHashes.forEach { expected ->
        val path = Path.of(expected.filePath)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || FileHashing.sha256(secureAdditionRead(path)) != expected.sha256) {
            failAddition(
                AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
                "A compiler source-context file changed during addition planning",
            )
        }
    }
    if (psiGeneration() != generation) failAddition(
        AdditionProofLimitation.GENERATION_CHANGED,
        "The semantic generation changed during addition proof finalization",
    )
}

internal fun KastIndexerBackend.findKtFileOrNull(path: Path): KtFile? {
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
    return PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
}

internal fun KastIndexerBackend.secureAdditionRead(path: Path): ByteArray = try {
    exactFileImageMutation.readFileBytes(path, IdeaWorkspaceMutation.TEXT_EDIT)
} catch (failure: ProcessCanceledException) {
    throw failure
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    failAddition(
        AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
        "An exact source-context image could not be read without following symbolic links",
    )
}

internal fun requireSecureAbsentTarget(target: Path, owner: AdditionSourceOwner) {
    val normalizedTarget = target.toAbsolutePath().normalize()
    val normalizedParent = normalizedTarget.parent ?: failAddition(
        AdditionProofLimitation.TARGET_PARENT_MISSING,
        "The add-file target has no parent directory",
    )
    val canonicalParent = try {
        normalizedParent.toRealPath()
    } catch (_: Exception) {
        failAddition(AdditionProofLimitation.TARGET_PARENT_MISSING, "The add-file parent is not canonical")
    }
    val sourceRoot = Path.of(owner.sourceRoot.value)
    val canonicalSourceRoot = try {
        sourceRoot.toRealPath()
    } catch (_: Exception) {
        failAddition(AdditionProofLimitation.SOURCE_OWNER_UNPROVEN, "The model-owned source root is not canonical")
    }
    val canonicalCandidate = canonicalParent.resolve(normalizedTarget.fileName).normalize()
    if (canonicalParent != normalizedParent || canonicalSourceRoot != sourceRoot ||
        canonicalCandidate != normalizedTarget || !canonicalCandidate.startsWith(canonicalSourceRoot)
    ) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The add-file target or its parent escapes the canonical model-owned source root",
    )
}

internal fun requireSecureExistingTarget(target: Path, owner: AdditionSourceOwner) {
    val normalizedTarget = target.toAbsolutePath().normalize()
    val canonicalTarget = try {
        normalizedTarget.toRealPath()
    } catch (_: Exception) {
        failAddition(AdditionProofLimitation.TARGET_NOT_KOTLIN_SOURCE, "The target path is not canonical")
    }
    val sourceRoot = Path.of(owner.sourceRoot.value)
    val canonicalSourceRoot = try {
        sourceRoot.toRealPath()
    } catch (_: Exception) {
        failAddition(AdditionProofLimitation.SOURCE_OWNER_UNPROVEN, "The model-owned source root is not canonical")
    }
    if (canonicalTarget != normalizedTarget || canonicalSourceRoot != sourceRoot ||
        !canonicalTarget.startsWith(canonicalSourceRoot)
    ) failAddition(
        AdditionProofLimitation.SOURCE_OWNER_UNPROVEN,
        "The add-declaration target escapes the canonical model-owned source root",
    )
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
