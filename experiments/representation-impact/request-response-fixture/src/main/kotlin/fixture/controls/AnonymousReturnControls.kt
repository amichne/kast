package fixture.controls

class AnonymousReturnControls {
    fun readValue(): String = "tracked"

    fun failure(): RuntimeException = IllegalStateException("tracked-failure")

    fun consume(block: () -> String): String = block()

    inline fun inlineConsume(block: () -> String): String = block()

    fun genuineNormal(): String = consume { readValue() }

    fun explicitLocal(): String = consume local@{
        return@local readValue()
    }

    fun discarded(): String = consume {
        readValue()
        "constant"
    }

    fun nonlocal(flag: Boolean): String {
        inlineConsume {
            if (flag) return readValue()
            "constant"
        }
        return "after"
    }

    fun abrupt(): String = consume { throw failure() }

    fun implicitFinally(): String = consume {
        try {
            readValue()
        } finally {
            cleanup()
        }
    }

    fun explicitFinally(): String = consume local@{
        try {
            return@local readValue()
        } finally {
            cleanup()
        }
    }

    fun cleanup() = Unit
}
