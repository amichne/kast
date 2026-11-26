package io.github.amichne.kast.symbol.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which Java member names Kotlin source can reach only by the name itself. */
class JavaNameSyntaxTest {
    @Test
    fun `plain method, field and class names have only their own syntax`() {
        for (name in
            listOf("sync", "mkdirs", "separatorChar", "File", "getaway", "issue", "settle", "g", "component")) {
            assertTrue(javaNameHasOnlyItsOwnKotlinSyntax(name), name)
        }
    }

    @Test
    fun `accessors and convention names have other Kotlin syntax`() {
        for (name in
            listOf(
                "getName",
                "isFile",
                "setReadable",
                "getURL",
                "plus",
                "get",
                "invoke",
                "compareTo",
                "contains",
                "iterator",
                "equals",
                "getValue",
                "component1",
                "component12",
                "plusAssign",
            )) {
            assertFalse(javaNameHasOnlyItsOwnKotlinSyntax(name), name)
        }
    }

    @Test
    fun `a property name maps to every accessor Kotlin reads as it`() {
        // Kotlin lowers an all-capital accessor suffix entirely, so getNAME also reads as the property name.
        assertEquals(setOf("getName", "setName", "getNAME", "setNAME"), accessorsNamedAsProperty("name").toSet())
        assertEquals(setOf("getUrl", "setUrl", "getURL", "setURL"), accessorsNamedAsProperty("url").toSet())
        assertTrue("getURLConnection" in accessorsNamedAsProperty("urlConnection"))
        assertTrue("getUrlConnection" in accessorsNamedAsProperty("urlConnection"))
        assertFalse("getURLconnection" in accessorsNamedAsProperty("urlConnection"))
        assertEquals(emptyList<String>(), accessorsNamedAsProperty("File"))
        assertEquals(emptyList<String>(), accessorsNamedAsProperty(""))
    }
}
