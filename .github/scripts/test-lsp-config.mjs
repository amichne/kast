import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import process from "node:process";

const repoRoot = resolve(import.meta.dirname, "..", "..");
const configPath = resolve(repoRoot, ".github", "lsp.json");
const config = JSON.parse(await readFile(configPath, "utf8"));
const server = config.lspServers?.["kast-kotlin"];
const rpcCatalogPath = resolve(repoRoot, "cli-rs", "resources", "kast-skill", "references", "commands.json");
const rpcCatalog = JSON.parse(await readFile(rpcCatalogPath, "utf8"));

assert(server, "lspServers.kast-kotlin is required");
assert(server.command === "kast", "kast-kotlin.command must be kast");
assertArrayEquals(server.args, ["lsp", "--stdio"], "kast-kotlin.args");
assert(server.fileExtensions?.[".kt"] === "kotlin", ".kt must map to kotlin");
assert(server.fileExtensions?.[".kts"] === "kotlin", ".kts must map to kotlin");
assert(server.rootUri === ".", "kast-kotlin.rootUri must be .");
assert(server.initializationTimeoutMs >= 120000, "initializationTimeoutMs must be at least 120000");
assert(server.requestTimeoutMs >= 90000, "requestTimeoutMs must be at least 90000");
assert(
  server.initializationOptions?.indexMode === "compiler-backed",
  "initializationOptions.indexMode must be compiler-backed",
);
assert(
  server.initializationOptions?.failOnStaleIndex === true,
  "initializationOptions.failOnStaleIndex must be true",
);
assert(
  server.initializationOptions?.preferCompilerFactsOverTextSearch === true,
  "initializationOptions.preferCompilerFactsOverTextSearch must be true",
);

const command = process.env.KAST_LSP_TEST_COMMAND ?? server.command;
const timeoutMs = process.env.KAST_LSP_REQUEST_TIMEOUT_MS ?? "1000";
const backend = process.env.KAST_LSP_BACKEND;
const requireInitializeSuccess = process.env.KAST_LSP_REQUIRE_INITIALIZE_SUCCESS === "1";
const workspaceSymbolQuery = process.env.KAST_LSP_WORKSPACE_SYMBOL_QUERY;
const args = [...server.args, "--workspace-root", repoRoot, "--request-timeout-ms", timeoutMs];
if (backend) {
  args.push("--backend", backend);
}
const child = spawn(command, args, {
  cwd: repoRoot,
  env: process.env,
  stdio: ["pipe", "pipe", "pipe"],
});

let stdout = Buffer.alloc(0);
let stderr = Buffer.alloc(0);
child.stdout.on("data", (chunk) => {
  stdout = Buffer.concat([stdout, chunk]);
});
child.stderr.on("data", (chunk) => {
  stderr = Buffer.concat([stderr, chunk]);
});

writeMessage(child, {
  jsonrpc: "2.0",
  id: 1,
  method: "initialize",
  params: {
    rootUri: pathToFileUri(repoRoot),
    capabilities: {},
    initializationOptions: server.initializationOptions,
  },
});

const initialize = await readOneMessage(child, () => stdout, (value) => {
  stdout = value;
});

const initializeErrorCode = initialize.error?.data?.code;
if (initialize.error) {
  assert(!requireInitializeSuccess, `initialize failed with ${initializeErrorCode}: ${initialize.error.message}`);
  assert(
    [
      "DAEMON_START_ERROR",
      "HEADLESS_BACKEND_NOT_INSTALLED",
      "IDEA_NOT_RUNNING",
      "NO_BACKEND_AVAILABLE",
      "RUNTIME_TIMEOUT",
    ].includes(initializeErrorCode),
    `initialize failed with unexpected code ${initializeErrorCode}`,
  );
} else {
  const capabilities = initialize.result?.capabilities;
  assert(capabilities && typeof capabilities === "object", "initialize result must include capabilities");
  assert(capabilities.textDocumentSync?.openClose === true, "textDocumentSync.openClose must be true");
  assert(capabilities.workspaceSymbolProvider !== undefined, "workspaceSymbolProvider must be advertised");
  assert(capabilities.renameProvider?.prepareProvider === true, "Kast LSP must advertise prepared rename when supported");
  assertArrayEquals(
    capabilities.experimental?.kastMethods,
    expectedCustomLspMethods(rpcCatalog),
    "capabilities.experimental.kastMethods",
  );
}

