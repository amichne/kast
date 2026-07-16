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
