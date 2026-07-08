#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialConfig {
    server: Option<PartialServer>,
    runtime: Option<PartialRuntime>,
    project_open: Option<PartialProjectOpen>,
    onboarding: Option<PartialOnboarding>,
    indexing: Option<PartialIndexing>,
    cache: Option<PartialCache>,
    watcher: Option<PartialWatcher>,
    gradle: Option<PartialGradle>,
    telemetry: Option<PartialTelemetry>,
    profiling: Option<PartialProfiling>,
    backends: Option<PartialBackends>,
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
struct PartialRuntime {
    default_backend: Option<RuntimeDefaultBackend>,
    idea_launch: Option<PartialIdeaLaunch>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialIdeaLaunch {
    enabled: Option<bool>,
    command: Option<PathBuf>,
    wait_timeout_millis: Option<NonZeroU64>,
    require_installed_plugin: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialProjectOpen {
    profile_auto_init: Option<bool>,
    profile: Option<ProjectOpenProfile>,
    auto_exclude_git: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialOnboarding {
    agent_up_completed: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialIndexing {
    phase2_enabled: Option<bool>,
    phase2_batch_size: Option<u32>,
    phase2_parallelism: Option<u32>,
    phase2_priority_depth: Option<u32>,
    identifier_index_wait_millis: Option<u64>,
    reference_batch_size: Option<u32>,
    remote: Option<PartialRemoteIndex>,
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
struct PartialBackends {
    headless: Option<PartialHeadless>,
    idea: Option<PartialIdea>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialHeadless {
    enabled: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialIdea {
    enabled: Option<bool>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PartialCli {
    dynamic_output: Option<bool>,
}
