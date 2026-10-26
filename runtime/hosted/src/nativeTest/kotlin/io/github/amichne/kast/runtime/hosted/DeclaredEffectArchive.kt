package io.github.amichne.kast.runtime.hosted

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

/** A case-owned archive contains real compiler metadata and no attached source roots. */
internal class DeclaredEffectArchive private constructor(val root: Path, val jar: Path) : AutoCloseable {
    val classDigest =
        ZipFile(jar.toFile()).use { zip ->
            digest(
                zip.getInputStream(checkNotNull(zip.getEntry("effects/DependencyEffectsKt.class"))).use {
                    it.readBytes()
                }
            )
        }

    override fun close() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    companion object {
        fun compile(): DeclaredEffectArchive {
            val root = Files.createTempDirectory("kast-declared-effect-case-")
            val jar = root.resolve("effects.jar")
            var transferred = false
            try {
                val prepared =
                    Path.of(
                        checkNotNull(System.getProperty("kast.declaredEffectArchive")) {
                            "Missing Gradle dependency fixture archive"
                        }
                    )
                Files.copy(prepared, jar)
                val archive = DeclaredEffectArchive(root, jar)
                transferred = true
                return archive
            } finally {
                if (!transferred) {
                    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                }
            }
        }

        fun resource(name: String): ByteArray =
            checkNotNull(DeclaredEffectArchive::class.java.getResourceAsStream("/semantic-effects/$name")) {
                    "Missing authored R1 fixture $name"
                }
                .use { it.readBytes() }

        private fun digest(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
