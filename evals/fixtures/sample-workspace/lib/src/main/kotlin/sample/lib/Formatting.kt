package sample.lib

fun formatGreeting(greeter: Greeter, name: String): String {
    val greeting = greeter.greet(name)
    return "[$greeting]"
}
