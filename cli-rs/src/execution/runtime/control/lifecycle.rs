fn stop_admitted_runtime(admission: AdmittedIndexerRuntime) -> Result<DaemonStopResult> {
    admission.validate_current()?;
    let candidate = admission.candidate().clone();
    let descriptor_directory = admission.config().paths.descriptor_dir.clone();
    let workspace_root = admission.workspace_root().display().to_string();
    let backend_name = admission.backend_name().to_string();

    let mut terminated = false;
    let mut forced = false;
    if candidate.pid_alive {
        terminate_process(candidate.descriptor.pid, false);
        terminated = true;
        for _ in 0..20 {
            if !is_process_alive(candidate.descriptor.pid) {
                break;
            }
            thread::sleep(Duration::from_millis(250));
        }
        if is_process_alive(candidate.descriptor.pid) {
            terminate_process(candidate.descriptor.pid, true);
            forced = true;
        }
    }
    delete_descriptor(&descriptor_directory, &candidate.descriptor)?;
    let action = RuntimeStopAction {
        backend_name: candidate.descriptor.backend_name,
        descriptor_path: candidate.descriptor_path.clone(),
        pid: candidate.descriptor.pid,
        pid_alive: candidate.pid_alive,
        reachable: candidate.reachable,
        lifecycle_accepted: false,
        lifecycle_method: None,
        lifecycle_action: None,
        terminated,
        descriptor_deleted: true,
        forced,
        skipped_reason: None,
        schema_version: SCHEMA_VERSION,
    };
    Ok(DaemonStopResult {
        workspace_root,
        backend_name,
        stopped: true,
        stopped_count: 1,
        descriptor_path: Some(candidate.descriptor_path),
        pid: Some(candidate.descriptor.pid),
        forced,
        candidates: vec![action],
        warnings: vec![],
        schema_version: SCHEMA_VERSION,
    })
}
