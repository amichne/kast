package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.TopologySourceFile
import java.security.MessageDigest

internal enum class TopologySourceContentFailure {
    CONTENT_MOVED,
}

/** Exact admitted topology file whose live bytes retain their enumerated content identity. */
internal class LiveTopologySourceContent private constructor(
    val file: TopologySourceFile,
) {
    companion object {
        /**
         * Proof transition: `(TopologySourceFile, ByteArray) ->
         * Refinement<LiveTopologySourceContent, TopologySourceContentFailure>`.
         *
         * Establishes that live VFS bytes have the exact SHA-256 identity carried by the admitted
         * topology file. [TopologySourceContentFailure] is the closed expected failure. Raw bytes
         * may enter only while loading one VFS file inside the IntelliJ extraction adapter.
         */
        fun validate(
            file: TopologySourceFile,
            content: ByteArray,
        ): Refinement<LiveTopologySourceContent, TopologySourceContentFailure> {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(content)
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
            return if (digest == file.contentHash.value) {
                Refinement.Refined(LiveTopologySourceContent(file))
            } else {
                Refinement.Rejected(TopologySourceContentFailure.CONTENT_MOVED)
            }
        }
    }
}
