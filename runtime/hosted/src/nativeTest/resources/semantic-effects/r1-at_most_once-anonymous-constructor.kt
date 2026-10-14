package consumer

fun atMostOnceAnonymousConstructor() {
    effects.atMostOnce(
        block =
            fun() {
                proof.Target()
            },
        marker = 1,
    )
}