let workspaceSymbol = null;
let customMethodSmoke = null;
if (!initialize.error) {
  writeMessage(child, {
    jsonrpc: "2.0",
    id: 4,
    method: "kast/capabilities",
    params: {},
  });
  const capabilitiesResponse = await readOneMessage(child, () => stdout, (value) => {
    stdout = value;
  });
  assert(!capabilitiesResponse.error, `kast/capabilities failed: ${capabilitiesResponse.error?.message}`);
  assert(
    Array.isArray(capabilitiesResponse.result?.readCapabilities),
    "kast/capabilities result must include readCapabilities",
  );

  writeMessage(child, {
    jsonrpc: "2.0",
    id: 5,
    method: "kast/symbolQuery",
    params: {
      query: "__kast_lsp_smoke__",
      limit: 1,
    },
  });
  const symbolQueryResponse = await readOneMessage(child, () => stdout, (value) => {
    stdout = value;
  });
  assert(!symbolQueryResponse.error, `kast/symbolQuery failed: ${symbolQueryResponse.error?.message}`);
  assert(
    typeof symbolQueryResponse.result?.type === "string"
      && symbolQueryResponse.result.type.startsWith("SYMBOL_QUERY_"),
    "kast/symbolQuery result must be a symbol-query response envelope",
  );
  customMethodSmoke = {
    capabilities: capabilitiesResponse.result.readCapabilities.length,
    symbolQueryType: symbolQueryResponse.result.type,
  };
}

if (workspaceSymbolQuery) {
  assert(!initialize.error, "workspace symbol smoke requires successful initialize");
  writeMessage(child, {
    jsonrpc: "2.0",
    id: 3,
    method: "workspace/symbol",
    params: {
      query: workspaceSymbolQuery,
    },
  });
  const response = await readOneMessage(child, () => stdout, (value) => {
    stdout = value;
  });
  assert(!response.error, `workspace/symbol failed: ${response.error?.message}`);
  assert(Array.isArray(response.result), "workspace/symbol result must be an array");
  assert(response.result.length > 0, `workspace/symbol returned no results for ${workspaceSymbolQuery}`);
  workspaceSymbol = {
    query: workspaceSymbolQuery,
    resultCount: response.result.length,
    first: response.result[0],
  };
}

writeMessage(child, {
  jsonrpc: "2.0",
  id: 2,
  method: "shutdown",
  params: {},
});
await readOneMessage(child, () => stdout, (value) => {
  stdout = value;
});

writeMessage(child, {
  jsonrpc: "2.0",
  method: "exit",
  params: {},
});
child.stdin.end();

const exitCode = await waitForExit(child);
assert(exitCode === 0, `kast lsp exited with ${exitCode}: ${stderr.toString("utf8")}`);

console.log(JSON.stringify({
  ok: true,
  command,
  args,
  initializeErrorCode: initializeErrorCode ?? null,
  serverInfo: initialize.result?.serverInfo ?? null,
  customMethodSmoke,
  workspaceSymbol,
}, null, 2));

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function assertArrayEquals(actual, expected, label) {
  assert(Array.isArray(actual), `${label} must be an array`);
  assert(actual.length === expected.length, `${label} length mismatch`);
  for (const [index, value] of expected.entries()) {
    assert(actual[index] === value, `${label}[${index}] must be ${value}`);
  }
}

function expectedCustomLspMethods(catalog) {
  return ["symbol", "database", "system"].flatMap((category) => {
    const methods = catalog.categories?.[category];
    assert(Array.isArray(methods), `catalog category ${category} must be an array`);
    return methods.map((method) => rpcMethodToLspMethod(method));
  });
}

function rpcMethodToLspMethod(method) {
  const [first, ...rest] = method.split("/");
  return `kast/${first}${rest.map(pascalCaseSegment).join("")}`;
}

function pascalCaseSegment(segment) {
  return segment
    .split("-")
    .map((word) => `${word.slice(0, 1).toUpperCase()}${word.slice(1)}`)
    .join("");
}

function pathToFileUri(path) {
  return `file://${path.split("/").map(encodeURIComponent).join("/")}`;
}

function writeMessage(childProcess, value) {
  const body = Buffer.from(JSON.stringify(value));
  childProcess.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
  childProcess.stdin.write(body);
}

async function readOneMessage(childProcess, getBuffer, setBuffer) {
  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    const buffer = getBuffer();
    const headerEnd = buffer.indexOf("\r\n\r\n");
    if (headerEnd !== -1) {
      const header = buffer.subarray(0, headerEnd).toString("utf8");
      const lengthMatch = /^Content-Length:\s*(\d+)/im.exec(header);
      assert(lengthMatch, `missing Content-Length header: ${header}`);
      const contentLength = Number.parseInt(lengthMatch[1], 10);
      const messageStart = headerEnd + 4;
      const messageEnd = messageStart + contentLength;
      if (buffer.length >= messageEnd) {
        const body = buffer.subarray(messageStart, messageEnd);
        setBuffer(buffer.subarray(messageEnd));
        return JSON.parse(body.toString("utf8"));
      }
    }
    if (childProcess.exitCode !== null) {
      throw new Error(`kast lsp exited before response with ${childProcess.exitCode}`);
    }
    await new Promise((resolveTimeout) => setTimeout(resolveTimeout, 10));
  }
  throw new Error("timed out waiting for LSP response");
}

function waitForExit(childProcess) {
  if (childProcess.exitCode !== null) {
    return Promise.resolve(childProcess.exitCode);
  }
  return new Promise((resolveExit) => {
    childProcess.once("exit", (code) => resolveExit(code));
  });
}
