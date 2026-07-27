package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.docs.internal.SchemaRegistry
import io.github.amichne.kast.api.docs.internal.openApiPaths
import io.github.amichne.kast.api.docs.internal.registerOpenApiSchemas
import io.github.amichne.kast.api.docs.internal.renderYaml as renderYamlFragment
import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates an OpenAPI 3.1 specification for the Kast analysis daemon JSON-RPC API.
 *
 * Each JSON-RPC method dispatched by the analysis server is modelled as a logical
 * `POST /rpc/{method}` operation whose request body is the `params` payload and whose
 * response body is the `result` payload. The JSON-RPC envelope and error format are
 * documented as separate schemas.
 *
 * The generated YAML is checked in at `cli-rs/protocol/openapi.yaml` for
 * release packaging and is validated by [AnalysisOpenApiDocumentTest] to prevent drift.
 */
object OpenApiDocument {

    fun renderYaml(): String {
        val registry = SchemaRegistry()
        registerSchemas(registry)
        return buildString {
            appendLine("openapi: 3.1.0")
            appendLine("info:")
            appendLine("  title: Kast Analysis API")
            appendLine("  version: \"$SCHEMA_VERSION.0.0\"")
            appendLine("  description: >")
            appendLine("    JSON-RPC 2.0 analysis protocol for the Kast daemon. Each operation is")
            appendLine("    modelled as a logical POST whose request body is the JSON-RPC params")
            appendLine("    payload and whose response body is the result payload. The actual")
            appendLine("    transport is line-delimited JSON-RPC over Unix domain sockets, stdio,")
            appendLine("    or TCP — not HTTP. Batch requests and JSON-RPC notifications are not")
            appendLine("    supported. Capability gating is noted per operation via")
            appendLine("    x-kast-required-capability.")
            appendLine("  license:")
            appendLine("    name: Apache-2.0")
            appendLine("    url: https://www.apache.org/licenses/LICENSE-2.0")
            appendLine("servers:")
            appendLine("  - url: jsonrpc://localhost")
            appendLine("    description: >")
            appendLine("      Logical server — the daemon binds a Unix domain socket, stdio pipe,")
            appendLine("      or TCP port, not an HTTP endpoint.")
            appendLine("tags:")
            appendLine("  - name: system")
            appendLine("    description: Health, status, and capability introspection")
            appendLine("  - name: read")
            appendLine("    description: Read-only analysis operations")
            appendLine("  - name: mutation")
            appendLine("    description: Operations that modify workspace state")
            appendLine("paths:")
            append(renderYamlFragment(writePaths(), 2))
            appendLine("components:")
            appendLine("  schemas:")
            appendLine("    JsonRpcRequest:")
            appendLine("      type: object")
            appendLine("      required:")
            appendLine("        - jsonrpc")
            appendLine("        - method")
            appendLine("      properties:")
            appendLine("        jsonrpc:")
            appendLine("          type: string")
            appendLine("          const: \"2.0\"")
            appendLine("        method:")
            appendLine("          type: string")
            appendLine("        params:")
            appendLine("          description: Method-specific parameter object")
            appendLine("        id:")
            appendLine("          description: Request identifier (string, number, or null)")
            appendLine("      additionalProperties: false")
            appendLine("    JsonRpcSuccessResponse:")
            appendLine("      type: object")
            appendLine("      required:")
            appendLine("        - jsonrpc")
            appendLine("        - result")
            appendLine("      properties:")
            appendLine("        jsonrpc:")
            appendLine("          type: string")
            appendLine("          const: \"2.0\"")
            appendLine("        result:")
            appendLine("          description: Method-specific result object")
            appendLine("        id:")
            appendLine("          description: Echoed request identifier")
            appendLine("      additionalProperties: false")
            appendLine("    JsonRpcErrorResponse:")
            appendLine("      type: object")
            appendLine("      required:")
            appendLine("        - jsonrpc")
            appendLine("        - error")
            appendLine("      properties:")
            appendLine("        jsonrpc:")
            appendLine("          type: string")
            appendLine("          const: \"2.0\"")
            appendLine("        error:")
            appendLine("          \$ref: \"#/components/schemas/JsonRpcErrorObject\"")
            appendLine("        id:")
            appendLine("          description: Echoed request identifier")
            appendLine("      additionalProperties: false")
            append(renderYamlFragment(registry.schemas, 4))
        }
    }

    private fun registerSchemas(registry: SchemaRegistry) = registerOpenApiSchemas(registry)

    private fun writePaths(): Map<String, Any?> = openApiPaths()
}

fun main(args: Array<String>) {
    val target = args.firstOrNull()?.let(Path::of)
                 ?: Path.of("cli-rs/protocol/openapi.yaml")
    Files.createDirectories(target.parent)
    Files.writeString(target, OpenApiDocument.renderYaml())
}
