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
        "- Read the installed quickstart: `{}/references/quickstart.md`",
        result.installed_at
    );
    print_markdown(&document.into_string())
}

fn print_instructions_install(result: &InstallInstructionsResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast instructions install");
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
        "- Read the installed guide: `{}/README.md`",
        result.installed_at
    );
    print_markdown(&document.into_string())
}

fn print_copilot_install(title: &str, result: &InstallCopilotPackageResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# {title}");
    mdln!(document);
    mdln!(document, "- Package path: `{}`", result.installed_at);
    mdln!(document, "- Version: `{}`", result.version);
    mdln!(
        document,
        "- Reused existing install: {}",
        yes_no(result.skipped)
    );
    if result.git_exclude.attempted {
        mdln!(
            document,
            "- Git info/exclude updated: {}",
            yes_no(result.git_exclude.updated)
        );
        print_optional(
            &mut document,
            "Git info/exclude",
            result.git_exclude.exclude_file.as_deref(),
        );
    } else if let Some(reason) = &result.git_exclude.reason {
        mdln!(document, "- Git info/exclude: {reason}");
    }
    print_warnings(&mut document, &result.warnings);
    print_markdown(&document.into_string())
}

fn print_idea_plugin_install(result: &InstallIdeaPluginResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    print_idea_plugin_install_with_table_style(&mut document, result, stdout_table_render_style());
    print_markdown(&document.into_string())
}

fn print_idea_plugin_install_with_table_style(
    document: &mut MarkdownDocument,
    result: &InstallIdeaPluginResult,
    table_style: TableRenderStyle,
) {
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
        IdeaPluginInstallSummaryRow {
            item: "Cask token".to_string(),
            value: result.cask_token.clone(),
        },
        IdeaPluginInstallSummaryRow {
            item: "Plugin version".to_string(),
            value: result.plugin_version.clone(),
        },
        IdeaPluginInstallSummaryRow {
            item: "Homebrew action".to_string(),
            value: result.brew_action.clone(),
        },
        IdeaPluginInstallSummaryRow {
            item: "Download cache".to_string(),
            value: result.download_cache.clone(),
        },
        IdeaPluginInstallSummaryRow {
            item: "Downloaded".to_string(),
            value: format_bytes_for_output(result.downloaded_bytes),
        },
        IdeaPluginInstallSummaryRow {
            item: "Dry run".to_string(),
            value: yes_no(result.dry_run).to_string(),
        },
        IdeaPluginInstallSummaryRow {
            item: "Homebrew prefix".to_string(),
            value: result.brew_prefix.clone(),
        },
        IdeaPluginInstallSummaryRow {
            item: "Formula prefix".to_string(),
            value: result.formula_prefix.clone(),
        },
        IdeaPluginInstallSummaryRow {
            item: "Running CLI".to_string(),
            value: result.cli_path.clone(),
        },
    ];
    if !result.brew_command.is_empty() {
        rows.push(IdeaPluginInstallSummaryRow {
            item: "Brew command".to_string(),
            value: result.brew_command.join(" "),
        });
    }
    if let Some(jetbrains_config_root) = &result.jetbrains_config_root {
        rows.push(IdeaPluginInstallSummaryRow {
            item: "JetBrains config root".to_string(),
            value: jetbrains_config_root.clone(),
        });
    }
    document.block(&render_table_with_style(rows, table_style));
    if !result.plugin_directories.is_empty() {
        mdln!(document);
        mdln!(document, "## JetBrains destinations");
        document.block(&render_table_with_style(
            result
                .plugin_directories
                .iter()
                .map(|path| IdeaPluginDirectoryRow { path: path.clone() }),
            table_style,
        ));
    }
    print_warnings(document, &result.warnings);
    mdln!(document);
    mdln!(document, "## Next steps");
    if result.dry_run {
        mdln!(
            document,
            "- Run `kast machine plugin` without `--dry-run` to install the Homebrew cask and link JetBrains profiles."
        );
    } else {
        mdln!(
            document,
            "- Restart any open IntelliJ IDEA or Android Studio windows so JetBrains reloads the linked plugin."
        );
        mdln!(
            document,
            "- Reopen the project, then run `kast runtime status --backend idea` if the IDE backend still is not available."
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
