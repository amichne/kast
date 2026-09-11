package repro.logging

class FixtureLogger {
    val isEnabled: Boolean = true
    private fun loggerFunction() = Unit
    fun trace() { loggerFunction() }
    fun debug() { loggerFunction() }
    fun info() { loggerFunction() }
    fun warn() { loggerFunction() }
    fun error() { loggerFunction() }
}
class FixtureLoggerManager
