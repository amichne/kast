package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.io.IOException
import java.io.InputStream
import java.net.URISyntaxException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile
import org.jetbrains.kotlin.KtNodeType
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.parsing.KotlinCommonParserDefinition
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.stubs.elements.KtStubElementType

internal enum class KotlinAstSourceBinding {
    SUPPORTED,
    UNSUPPORTED,
    HALTED,
}

/** Byte witnesses for the audited platform and Host bundles, not an IDEA version/name guess. */
internal object IntellijKotlinAstSourceBinding {
    private const val MAX_CLASS_BYTES = 65_536L
    private const val READ_CHUNK_BYTES = 1024

    private data class Witness(val owner: Class<*>, val sha256: String)

    fun inspect(observe: () -> Boolean, observation: IntellijReadObservation): KotlinAstSourceBinding =
        try {
            inspectOrigins(observe, observation)
        } catch (_: IOException) {
            KotlinAstSourceBinding.UNSUPPORTED
        } catch (_: URISyntaxException) {
            KotlinAstSourceBinding.UNSUPPORTED
        } catch (_: SecurityException) {
            KotlinAstSourceBinding.UNSUPPORTED
        } catch (_: ClassNotFoundException) {
            KotlinAstSourceBinding.UNSUPPORTED
        }

    private fun inspectOrigins(observe: () -> Boolean, observation: IntellijReadObservation): KotlinAstSourceBinding {
        if (!observe()) return KotlinAstSourceBinding.HALTED
        val origins =
            witnesses().groupBy { witness ->
                val location = witness.owner.protectionDomain?.codeSource?.location?.toURI()
                location?.takeIf { it.scheme == "file" }?.let(Path::of)
            }
        if (null in origins) return KotlinAstSourceBinding.UNSUPPORTED
        for ((origin, group) in origins) {
            if (!observe()) return KotlinAstSourceBinding.HALTED
            val result =
                JarFile(requireNotNull(origin).toFile()).use { jar ->
                    inspectGroup(jar, group, observe, observation)
                }
            if (result != KotlinAstSourceBinding.SUPPORTED) return result
        }
        return KotlinAstSourceBinding.SUPPORTED
    }

    private fun inspectGroup(
        jar: JarFile,
        group: List<Witness>,
        observe: () -> Boolean,
        observation: IntellijReadObservation,
    ): KotlinAstSourceBinding {
        for (witness in group) {
            if (!observe()) return KotlinAstSourceBinding.HALTED
            val result = inspectWitness(jar, witness, observe, observation)
            if (result != KotlinAstSourceBinding.SUPPORTED) return result
        }
        return KotlinAstSourceBinding.SUPPORTED
    }

    private fun inspectWitness(
        jar: JarFile,
        witness: Witness,
        observe: () -> Boolean,
        observation: IntellijReadObservation,
    ): KotlinAstSourceBinding {
        val entry =
            jar.getJarEntry(witness.owner.name.replace('.', '/') + ".class")
                ?: return KotlinAstSourceBinding.UNSUPPORTED
        if (entry.size !in 0..MAX_CLASS_BYTES) return KotlinAstSourceBinding.UNSUPPORTED
        return jar.getInputStream(entry).use { input -> inspectBytes(input, witness.sha256, observe, observation) }
    }

    private fun inspectBytes(
        input: InputStream,
        expected: String,
        observe: () -> Boolean,
        observation: IntellijReadObservation,
    ): KotlinAstSourceBinding {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var total = 0L
        while (true) {
            if (!observe()) return KotlinAstSourceBinding.HALTED
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_CLASS_BYTES) return KotlinAstSourceBinding.UNSUPPORTED
            observation.count(IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ, amount = read)
            digest.update(buffer, 0, read)
        }
        return if (HexFormat.of().formatHex(digest.digest()) == expected) KotlinAstSourceBinding.SUPPORTED
        else KotlinAstSourceBinding.UNSUPPORTED
    }

    private fun witnesses(): List<Witness> {
        val loader = KtNodeTypes::class.java.classLoader
        return listOf(
            Witness(KtNodeTypes::class.java, "7bba19a4a06f917e76a30dc9370dd93728cd75c6107ff243fe1561a2b4798000"),
            Witness(KtNodeType::class.java, "dff9c81c446d33814cbf8594eb0b48434cf7ffa7269516295cb2cf506275c621"),
            Witness(
                Class.forName("org.jetbrains.kotlin.KtStubBasedElementTypes", false, loader),
                "03a527dc9e26c4a2bd55ea5a32476f40142cd2de2ed3a3d8677621039ebc595c",
            ),
            Witness(
                KotlinCommonParserDefinition::class.java,
                "13dde298362b2581bdede3595fbce1ab933e5d5758a94f75fa180a1b5e096b1c",
            ),
            Witness(
                KotlinParserDefinition::class.java,
                "cf21b1aa31cc5c4860ca9e35c5cf640d426ad896a37e57b8c0a09c0ce2ae22b1",
            ),
            Witness(KtStubElementType::class.java, "14f62ea11505cea137f65cae7fac04bab9fcaa1e6e7e520700576845d708b6cb"),
            Witness(
                Class.forName("org.jetbrains.kotlin.psi.impl.KotlinElementTypeProviderImpl", false, loader),
                "ca571266ecd2395a52bc6582b7d44589c7b57cfc713925dae9d44caffcf41106",
            ),
            Witness(
                Class.forName("org.jetbrains.kotlin.KotlinElementTypeProvider\$Companion", false, loader),
                "9b4e07d3a2e8b652c5633b42ecef5003e99ee3e720db79608c40839580e81dce",
            ),
            Witness(CompositeElement::class.java, "53813013fcff59743a3b99e957edd24413463b37b813871efb467432221e3d45"),
            Witness(LeafPsiElement::class.java, "7f2fe7eb0566ef51223e774f209b9110b57f4cdf75993b67d3ec36f32e81e1bd"),
        )
    }
}
