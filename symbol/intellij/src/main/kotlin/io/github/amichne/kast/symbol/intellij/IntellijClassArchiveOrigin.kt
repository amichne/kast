package io.github.amichne.kast.symbol.intellij

import com.intellij.util.lang.ClassPath
import com.intellij.util.lang.UrlClassLoader
import java.net.URI
import java.nio.file.Path

/** Defining-loader lookup, never Class.getResource or parent-delegating PluginClassLoader.findResource. */
internal data class IntellijClassArchiveOrigin(val archive: Path, val loaderWitnesses: Set<Class<*>>) {
    companion object {
        fun resolve(owner: Class<*>): IntellijClassArchiveOrigin? {
            val loader = owner.classLoader
            if (loader is UrlClassLoader) return fromPlatformLoader(owner, loader)
            // CodeSource alone cannot attest an arbitrary loader's definitions. This bootstrap-defined
            // JDK loader and the audited IDEA loaders are the supported runtime paths.
            val applicationLoader = Class.forName("jdk.internal.loader.ClassLoaders\$AppClassLoader", false, null)
            if (loader?.javaClass != applicationLoader) return null
            val location = owner.protectionDomain?.codeSource?.location?.toURI() ?: return null
            return location.takeIf { it.scheme == "file" }?.let { IntellijClassArchiveOrigin(Path.of(it), emptySet()) }
        }

        private fun fromPlatformLoader(owner: Class<*>, loader: UrlClassLoader): IntellijClassArchiveOrigin? {
            if (!supportedLoader(loader)) return null
            val name = owner.name.replace('.', '/') + ".class"
            val url = loader.classPath.findResource(name)?.url ?: return null
            val resource = url.toURI()
            if (resource.scheme != "jar") return null
            val suffix = "!/$name"
            val source = resource.rawSchemeSpecificPart
            if (!source.endsWith(suffix)) return null
            val location = URI(source.removeSuffix(suffix))
            if (location.scheme != "file") return null
            return IntellijClassArchiveOrigin(
                Path.of(location),
                setOf(
                    loader.javaClass,
                    UrlClassLoader::class.java,
                    ClassPath::class.java,
                ),
            )
        }

        private fun supportedLoader(loader: UrlClassLoader): Boolean =
            when (loader.javaClass.name) {
                "com.intellij.util.lang.UrlClassLoader",
                "com.intellij.ide.plugins.cl.PluginClassLoader" -> true
                "com.intellij.util.lang.PathClassLoader" -> {
                    // This audited loader can transform definitions. An active transformer is unproven.
                    val transformer = loader.javaClass.getDeclaredField("transformer")
                    transformer.trySetAccessible() && transformer.get(loader) == null
                }
                else -> false
            }
    }
}
