pub fn print_paths(result: &PathResolutionReport) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast paths");
    print_path_resolution(&mut document, result);
    print_markdown(&document.into_string())
}

fn print_skill_install(result: &InstallSkillResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast skill install");
    mdln!(document);
    mdln!(document, "- Installed at: `{}`", result.installed_at);
    mdln!(document, "- Version: `{}`", result.version);
    mdln!(
        document,
        "- Reused existing install: {}",
        yes_no(result.skipped)
    );
    mdln!(document);
    mdln!(document, "## Next steps");
    mdln!(
        document,
        "- Read the installed skill entrypoint: `{}/SKILL.md`",
        result.installed_at
    );
    mdln!(document, "- Read command help with: `kast help agent`");
    print_markdown(&document.into_string())
}

fn print_idea_plugin_install(result: &InstallIdeaPluginResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    print_idea_plugin_install_summary(&mut document, result);
    print_markdown(&document.into_string())
}

fn print_idea_plugin_install_summary(document: &mut MarkdownDocument, result: &InstallIdeaPluginResult) {
    mdln!(document, "# Kast IDEA plugin install");
    mdln!(document);
    mdln!(
        document,
        "- Status: {}",
        if result.dry_run { "planned" } else { "applied" }
    );
    mdln!(document);
    mdln!(document, "## Install summary");
    let mut rows = vec![
        ("Cask token", result.cask_token.clone()),
        ("Plugin version", result.plugin_version.clone()),
        ("Homebrew action", result.brew_action.clone()),
        ("Download cache", compact_path_for_output(&result.download_cache)),
        ("Downloaded", format_bytes_for_output(result.downloaded_bytes)),
        ("Dry run", yes_no(result.dry_run).to_string()),
        ("Homebrew prefix", compact_path_for_output(&result.brew_prefix)),
        ("Formula prefix", compact_path_for_output(&result.formula_prefix)),
        ("Running CLI", compact_path_for_output(&result.cli_path)),
    ];
    if !result.brew_command.is_empty() {
        rows.push(("Brew command", result.brew_command.join(" ")));
    }
    if let Some(jetbrains_config_root) = &result.jetbrains_config_root {
        rows.push((
            "JetBrains config root",
            compact_path_for_output(jetbrains_config_root),
        ));
    }
    rows.push((
        "Developer default backend",
        format!("{:?}", result.developer_defaults.default_backend).to_lowercase(),
    ));
    rows.push((
        "Developer config",
        compact_path_for_output(&result.developer_defaults.config_path),
    ));
    for (item, value) in rows {
        mdln!(document, "- {item}: `{value}`");
    }
    if !result.plugin_directories.is_empty() {
        mdln!(document);
        mdln!(document, "## JetBrains destinations");
        for path in &result.plugin_directories {
            mdln!(document, "- `{}`", compact_path_for_output(path));
        }
    }
    print_warnings(document, &result.warnings);
    mdln!(document);
    mdln!(document, "## Next steps");
    if result.dry_run {
        mdln!(
            document,
            "- Run `kast developer machine plugin` without `--dry-run` to install the Homebrew cask and link JetBrains profiles."
        );
    } else {
        mdln!(
            document,
            "- Restart any open IntelliJ IDEA or Android Studio windows so JetBrains reloads the linked plugin."
        );
        mdln!(
            document,
            "- Reopen the project, then run `kast status --backend idea` if the IDE backend still is not available."
        );
    }
}

fn print_shell_install(result: &InstallShellResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast shell install");
    mdln!(document);
    mdln!(document, "- Shell: `{}`", result.shell);
    mdln!(document, "- Command name: `{}`", result.command_name);
    mdln!(document, "- Bin directory: `{}`", result.bin_dir);
    mdln!(document, "- Config home: `{}`", result.config_home);
    mdln!(document, "- Source file: `{}`", result.source_file);
    mdln!(document, "- Profile: `{}`", result.profile);
    mdln!(
        document,
        "- Profile updated: {}",
        yes_no(result.profile_updated)
    );
    mdln!(document, "- Dry run: {}", yes_no(result.dry_run));
    mdln!(document);
    mdln!(document, "## Next steps");
    mdln!(
        document,
        "- Open a fresh shell or run `{}`.",
        result.source_line
    );
    print_markdown(&document.into_string())
}

pub fn print_developer_machine_defaults(result: &DeveloperMachineDefaultsResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast developer-machine defaults");
    mdln!(document);
    mdln!(
        document,
        "- Status: {}",
        if result.applied { "applied" } else { "planned" }
    );
    mdln!(document, "- Config path: `{}`", result.config_path);
    mdln!(document, "- Default backend: `idea`");
    mdln!(
        document,
        "- IDEA launch enabled: {}",
        yes_no(result.idea_launch_enabled)
    );
    mdln!(document, "- IDEA launch command: `{}`", result.idea_launch_command);
    mdln!(
        document,
        "- Require installed plugin: {}",
        yes_no(result.require_installed_plugin)
    );
    mdln!(document);
    mdln!(document, "## Next steps");
    mdln!(
        document,
        "- Restart any open IntelliJ IDEA or Android Studio windows after updating the plugin."
    );
    mdln!(
        document,
        "- Reopen the project, then run `kast status` to verify the IDEA backend."
    );
    print_markdown(&document.into_string())
}

fn print_activate_bundle_install(result: &ActivateBundleResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast bundle activation");
    mdln!(document);
    mdln!(document, "- Version: `{}`", result.version);
    mdln!(document, "- Platform: `{}`", result.platform);
    mdln!(document, "- Profile: `{}`", result.profile);
    mdln!(document, "- Installed at: `{}`", result.installed_at);
    mdln!(document, "- Install root: `{}`", result.install_root);
    mdln!(document, "- Current link: `{}`", result.current);
    mdln!(document, "- Manifest: `{}`", result.manifest);
    mdln!(document, "- Active binary: `{}`", result.active_binary);
    mdln!(document, "- Shim: `{}`", result.shim);
    mdln!(
        document,
        "- Reused existing install: {}",
        yes_no(result.skipped)
    );
    mdln!(document, "- Verify only: {}", yes_no(result.verify_only));
    print_markdown(&document.into_string())
}

fn print_ubuntu_debian_bundle_package(result: &UbuntuDebianBundlePackageResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast Ubuntu/Debian bundle package");
    mdln!(document);
    mdln!(document, "- Output: `{}`", result.output);
    mdln!(document, "- SHA-256 sidecar: `{}`", result.sha256_sidecar);
    mdln!(document, "- Version: `{}`", result.version);
    mdln!(document, "- Platform: `{}`", result.platform);
    mdln!(
        document,
        "- Bundle manifest schema: {}",
        result.manifest_schema_version
    );
    mdln!(document, "- CLI archive: `{}`", result.cli_archive);
    mdln!(document, "- Backend archive: `{}`", result.backend_archive);
    mdln!(document, "- Bundle SHA-256: `{}`", result.bundle_sha256);
    print_markdown(&document.into_string())
}
