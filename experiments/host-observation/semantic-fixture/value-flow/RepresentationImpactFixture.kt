package representation.fixture

// Native semantic fixture. These functions supply identities and positions, not runtime crypto proof.
object Voltage {
    fun encrypt(value: String): String = value
    fun decrypt(value: String): String = value
}

object Hiped {
    fun encrypt(value: String): String = value
    fun decrypt(value: String): String = value
}

object Unrelated {
    fun decrypt(value: String): String = value
}

data class Response(val ciphertext: String)
data class UnrelatedResponse(val ciphertext: String)

fun wrapper(value: String): String = value
fun submit(primary: String, secondary: String) = Unit
fun display(value: String) = Unit
fun unmodeled(value: String): String = value
fun send(response: Response) = Unit
fun persist(slot: String, value: String) = Unit

fun investigate(accountA: String, accountB: String, choose: Boolean) {
    val first = Voltage.encrypt(accountA)
    val second = Voltage.encrypt(accountB)
    val misleadingHipedName = first
    submit(misleadingHipedName, second)
    display(second)
    val wrapped = wrapper(first)
    submit(wrapped, second)
    var reassigned = first
    display(reassigned)
    reassigned = second
    display(reassigned)
    val alternatives = if (choose) first else second
    display(alternatives)
    val hiped = Hiped.encrypt(accountA)
    val plaintext = Hiped.decrypt(hiped)
    val voltageBridge = Voltage.encrypt(plaintext)
    display(voltageBridge)
    val unknown = unmodeled(first)
    display(unknown)
    val unrelated = Unrelated.decrypt(first)
    display(unrelated)
    val response = Response(first)
    send(response)
    val unrelatedResponse = UnrelatedResponse(second)
    display(unrelatedResponse.ciphertext)
    persist("account", first)
}
