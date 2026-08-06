pub fn indexer_runtime_libs_dir(
    config: &KastConfig,
    override_dir: Option<PathBuf>,
) -> Result<PathBuf> {
    let configured = config.indexer.runtime_libs_dir.clone();
    override_dir.map(normalize).or(configured).ok_or_else(|| {
        CliError::new(
            "DAEMON_START_ERROR",
            "Cannot locate backend runtime-libs. Rerun `kast setup --source <bundle>`, or pass --runtime-libs-dir for this launch.",
        )
    })
}

pub fn server_launch_args(args: &DaemonStartArgs, config: &KastConfig) -> Result<Vec<String>> {
    let workspace_root = resolve_workspace_root(args.workspace_root.clone())?;
    let socket_path = args
        .socket_path
        .clone()
        .map(normalize)
        .unwrap_or_else(|| default_socket_path_for_config(config, &workspace_root));
    let mut result = vec![format!("--workspace-root={}", workspace_root.display())];
    if let Some(claim) = linked_worktree_registration_claim(&workspace_root) {
        result.extend([
            format!("--linked-worktree-git-file={}", claim.git_file.display()),
            format!(
                "--linked-worktree-git-directory={}",
                claim.git_directory.display()
            ),
        ]);
    }
    if args.stdio {
        result.push("--stdio".to_string());
    } else {
        result.push(format!("--socket-path={}", socket_path.display()));
    }
    result.push(format!(
        "--module-name={}",
        args.module_name.as_deref().unwrap_or("sources")
    ));
    if let Some(source_roots) = &args.source_roots {
        result.push(format!("--source-roots={source_roots}"));
    }
    if let Some(classpath) = &args.classpath {
        result.push(format!("--classpath={classpath}"));
    }
    result.push(format!(
        "--request-timeout-ms={}",
        args.request_timeout_ms
            .unwrap_or(config.server.request_timeout_millis)
    ));
    result.push(format!(
        "--max-results={}",
        args.max_results.unwrap_or(config.server.max_results)
    ));
    result.push(format!(
        "--max-concurrent-requests={}",
        args.max_concurrent_requests
            .unwrap_or(config.server.max_concurrent_requests)
    ));
    if args.profile {
        result.push("--profile".to_string());
    }
    if let Some(value) = &args.profile_modes {
        result.push(format!("--profile-modes={value}"));
    }
    if let Some(value) = args.profile_duration {
        result.push(format!("--profile-duration={value}"));
    }
    if let Some(value) = &args.profile_otlp_endpoint {
        result.push(format!("--profile-otlp-endpoint={value}"));
    }
    if let Some(runtime_instance_id) = args.runtime_instance_id {
        result.push(format!("--runtime-instance-id={runtime_instance_id}"));
    }
    Ok(result)
}
