package io.github.amichne.kast.indexstore.api.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class IndexedPackageEvidenceTest {
    @Test
    fun `canonical names admit Kotlin PSI semantic package forms`() {
        listOf(
            "com.example.when",
            "com.example.not-an-identifier",
            "com.example.semi;colon",
            "com.example.angle<name>",
            "café.日本",
        ).forEach { semanticName ->
            assertEquals(
                semanticName,
                IndexedPackageEvidence.CanonicalName.parse(semanticName).value,
            )
        }
    }

    @Test
    fun `canonical names reject corrupt or source-syntax package forms`() {
        listOf(
            "",
            " ",
            ".example",
            "com.example.",
            "com..example",
            "com/example",
            "com\\example",
            "com:example",
            "com[example",
            "com]example",
            "com.`when`",
            "com.\u0000example",
            "com.\nexample",
        ).forEach { corruptName ->
            assertThrows(IllegalArgumentException::class.java) {
                IndexedPackageEvidence.CanonicalName.parse(corruptName)
            }
        }
    }

    @Test
    fun `legacy text parsing never upgrades package text to semantic evidence`() {
        val update = parseSourceFileIndex(
            path = "/src/Legacy.kt",
            content = "package com.example.`when`\nclass Legacy",
        )

        assertEquals(
            IndexedPackageEvidence.Unproven(IndexedPackageUnprovenReason.LEGACY_TEXT_ONLY),
            update.packageEvidence,
        )
    }
}
