package semanticowners
fun target() {}

class Owner {
    val supply = kotlin.lazy { target() }
}
