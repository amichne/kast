package kast.identity.fixture

// These declarations deliberately repeat the root module's compiler identities.
class Overloads {
    fun select(value: String): String = value
    fun select(value: Int): String = value.toString()
}
fun shadowText(value: Overloads): String = value.select("shadow")
fun shadowNumber(value: Overloads): String = value.select(2)
