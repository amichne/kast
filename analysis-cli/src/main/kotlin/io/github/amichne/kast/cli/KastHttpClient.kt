package io.github.amichne.kast.cli

import io.github.amichne.kast.api.ApiErrorResponse
import io.github.amichne.kast.api.ServerInstanceDescriptor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal class KastHttpClient(
    private val json: Json,
) {
    private val client = HttpClient.newHttpClient()

    inline fun <reified Response> get(
        descriptor: ServerInstanceDescriptor,
        path: String,
    ): Response {
        val request = baseRequest(descriptor, path)
            .GET()
            .build()
        return execute(request)
    }

    inline fun <reified Request : Any, reified Response> post(
        descriptor: ServerInstanceDescriptor,
        path: String,
        body: Request,
    ): Response {
        val request = baseRequest(descriptor, path)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(body)))
            .build()
        return execute(request)
    }

    fun baseRequest(
        descriptor: ServerInstanceDescriptor,
        path: String,
    ): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(URI.create("http://${descriptor.host}:${descriptor.port}$path"))
        descriptor.token?.let { token ->
            builder.header("X-Kast-Token", token)
        }
        return builder
    }

    inline fun <reified Response> execute(request: HttpRequest): Response {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val apiError = runCatching {
                json.decodeFromString<ApiErrorResponse>(response.body())
            }.getOrNull()
            if (apiError != null) {
                throw CliFailure(
                    code = apiError.code,
                    message = apiError.message,
                    details = apiError.details,
                )
            }
            throw CliFailure(
                code = "HTTP_${response.statusCode()}",
                message = "Unexpected HTTP ${response.statusCode()} from ${request.uri()}",
            )
        }
        return json.decodeFromString(response.body())
    }
}
