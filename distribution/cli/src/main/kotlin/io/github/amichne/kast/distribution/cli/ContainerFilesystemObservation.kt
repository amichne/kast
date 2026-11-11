package io.github.amichne.kast.distribution.cli

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileSystemLoopException
import java.nio.file.NoSuchFileException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal enum class ContainerIoCondition {
    MISSING_PATH,
    EXISTING_PATH,
    ACCESS_DENIED,
    FILESYSTEM_LOOP,
    ATOMIC_MOVE_UNSUPPORTED,
    NO_SPACE,
    PATH_TOO_LONG,
    READ_ONLY,
    OPEN_FILE_LIMIT,
    NONEMPTY_DESTINATION,
    UNEXPECTED_END,
    CLOSED_CHANNEL,
    IO_REJECTED,
}

/** Only finite adapter evidence is retained: no exception message, filename, or source payload. */
@Serializable
internal data class ContainerFilesystemObservation(val condition: ContainerIoCondition) {
    val stage: ContainerStage = ContainerStage.FILESYSTEM_IO
    val outcome: ContainerStageOutcome = ContainerStageOutcome.REJECTED
    val component: String = "kast-container"
}

internal fun containerFilesystemObservation(failure: IOException): ContainerFilesystemObservation =
    ContainerFilesystemObservation(
        when (failure) {
            is NoSuchFileException -> ContainerIoCondition.MISSING_PATH
            is FileAlreadyExistsException -> ContainerIoCondition.EXISTING_PATH
            is AccessDeniedException -> ContainerIoCondition.ACCESS_DENIED
            is FileSystemLoopException -> ContainerIoCondition.FILESYSTEM_LOOP
            is AtomicMoveNotSupportedException -> ContainerIoCondition.ATOMIC_MOVE_UNSUPPORTED
            is java.io.EOFException -> ContainerIoCondition.UNEXPECTED_END
            is java.nio.channels.ClosedChannelException -> ContainerIoCondition.CLOSED_CHANNEL
            is FileSystemException -> filesystemReason(failure.reason)
            else -> ContainerIoCondition.IO_REJECTED
        }
    )

private fun filesystemReason(reason: String?): ContainerIoCondition =
    when (reason) {
        "No space left on device",
        "Disk quota exceeded" -> ContainerIoCondition.NO_SPACE
        "File name too long" -> ContainerIoCondition.PATH_TOO_LONG
        "Read-only file system" -> ContainerIoCondition.READ_ONLY
        "Too many open files" -> ContainerIoCondition.OPEN_FILE_LIMIT
        "Directory not empty" -> ContainerIoCondition.NONEMPTY_DESTINATION
        "Permission denied",
        "Operation not permitted" -> ContainerIoCondition.ACCESS_DENIED
        else -> ContainerIoCondition.IO_REJECTED
    }

internal fun recordContainerFilesystemObservation(observation: ContainerFilesystemObservation) {
    System.err.println(containerJson.encodeToString(observation))
}
