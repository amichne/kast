package fixture.impact.capture

fun stored(input: Tracking): () -> String {
    val captured = /*<seed>*/ source(input) /*</seed>*/
    return { observe(/*<stored-read>*/ captured.componentId /*</stored-read>*/) }
}

fun ignore(block: () -> Unit) = Unit

fun ignored(input: Tracking) {
    val captured = /*<seed>*/ source(input) /*</seed>*/
    ignore { observe(/*<ignored-read>*/ captured.componentId /*</ignored-read>*/) }
}

interface UnknownScheduler {
    fun submit(block: () -> Unit)
}

fun scheduled(input: Tracking, scheduler: UnknownScheduler) {
    val captured = /*<seed>*/ source(input) /*</seed>*/
    scheduler.submit { observe(/*<scheduled-read>*/ captured.componentId /*</scheduled-read>*/) }
}

class ScopeLookalike {
    fun apply(block: ScopeLookalike.() -> Unit): ScopeLookalike = this
}

fun shadowedApply(input: Tracking): ScopeLookalike {
    val captured = /*<seed>*/ source(input) /*</seed>*/
    return ScopeLookalike().apply { observe(/*<excluded-read>*/ captured.componentId /*</excluded-read>*/) }
}

interface OpaqueMap {
    fun add(key: String, value: String)
}

fun adapterBoundary(input: Tracking, target: OpaqueMap): OpaqueMap {
    val captured = /*<seed>*/ source(input) /*</seed>*/
    return target.apply { add("component", /*<adapter-argument>*/ captured.componentId /*</adapter-argument>*/) }
}
