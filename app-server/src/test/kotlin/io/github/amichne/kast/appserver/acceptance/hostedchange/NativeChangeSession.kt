package io.github.amichne.kast.appserver.acceptance.hostedchange

import io.github.amichne.kast.appserver.BrokerInstallationState
import io.github.amichne.kast.appserver.KastToolSelection
import io.github.amichne.kast.appserver.WorkspaceEnrollment
import io.github.amichne.kast.appserver.core.Broker
import io.github.amichne.kast.appserver.core.BrokerLimits
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.appserver.protocol.FileThreadCatalogStore
import io.github.amichne.kast.appserver.protocol.FileThreadCatalogStoreOpen
import io.github.amichne.kast.appserver.provider.KastHostedPlanApprovalGateway
import io.github.amichne.kast.appserver.provider.KastProviderOptions
import io.github.amichne.kast.appserver.provider.KastProviderQualification
import io.github.amichne.kast.appserver.provider.KastProviderQualifier
import io.github.amichne.kast.appserver.runtime.BrokerSessionHub
import io.github.amichne.kast.appserver.runtime.BrokerSocketPath
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnection
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnectionAdmission
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamConnector
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamFrame
import io.github.amichne.kast.appserver.runtime.BrokerUpstreamSend
import io.github.amichne.kast.appserver.runtime.KtorBrokerServerOptions
import io.github.amichne.kast.appserver.runtime.SessionActivitySink
import io.github.amichne.kast.appserver.runtime.document
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Scripted native client/upstream transport; broker, schemas, provider, subprocesses and hosted plugin are real. */
internal class NativeChangeSession
private constructor(
    private val reopenHub: () -> BrokerSessionHub,
    val trace: NativeProcessTrace,
    private val connecting: Channel<NativeUpstream>,
    private val workspace: Path,
    private val privateDirectory: Path,
    val foreignRoot: NativeForeignRootBoundary,
) {
    var hub: BrokerSessionHub = reopenHub()
        private set

    private val sequence = java.util.concurrent.atomic.AtomicInteger(10)
    private var peerCount = 0

    suspend fun connect(): NativeChangePeer {
        val upstream = NativeUpstream()
        connecting.send(upstream)
        val peer =
            NativeChangePeer(
                session = checkNotNull(hub.attach(NativeControllerProtocol.initialize().toString())),
                upstream = upstream,
                thread = "native-${++peerCount}",
                trace = trace,
                privateDirectory = privateDirectory,
                nextSequence = sequence::getAndIncrement,
            )
        peer.forwarded()
        upstream.received.send(BrokerUpstreamFrame.Text("""{"id":0,"result":{}}"""))
        peer.session.output.receive()
        peer.session.accept("""{"method":"initialized"}""")
        peer.forwarded()
        peer.session.accept(NativeControllerProtocol.threadStart(workspace).toString())
        peer.forwarded()
        upstream.received.send(
            BrokerUpstreamFrame.Text(NativeControllerProtocol.threadStarted(workspace, peer.thread).toString())
        )
        demand(
            Json.parseToJsonElement(peer.session.output.receive()).jsonObject["error"] == null,
            NativeFailure.PROTOCOL_REJECTED,
        )
        return peer
    }

    suspend fun replaceBroker(
        expectation: NativeBrokerRetentionExpectation = NativeBrokerRetentionExpectation.UNCERTAIN
    ): NativeBrokerStoreSnapshot {
        hub.close()
        val retained = NativeBrokerStoreSnapshot.capture(privateDirectory, expectation)
        hub = reopenHub()
        retained.requireUnchanged(privateDirectory)
        return retained
    }

    fun requireRetained(snapshot: NativeBrokerStoreSnapshot) = snapshot.requireRecordsRetained(privateDirectory)

    suspend fun close() = hub.close()

    companion object {
        suspend fun open(
            product: Path,
            workspace: Path,
            home: Path,
            schemas: Path,
            privateDirectory: Path,
        ): NativeChangeSession {
            validateProductOrigin(product)
            val trace = NativeProcessTrace(privateDirectory)
            val options = providerOptions(product, home, trace)
            val qualification =
                KastProviderQualifier.qualify(options) as? KastProviderQualification.Qualified
                    ?: throw NativeRejected(NativeFailure.PROVIDER_QUALIFICATION_REJECTED)
            val contracts = NativeControllerProtocol.contracts(schemas, workspace)
            val connecting = Channel<NativeUpstream>(4)
            val activities = activitySink(privateDirectory)
            val reopen = {
                val threads =
                    FileThreadCatalogStore.open(privateDirectory.resolve("threads.json"))
                        as? FileThreadCatalogStoreOpen.Opened ?: throw NativeRejected(NativeFailure.INPUT_REJECTED)
                BrokerSessionHub(
                    KtorBrokerServerOptions(
                        publicSocket = BrokerSocketPath.admit(privateDirectory.resolve("native.sock")).nativeValue(),
                        broker =
                            Broker.create(listOf(qualification.registration), BrokerLimits.defaults()).nativeValue(),
                        contracts = contracts,
                        threadStore = threads.store,
                        upstream =
                            BrokerUpstreamConnector {
                                BrokerUpstreamConnectionAdmission.Connected(connecting.receive())
                            },
                        maximumConnections = 4,
                        maximumMessageBytes = 4 * 1024 * 1024,
                        enrollment =
                            WorkspaceEnrollment.Enrolled(checkNotNull(CanonicalBrokerDirectory.admit(workspace))),
                        sessionBootstrap = qualification.bootstrap,
                        bindingOwner = BrokerInstallationState.admit(product).nativeValue(),
                        planApprovalGateway = KastHostedPlanApprovalGateway(options, home),
                        invocationJournal = privateDirectory.resolve("invocations.json"),
                        sessionActivitySink = activities,
                    )
                )
            }
            return NativeChangeSession(
                reopenHub = reopen,
                trace = trace,
                connecting = connecting,
                workspace = workspace,
                privateDirectory = privateDirectory,
                foreignRoot = NativeForeignRootBoundary(product, workspace, trace),
            )
        }

        private fun activitySink(privateDirectory: Path): SessionActivitySink {
            val activities = java.util.concurrent.atomic.AtomicInteger()
            return SessionActivitySink { activity ->
                privateWrite(
                    privateDirectory.resolve("activity-${activities.incrementAndGet()}.private.json"),
                    activity.document().toString(),
                )
            }
        }

        private fun validateProductOrigin(product: Path) {
            val origin = Path.of(Broker::class.java.protectionDomain.codeSource.location.toURI()).toRealPath()
            demand(
                origin.startsWith(product.resolve("lib")) && Files.isRegularFile(origin),
                NativeFailure.PRODUCT_CLASS_ORIGIN_REJECTED,
            )
        }

        private fun providerOptions(product: Path, home: Path, trace: NativeProcessTrace): KastProviderOptions {
            return KastProviderOptions.admit(
                    executable = product.resolve("bin/kast"),
                    qualificationDirectory = home,
                    processExecutor = trace,
                    toolSelection =
                        KastToolSelection.admit(
                                "search_classes,search_functions,change_plan,change_apply,change_recover"
                            )
                            .nativeValue(),
                )
                .nativeValue()
        }
    }

    internal class NativeUpstream : BrokerUpstreamConnection {
        val sent = Channel<String>(64)
        val received = Channel<BrokerUpstreamFrame>(64)

        override suspend fun send(message: String): BrokerUpstreamSend {
            sent.send(message)
            return BrokerUpstreamSend.SENT
        }

        override suspend fun receive(): BrokerUpstreamFrame =
            received.receiveCatching().getOrNull() ?: BrokerUpstreamFrame.Closed

        override suspend fun close() {
            sent.close()
            received.close()
        }
    }
}
