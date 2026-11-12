package io.github.amichne.kast.appserver.ide

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/** Counts required-policy loading while the real local transport keeps live admission fresh. */
class HostedRequiredPolicyExchangeTest {
    private val fixture = HostedSocketExchangeFixture()
    private val compatibility =
        HostedCompatibilityDocument(
            ideBuild = "262.1.1",
            kotlinPluginBuild = "262.1.1-IJ",
            hostedPluginVersion = "0.49.0",
            hostedContract = CanonicalHostedContract.document,
        )

    @Test
    fun `installed Control policy admits a 263 describe before dispatch and rejects a 264 successor`() {
        val actual = compatibility.copy(ideBuild = "263.7307.200", kotlinPluginBuild = "263.7307.200-IJ")
        val successor = compatibility.copy(ideBuild = "264.1.1", kotlinPluginBuild = "264.1.1-IJ")
        fixture.exchange(
            script =
                HostedSocketExchangeScript(
                    expected = listOf("DESCRIBE", "CLASS_LOOKUP", "DESCRIBE"),
                    describeMetadata = listOf(actual, successor),
                )
        ) { client, root, _ ->
            assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            val admitted =
                assertInstanceOf(HostedServiceObservation.Compatible::class.java, client.latestObservation(root))
            assertEquals("263.7307.200", admitted.compatibility.ideBuild.value)
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.COMPATIBILITY_REJECTED),
                client.query(root, classes()),
            )
        }
    }

    @Test
    fun `three same client operations load required policy once but each admits a fresh describe`() {
        var loads = 0
        fixture.exchange(
            script =
                HostedSocketExchangeScript(
                    expected = List(3) { listOf("DESCRIBE", "CLASS_LOOKUP") }.flatten(),
                    describeMetadata = List(3) { compatibility },
                ),
            clientFactory = { deployment ->
                ExistingIdeSocketClient(
                    deployment.home,
                    ReadLimits.Default,
                    2_000,
                    deployment = Refinement.Refined(deployment.selected),
                ) {
                    loads += 1
                    check(loads <= 3) { "unexpected required policy load" }
                    requiredHostedCompatibilityPolicy()
                }
            },
        ) { client, root, _ ->
            repeat(3) {
                assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            }
            assertEquals(1, loads)
        }
    }

    @Test
    fun `changed live contract rejects before dispatch after an earlier admitted operation`() {
        val changed =
            compatibility.copy(
                hostedContract = compatibility.hostedContract.copy(wireSchemaDigest = "sha256:" + "0".repeat(64))
            )
        fixture.exchange(
            script =
                HostedSocketExchangeScript(
                    expected = listOf("DESCRIBE", "CLASS_LOOKUP", "DESCRIBE"),
                    describeMetadata = listOf(compatibility, changed),
                )
        ) { client, root, _ ->
            assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.COMPATIBILITY_REJECTED),
                client.query(root, classes()),
            )
            val observed =
                assertInstanceOf(HostedServiceObservation.Incompatible::class.java, client.latestObservation(root))
            val mismatch =
                assertInstanceOf(
                    io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure.Mismatch::class.java,
                    observed.compatibilityFailure,
                )
            assertEquals(
                io.github.amichne.kast.protocol.contract.IdeHostCompatibilityField.WIRE_SCHEMA_DIGEST,
                mismatch.mismatch.field,
            )
        }
    }

    @Test
    fun `rejected required policy load retries successfully on the next request`() {
        var loads = 0
        fixture.exchange(
            script =
                HostedSocketExchangeScript(
                    expected = listOf("DESCRIBE", "DESCRIBE", "CLASS_LOOKUP", "DESCRIBE", "CLASS_LOOKUP"),
                    describeMetadata = List(3) { compatibility },
                ),
            clientFactory = { deployment ->
                ExistingIdeSocketClient(
                    deployment.home,
                    ReadLimits.Default,
                    2_000,
                    deployment = Refinement.Refined(deployment.selected),
                ) {
                    loads += 1
                    when (loads) {
                        1 -> Refinement.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE)
                        2,
                        3 -> requiredHostedCompatibilityPolicy()
                        else -> error("unexpected required policy load")
                    }
                }
            },
        ) { client, root, _ ->
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.SCHEMA_UNAVAILABLE),
                client.query(root, classes()),
            )
            assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            assertInstanceOf(ExistingIdeExchange.HostRejected::class.java, client.query(root, classes()))
            assertEquals(2, loads)
        }
    }

    private fun classes() =
        ExistingIdeOperation.Classes((ExistingIdeClassName.parse("Main") as Refinement.Refined).value)
}
