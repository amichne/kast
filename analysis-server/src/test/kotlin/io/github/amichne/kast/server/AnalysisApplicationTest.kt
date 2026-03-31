package io.github.amichne.kast.server

import io.github.amichne.kast.testing.FakeAnalysisBackend
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class AnalysisApplicationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `health reports backend metadata`() = testApplication {
        application {
            kastModule(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(),
            )
        }

        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(true, body.contains("\"backendName\":\"fake\""))
        assertEquals(true, body.contains("\"status\":\"ok\""))
    }

    @Test
    fun `runtime status reports backend readiness`() = testApplication {
        application {
            kastModule(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(),
            )
        }

        val response = client.get("/api/v1/runtime/status")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(true, body.contains("\"backendName\":\"fake\""))
        assertEquals(true, body.contains("\"state\":\"READY\""))
        assertEquals(true, body.contains("\"healthy\":true"))
    }

    @Test
    fun `token protected routes reject missing credentials`() = testApplication {
        application {
            kastModule(
                backend = FakeAnalysisBackend.sample(tempDir),
                config = AnalysisServerConfig(token = "secret"),
            )
        }

        val response = client.post("/api/v1/references") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"position":{"filePath":"${tempDir.resolve("src/Sample.kt")}","offset":20},"includeDeclaration":true}
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(true, response.bodyAsText().contains("\"code\":\"UNAUTHORIZED\""))
    }

    @Test
    fun `apply edits rejects stale hashes`() = testApplication {
        val backend = FakeAnalysisBackend.sample(tempDir)
        val file = tempDir.resolve("src/Sample.kt")
        application {
            kastModule(
                backend = backend,
                config = AnalysisServerConfig(token = "secret"),
            )
        }

        Files.writeString(file, file.readText() + "// drift\n")

        val response = client.post("/api/v1/edits/apply") {
            header("X-Kast-Token", "secret")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "edits": [
                    {
                      "filePath": "${file}",
                      "startOffset": 20,
                      "endOffset": 25,
                      "newText": "hello"
                    }
                  ],
                  "fileHashes": [
                    {
                      "filePath": "${file}",
                      "hash": "stale"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(true, response.bodyAsText().contains("\"code\":\"CONFLICT\""))
    }
}
