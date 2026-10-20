package fixture.controls

class TestEchoMapper : DefaultIdentityMapper {
    override fun identity(request: DispatchRequest): DispatchRequest = request
}
