package kast.baseline.read

import kast.baseline.model.*

fun interface SemanticRead { fun read(selector: Selector): Outcome<Coordinates> }

/** This module cannot depend on start, synchronization, topology, or physical adapters. */
class ReadCoordinator(private val semantic: SemanticRead) {
    fun read(selector: Selector): Outcome<Coordinates> = semantic.read(selector).atCoordinates(selector.coordinates)
}
