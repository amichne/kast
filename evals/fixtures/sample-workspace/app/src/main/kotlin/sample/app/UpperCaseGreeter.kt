package sample.app

import sample.lib.Greeter

class UpperCaseGreeter(private val delegate: Greeter) : Greeter {
    override fun greet(name: String): String =
        delegate.greet(name).uppercase()
}
