package consumer

fun unknownAnonymousConstructor() {
    effects.unknown(
        block =
            fun() {
                proof.Target()
            },
        marker = 1,
    )
}
