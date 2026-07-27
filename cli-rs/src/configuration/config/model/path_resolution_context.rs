#[derive(Debug, Clone, Copy)]
struct PathResolutionEntryContext {
    install_root_source: PathResolutionSource,
    bin_dir_source: PathResolutionSource,
    cache_dir_source: PathResolutionSource,
    logs_dir_source: PathResolutionSource,
    logs_dir_parent: Option<&'static str>,
    runtime_dir_source: PathResolutionSource,
    runtime_dir_parent: Option<&'static str>,
    workspace_state_source: PathResolutionSource,
    workspace_state_parent: Option<&'static str>,
}

impl PathResolutionEntryContext {
    fn from_environment(
        workspace_root: Option<&Path>,
        install_manifest_exists: bool,
    ) -> Self {
        Self::from_states(
            install_manifest_exists,
            env_present("KAST_INSTALL_ROOT"),
            env_present("KAST_CACHE_HOME"),
            workspace_root.is_some() && env_present("KAST_CACHE_HOME"),
        )
    }

    fn from_states(
        install_manifest_exists: bool,
        install_root_env: bool,
        cache_home_env: bool,
        workspace_cache_environment: bool,
    ) -> Self {
        let install_manifest_active = install_manifest_exists;
        let install_root_source =
            source_for_manifest_or_env_state(install_manifest_active, install_root_env);
        let runtime_dir_source = if install_manifest_active {
            PathResolutionSource::Manifest
        } else {
            install_root_source
        };
        let workspace_state_source = if workspace_cache_environment {
            PathResolutionSource::Env
        } else {
            runtime_dir_source
        };
        Self {
            install_root_source,
            bin_dir_source: if install_manifest_active {
                PathResolutionSource::Manifest
            } else {
                PathResolutionSource::Default
            },
            cache_dir_source: if workspace_cache_environment {
                PathResolutionSource::Env
            } else {
                source_for_manifest_or_env_state(install_manifest_active, cache_home_env)
            },
            logs_dir_source: if workspace_cache_environment {
                PathResolutionSource::Env
            } else if install_manifest_active {
                PathResolutionSource::Manifest
            } else {
                PathResolutionSource::Default
            },
            logs_dir_parent: workspace_cache_environment.then_some("paths.cacheDir"),
            runtime_dir_source,
            runtime_dir_parent: (!install_manifest_active).then_some("paths.installRoot"),
            workspace_state_source,
            workspace_state_parent: Some(if workspace_cache_environment {
                "paths.cacheDir"
            } else {
                "paths.runtimeDir"
            }),
        }
    }
}
