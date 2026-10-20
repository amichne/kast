package fixture.controls

data class DispatchRequest(val destination: String)

interface DefaultIdentityMapper {
    fun identity(request: DispatchRequest): DispatchRequest = request
}

open class ProductionConstantMapper : DefaultIdentityMapper {
    final override fun identity(request: DispatchRequest): DispatchRequest =
        DispatchRequest(destination = "production-constant")
}

class DefaultIdentityControls {
    fun apply(mapper: DefaultIdentityMapper, destination: String): DispatchRequest {
        val request = DispatchRequest(destination)
        return mapper.identity(request)
    }
}
