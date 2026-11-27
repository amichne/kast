package io.github.amichne.kast.symbol.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which names Kotlin also reaches through operator syntax, and which accessors Kotlin reads as a property. */
class JavaNameSyntaxTest {
    @Test
    fun `operator and delegate names are conventions`() {
        for (name in
            listOf(
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
            assertTrue(isKotlinConventionName(name), name)
        }
    }

    @Test
    fun `other names are not conventions`() {
        for (name in listOf("mkdirs", "getName", "component", "component0", "plusOne", "File", "")) {
            assertFalse(isKotlinConventionName(name), name)
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
