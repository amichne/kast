package fixture.controls.entrybinding

class EntryBindingControls {
    private data class Box(val value: String)

    fun immutable(input: String): String {
        val (value) = Box(input)
        return value
    }

    fun mutable(input: String, replace: Boolean): String {
        var (value) = Box(input)
        if (replace) value = "replacement"
        return value
    }
}
