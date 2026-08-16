impl KastConfig {
    pub fn defaults() -> Self {
        Self::from_resolved_paths(&manifest::default_resolved_paths())
    }

    fn from_resolved_paths(paths: &manifest::ResolvedKastPaths) -> Self {
        Self {
            server: ServerConfig {
                max_results: 500,
                request_timeout_millis: 30_000,
                max_concurrent_requests: 4,
            },
            indexer: IndexerConfig {
                runtime_libs_dir: Some(paths.indexer_runtime_libs_dir.clone()),
                host_home: paths.indexer_host_home.clone(),
                ..IndexerConfig::default()
            },
            codex: CodexConfig::default(),
            indexing: IndexingConfig {
                critical_paths: Vec::new(),
                ignored_paths: Vec::new(),
                graph: GraphIndexingConfig {
                    batch_size: NonZeroU32::new(32).expect("default graph batch size"),
                },
                relationships: RelationshipIndexingConfig {
                    enabled: true,
                    batch_size: 50,
                    parallelism: 4,
                    module_priority_depth: 2,
                },
                identifier_index_wait_millis: 10_000,
                remote: RemoteIndexConfig {
                    enabled: false,
                    source_index_url: None,
                },
            },
            cache: CacheConfig {
                enabled: true,
                write_delay_millis: 5_000,
                source_index_save_delay_millis: 5_000,
            },
            watcher: WatcherConfig {
                debounce_millis: 200,
            },
            gradle: GradleConfig {
                tooling_api_timeout_millis: 120_000,
            },
            telemetry: TelemetryConfig {
                enabled: false,
                scopes: "all".to_string(),
                detail: "basic".to_string(),
                output_file: None,
            },
            profiling: ProfilingConfig {
                enabled: false,
                modes: "cpu".to_string(),
                duration_seconds: 30,
                output_dir: "{logsDir}/profiling".to_string(),
                otlp_endpoint: None,
                emit_manifest: true,
            },
            paths: PathsConfig {
                install_root: paths.install_root.clone(),
                bin_dir: paths.bin_dir.clone(),
                lib_dir: paths.lib_dir.clone(),
                cache_dir: paths.cache_dir.clone(),
                logs_dir: paths.logs_dir.clone(),
                runtime_dir: paths.runtime_dir.clone(),
                descriptor_dir: paths.descriptor_dir.clone(),
                socket_dir: paths.socket_dir.clone(),
            },
            cli: CliConfig {
                binary_path: paths.shim_path.clone(),
                dynamic_output: true,
            },
        }
    }

    pub fn load_global() -> Result<Self> {
        let resolved_paths = manifest::resolve_paths()?;
        let mut config = Self::from_resolved_paths(&resolved_paths);
        let global_config = global_config_path();
        if global_config.is_file() {
            config.apply(read_partial_config(&global_config)?);
        }
        Ok(config)
    }

    pub fn load(workspace_root: &Path) -> Result<Self> {
        let mut config = Self::load_global()?;
        let workspace_config = workspace_data_directory(workspace_root)?.join("config.toml");
        if workspace_config.is_file() {
            config.apply(read_partial_config(&workspace_config)?);
        }
        config.apply_workspace_cache_environment(workspace_root);
        Ok(config)
    }

    fn apply_workspace_cache_environment(&mut self, workspace_root: &Path) {
        let Some(cache_home) = env_path("KAST_CACHE_HOME") else {
            return;
        };
        let workspace_id = env::var("KAST_WORKSPACE_ID")
            .ok()
            .filter(|value| !value.trim().is_empty());
        self.apply_workspace_cache_home(&cache_home, workspace_root, workspace_id.as_deref());
    }

    fn apply_workspace_cache_home(
        &mut self,
        cache_home: &Path,
        workspace_root: &Path,
        workspace_id: Option<&str>,
    ) {
        let workspace_dir = workspace_cache_directory(cache_home, workspace_root, workspace_id);
        self.paths.cache_dir = cache_home.to_path_buf();
        self.paths.logs_dir = workspace_dir.join("logs");
        self.paths.descriptor_dir = workspace_dir.clone();
        self.paths.socket_dir = workspace_dir;
    }

    fn apply(&mut self, partial: PartialConfig) {
        if let Some(server) = partial.server {
            if let Some(value) = server.max_results {
                self.server.max_results = value;
            }
            if let Some(value) = server.request_timeout_millis {
                self.server.request_timeout_millis = value;
            }
            if let Some(value) = server.max_concurrent_requests {
                self.server.max_concurrent_requests = value;
            }
        }
        if let Some(value) = partial.indexer.and_then(|indexer| indexer.host_command) {
            self.indexer.host_command = value;
        }
        if let Some(hooks) = partial.codex.and_then(|codex| codex.hooks) {
            if let Some(value) = hooks.enabled {
                self.codex.hooks.enabled = value;
            }
            if let Some(value) = hooks.session_start {
                self.codex.hooks.session_start = value;
            }
            if let Some(value) = hooks.post_tool_use {
                self.codex.hooks.post_tool_use = value;
            }
            if let Some(value) = hooks.auto_start_indexer {
                self.codex.hooks.auto_start_indexer = Some(value).into();
            }
        }
        if let Some(indexing) = partial.indexing {
            if let Some(value) = indexing.critical_paths {
                self.indexing.critical_paths = value;
            }
            if let Some(value) = indexing.ignored_paths {
                self.indexing.ignored_paths = value;
            }
            if let Some(graph) = indexing.graph
                && let Some(value) = graph.batch_size
            {
                self.indexing.graph.batch_size = value;
            }
            if let Some(relationships) = indexing.relationships {
                if let Some(value) = relationships.enabled {
                    self.indexing.relationships.enabled = value;
                }
                if let Some(value) = relationships.batch_size {
                    self.indexing.relationships.batch_size = value;
                }
                if let Some(value) = relationships.parallelism {
                    self.indexing.relationships.parallelism = value;
                }
                if let Some(value) = relationships.module_priority_depth {
                    self.indexing.relationships.module_priority_depth = value;
                }
            }
            if let Some(value) = indexing.identifier_index_wait_millis {
                self.indexing.identifier_index_wait_millis = value;
            }
            if let Some(remote) = indexing.remote {
                if let Some(value) = remote.enabled {
                    self.indexing.remote.enabled = value;
                }
                if let Some(value) = remote.source_index_url {
                    self.indexing.remote.source_index_url = value;
                }
            }
        }
        if let Some(cache) = partial.cache {
            if let Some(value) = cache.enabled {
                self.cache.enabled = value;
            }
            if let Some(value) = cache.write_delay_millis {
                self.cache.write_delay_millis = value;
            }
            if let Some(value) = cache.source_index_save_delay_millis {
                self.cache.source_index_save_delay_millis = value;
            }
        }
        if let Some(watcher) = partial.watcher
            && let Some(value) = watcher.debounce_millis
        {
            self.watcher.debounce_millis = value;
        }
        if let Some(gradle) = partial.gradle
            && let Some(value) = gradle.tooling_api_timeout_millis
        {
            self.gradle.tooling_api_timeout_millis = value;
        }
        if let Some(telemetry) = partial.telemetry {
            if let Some(value) = telemetry.enabled {
                self.telemetry.enabled = value;
            }
            if let Some(value) = telemetry.scopes {
                self.telemetry.scopes = value;
            }
            if let Some(value) = telemetry.detail {
                self.telemetry.detail = value;
            }
            if let Some(value) = telemetry.output_file {
                self.telemetry.output_file = value;
            }
        }
        if let Some(profiling) = partial.profiling {
            if let Some(value) = profiling.enabled {
                self.profiling.enabled = value;
            }
            if let Some(value) = profiling.modes {
                self.profiling.modes = value;
            }
            if let Some(value) = profiling.duration_seconds {
                self.profiling.duration_seconds = value;
            }
            if let Some(value) = profiling.output_dir {
                self.profiling.output_dir = value;
            }
            if let Some(value) = profiling.otlp_endpoint {
                self.profiling.otlp_endpoint = value;
            }
            if let Some(value) = profiling.emit_manifest {
                self.profiling.emit_manifest = value;
            }
        }
        if let Some(cli) = partial.cli
            && let Some(value) = cli.dynamic_output
        {
            self.cli.dynamic_output = value;
        }
    }
}
