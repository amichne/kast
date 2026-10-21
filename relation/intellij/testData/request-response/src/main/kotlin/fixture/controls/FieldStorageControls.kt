package fixture.controls

class StoredRequest(val destination: String)
interface AbstractRequest { val destination: String }
class MutableRequest(var destination: String)
class ComputedRequest(val stored: String) { val destination: String get() = "constant" }
class StoredWireRequest(val destination: String)
class FieldStorageControls {
    fun fromStored(request: StoredRequest) = StoredWireRequest(request.destination)
    fun fromAbstract(request: AbstractRequest) = StoredWireRequest(request.destination)
    fun fromMutable(request: MutableRequest) = StoredWireRequest(request.destination)
    fun fromComputed(request: ComputedRequest) = StoredWireRequest(request.destination)
}
