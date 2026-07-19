fn runtime_backend_preference(
    config: &KastConfig,
    backend_name: Option<BackendName>,
) -> RuntimeBackendPreference {
    let selected = backend_name.or(config.runtime.default_backend.backend_name());
    #[cfg(target_os = "macos")]
    let selected = selected.or(Some(BackendName::Idea));
    selected
        .map(RuntimeBackendPreference::Fixed)
        .unwrap_or(RuntimeBackendPreference::Automatic)
}

fn fallback_launch_backend(preference: RuntimeBackendPreference) -> Option<BackendName> {
    match preference.fixed_backend() {
        Some(BackendName::Idea) => None,
        Some(BackendName::Headless) | None => Some(BackendName::Headless),
    }
}
