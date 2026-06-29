impl From<RuntimeArgs> for DaemonStartArgs {
    fn from(value: RuntimeArgs) -> Self {
        Self {
            workspace_root: value.workspace_root,
            backend_name: value.backend_name,
            runtime_libs_dir: None,
            idea_home: value.idea_home,
            socket_path: value.socket_path,
            module_name: value.module_name,
            source_roots: value.source_roots,
            classpath: value.classpath,
            request_timeout_ms: value.request_timeout_ms,
            max_results: value.max_results,
            max_concurrent_requests: value.max_concurrent_requests,
            stdio: false,
            profile: value.profile,
            profile_modes: value.profile_modes,
            profile_duration: value.profile_duration,
            profile_otlp_endpoint: value.profile_otlp_endpoint,
        }
    }
}
