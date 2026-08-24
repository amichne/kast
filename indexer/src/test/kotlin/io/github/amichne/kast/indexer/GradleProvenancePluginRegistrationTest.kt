package io.github.amichne.kast.indexer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.xml.parsers.DocumentBuilderFactory

class GradleProvenancePluginRegistrationTest {
    @Test
    fun `private plugin registers the Gradle producer provenance resolver`() {
        val descriptor = checkNotNull(javaClass.getResourceAsStream("/META-INF/plugin.xml")).use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val registrations = descriptor.getElementsByTagName("projectResolve")
        val implementations = buildList {
            for (index in 0 until registrations.length) {
                add(registrations.item(index).attributes.getNamedItem("implementation").nodeValue)
            }
        }

        assertEquals(
            listOf(
                "io.github.amichne.kast.workspace.intellij.provenance." +
                    "KastGradleSourceRootProvenanceResolver",
            ),
            implementations,
        )
    }
}
