package fixture.controls

fun formPayload(): String = "payload"
fun formConsume(value: String) {}

class LoopComponent {
    operator fun component1(): String = formPayload()
}

fun loopForms(first: Iterable<LoopComponent>, second: Iterable<LoopComponent>) {
    for ((/*<first-binding>*/firstValue/*</first-binding>*/) in first) {
        formConsume(/*<first-use>*/firstValue/*</first-use>*/)
    }
    for ((/*<second-binding>*/secondValue/*</second-binding>*/) in second) {
        formConsume(/*<second-use>*/secondValue/*</second-use>*/)
    }
}

class LambdaComponent {
    operator fun component1(): String = formPayload()
}

fun invokeLambda(value: LambdaComponent, action: (LambdaComponent) -> Unit) {
    action(value)
}

fun lambdaForm(value: LambdaComponent) {
    invokeLambda(value) { (/*<binding>*/payload/*</binding>*/) ->
        formConsume(/*<use>*/payload/*</use>*/)
    }
}

class DormantComponent {
    operator fun component1(): String = formPayload()
}

fun dormantLambda() {
    val dormant: (DormantComponent) -> Unit = { (/*<binding>*/payload/*</binding>*/) ->
        formConsume(/*<use>*/payload/*</use>*/)
    }
}

class SkippedFormComponent {
    operator fun component1(): String = error("skipped component must not execute")
    operator fun component2(): String = formPayload()
}

fun skippedLoop(values: Iterable<SkippedFormComponent>) {
    for ((_, /*<binding>*/payload/*</binding>*/) in values) {
        formConsume(/*<use>*/payload/*</use>*/)
    }
}

fun invokeSkipped(value: SkippedFormComponent, action: (SkippedFormComponent) -> Unit) {
    action(value)
}

fun skippedLambda(value: SkippedFormComponent) {
    invokeSkipped(value) { (_, /*<binding>*/payload/*</binding>*/) ->
        formConsume(/*<use>*/payload/*</use>*/)
    }
}

class OrderedFormComponent {
    operator fun component1(): String = error("first component stops evaluation")
    operator fun component2(): String = formPayload()
}

fun orderedLoop(values: Iterable<OrderedFormComponent>) {
    for ((first, second) in values) {
        formConsume(first)
        formConsume(second)
    }
}

fun invokeOrdered(value: OrderedFormComponent, action: (OrderedFormComponent) -> Unit) {
    action(value)
}

fun orderedLambda(value: OrderedFormComponent) {
    invokeOrdered(value) { (first, second) ->
        formConsume(first)
        formConsume(second)
    }
}

data class GeneratedFormComponent(val first: String, val second: String)

fun generatedLoop(values: Iterable<GeneratedFormComponent>) {
    for ((/*<first-binding>*/first/*</first-binding>*/, /*<second-binding>*/second/*</second-binding>*/) in values) {
        formConsume(/*<first-use>*/first/*</first-use>*/)
        formConsume(/*<second-use>*/second/*</second-use>*/)
    }
}

fun invokeGenerated(value: GeneratedFormComponent, action: (GeneratedFormComponent) -> Unit) {
    action(value)
}

fun generatedLambda(value: GeneratedFormComponent) {
    invokeGenerated(value) { (/*<first-binding>*/first/*</first-binding>*/, /*<second-binding>*/second/*</second-binding>*/) ->
        formConsume(/*<first-use>*/first/*</first-use>*/)
        formConsume(/*<second-use>*/second/*</second-use>*/)
    }
}
