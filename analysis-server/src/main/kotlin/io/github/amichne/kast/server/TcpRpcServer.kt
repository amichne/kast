package io.github.amichne.kast.server

import java.net.InetSocketAddress
import java.net.StandardProtocolFamily

internal class TcpRpcServer(
    private val host: String,
    private val port: Int,
    dispatcher: RpcAnalysisDispatcher,
) : LocalRpcServer {
    private val server = ChannelRpcServer(
        protocolFamily = StandardProtocolFamily.INET,
        dispatcher = dispatcher,
        threadNamePrefix = "kast-tcp-rpc",
    )

    fun start(): TcpRpcServer {
        server.start(InetSocketAddress(host, port))
        return this
    }

    fun boundPort(): Int = (server.localAddress as InetSocketAddress).port

    override fun await() = server.await()

    override fun close() = server.close()
}
