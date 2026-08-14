package io.github.amichne.kast.change.verify.intellij

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import io.github.amichne.kast.change.contract.AddDeclarationCompilerContextFile
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAdmission
import io.github.amichne.kast.change.contract.AddDeclarationIntellijRuntimeAuthority
import io.github.amichne.kast.change.contract.AddDeclarationKind
import io.github.amichne.kast.change.contract.ExpectedAddDeclarationCompilerContext
import io.github.amichne.kast.change.verify.spi.AddDeclarationObservedIdentity
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationCommand
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationExecutor
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationLimitation
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationRejection
import io.github.amichne.kast.change.verify.spi.AddDeclarationVerificationResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.PublishedWorkspaceGenerationState
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class IntellijAddDeclarationVerificationExecutor(
    private val project: Project,
    private val publications: IntellijPublishedWorkspaceGenerationAuthority,
    private val environment: IntellijAddDeclarationCompilerEnvironmentAuthority,
    private val runtime: AddDeclarationIntellijRuntimeAuthority = liveVerificationRuntimeAuthority(),
    private val beforeRead: () -> Unit = ProgressManager::checkCanceled,
) : AddDeclarationVerificationExecutor() {
    /**
     * Proof transition: [AddDeclarationVerificationCommand] to
     * [AddDeclarationVerificationResult].
     *
     * Observed proves the exact G1 publication, physical/document postimage, current compiler
     * context, appended declaration identity, bounded diagnostics, collision absence, outbound
     * resolution count, and zero existing rebinding candidates under one scoped smart read.
     * Expected failure is closed by [AddDeclarationVerificationRejection] and retains the admitted
     * command. Live IntelliJ and K2 values are consumed before return.
     */
    override suspend fun verify(
        command: AddDeclarationVerificationCommand,
    ): AddDeclarationVerificationResult = try {
        beforeRead()
        if (runtime.current() is AddDeclarationIntellijRuntimeAdmission.Unsupported) {
            return rejected(command, AddDeclarationVerificationLimitation.UNSUPPORTED_RUNTIME)
        }
        if (publicationObservation(command) == PublicationObservation.Moved) {
            return rejected(command, AddDeclarationVerificationLimitation.RESULT_GENERATION_MOVED)
        }
        smartReadAction(project) { verifyScoped(command) }
    } catch (failure: ProcessCanceledException) {
        throw failure
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        rejected(command, AddDeclarationVerificationLimitation.SEMANTIC_READ_UNAVAILABLE)
    }

    private fun verifyScoped(
        command: AddDeclarationVerificationCommand,
    ): AddDeclarationVerificationResult {
        ProgressManager.checkCanceled()
        if (publicationObservation(command) == PublicationObservation.Moved) {
            return rejected(command, AddDeclarationVerificationLimitation.RESULT_GENERATION_MOVED)
        }
        val currentEnvironment = when (val observed = environment.observe(command)) {
            is IntellijAddDeclarationCompilerEnvironmentResult.Observed -> observed.environment
            is IntellijAddDeclarationCompilerEnvironmentResult.Rejected ->
                return rejected(command, observed.limitation)
        }
        if (currentEnvironment.owner != command.plan.target.owner) {
            return rejected(command, AddDeclarationVerificationLimitation.OWNER_AND_PROVENANCE_CHANGED)
        }
        val targetPath = Path.of(command.plan.target.targetPath.value)
        val targetBytes = when (val read = readRegularSource(targetPath)) {
            is RegularSourceRead.Read -> read.bytes
            RegularSourceRead.Unavailable -> return rejected(
                command,
                AddDeclarationVerificationLimitation.TARGET_CONTEXT_MISSING,
            )
        }
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(targetPath)
                          ?: return rejected(
                              command,
                              AddDeclarationVerificationLimitation.TARGET_CONTEXT_MISSING,
                          )
        if (!virtualFile.isValid) {
            return rejected(command, AddDeclarationVerificationLimitation.TARGET_CONTEXT_MISSING)
        }
        val target = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
                     ?: return rejected(
                         command,
                         AddDeclarationVerificationLimitation.TARGET_CONTEXT_MISSING,
                     )
        val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                       ?: return rejected(
                           command,
                           AddDeclarationVerificationLimitation.COMPILER_CONTEXT_UNAVAILABLE,
                       )
        val postimage = when (val admitted = ExactVerifiedAddDeclarationPostimage.admit(
            expectedPreimage = command.plan.expectedFile.preimage,
            expectedPostimage = command.plan.expectedFile.postimage,
            currentPhysicalBytes = targetBytes,
            normalizedDocumentText = document.text,
            proposedDeclaration = command.plan.intent.proposedDeclaration.value,
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                command,
                AddDeclarationVerificationLimitation.TARGET_POSTIMAGE_MISMATCH,
            )
        }
        val declaration = when (val located = locateDeclaration(target, postimage.declarationRange)) {
            is LocatedDeclaration.Found -> located.declaration
            LocatedDeclaration.Absent ->
                return rejected(command, AddDeclarationVerificationLimitation.DECLARATION_NOT_FOUND)
            LocatedDeclaration.Ambiguous ->
                return rejected(command, AddDeclarationVerificationLimitation.DECLARATION_AMBIGUOUS)
        }
        val declarationIdentity = when (val observed = declarationIdentity(declaration)) {
            is DeclarationIdentityInput.Observed -> observed
            DeclarationIdentityInput.Unsupported -> return rejected(
                command,
                AddDeclarationVerificationLimitation.DECLARATION_IDENTITY_MISMATCH,
            )
        }
        val identity = when (val admitted = AddDeclarationObservedIdentity.admit(
            expected = command.plan.expectedSemanticDelta,
            expectedTargetPath = command.plan.target.targetPath,
            observedPackageName = target.packageFqName.asString(),
            observedDeclarationName = declarationIdentity.name.value,
            observedKind = declarationIdentity.kind,
            observedStartOffset = postimage.declarationRange.startOffset,
            observedEndOffset = postimage.declarationRange.endOffset,
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                command,
                AddDeclarationVerificationLimitation.DECLARATION_IDENTITY_MISMATCH,
            )
        }
        val semantics = when (val proven = proveAddDeclarationSemantics(
            target,
            declaration,
            postimage.declarationRange,
            command.plan.compilerContext.outboundReferenceCount,
        )) {
            is Refinement.Refined -> proven.value
            is Refinement.Rejected -> return rejected(command, proven.failure.toLimitation())
        }
        val contextFiles = when (val observed = currentContextFiles(command, targetBytes)) {
            is CurrentContextFiles.Observed -> observed.files
            CurrentContextFiles.Unavailable -> return rejected(
                command,
                AddDeclarationVerificationLimitation.COMPILER_CONTEXT_UNAVAILABLE,
            )
        }
        val compilerContext = when (val admitted = ExpectedAddDeclarationCompilerContext.admit(
            generation = command.publication.generation,
            projectModelFingerprint = currentEnvironment.projectModelFingerprint,
            classpathFingerprint = currentEnvironment.classpathFingerprint,
            contextFiles = contextFiles,
            outboundReferenceCount = semantics.outboundReferenceCount,
        )) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return rejected(
                command,
                AddDeclarationVerificationLimitation.COMPILER_CONTEXT_UNAVAILABLE,
            )
        }
        return verified(
            command,
            compilerContext,
            identity,
            semantics.diagnostics,
            semantics.collision,
            semantics.outboundBindings,
            semantics.existingBindings,
        )
    }

    /**
     * Proof transition: current publication authority to [PublicationObservation].
     *
     * Exact preserves equality to the command's G1; Moved is the closed contrary state. Raw
     * workspace state is consumed only at this request-local observation boundary.
     */
    private fun publicationObservation(
        command: AddDeclarationVerificationCommand,
    ): PublicationObservation =
        if (publications.current() == PublishedWorkspaceGenerationState.Published(command.publication)) {
            PublicationObservation.Exact
        } else {
            PublicationObservation.Moved
        }

    private fun currentContextFiles(
        command: AddDeclarationVerificationCommand,
        targetBytes: ByteArray,
    ): CurrentContextFiles {
        val observed = mutableListOf<AddDeclarationCompilerContextFile>()
        for (planned in command.plan.compilerContext.contextFiles) {
            ProgressManager.checkCanceled()
            val bytes = if (planned.path == command.plan.target.targetPath.value) {
                targetBytes
            } else {
                when (val read = readRegularSource(Path.of(planned.path))) {
                    is RegularSourceRead.Read -> read.bytes
                    RegularSourceRead.Unavailable -> return CurrentContextFiles.Unavailable
                }
            }
            when (val admitted = AddDeclarationCompilerContextFile.admit(
                planned.path,
                sha256(bytes).value,
            )) {
                is Refinement.Refined -> observed += admitted.value
                is Refinement.Rejected -> return CurrentContextFiles.Unavailable
            }
        }
        return CurrentContextFiles.Observed(observed)
    }
}

