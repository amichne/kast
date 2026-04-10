package sample.app

import sample.lib.GreeterFactory
import sample.lib.formatGreeting

fun main() {
    val greeter = GreeterFactory.create()
    println(formatGreeting(greeter, "World"))

    val formal = GreeterFactory.create(formal = true)
    println(formatGreeting(formal, "World"))
}
