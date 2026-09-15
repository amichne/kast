package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactRevalidationRejection
import java.io.InputStream

/** Request-local bytes only; callers must discard them before returning detached capture evidence. */
internal sealed interface ExactSavedBytesRead {
    class Read(val bytes: ByteArray) : ExactSavedBytesRead

    data class Rejected(val reason: ExactRevalidationRejection) : ExactSavedBytesRead
}

/** At most the declared length plus one probe byte is read. Actual work is reported even on rejection. */
internal fun readExactSavedBytes(
    input: InputStream,
    expectedBytes: Int,
    bytesRead: (Int) -> Unit,
    cancellationCheck: () -> Unit,
): ExactSavedBytesRead {
    if (expectedBytes !in 0..IntellijExactRevalidationCapture.MAX_FILE_BYTES)
        return ExactSavedBytesRead.Rejected(ExactRevalidationRejection.CAPACITY)
    val bytes = ByteArray(expectedBytes)
    var offset = 0
    while (offset < expectedBytes) {
        cancellationCheck()
        val count =
            input.read(bytes, offset, minOf(IntellijExactRevalidationCapture.CHUNK_BYTES, expectedBytes - offset))
        if (count < 0) return ExactSavedBytesRead.Rejected(ExactRevalidationRejection.CONTENT_CHANGED)
        if (count == 0) return ExactSavedBytesRead.Rejected(ExactRevalidationRejection.CAPTURE_UNAVAILABLE)
        bytesRead(count)
        offset += count
    }
    cancellationCheck()
    if (input.read() >= 0) {
        bytesRead(1)
        return ExactSavedBytesRead.Rejected(ExactRevalidationRejection.CONTENT_CHANGED)
    }
    return ExactSavedBytesRead.Read(bytes)
}

/** Saved/committed flags do not establish text equality. All text stays inside the current read action. */
internal fun admitExactSavedPsiText(
    saved: CharSequence,
    psi: CharSequence,
    document: CharSequence?,
): Refinement<Unit, ExactRevalidationRejection> =
    if (!saved.contentEquals(psi) || document != null && !saved.contentEquals(document))
        Refinement.Rejected(ExactRevalidationRejection.CONTENT_UNCOMMITTED)
    else Refinement.Refined(Unit)