private enum class PublicationObservation { Exact, Moved }
private sealed interface CurrentContextFiles {
    data class Observed(val files: List<AddDeclarationCompilerContextFile>) : CurrentContextFiles
    data object Unavailable : CurrentContextFiles
}

private sealed interface LocatedDeclaration {
    data class Found(val declaration: KtNamedDeclaration) : LocatedDeclaration
    data object Absent : LocatedDeclaration
    data object Ambiguous : LocatedDeclaration
}

/**
 * Proof transition: exact top-level PSI and approved range to [LocatedDeclaration].
 *
 * Found carries the sole declaration with the exact UTF-16 range. Absent and Ambiguous are the
 * closed expected failures. PSI is retained only inside the scoped read.
 */
private fun locateDeclaration(
    target: KtFile,
    range: VerifiedDeclarationRange,
): LocatedDeclaration {
    val candidates = target.declarations.filterIsInstance<KtNamedDeclaration>().filter { declaration ->
        declaration.textRange.startOffset == range.startOffset &&
        declaration.textRange.endOffset == range.endOffset
    }
    return when (candidates.size) {
        0 -> LocatedDeclaration.Absent
        1 -> LocatedDeclaration.Found(candidates.single())
        else -> LocatedDeclaration.Ambiguous
    }
}

