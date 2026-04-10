package sample.lib

object GreeterFactory {
    fun create(formal: Boolean = false): Greeter =
        if (formal) FormalGreeter() else DefaultGreeter()
}
