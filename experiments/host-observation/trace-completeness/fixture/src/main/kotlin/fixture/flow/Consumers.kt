package fixture.flow

class NamedCollector : FlowCollector<String> {
    override suspend fun emit(value: String) { consume(value) }
}
class SourceFlow : Flow<String> {
    override suspend fun collect(collector: FlowCollector<String>) { collector.emit("source") }
}
fun flowFactory(): Flow<String> = SourceFlow()
fun consume(value: String) = Unit
suspend fun ordinaryFlow(flow: Flow<String>) { flow.collectIndexed { _, value -> consume(value) } }
suspend fun inferredFlow() { flowFactory().collectIndexed { _, value -> consume(value) } }
suspend fun secondFlow(flow: Flow<String>) { flow.collectSecond { _, value -> consume(value) } }
suspend fun namedFlow(flow: Flow<String>) { flow.collect(NamedCollector()) }
