package fixture.controls

import fixture.client.HeaderBuilder
import fixture.client.OffersClientImpl
import fixture.feature.FeatureError
import fixture.feature.FeatureRequest
import fixture.feature.FeatureResponse
import fixture.feature.OffersProcessorImpl
import fixture.outcome.Outcome
import fixture.wire.Call
import fixture.wire.OffersApi
import fixture.wire.WireError
import fixture.wire.WireRequest
import fixture.wire.WireResponse

class SourceKnownEmptyOffersApi : OffersApi {
    override fun readOffers(headers: Map<String, String>, body: WireRequest): Call<WireError, WireResponse> =
        Call(adapt = { sourceKnownEmpty() })
}

fun sourceKnownEmptyOffer(request: FeatureRequest): Outcome<FeatureError, FeatureResponse> {
    val client = OffersClientImpl(api = SourceKnownEmptyOffersApi(), headers = HeaderBuilder())
    return OffersProcessorImpl(client = client).getOffer(request)
}
