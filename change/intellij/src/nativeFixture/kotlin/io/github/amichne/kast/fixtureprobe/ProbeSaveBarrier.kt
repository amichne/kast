package io.github.amichne.kast.fixtureprobe

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A single fixed-image fixture interruption point; no production code is referenced. */
internal class ProbeSaveBarrier(
    private val project: Project,
    private val sandbox: ProbeSandbox,
    private val id: UUID,
    private val images: ProbeExpectedImages.Changed,
    private val document: Document,
) : Disposable, FileDocumentManagerListener {
    private val state = AtomicReference(ProbeBarrierState.CREATED)
    private val release = CountDownLatch(1)
    private val connection = ApplicationManager.getApplication().messageBus.connect()
    private val directory = sandbox.root.resolve("native-probe/barriers")

    fun install(): ProbeResult<Unit> =
        try {
            if (!sandbox.valid(project)) ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
            else {
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(
                        directory,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                    )
                }
                if (
                    directory.toRealPath() != directory ||
                        Files.getPosixFilePermissions(directory) != PosixFilePermissions.fromString("rwx------")
                ) {
                    ProbeResult.Rejected(ProbeFailure.SPOOL_REJECTED)
                } else {
                    connection.subscribe(FileDocumentManagerListener.TOPIC, this)
                    state.set(ProbeBarrierState.ARMED)
                    ProbeResult.Accepted(Unit)
                }
            }
        } catch (_: Exception) {
            ProbeResult.Rejected(ProbeFailure.SPOOL_REJECTED)
        }

    override fun afterDocumentSaved(saved: Document) {
        if (saved !== document || !state.compareAndSet(ProbeBarrierState.ARMED, ProbeBarrierState.REACHED)) return
        try {
            when (val admitted = savedPostimage()) {
                is ProbeResult.Rejected -> publish(".json", rejected(admitted.failure))
                is ProbeResult.Accepted -> {
                    publish(".json", reached(admitted.value))
                    val reason = awaitRelease()
                    publish(".released.json", released(reason))
                }
            }
        } catch (_: Exception) {
            // Transport failure cannot strand the native callback or imply that the barrier was observed.
        } finally {
            state.set(ProbeBarrierState.RELEASED)
            release.countDown()
            connection.disconnect()
        }
    }

    private fun savedPostimage(): ProbeResult<ProbeSavedBarrierEvidence> {
        if (!sandbox.valid(project)) return ProbeResult.Rejected(ProbeFailure.SANDBOX_REJECTED)
        val file =
            FileDocumentManager.getInstance().getFile(document)
                ?: return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
        val target = sandbox.project.resolve("src/main/kotlin/Fixture.kt")
        if (Path.of(file.path) != target || target.toRealPath() != target)
            return ProbeResult.Rejected(ProbeFailure.TARGET_UNAVAILABLE)
        val bytes = Files.newInputStream(target).use { source -> source.readNBytes(MAXIMUM_SOURCE_BYTES + 1) }
        if (bytes.size > MAXIMUM_SOURCE_BYTES) return ProbeResult.Rejected(ProbeFailure.SOURCE_TOO_LARGE)
        val physical = ProbeDigest.observe(bytes)
        val current = ProbeDigest.observe(document.text.toByteArray(Charsets.UTF_8))
        val documentState =
            ProbeDocumentState.observe(
                FileDocumentManager.getInstance().isDocumentUnsaved(document),
                PsiDocumentManager.getInstance(project).isCommitted(document),
            )
        return ProbeSavedBarrierEvidence.admit(
            images = images,
            saved = physical,
            document = current,
            state = documentState,
        )
    }

    private fun awaitRelease(): ProbeBarrierRelease =
        try {
            if (release.await(MAXIMUM_BARRIER_WAIT_MILLIS, TimeUnit.MILLISECONDS)) ProbeBarrierRelease.CANCELLED
            else ProbeBarrierRelease.TIMED_OUT
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ProbeBarrierRelease.CANCELLED
        }

    private fun reached(evidence: ProbeSavedBarrierEvidence): String = buildJsonObject {
        put("version", PROBE_VERSION)
        put("id", id.toString())
        put("command", ProbeCommand.ARM_POST_SAVE_BARRIER.name)
        put("outcome", "REACHED")
        put("expectedPreimageSha256", images.preimage.value)
        put("expectedPostimageSha256", images.postimage.value)
        put("savedSha256", evidence.saved.value)
        put("documentSha256", evidence.document.value)
        put("documentState", ProbeDocumentState.SAVED_COMMITTED.name)
        put("maximumWaitMillis", MAXIMUM_BARRIER_WAIT_MILLIS)
    }
        .toString()

    private fun rejected(failure: ProbeFailure): String = buildJsonObject {
        put("version", PROBE_VERSION)
        put("id", id.toString())
        put("command", ProbeCommand.ARM_POST_SAVE_BARRIER.name)
        put("outcome", "REJECTED")
        put("failure", failure.name)
    }
        .toString()

    private fun released(reason: ProbeBarrierRelease): String = buildJsonObject {
        put("version", PROBE_VERSION)
        put("id", id.toString())
        put("command", ProbeCommand.ARM_POST_SAVE_BARRIER.name)
        put("outcome", "RELEASED")
        put("reason", reason.name)
    }
        .toString()

    private fun publish(suffix: String, body: String) {
        if (!sandbox.valid(project) || directory.toRealPath() != directory) return
        val path = directory.resolve("$id$suffix")
        val temporary = directory.resolve("$id.${UUID.randomUUID()}.tmp")
        Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(temporary, body.toByteArray(Charsets.UTF_8), StandardOpenOption.WRITE)
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun dispose() {
        state.set(ProbeBarrierState.RELEASED)
        release.countDown()
        connection.disconnect()
    }
}

internal class ProbeSavedBarrierEvidence private constructor(val saved: ProbeDigest, val document: ProbeDigest) {
    companion object {
        fun admit(
            images: ProbeExpectedImages.Changed,
            saved: ProbeDigest,
            document: ProbeDigest,
            state: ProbeDocumentState,
        ): ProbeResult<ProbeSavedBarrierEvidence> =
            if (
                saved == images.postimage && document == images.postimage && state == ProbeDocumentState.SAVED_COMMITTED
            ) {
                ProbeResult.Accepted(ProbeSavedBarrierEvidence(saved, document))
            } else ProbeResult.Rejected(ProbeFailure.BARRIER_IMAGE_MISMATCH)
    }
}

private enum class ProbeBarrierState {
    CREATED,
    ARMED,
    REACHED,
    RELEASED,
}

private enum class ProbeBarrierRelease {
    CANCELLED,
    TIMED_OUT,
}

private const val MAXIMUM_BARRIER_WAIT_MILLIS = 10000L
