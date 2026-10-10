package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/** Case-owned VFS metadata; every unsupported observation remains a fixture failure. */
internal class EnumerationTestFile(
    private val nio: Path,
    private val persistentId: Int,
    private val entries: List<EnumerationTestFile>? = null,
    private val valid: Boolean = true,
) : VirtualFile(), VirtualFileWithId {
    var childrenCalls = 0
        private set

    override fun getId(): Int = persistentId

    override fun toNioPath(): Path = nio

    override fun isDirectory(): Boolean = entries != null

    override fun isValid(): Boolean = valid

    override fun getChildren(): Array<VirtualFile> {
        childrenCalls++
        return (entries ?: error("Children of a file")).toTypedArray()
    }

    override fun getName(): String = error("Unexpected file name")

    override fun getPath(): String = error("Unexpected path")

    override fun getFileSystem(): com.intellij.openapi.vfs.VirtualFileSystem = error("Unexpected filesystem")

    override fun isWritable(): Boolean = error("Unexpected writable check")

    override fun getParent(): VirtualFile = error("Unexpected parent")

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
        error("Unexpected output stream")

    override fun contentsToByteArray(): ByteArray = error("Unexpected content")

    override fun getTimeStamp(): Long = error("Unexpected timestamp")

    override fun getLength(): Long = error("Unexpected length")

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) =
        error("Unexpected refresh")

    override fun getInputStream(): InputStream = error("Unexpected input stream")
}
