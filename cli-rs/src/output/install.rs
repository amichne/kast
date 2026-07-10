pub fn print_paths(result: &PathResolutionReport) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast paths");
    print_path_resolution(&mut document, result);
    print_markdown(&document.into_string())
}

pub fn print_install_result(result: &InstallResult) -> Result<()> {
    match result {
        InstallResult::ActivateBundle(result) => print_activate_bundle_install(result),
        InstallResult::AgentGuidance(result) => print_agent_guidance_setup_result(result),
        InstallResult::IdeaPlugin(result) => print_idea_plugin_install(result),
        InstallResult::Shell(result) => print_shell_install(result),
    }
}

pub fn print_agent_guidance_setup_plan(result: &AgentGuidanceSetupPlan) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast setup plan");
    mdln!(document);
    mdln!(document, "- Skill target: `{}`", result.skill_target);
    mdln!(
        document,
        "- Would run: `{}`",
        result.install_command.join(" ")
    );
    mdln!(document, "- Force: {}", yes_no(result.force));
    mdln!(document, "- Dry run: {}", yes_no(result.dry_run));
    if !result.agents_md_targets.is_empty() {
        mdln!(document);
        mdln!(document, "## Agent guidance targets");
        for target in &result.agents_md_targets {
            mdln!(
                document,
                "- `{}` exists {} will create {} will modify {}: {}",
                target.path,
                yes_no(target.exists),
                yes_no(target.will_create),
                yes_no(target.will_modify),
                target.reason
            );
        }
    }
    print_markdown(&document.into_string())
}

pub fn print_agent_guidance_setup_result(result: &AgentGuidanceSetupResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast setup");
    mdln!(document);
    mdln!(document, "- Skill target: `{}`", result.skill.installed_at);
    mdln!(
        document,
        "- Reused existing skill install: {}",
        yes_no(result.skill.skipped)
    );
    mdln!(document, "- Setup skipped: {}", yes_no(result.skipped));
    if !result.agents_md_targets.is_empty() {
        mdln!(document);
        mdln!(document, "## Agent guidance targets");
        for target in &result.agents_md_targets {
            mdln!(
                document,
                "- `{}` created {} updated {} skipped {}",
                target.path,
                yes_no(target.created),
                yes_no(target.updated),
                yes_no(target.skipped)
            );
        }
    }
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
        (
            "Homebrew receipt",
            compact_path_for_output(&result.homebrew_receipt),
        ),
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
            "- Open IntelliJ IDEA or Android Studio so JetBrains loads the linked plugin."
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
        "- Open IntelliJ IDEA or Android Studio after updating the plugin."
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
