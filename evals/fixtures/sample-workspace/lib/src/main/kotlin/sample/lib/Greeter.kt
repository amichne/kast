package sample.lib

interface Greeter {
    fun greet(name: String): String
}

class DefaultGreeter : Greeter {
    override fun greet(name: String): String = "Hello, $name!"
}

class FormalGreeter : Greeter {
    override fun greet(name: String): String = "Good day, $name."
}
