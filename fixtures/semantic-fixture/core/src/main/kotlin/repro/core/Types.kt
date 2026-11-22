package repro.core

typealias TraceLabel = String
class CarrierOne(val label: TraceLabel)
class CarrierTwo(val label: TraceLabel)
class CarrierThree(val label: TraceLabel)
class CarrierFour(val label: TraceLabel)
class LabelBuilder { fun buildLabel(): TraceLabel = "built" }
fun defaultLabel(): TraceLabel = "default"
class UnusedMarker
