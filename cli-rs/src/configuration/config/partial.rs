#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialConfig {
    server: Option<PartialServer>,
    indexer: Option<PartialIndexer>,
    codex: Option<PartialCodex>,
    indexing: Option<PartialIndexing>,
    cache: Option<PartialCache>,
    watcher: Option<PartialWatcher>,
    gradle: Option<PartialGradle>,
    telemetry: Option<PartialTelemetry>,
    profiling: Option<PartialProfiling>,
    cli: Option<PartialCli>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialServer {
    max_results: Option<u32>,
    request_timeout_millis: Option<u64>,
    max_concurrent_requests: Option<u32>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialIndexer {
    host_command: Option<PathBuf>,
}

#[derive(Debug, Default, Deserialize)]
struct PartialCodex {
    hooks: Option<PartialCodexHooks>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialCodexHooks {
    enabled: Option<bool>,
    session_start: Option<bool>,
    post_tool_use: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PartialIndexing {
    critical_paths: Option<Vec<String>>,
    ignored_paths: Option<Vec<String>>,
    graph: Option<PartialGraphIndexing>,
    relationships: Option<PartialRelationshipIndexing>,
    identifier_index_wait_millis: Option<u64>,
    remote: Option<PartialRemoteIndex>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct PartialGraphIndexing {
    batch_size: Option<NonZeroU32>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialRelationshipIndexing {
    enabled: Option<bool>,
    batch_size: Option<u32>,
    parallelism: Option<u32>,
    module_priority_depth: Option<u32>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialRemoteIndex {
    enabled: Option<bool>,
    source_index_url: Option<Option<String>>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialCache {
    enabled: Option<bool>,
    write_delay_millis: Option<u64>,
    source_index_save_delay_millis: Option<u64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialWatcher {
    debounce_millis: Option<u64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialGradle {
    tooling_api_timeout_millis: Option<u64>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialTelemetry {
    enabled: Option<bool>,
    scopes: Option<String>,
    detail: Option<String>,
    output_file: Option<Option<String>>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialProfiling {
    enabled: Option<bool>,
    modes: Option<String>,
    duration_seconds: Option<u64>,
    output_dir: Option<String>,
    otlp_endpoint: Option<Option<String>>,
    emit_manifest: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialCli {
    dynamic_output: Option<bool>,
}
