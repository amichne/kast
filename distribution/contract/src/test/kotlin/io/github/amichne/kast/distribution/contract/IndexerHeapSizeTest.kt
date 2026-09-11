package io.github.amichne.kast.distribution.contract

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IndexerHeapSizeTest {
    @Test
    fun `absent selects retained default and explicit units normalize`() {
        assertEquals(Refinement.Refined(IndexerHeapSize.Default), IndexerHeapSize.parse(null))
        for ((raw, expected) in listOf("256m" to 256, "1536m" to 1536, "8g" to 8192, "2147483647m" to Int.MAX_VALUE)) {
            assertEquals(expected, (IndexerHeapSize.parse(raw) as Refinement.Refined).value.mebibytes)
        }
    }

    @Test
    fun `invalid requests never default`() {
        for (raw in listOf("", " ", "8", "8G", "8.5g", "-8g", "+8g", "8g ", "8g -javaagent:bad")) {
            assertEquals(Refinement.Rejected(IndexerHeapFailure.INVALID_SYNTAX), IndexerHeapSize.parse(raw), raw)
        }
        for (raw in listOf("0g", "0m", "255m")) {
            assertEquals(Refinement.Rejected(IndexerHeapFailure.BELOW_INITIAL_HEAP), IndexerHeapSize.parse(raw), raw)
        }
        for (raw in listOf("2147483648m", "2097152g", "999999999999999999999999g")) {
            assertEquals(Refinement.Rejected(IndexerHeapFailure.OVERFLOW), IndexerHeapSize.parse(raw), raw)
        }
    }
}
