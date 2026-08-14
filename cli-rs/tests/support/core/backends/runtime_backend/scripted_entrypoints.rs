pub(crate) fn spawn_scripted_indexer_backend_for_invocations(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    invocation_count: usize,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        invocation_count,
        true,
        vec![],
        None,
        None,
        None,
        None,
        scripted_results,
    )
}

pub(crate) fn spawn_scripted_indexer_backend_for_published_workspace_read(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_scripted_backend_with_additional_runtime_status_requests(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        1,
        false,
        vec![],
        None,
        None,
        None,
        None,
        1,
        false,
        scripted_results,
        ScriptedRuntimeAuthority::PublishExact,
    )
}

pub(crate) fn spawn_ready_indexer_backend_after_marker(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    marker: &Path,
    invocation_count: usize,
) -> std::thread::JoinHandle<Option<Vec<serde_json::Value>>> {
    let home = home.to_path_buf();
    let config_home = config_home.to_path_buf();
    let workspace = workspace.to_path_buf();
    let socket_path = socket_path.to_path_buf();
    let marker = marker.to_path_buf();
    thread::spawn(move || {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while !marker.is_file() && std::time::Instant::now() < deadline {
            thread::sleep(std::time::Duration::from_millis(10));
        }
        if !marker.is_file() {
            return None;
        }
        let observation_deadline = std::time::Instant::now() + std::time::Duration::from_secs(1);
        while std::time::Instant::now() < observation_deadline {
            let launches = std::fs::read_to_string(&marker)
                .unwrap_or_default()
                .lines()
                .filter(|line| *line == "__KAST_SIDECAR_LAUNCH__")
                .count();
            if launches >= invocation_count {
                break;
            }
            thread::sleep(std::time::Duration::from_millis(10));
        }
        Some(
            spawn_registered_scripted_backend(
                &home,
                &config_home,
                &workspace,
                &socket_path,
                "indexer",
                invocation_count,
                true,
                vec![],
                None,
                None,
                None,
                None,
                vec![],
            )
            .join()
            .expect("ready indexer"),
        )
    })
}

#[allow(clippy::too_many_arguments)]
fn spawn_registered_scripted_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    invocation_count: usize,
    semantic_ready: bool,
    mutation_capabilities: Vec<&'static str>,
    mutation_file_write: Option<(PathBuf, Vec<u8>)>,
    mutation_gate: Option<(PathBuf, PathBuf)>,
    keepalive_until: Option<PathBuf>,
    scratch_crash_gate: Option<ScriptedScratchCrashGate>,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    let backend = spawn_scripted_backend_with_additional_runtime_status_requests(
        home,
        config_home,
        workspace,
        socket_path,
        backend_name,
        invocation_count,
        semantic_ready,
        mutation_capabilities,
        mutation_file_write,
        mutation_gate,
        keepalive_until,
        scratch_crash_gate,
        0,
        false,
        scripted_results,
        ScriptedRuntimeAuthority::ReuseRegistered,
    );
    publish_registered_runtime_descriptor(home, workspace, socket_path, backend_name);
    backend
}

fn publish_registered_runtime_descriptor(
    home: &Path,
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
) {
    let services = default_install_root(home).join("state/runtime/services");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    let (runtime_instance_id, pid) = loop {
        if let Some(identity) = registered_runtime_identity(&services) {
            break identity;
        }
        assert!(
            std::time::Instant::now() < deadline,
            "registered runtime identity was not published under {}",
            services.display(),
        );
        thread::sleep(std::time::Duration::from_millis(10));
    };
    let mut descriptor = runtime_descriptor_for_process_test(
        workspace,
        socket_path,
        backend_name,
        "scripted-test",
        pid,
    );
    descriptor["runtimeInstanceId"] = runtime_instance_id.into();
    std::fs::write(
        default_descriptor_dir(home).join("daemons.json"),
        serde_json::to_vec_pretty(&serde_json::json!([descriptor])).expect("descriptor json"),
    )
    .expect("registered descriptor");
}

fn registered_runtime_identity(services: &Path) -> Option<(String, u32)> {
    let workspace = std::fs::read_dir(services)
        .ok()?
        .filter_map(Result::ok)
        .find(|entry| entry.file_type().ok().is_some_and(|kind| kind.is_dir()))?;
    let active: serde_json::Value = serde_json::from_slice(
        &std::fs::read(workspace.path().join("active.json")).ok()?,
    )
    .ok()?;
    let runtime_instance_id = active["runtimeInstanceId"].as_str()?.to_string();
    let receipt: serde_json::Value = serde_json::from_slice(
        &std::fs::read(workspace.path().join(&runtime_instance_id).join("receipt.json")).ok()?,
    )
    .ok()?;
    let state_path = Path::new(receipt["manager"]["state_path"].as_str()?);
    let state: serde_json::Value = serde_json::from_slice(&std::fs::read(state_path).ok()?).ok()?;
    Some((runtime_instance_id, u32::try_from(state["pid"].as_u64()?).ok()?))
}

#[allow(clippy::too_many_arguments)]
pub(super) fn spawn_scripted_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    backend_name: &str,
    invocation_count: usize,
    semantic_ready: bool,
    mutation_capabilities: Vec<&'static str>,
    _mutation_file_write: Option<(PathBuf, Vec<u8>)>,
    mutation_gate: Option<(PathBuf, PathBuf)>,
    keepalive_until: Option<PathBuf>,
    scratch_crash_gate: Option<ScriptedScratchCrashGate>,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    spawn_scripted_backend_with_additional_runtime_status_requests(
        home,
        config_home,
        workspace,
        socket_path,
        backend_name,
        invocation_count,
        semantic_ready,
        mutation_capabilities,
        _mutation_file_write,
        mutation_gate,
        keepalive_until,
        scratch_crash_gate,
        0,
        false,
        scripted_results,
        ScriptedRuntimeAuthority::PublishExact,
    )
}

#[allow(clippy::too_many_arguments)]
pub(super) fn spawn_strictly_scripted_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    invocation_count: usize,
    semantic_ready: bool,
    mutation_capabilities: Vec<&'static str>,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    spawn_scripted_backend_with_additional_runtime_status_requests(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        invocation_count,
        semantic_ready,
        mutation_capabilities,
        None,
        None,
        None,
        None,
        0,
        true,
        scripted_results,
        ScriptedRuntimeAuthority::PublishExact,
    )
}

pub(crate) fn spawn_published_semantic_read_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    spawn_published_semantic_read_backend_for_reads(home, config_home, workspace, socket_path, 1)
}

pub(crate) fn spawn_published_semantic_read_backend_for_reads(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    read_count: usize,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    spawn_scripted_backend_with_additional_runtime_status_requests(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        read_count,
        true,
        vec![],
        None,
        None,
        None,
        None,
        read_count,
        false,
        vec![],
        ScriptedRuntimeAuthority::PublishExact,
    )
}

pub(crate) fn spawn_ready_scripted_indexer_backend_for_invocations(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    invocation_count: usize,
    scripted_results: Vec<(&'static str, serde_json::Value)>,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    spawn_scripted_backend_with_additional_runtime_status_requests(
        home,
        config_home,
        workspace,
        socket_path,
        "indexer",
        invocation_count,
        true,
        vec![],
        None,
        None,
        None,
        None,
        invocation_count.saturating_mul(3),
        false,
        scripted_results,
        ScriptedRuntimeAuthority::PublishExact,
    )
}
