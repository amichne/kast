package fixture.controls

sealed class ReceiverResult {
    abstract fun preserve(): ReceiverResult
}

class FirstReceiverResult : ReceiverResult() {
    override fun preserve(): ReceiverResult = this
}

class SecondReceiverResult : ReceiverResult() {
    override fun preserve(): ReceiverResult = this
}

fun dispatchReceiver(input: ReceiverResult): ReceiverResult =
    /*<receiver>*/input/*</receiver>*/.preserve()

class ReceiverPayload

interface ReceiverScope {
    fun ReceiverPayload.preserveExtension(): ReceiverPayload
}

class FirstReceiverScope : ReceiverScope {
    override fun ReceiverPayload.preserveExtension(): ReceiverPayload = this
}

class SecondReceiverScope : ReceiverScope {
    override fun ReceiverPayload.preserveExtension(): ReceiverPayload = this
}

fun ReceiverScope.extensionReceiver(input: ReceiverPayload): ReceiverPayload =
    /*<receiver>*/input/*</receiver>*/.preserveExtension()

interface TestOnlyReceiver {
    fun preserveTest(): TestOnlyReceiver
}

fun testOnlyReceiver(input: TestOnlyReceiver): TestOnlyReceiver =
    /*<receiver>*/input/*</receiver>*/.preserveTest()

interface DefaultReceiver {
    fun preserveDefault(): DefaultReceiver = this
}

class InheritedReceiver : DefaultReceiver

fun defaultReceiver(input: DefaultReceiver): DefaultReceiver =
    /*<receiver>*/input/*</receiver>*/.preserveDefault()

class FinalReceiver {
    fun preserveDirect(): FinalReceiver = this
}

fun finalReceiver(input: FinalReceiver): FinalReceiver =
    /*<receiver>*/input/*</receiver>*/.preserveDirect()
