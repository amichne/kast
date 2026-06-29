fn runtime_backend_preference(
    config: &KastConfig,
    backend_name: Option<BackendName>,
) -> RuntimeBackendPreference {
    backend_name
        .or(config.runtime.default_backend.backend_name())
        .map(RuntimeBackendPreference::Fixed)
        .unwrap_or(RuntimeBackendPreference::Automatic)
}

fn fallback_launch_backend(preference: RuntimeBackendPreference) -> Option<BackendName> {
    match preference.fixed_backend() {
        Some(BackendName::Idea) => None,
        Some(BackendName::Headless) | None => Some(BackendName::Headless),
    }
}
