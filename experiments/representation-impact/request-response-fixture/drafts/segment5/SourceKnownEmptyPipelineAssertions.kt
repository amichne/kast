package fixture.runtime

import fixture.controls.sourceKnownEmptyOffer
import fixture.feature.FeatureError
import fixture.feature.FeatureRequest
import fixture.feature.FeatureResponse
import fixture.outcome.Outcome

/** Runtime behavior alone does not establish native cardinality or forbidden-witness evidence. */
fun assertSourceKnownEmptyPipelineRuntime() {
    val request = FeatureRequest(destination = "known-empty-destination", sessionToken = "known-empty-token")
    val expected: Outcome<FeatureError, FeatureResponse> = Outcome.error(FeatureError(reason = "NO_OFFER"))
    check(sourceKnownEmptyOffer(request) == expected)
}
