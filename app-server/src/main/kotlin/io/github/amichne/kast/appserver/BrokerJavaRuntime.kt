package io.github.amichne.kast.appserver

import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Physical path admission precedes this bounded Java release compatibility proof. */
internal class BrokerJavaRuntime private constructor(val home: Path, val executable: Path, val feature: Int) {
    companion object {
        fun admit(home: Path, executable: Path): Refinement<BrokerJavaRuntime, PersistentBrokerServiceFailure> {
            val release = home.resolve("release")
            val raw =
                try {
                    if (!Files.isRegularFile(release, NOFOLLOW_LINKS)) return rejected()
                    Files.newInputStream(release, NOFOLLOW_LINKS).use { it.readNBytes(MAXIMUM_RELEASE_BYTES + 1) }
                } catch (_: IOException) {
                    return rejected()
                } catch (_: SecurityException) {
                    return rejected()
                }
            if (raw.size > MAXIMUM_RELEASE_BYTES) return rejected()
            val metadata = raw.toString(Charsets.UTF_8)
            if (DECLARATION.findAll(metadata).count() != 1) return rejected()
            val versions = VERSION.findAll(metadata).toList()
            if (versions.size != 1) return rejected()
            val feature = versions.single().groupValues[1].toIntOrNull() ?: return rejected()
            if (feature < MINIMUM_JAVA_FEATURE) return rejected()
            return Refinement.Refined(BrokerJavaRuntime(home, executable, feature))
        }

        private fun rejected() = Refinement.Rejected(PersistentBrokerServiceFailure.JAVA_RUNTIME_UNAVAILABLE)

        private val VERSION = Regex("^JAVA_VERSION=\"([0-9]+)(?:[^\"\\r\\n]*)\"$", RegexOption.MULTILINE)
        private val DECLARATION = Regex("^JAVA_VERSION=", RegexOption.MULTILINE)
        private const val MINIMUM_JAVA_FEATURE = 25
        private const val MAXIMUM_RELEASE_BYTES = 16 * 1_024
    }
}
