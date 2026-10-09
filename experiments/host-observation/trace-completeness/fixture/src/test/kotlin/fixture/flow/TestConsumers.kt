package fixture.flow
suspend fun testFlow(flow: Flow<String>) { flow.collectIndexed { _, value -> consume(value) } }
