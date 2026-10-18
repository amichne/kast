// Catalog, registration and execution are separate admissions; this checks only discovery.
export function discoveryFailures({ errors, model, servers, adapter }) {
  const failures = [];
  if (errors.length) failures.push('EXTENSION_LOAD_FAILED');
  if (!model) failures.push('REQUESTED_MODEL_UNAVAILABLE');
  if (adapter && !servers.some(server => server.name === 'kast' && server.extensionPath === adapter)) {
    failures.push('KAST_MCP_REGISTRATION_UNAVAILABLE');
  }
  return failures;
}
