package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/** Only the VFS observations used by production capture are allowed; length is deliberately unavailable. */
internal class SdkPlanTestFile(
    private val location: String,
    var bytes: ByteArray = byteArrayOf(),
    private val entries: List<SdkPlanTestFile>? = null,
    private val valid: Boolean = true,
    private val providerProtocol: String = "file",
) : VirtualFile() {
    var visits = 0
        private set

    var childrenCalls = 0
        private set

    var opens = 0
        private set

    var reads = 0
        private set

    var closes = 0
        private set

    var beforeChildren: () -> Unit = {}

    private val filesystem =
        object : DeprecatedVirtualFileSystem() {
            override fun getProtocol(): String = providerProtocol

            override fun findFileByPath(path: String): VirtualFile = error("Unexpected lookup")

            override fun refresh(asynchronous: Boolean) = error("Unexpected filesystem refresh")

            override fun refreshAndFindFileByPath(path: String): VirtualFile = error("Unexpected refresh lookup")
        }

    override fun getUrl(): String = "$providerProtocol://$location"

    override fun getFileSystem(): VirtualFileSystem = filesystem

    override fun isValid(): Boolean {
        visits++
        return valid
    }

    override fun isDirectory(): Boolean = entries != null

    override fun getChildren(): Array<VirtualFile> {
        beforeChildren()
        childrenCalls++
        return (entries ?: error("Unexpected leaf children")).toTypedArray()
    }

    override fun getInputStream(): InputStream {
        opens++
        return object : ByteArrayInputStream(bytes) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                reads++
                return super.read(target, offset, length)
            }

            override fun close() {
                closes++
                super.close()
            }
        }
    }

    override fun getName(): String = error("Unexpected name")

    override fun getPath(): String = error("Unexpected path")

    override fun isWritable(): Boolean = error("Unexpected writable check")

    override fun getParent(): VirtualFile = error("Unexpected parent")

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
        error("Unexpected write")

    override fun contentsToByteArray(): ByteArray = error("Unexpected bulk read")

    override fun getTimeStamp(): Long = error("Unexpected timestamp")

    override fun getLength(): Long = error("Length cannot prove the minimum read cost")

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) =
        error("Unexpected refresh")
}
