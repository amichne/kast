package semanticowners
fun target() {}

val supply: Any
    get() = kotlin.lazy { target() }
