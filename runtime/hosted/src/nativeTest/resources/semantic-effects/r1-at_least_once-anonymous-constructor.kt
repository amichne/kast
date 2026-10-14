package consumer

fun atLeastOnceAnonymousConstructor() {
    effects.atLeastOnce(
        block =
            fun() {
                proof.Target()
            },
        marker = 1,
    )
}
