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

fn print_path_resolution(document: &mut MarkdownDocument, report: &PathResolutionReport) {
    print_path_resolution_with_table_style(document, report, stdout_table_render_style());
}

fn print_path_resolution_with_table_style(
    document: &mut MarkdownDocument,
    report: &PathResolutionReport,
    table_style: TableRenderStyle,
) {
    mdln!(document);
    mdln!(document, "## Path resolution");
    mdln!(document, "- Root: `{}`", report.root);
    if !report.config_files.is_empty() {
        mdln!(document);
        mdln!(document, "Config files:");
        document.block(&render_table_with_style(
            report
                .config_files
                .iter()
                .map(|config_file| PathConfigFileRow {
                    scope: config_file.scope.clone(),
                    state: exists_label(config_file.exists).to_string(),
                    path: config_file.path.clone(),
                }),
            table_style,
        ));
    }
    if !report.entries.is_empty() {
        mdln!(document);
        mdln!(document, "Path entries:");
        document.block(&render_table_with_style(
            report.entries.iter().map(|entry| PathEntryRow {
                key: entry.key.clone(),
                source: entry.source.to_string(),
                kind: entry.expected_kind.clone(),
                from: entry
                    .derived_from
                    .clone()
                    .unwrap_or_else(|| "-".to_string()),
                state: exists_label(entry.exists).to_string(),
                value: entry.value.clone(),
            }),
            table_style,
        ));
    }
    print_messages(document, "Path warnings", &report.warnings);
}
