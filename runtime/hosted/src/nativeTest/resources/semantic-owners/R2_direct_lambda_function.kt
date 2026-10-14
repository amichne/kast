package semanticowners
fun target() {}

fun entry() {
    ({ kotlin.lazy { target() } })()
}
