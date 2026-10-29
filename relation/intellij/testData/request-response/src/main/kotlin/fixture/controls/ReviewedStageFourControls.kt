package fixture.controls

fun stageFourPayload(): String = "producer-value"
fun stageFourSibling(): String = "sibling-key"

fun captureMapValues(input: Map<String, String>): Map<String, String> = input.mapValues { it.value }
fun captureEntryKey(entry: Map.Entry<String, String>): String = entry.key
fun captureEntryValue(entry: Map.Entry<String, String>): String = entry.value
fun captureEntryComponent1(entry: Map.Entry<String, String>): String = entry.component1()
fun captureEntryComponent2(entry: Map.Entry<String, String>): String = entry.component2()
fun captureFilter(input: List<String>): List<String> = input.filter { it.isNotEmpty() }
fun captureMapNotNull(input: List<String>): List<String> = input.mapNotNull { it.takeIf { value -> value.isNotEmpty() } }
fun captureGroupBy(input: List<String>): Map<String, List<String>> = input.groupBy { it }
fun captureGroupByValues(input: List<String>): Map<String, List<String>> = input.groupBy({ it }, { "value-$it" })
fun captureTopRun(): String = run { "result" }
fun captureReceiverRun(): String = "receiver".run { this }
fun captureWith(): String = with("receiver") { this }
fun captureApply(): String = "receiver".apply { length }

fun mapValuesKeyCannotBecomeValue(): Map<String, String> {
    val input = mapOf(stageFourPayload() to stageFourSibling())
    return /*<destination>*/input.mapValues { it.value }/*</destination>*/
}

fun mapValuesValueCannotBecomeKey(): Map<String, String> {
    val input = mapOf(stageFourSibling() to stageFourPayload())
    return /*<destination>*/input.mapValues { it.key }/*</destination>*/
}

fun mapValuesDirectProducer(): Map<String, String> =
    /*<destination>*/mapOf("left-key" to "right-value").mapValues { stageFourPayload() }/*</destination>*/

fun mapValuesDiscardedProducer(): Map<String, String> =
    /*<destination>*/mapOf("left-key" to "right-value").mapValues {
        stageFourPayload()
        "constant-result"
    }/*</destination>*/

fun safeLetNestedMapValues(input: Map<String, String>?): Map<String, String>? =
    /*<destination>*/input?.let { values ->
        /*<map-output>*/values.mapValues { stageFourPayload() }/*</map-output>*/
    }/*</destination>*/

fun safeLetNestedMapValuesDiscarded(input: Map<String, String>?): Map<String, String>? =
    /*<destination>*/input?.let { values ->
        /*<map-output>*/values.mapValues {
            stageFourPayload()
            "constant-result"
        }/*</map-output>*/
    }/*</destination>*/

fun topRunDirectProducer(): String = /*<destination>*/run { stageFourPayload() }/*</destination>*/
fun topRunDiscardedProducer(): String = /*<destination>*/run { stageFourPayload(); "constant-result" }/*</destination>*/
fun applyDiscardedProducer(): String = /*<destination>*/"receiver".apply { stageFourPayload() }/*</destination>*/