private sealed interface DeclarationIdentityInput {
    data class Observed(
        val name: CompilerObservedDeclarationName,
        val kind: AddDeclarationKind,
    ) : DeclarationIdentityInput

    data object Unsupported : DeclarationIdentityInput
}

@JvmInline
private value class CompilerObservedDeclarationName private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: nullable PSI name to [DeclarationIdentityInput].
         *
         * Observed carries a non-empty compiler declaration name and its supported kind.
         * Unsupported is the closed expected failure. The raw name may be extracted only at the
         * SPI semantic-identity admission boundary.
         */
        fun fromPsi(
            declaration: KtNamedDeclaration,
            kind: AddDeclarationKind,
        ): DeclarationIdentityInput {
            val name = declaration.name
            return if (name.isNullOrEmpty()) {
                DeclarationIdentityInput.Unsupported
            } else {
                DeclarationIdentityInput.Observed(CompilerObservedDeclarationName(name), kind)
            }
        }
    }
}

/**
 * Proof transition: located named declaration to [DeclarationIdentityInput].
 *
 * Observed carries a non-absent compiler name and supported declaration kind. Unsupported is the
 * closed expected failure. Raw PSI names may be extracted only at the SPI identity admission call.
 */
private fun declarationIdentity(declaration: KtNamedDeclaration): DeclarationIdentityInput {
    val kind = when (declaration) {
        is org.jetbrains.kotlin.psi.KtClass -> when {
            declaration.isInterface() -> AddDeclarationKind.INTERFACE
            declaration.isEnum() -> AddDeclarationKind.ENUM_CLASS
            declaration.isAnnotation() -> AddDeclarationKind.ANNOTATION_CLASS
            else -> AddDeclarationKind.CLASS
        }
        is org.jetbrains.kotlin.psi.KtObjectDeclaration -> AddDeclarationKind.OBJECT
        is org.jetbrains.kotlin.psi.KtNamedFunction -> AddDeclarationKind.FUNCTION
        is org.jetbrains.kotlin.psi.KtProperty -> AddDeclarationKind.PROPERTY
        is org.jetbrains.kotlin.psi.KtTypeAlias -> AddDeclarationKind.TYPE_ALIAS
        else -> return DeclarationIdentityInput.Unsupported
    }
    return CompilerObservedDeclarationName.fromPsi(declaration, kind)
}

