package fixture.controls

data class PolicyRequest(val value: String)
interface PolicyMapper { fun map(input: PolicyRequest): PolicyRequest }
class PolicyCaller(private val mapper: PolicyMapper) {
    fun invoke(value: String): PolicyRequest {
        val request = PolicyRequest(value)
        return mapper.map(request)
    }
}
