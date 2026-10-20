package fixture.controls

data class StoredRequest(val destination: String)

interface AbstractRequest {
    val destination: String
}

data class MutableRequest(var destination: String)

class ComputedRequest {
    val destination: String
        get() = "constant"
}

data class StoredWireRequest(val destination: String)

class FieldStorageControls {
    fun fromStored(request: StoredRequest): StoredWireRequest = StoredWireRequest(request.destination)

    fun fromAbstract(request: AbstractRequest): StoredWireRequest = StoredWireRequest(request.destination)

    fun fromMutable(request: MutableRequest): StoredWireRequest = StoredWireRequest(request.destination)

    fun fromComputed(request: ComputedRequest): StoredWireRequest = StoredWireRequest(request.destination)
}
