package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.configuration.CliOutputFailure
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.output.cliOutputRejection
import io.github.amichne.kast.distribution.output.renderCliOutput
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException

internal sealed interface OwnedChildOutput {
    data object Inherited : OwnedChildOutput

    data class Document(val format: CliOutputFormat) : OwnedChildOutput

    class Capture : OwnedChildOutput {
        var result: CapturedChildOutput = CapturedChildOutput.Pending
            private set

        fun read(child: Process): ChildOutputDelivery =
            try {
                child.outputStream.close()
                val bytes = child.inputStream.readNBytes(MAXIMUM_CAPTURE_BYTES + 1)
                if (bytes.size > MAXIMUM_CAPTURE_BYTES) {
                    result = CapturedChildOutput.Rejected
                    ChildOutputDelivery.Rejected.IO
                } else {
                    result = CapturedChildOutput.Read(bytes.decodeToString(throwOnInvalidSequence = true))
                    ChildOutputDelivery.Delivered
                }
            } catch (_: CharacterCodingException) {
                result = CapturedChildOutput.Rejected
                ChildOutputDelivery.Rejected.ENCODING
            } catch (_: IOException) {
                result = CapturedChildOutput.Rejected
                ChildOutputDelivery.Rejected.IO
            }
    }
}

internal sealed interface CapturedChildOutput {
    data object Pending : CapturedChildOutput

    data object Rejected : CapturedChildOutput

    data class Read(val text: String) : CapturedChildOutput
}

private const val MAXIMUM_CAPTURE_BYTES = 1_048_576

internal sealed interface ChildOutputDelivery {
    data object Delivered : ChildOutputDelivery

    @Suppress("MagicNumber") // Published process exit codes.
    enum class Rejected(val exit: Int) : ChildOutputDelivery {
        IO(5),
        ENCODING(6),
    }
}

/** Only the installer result uses this adapter. Semantic children own their own presentation. */
internal fun deliverOwnedChildOutput(child: Process, output: OwnedChildOutput): ChildOutputDelivery =
    when (output) {
        OwnedChildOutput.Inherited -> ChildOutputDelivery.Delivered
        is OwnedChildOutput.Document -> deliverChildDocument(child, output.format)
        is OwnedChildOutput.Capture -> output.read(child)
    }

private fun deliverChildDocument(child: Process, format: CliOutputFormat): ChildOutputDelivery =
    try {
        val bytes = child.inputStream.readNBytes(MAXIMUM_CHILD_OUTPUT_BYTES + 1)
        when {
            bytes.isEmpty() -> ChildOutputDelivery.Delivered
            bytes.size > MAXIMUM_CHILD_OUTPUT_BYTES -> rejectChildLimit(format)
            else -> publishChildDocument(bytes.decodeToString(throwOnInvalidSequence = true), format)
        }
    } catch (_: CharacterCodingException) {
        rejectChildEncoding(format)
    } catch (_: IOException) {
        ChildOutputDelivery.Rejected.IO
    }

private fun publishChildDocument(document: String, format: CliOutputFormat): ChildOutputDelivery =
    when (val rendered = renderCliOutput(document, format)) {
        is Refinement.Refined -> {
            System.out.println(rendered.value)
            System.out.flush()
            if (System.out.checkError()) ChildOutputDelivery.Rejected.IO else ChildOutputDelivery.Delivered
        }
        is Refinement.Rejected -> rejectChildEncoding(format)
    }

private fun rejectChildEncoding(format: CliOutputFormat): ChildOutputDelivery {
    System.out.println(cliOutputRejection(CliOutputFailure.OUTPUT_ENCODING_REJECTED, format))
    return ChildOutputDelivery.Rejected.ENCODING
}

private fun rejectChildLimit(format: CliOutputFormat): ChildOutputDelivery {
    System.out.println(cliOutputRejection(CliOutputFailure.OUTPUT_LIMIT_EXCEEDED, format))
    return ChildOutputDelivery.Rejected.IO
}

private const val MAXIMUM_CHILD_OUTPUT_BYTES = 16_777_216