private sealed interface RegularSourceRead {
    class Read(val bytes: ByteArray) : RegularSourceRead
    data object Unavailable : RegularSourceRead
}

/**
 * Proof transition: planned source path to [RegularSourceRead].
 *
 * Read proves a regular, non-symlink physical file and carries its exact bytes. Unavailable is the
 * closed expected failure. Filesystem bytes may leave this capability only at exact-image checks.
 */
private fun readRegularSource(path: Path): RegularSourceRead = try {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
        RegularSourceRead.Unavailable
    } else {
        RegularSourceRead.Read(Files.readAllBytes(path))
    }
} catch (_: Exception) {
    RegularSourceRead.Unavailable
}

@JvmInline
private value class ObservedSha256 private constructor(val value: String) {
    companion object {
        /**
         * Proof transition: physical source bytes to [ObservedSha256].
         *
         * The output is the exact lowercase SHA-256 digest. There is no expected failure. The raw
         * digest string may be extracted only at compiler-context file admission.
         */
        fun digest(bytes: ByteArray): ObservedSha256 = ObservedSha256(
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte) },
        )
    }
}

private fun sha256(bytes: ByteArray): ObservedSha256 = ObservedSha256.digest(bytes)

private fun IntellijAddDeclarationSemanticProofFailure.toLimitation(): AddDeclarationVerificationLimitation =
    when (this) {
        IntellijAddDeclarationSemanticProofFailure.DIAGNOSTICS_INCOMPLETE ->
            AddDeclarationVerificationLimitation.COMPILER_DIAGNOSTICS_INCOMPLETE
        IntellijAddDeclarationSemanticProofFailure.DIAGNOSTICS_REJECTED ->
            AddDeclarationVerificationLimitation.COMPILER_DIAGNOSTICS_REJECTED
        IntellijAddDeclarationSemanticProofFailure.COLLISION_SCOPE_INCOMPLETE ->
            AddDeclarationVerificationLimitation.COLLISION_SCOPE_INCOMPLETE
        IntellijAddDeclarationSemanticProofFailure.COLLISION_OBSERVED ->
            AddDeclarationVerificationLimitation.EXISTING_BINDINGS_CHANGED
        IntellijAddDeclarationSemanticProofFailure.OUTBOUND_SCOPE_INCOMPLETE,
        IntellijAddDeclarationSemanticProofFailure.OUTBOUND_COUNT_INVALID,
            -> AddDeclarationVerificationLimitation.OUTBOUND_SCOPE_INCOMPLETE
        IntellijAddDeclarationSemanticProofFailure.EXISTING_BINDINGS_CHANGED ->
            AddDeclarationVerificationLimitation.EXISTING_BINDINGS_CHANGED
    }

private fun rejected(
    command: AddDeclarationVerificationCommand,
    limitation: AddDeclarationVerificationLimitation,
): AddDeclarationVerificationResult.Rejected = AddDeclarationVerificationResult.Rejected(
    command,
    AddDeclarationVerificationRejection.of(limitation),
)
