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

// Appended cases preserve every earlier authored UTF-16 anchor.
fun tryResult(input: String): String =
    try {
        Voltage.encrypt(input)
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("try-result", failure)
    }

fun tryFallback(input: String): String =
    try {
        Voltage.encrypt(input)
    } catch (failure: IllegalArgumentException) {
        Hiped.encrypt("fallback")
    }

fun catchResult(input: String): String =
    try {
        throw IllegalArgumentException("enter-catch")
    } catch (failure: IllegalArgumentException) {
        Voltage.encrypt(input)
    }

fun tryNonFinal(input: String): String =
    try {
        Voltage.encrypt(input)
        "independent-result"
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("non-final", failure)
    }

fun tryNested(input: String): String =
    try {
        try {
            Voltage.encrypt(input)
        } catch (inner: IllegalArgumentException) {
            throw IllegalStateException("inner", inner)
        }
    } catch (outer: IllegalStateException) {
        throw IllegalArgumentException("outer", outer)
    }

fun tryExplicitReturn(input: String): String {
    try {
        return Voltage.encrypt(input)
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("explicit-return", failure)
    }
}

fun tryUnit(input: String) {
    try {
        Voltage.encrypt(input)
        Unit
    } catch (failure: IllegalArgumentException) {
        Unit
    }
}

fun tryFinallyResult(input: String): String =
    try {
        Voltage.encrypt(input)
    } finally {
        display("cleanup")
    }

fun tryFinallyReturn(input: String): String {
    try {
        return Voltage.encrypt(input)
    } finally {
        display("return-cleanup")
    }
}

fun tryFinallyThrows(input: String): String =
    try {
        Voltage.encrypt(input)
    } finally {
        throw IllegalStateException("cleanup-overrides-result")
    }

fun localBindings(input: String, choose: Boolean) {
    val binding = Voltage.encrypt(input)
    display(binding)
    if (choose) {
        val binding = Hiped.encrypt(input)
        display(binding)
    }
    display(binding)
    var mutable = Voltage.encrypt(input)
    display(mutable)
    mutable = Hiped.encrypt(input)
    display(mutable)
}

fun localOtherOwner(input: String) {
    val binding = Voltage.encrypt(input)
    display(binding)
    fun named(value: String): String = value
    display(named(binding))
}

fun localFunctions(input: String, choose: Boolean) {
    fun named(value: String): String = value
    display(named(input))
    if (choose) {
        fun named(value: String): String = value + "inner"
        display(named(input))
    }
    display(named(input))
}
