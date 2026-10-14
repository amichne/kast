package semanticowners
fun target() {}

class Owner {
    init {
        kotlin.lazy { target() }
    }
}
