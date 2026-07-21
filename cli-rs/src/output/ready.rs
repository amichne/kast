pub fn print_ready(result: &SelfDoctorResult) -> Result<()> {
    print_self_check("Kast ready", result)
}

fn print_self_check(title: &str, result: &SelfDoctorResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# {title}");
    mdln!(document);
    mdln!(
        document,
        "- Target: `{}`",
        ready_target_label(result.target)
    );
    mdln!(document, "- Healthy: {}", yes_no(result.ok));
    mdln!(document, "- Installed: {}", yes_no(result.installed));
    mdln!(
        document,
        "- Install authority: `{}`",
        install_authority_label(result.install_authority)
    );
    mdln!(
        document,
        "- Config valid: {}",
        yes_no(result.configuration.valid)
    );
    mdln!(document, "- Config path: `{}`", result.config_path);
    mdln!(document, "- Install manifest: `{}`", result.manifest_path);
    mdln!(
        document,
        "- Canonical directory: `{}`",
        result.canonical_directory.root
    );
    mdln!(
        document,
        "- Running binary: `{}`",
        result.binary.running_binary
    );
    mdln!(
        document,
        "- Configured binary: `{}`",
        result.binary.configured_binary
    );
    mdln!(
        document,
        "- Minimum backend version: `{}`",
        result.minimum_backend_version
    );
    if let Some(receipt) = &result.homebrew_install {
        mdln!(document);
        mdln!(document, "## macOS Homebrew authority");
        mdln!(
            document,
            "- Receipt: `{}`",
            compact_path_for_output(
                &crate::install::default_macos_homebrew_receipt_path().display().to_string()
            )
        );
        mdln!(
            document,
            "- CLI: `{}` (`{}`)",
            compact_path_for_output(&receipt.cli.binary.display().to_string()),
            receipt.cli.version
        );
    }
    if let Some(shadow) = &result.legacy_shadow {
        mdln!(document);
        mdln!(document, "## Legacy PATH shadow");
        mdln!(document, "- Path: `{}`", compact_path_for_output(&shadow.path));
        mdln!(document, "- Kast-managed: {}", yes_no(shadow.managed));
        mdln!(document, "- Writable: {}", yes_no(shadow.writable));
        if let Some(command) = &shadow.cleanup_command {
            mdln!(document, "- Safe cleanup: `{command}`");
        }
    }
    if let Some(environment) = &result.agent_environment {
        print_agent_environment(&mut document, environment);
    }
    print_path_resolution(&mut document, &result.path_resolution);
    print_messages(&mut document, "Issues", &result.issues);
    print_warnings(&mut document, &result.warnings);
    if let Some(repair) = &result.repair {
        mdln!(document);
        mdln!(document, "## Repair");
        mdln!(document, "- Applied changes: {}", yes_no(repair.applied));
        mdln!(document, "- Actions: {}", repair.actions.len());
        print_messages(&mut document, "Backups", &repair.backups);
        print_warnings(&mut document, &repair.warnings);
    }
    if let Some(install) = &result.install {
        mdln!(document);
        mdln!(document, "## Installed versions");
        mdln!(document, "- CLI: `{}`", value_or_dash(&install.version));
        mdln!(
            document,
            "- Active: `{}`",
            value_or_dash(&install.active_version)
        );
        if !install.components.is_empty() {
            mdln!(document, "- Components: {}", install.components.join(", "));
        }
        for backend in &install.backends {
            mdln!(
                document,
                "- Backend {}: `{}` runtime `{}`",
                backend.name,
                backend.version,
                backend.runtime_libs_dir
            );
        }
        for repo in &install.repos {
            mdln!(
                document,
                "- Copilot repo `{}`: `{}`",
                repo.path,
                repo.copilot_package_version
            );
        }
    }
    if result.ok {
        mdln!(document);
        mdln!(document, "No blocking issues were found.");
    }
    print_markdown(&document.into_string())
}

fn print_agent_environment(
    document: &mut MarkdownDocument,
    environment: &crate::self_mgmt::DoctorAgentEnvironmentDiagnostic,
) {
    mdln!(document);
    mdln!(document, "## Effective agent environment");
    mdln!(document, "- Compatible: {}", yes_no(environment.ok));
    mdln!(
        document,
        "- Authority: `{}`",
        install_authority_label(environment.install_authority)
    );
    mdln!(
        document,
        "- Binary: `{}` version `{}` revision `{}` (source `{}`)",
        compact_path_for_output(&environment.binary.path),
        environment.binary.version,
        environment.binary.revision,
        compact_path_for_output(&environment.binary.source_path)
    );
    mdln!(
        document,
        "- Backend: `{}` kind `{}` version `{}` revision `{}` (source `{}`)",
        agent_resource_state_label(environment.backend.state),
        environment.backend.kind.as_deref().unwrap_or("-"),
        environment.backend.version.as_deref().unwrap_or("-"),
        environment.backend.revision.as_deref().unwrap_or("-"),
        environment
            .backend
            .source_path
            .as_deref()
            .map(compact_path_for_output)
            .unwrap_or_else(|| "-".to_string())
    );
}

fn agent_resource_state_label(
    state: crate::self_mgmt::AgentResourceState,
) -> &'static str {
    state.as_str()
}

fn install_authority_label(authority: crate::self_mgmt::InstallAuthority) -> &'static str {
    match authority {
        crate::self_mgmt::InstallAuthority::Machine => "machine",
        crate::self_mgmt::InstallAuthority::MacosHomebrew => "macos-homebrew",
        crate::self_mgmt::InstallAuthority::ManagedLocal => "managed-local",
        crate::self_mgmt::InstallAuthority::Missing => "missing",
    }
}

fn print_path_resolution(document: &mut MarkdownDocument, report: &PathResolutionReport) {
    mdln!(document);
    mdln!(document, "## Path resolution");
    mdln!(document, "- Root: `{}`", compact_path_for_output(&report.root));
    if !report.config_files.is_empty() {
        mdln!(document);
        mdln!(document, "Config files:");
        for config_file in &report.config_files {
            mdln!(
                document,
                "- {}: {} `{}`",
                config_file.scope,
                exists_label(config_file.exists),
                compact_path_for_output(&config_file.path)
            );
        }
    }
    if !report.entries.is_empty() {
        mdln!(document);
        mdln!(document, "Path entries:");
        for entry in &report.entries {
            let from = entry
                .derived_from
                .as_ref()
                .map(|value| format!(" from `{value}`"))
                .unwrap_or_default();
            mdln!(
                document,
                "- `{}`: {} {} via `{}`{} -> `{}`",
                entry.key,
                exists_label(entry.exists),
                entry.expected_kind,
                entry.source,
                from,
                compact_path_for_output(&entry.value)
            );
        }
    }
    print_messages(document, "Path warnings", &report.warnings);
}
