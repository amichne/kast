package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * IDEA IU-262.10968.63 Kotlin 2.4.20-ij262-52 KtCompilerPluginsCache.computeExtensionStorage loads registrars and
 * processors via ServiceLoaderLite from substituted module plugin paths. Its main bundled provider substitutes
 * registrar metadata; the fallback substitutes unreadable dist paths. The caller checks that exact provider set and
 * unchanged readable paths. These archive checks therefore establish no native plugin registration; filenames never
 * establish that proof. Provider archives are rejected. Evidence: that distribution's Kotlin/lib/kotlin-plugin.jar,
 * KtCompilerPluginsCache and its Companion, MainByRegistrarContentBundledFirCompilerPluginProvider and
 * FromKotlinDistForIdeByNameFallbackBundledFirCompilerPluginProvider. The caller hashes the complete ordered raw
 * archive bytes before this bounded metadata observation.
 */
internal fun observePassiveCompilerClasspathJar(
    path: Path,
    limits: ReadLimits,
    budget: DependencyCaptureBudget,
): SemanticCapture<Unit> {
    when (val spent = budget.step()) {
        is Refinement.Rejected -> return spent
        is Refinement.Refined -> Unit
    }
    return try {
        ZipFile(path.toFile()).use { archive -> PassiveCompilerArchive(limits, budget).read(archive) }
    } catch (_: IOException) {
        captureRejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)
    }
}

private class PassiveCompilerArchive(private val limits: ReadLimits, private val budget: DependencyCaptureBudget) {
    private val names = hashSetOf<String>()
    private var manifestSeen = false

    fun read(archive: ZipFile): SemanticCapture<Unit> {
        if (archive.size() > limits[ReadLimitParameter.DISCOVERY_FILES].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        val entries = archive.entries()
        while (entries.hasMoreElements()) {
            when (val spent = budget.step()) {
                is Refinement.Rejected -> return spent
                is Refinement.Refined -> Unit
            }
            when (val admitted = entry(archive, entries.nextElement())) {
                is Refinement.Rejected -> return admitted
                is Refinement.Refined -> Unit
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun entry(archive: ZipFile, entry: java.util.zip.ZipEntry): SemanticCapture<Unit> {
        val name = entry.name
        if (name.length > limits[ReadLimitParameter.MODEL_CLASSPATH_URL_CHARACTERS].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        if (!names.add(name) || name.startsWith("META-INF/services/") || name == "META-INF/INDEX.LIST")
            return captureRejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)
        return if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) manifest(archive, entry)
        else Refinement.Refined(Unit)
    }

    private fun manifest(archive: ZipFile, entry: java.util.zip.ZipEntry): SemanticCapture<Unit> {
        if (manifestSeen) return captureRejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)
        manifestSeen = true
        val bound = limits[ReadLimitParameter.MODEL_CLASSPATH_URL_CHARACTERS].value
        when (val spent = budget.step()) {
            is Refinement.Rejected -> return spent
            is Refinement.Refined -> Unit
        }
        val bytes = archive.getInputStream(entry).use { it.readNBytes(bound + 1) }
        if (bytes.size > bound) return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        if (Manifest(ByteArrayInputStream(bytes)).mainAttributes.getValue(Attributes.Name.CLASS_PATH) != null)
            return captureRejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)
        return Refinement.Refined(Unit)
    }
}
