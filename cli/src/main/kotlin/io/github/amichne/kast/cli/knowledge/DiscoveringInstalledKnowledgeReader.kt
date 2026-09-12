package io.github.amichne.kast.cli.knowledge

import java.io.IOException
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Discovers the sibling installed resource tree from the CLI code source only when knowledge is requested. */
internal object DiscoveringInstalledKnowledgeReader : KnowledgeReader {
    override fun lookup(selection: KnowledgeSelection): KnowledgeLookup {
        val codeSource =
            try {
                Path.of(DiscoveringInstalledKnowledgeReader::class.java.protectionDomain.codeSource.location.toURI())
                    .toRealPath()
            } catch (_: IOException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            } catch (_: URISyntaxException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            } catch (_: SecurityException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            }
        val libraryDirectory =
            codeSource.parent?.takeIf { it.fileName.toString() == "lib" }
                ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
        val productRoot =
            libraryDirectory.parent ?: return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
        val knowledgeRoot = productRoot.resolve("share/kast/knowledge")
        if (Files.isSymbolicLink(knowledgeRoot)) {
            return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_REJECTED)
        }
        val physical =
            try {
                knowledgeRoot.toRealPath()
            } catch (_: IOException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            } catch (_: SecurityException) {
                return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_UNAVAILABLE)
            }
        if (!Files.isDirectory(physical, LinkOption.NOFOLLOW_LINKS) || physical != knowledgeRoot) {
            return KnowledgeLookup.Rejected(KnowledgeLookupFailure.BUNDLE_REJECTED)
        }
        return InstalledKnowledgeReader(physical).lookup(selection)
    }
}
