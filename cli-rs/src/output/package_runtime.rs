pub fn print_package_result(result: &PackageResult) -> Result<()> {
    match result {
        PackageResult::UbuntuDebianBundle(result) => print_ubuntu_debian_bundle_package(result),
    }
}

pub fn print_workspace_status(result: &WorkspaceStatusResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast status");
    mdln!(document);
    mdln!(document, "- Workspace: `{}`", result.workspace_root);
    mdln!(
        document,
        "- Descriptor directory: `{}`",
        result.descriptor_directory
    );
    mdln!(document, "- Candidates: {}", result.candidates.len());
    print_path_resolution(&mut document, &result.path_resolution);
    mdln!(document);
    if let Some(selected) = &result.selected {
        print_candidate(&mut document, "Selected runtime", selected);
    } else {
        mdln!(document, "No runtime candidates were found.");
        mdln!(document);
        mdln!(document, "## Next steps");
        mdln!(document, "- Start a backend: `kast setup`");
        mdln!(
            document,
            "- For headless use, install the Linux headless tarball; for macOS IDE use, install Kast through Homebrew."
        );
    }
    if result.selected.is_some() && result.candidates.len() > 1 {
        mdln!(document);
        mdln!(document, "## Other candidates");
        for candidate in &result.candidates {
            mdln!(
                document,
                "- {} pid {} ready {}",
                candidate.descriptor.backend_name,
                candidate.descriptor.pid,
                yes_no(candidate.ready)
            );
        }
    }
    print_markdown(&document.into_string())
}

fn ready_target_label(target: ReadyTarget) -> &'static str {
    match target {
        ReadyTarget::Agent => "agent",
        ReadyTarget::Kotlin => "kotlin",
        ReadyTarget::Release => "release",
        ReadyTarget::Machine => "machine",
    }
}

pub fn print_workspace_ensure(result: &WorkspaceEnsureResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast up");
    mdln!(document);
    mdln!(document, "- Workspace: `{}`", result.workspace_root);
    mdln!(document, "- Started new daemon: {}", yes_no(result.started));
    if let Some(log_file) = &result.log_file {
        mdln!(document, "- Log file: `{log_file}`");
    }
    if let Some(note) = &result.note {
        mdln!(document, "- Note: {note}");
    }
    print_path_resolution(&mut document, &result.path_resolution);
    mdln!(document);
    print_candidate(&mut document, "Selected runtime", &result.selected);
    mdln!(document);
    mdln!(document, "## Next steps");
    mdln!(document, "- Check state again: `kast status`");
    mdln!(document, "- Check agent health: `kast agent verify`");
    print_markdown(&document.into_string())
}

pub fn print_stop_result(result: &DaemonStopResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    let lifecycle_count = result
        .candidates
        .iter()
        .filter(|candidate| candidate.lifecycle_accepted)
        .count();
    mdln!(document, "# Kast stop");
    mdln!(document);
    mdln!(document, "- Workspace: `{}`", result.workspace_root);
    mdln!(document, "- Backend: `{}`", result.backend_name);
    mdln!(document, "- Stopped runtime: {}", yes_no(result.stopped));
    if lifecycle_count > 0 {
        mdln!(document, "- Host lifecycle requests: {lifecycle_count}");
    }
    mdln!(
        document,
        "- Runtime records handled: {}",
        result.stopped_count
    );
    mdln!(document, "- Forced termination: {}", yes_no(result.forced));
    if let Some(pid) = result.pid {
        mdln!(document, "- PID: {pid}");
    }
    if let Some(descriptor_path) = &result.descriptor_path {
        mdln!(document, "- Descriptor: `{descriptor_path}`");
    }
    print_warnings(&mut document, &result.warnings);
    if !result.stopped {
        mdln!(document);
        mdln!(document, "No matching daemon was running.");
    }
    print_markdown(&document.into_string())
}

pub fn print_restart_result(result: &WorkspaceRestartResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    let lifecycle_count = result
        .stop
        .candidates
        .iter()
        .filter(|candidate| candidate.lifecycle_accepted)
        .count();
    mdln!(document, "# Kast restart");
    mdln!(document);
    mdln!(document, "- Workspace: `{}`", result.workspace_root);
    mdln!(document, "- Backend: `{}`", result.backend_name);
    mdln!(
        document,
        "- Runtime records handled: {}",
        result.stop.stopped_count
    );
    if lifecycle_count > 0 {
        mdln!(document, "- Host lifecycle requests: {lifecycle_count}");
    }
    mdln!(
        document,
        "- Started new daemon: {}",
        yes_no(result.ensure.started)
    );
    if let Some(log_file) = &result.ensure.log_file {
        mdln!(document, "- Log file: `{log_file}`");
    }
    if let Some(note) = &result.ensure.note {
        mdln!(document, "- Note: {note}");
    }
    print_warnings(&mut document, &result.stop.warnings);
    mdln!(document);
    print_candidate(&mut document, "Selected runtime", &result.ensure.selected);
    print_markdown(&document.into_string())
}

pub fn print_capabilities(value: &Value) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast capabilities");
    mdln!(document);
    if let Some(methods) = value.get("methods").and_then(Value::as_array) {
        mdln!(document, "- Methods advertised: {}", methods.len());
        for method in methods.iter().filter_map(Value::as_str).take(30) {
            mdln!(document, "- `{method}`");
        }
        if methods.len() > 30 {
            mdln!(document, "- ... {} more", methods.len() - 30);
        }
    } else if let Some(object) = value.as_object() {
        mdln!(
            document,
            "- Top-level fields: {}",
            object.keys().cloned().collect::<Vec<_>>().join(", ")
        );
    } else {
        mdln!(document, "- Capabilities payload is available.");
    }
    mdln!(document);
    mdln!(
        document,
        "Use `kast --output json capabilities ...` for the full payload."
    );
    print_markdown(&document.into_string())
}
