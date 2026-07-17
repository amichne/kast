package fixture.domain

@JvmInline
value class RenderToken(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "RenderToken must not be blank" }
    }
}
