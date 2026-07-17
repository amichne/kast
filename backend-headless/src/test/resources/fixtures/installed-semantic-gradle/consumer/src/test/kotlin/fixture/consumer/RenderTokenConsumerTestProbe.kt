package fixture.consumer

import fixture.domain.RenderToken
import fixture.domain.RenderTokenFixture

object RenderTokenConsumerTestProbe {
    val rendered: String = RenderTokenConsumer().render(RenderToken("consumer-test"))
    val fixtureRendered: String = RenderTokenConsumer().render(RenderTokenFixture.token)
}
