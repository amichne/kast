package fixture.consumer

import fixture.domain.RenderToken

class RenderTokenConsumer {
    fun render(token: RenderToken): String = token.value
}
