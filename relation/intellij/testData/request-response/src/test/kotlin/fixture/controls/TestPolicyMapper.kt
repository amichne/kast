package fixture.controls

class TestPolicyMapper : PolicyMapper {
    override fun map(input: PolicyRequest): PolicyRequest = input
}
