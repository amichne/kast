use crate::backend::BackendInstallResult;
use crate::cli::OutputFormat;
use crate::error::{CliError, Result};
use crate::install::{
    ArchiveInstallResult, InstallAffectedResult, InstallCopilotExtensionResult,
    InstallIdeaPluginResult, InstallResult, InstallShellResult, InstallSkillResult, SetupResult,
};
use crate::runtime::{
    DaemonStopResult, RuntimeCandidateStatus, RuntimeState, WorkspaceEnsureResult,
    WorkspaceStatusResult,
};
use crate::self_mgmt::SelfDoctorResult;
use serde::Serialize;
use serde_json::Value;
use std::io;

pub fn print_json(value: &impl Serialize) -> Result<()> {
    serde_json::to_writer_pretty(io::stdout(), value)?;
    println!();
    Ok(())
}

pub fn print_error(error: &CliError, output: OutputFormat) -> Result<()> {
    if output == OutputFormat::Json {
        serde_json::to_writer_pretty(io::stderr(), &error.to_response())?;
        eprintln!();
        return Ok(());
    }

    eprintln!("# Kast error");
    eprintln!();
    eprintln!("- Code: {}", error.code);
    eprintln!("- Message: {}", error.message);
    if !error.details.is_empty() {
        eprintln!();
        eprintln!("## Details");
        for (key, value) in &error.details {
            eprintln!("- {key}: `{value}`");
        }
    }
    eprintln!();
    eprintln!("Use `kast --output json ...` for the machine-readable error payload.");
    Ok(())
}

pub fn print_install_result(result: &InstallResult) -> Result<()> {
    match result {
        InstallResult::Skill(result) => print_skill_install(result),
        InstallResult::Copilot(result) => print_copilot_install("Kast Copilot install", result),
        InstallResult::IdeaPlugin(result) => print_idea_plugin_install(result),
        InstallResult::Shell(result) => print_shell_install(result),
        InstallResult::Affected(result) => print_affected_install(result),
        InstallResult::Headless(result) => print_backend_install(result),
        InstallResult::Archive(result) => print_archive_install(result),
    }
}

pub fn print_workspace_status(result: &WorkspaceStatusResult) -> Result<()> {
    println!("# Kast status");
    println!();
    println!("- Workspace: `{}`", result.workspace_root);
    println!("- Descriptor directory: `{}`", result.descriptor_directory);
    println!("- Candidates: {}", result.candidates.len());
    println!();
    if let Some(selected) = &result.selected {
        print_candidate("Selected runtime", selected);
    } else {
        println!("No runtime candidates were found.");
        println!();
        println!("## Next steps");
        println!("- Start a backend: `kast up`");
        println!(
            "- For headless use, install the Linux headless tarball; for macOS IDE use, install Kast through Homebrew."
        );
    }
    if result.selected.is_some() && result.candidates.len() > 1 {
        println!();
        println!("## Other candidates");
        for candidate in &result.candidates {
            println!(
                "- {} pid {} ready {}",
                candidate.descriptor.backend_name,
                candidate.descriptor.pid,
                yes_no(candidate.ready)
            );
        }
    }
    Ok(())
}

pub fn print_workspace_ensure(result: &WorkspaceEnsureResult) -> Result<()> {
    println!("# Kast up");
    println!();
    println!("- Workspace: `{}`", result.workspace_root);
    println!("- Started new daemon: {}", yes_no(result.started));
    if let Some(log_file) = &result.log_file {
        println!("- Log file: `{log_file}`");
    }
    if let Some(note) = &result.note {
        println!("- Note: {note}");
    }
    println!();
    print_candidate("Selected runtime", &result.selected);
    println!();
    println!("## Next steps");
    println!("- Check state again: `kast status`");
    println!("- Send analysis requests with `kast rpc`");
    Ok(())
}

pub fn print_stop_result(result: &DaemonStopResult) -> Result<()> {
    println!("# Kast stop");
    println!();
    println!("- Workspace: `{}`", result.workspace_root);
    println!("- Stopped daemon: {}", yes_no(result.stopped));
    println!("- Forced termination: {}", yes_no(result.forced));
    if let Some(pid) = result.pid {
        println!("- PID: {pid}");
    }
    if let Some(descriptor_path) = &result.descriptor_path {
        println!("- Descriptor: `{descriptor_path}`");
    }
    if !result.stopped {
        println!();
        println!("No matching daemon was running.");
    }
    Ok(())
}

pub fn print_capabilities(value: &Value) -> Result<()> {
    println!("# Kast capabilities");
    println!();
    if let Some(methods) = value.get("methods").and_then(Value::as_array) {
        println!("- Methods advertised: {}", methods.len());
        for method in methods.iter().filter_map(Value::as_str).take(30) {
            println!("- `{method}`");
        }
        if methods.len() > 30 {
            println!("- ... {} more", methods.len() - 30);
        }
    } else if let Some(object) = value.as_object() {
        println!(
            "- Top-level fields: {}",
            object.keys().cloned().collect::<Vec<_>>().join(", ")
        );
    } else {
        println!("- Capabilities payload is available.");
    }
    println!();
    println!("Use `kast --output json capabilities ...` for the full payload.");
    Ok(())
}

pub fn print_doctor(result: &SelfDoctorResult) -> Result<()> {
    println!("# Kast doctor");
    println!();
    println!("- Healthy: {}", yes_no(result.ok));
    println!("- Installed: {}", yes_no(result.installed));
    println!("- Config path: `{}`", result.config_path);
    println!(
        "- Minimum backend version: `{}`",
        result.minimum_backend_version
    );
    print_messages("Issues", &result.issues);
    print_warnings(&result.warnings);
    if let Some(install) = &result.install {
        println!();
        println!("## Installed versions");
        println!("- CLI: `{}`", value_or_dash(&install.version));
        if !install.components.is_empty() {
            println!("- Components: {}", install.components.join(", "));
        }
        for backend in &install.backends {
            println!(
                "- Backend {}: `{}` runtime `{}`",
                backend.name, backend.version, backend.runtime_libs_dir
            );
        }
        for repo in &install.repos {
            println!(
                "- Copilot repo `{}`: `{}`",
                repo.path, repo.copilot_extension_version
            );
        }
    }
    if result.ok {
        println!();
        println!("No blocking issues were found.");
    }
    Ok(())
}

pub fn print_setup(result: &SetupResult) -> Result<()> {
    println!("# Kast setup");
    println!();
    if let Some(repair) = &result.repair {
        println!("- Repair applied: {}", yes_no(repair.applied));
    }
    if let Some(headless) = &result.headless {
        println!("- Headless backend: `{}`", headless.version);
    }
    if let Some(shell) = &result.shell {
        println!(
            "- Shell integration: `{}` profile `{}`",
            shell.shell, shell.profile
        );
    }
    if let Some(skill) = &result.skill {
        println!("- Skill: `{}`", skill.installed_at);
    }
    if let Some(copilot) = &result.copilot {
        println!("- Copilot extension: `{}`", copilot.installed_at);
    }
    if let Some(plugin) = &result.idea_plugin {
        println!("- IDEA plugin action: `{}`", plugin.brew_action);
    }
    print_warnings(&result.warnings);
    Ok(())
}

fn print_backend_install(result: &BackendInstallResult) -> Result<()> {
    println!("# Kast backend install");
    println!();
    println!("- Backend: `{}`", result.backend_name);
    println!("- Version: `{}`", result.version);
    println!("- Installed directory: `{}`", result.install_dir);
    println!("- Runtime libraries: `{}`", result.runtime_libs_dir);
    print_optional("IDEA home", result.idea_home.as_deref());
    println!("- Source archive: `{}`", result.source_archive);
    println!("- Downloaded release asset: {}", yes_no(result.downloaded));
    println!("- Reused existing install: {}", yes_no(result.skipped));
    println!();
    println!("## Next steps");
    println!("- Start it: `kast up --backend={}`", result.backend_name);
    println!("- Inspect it: `kast status`");
    Ok(())
}

fn print_skill_install(result: &InstallSkillResult) -> Result<()> {
    println!("# Kast skill install");
    println!();
    println!("- Installed at: `{}`", result.installed_at);
    println!("- Version: `{}`", result.version);
    println!("- Reused existing install: {}", yes_no(result.skipped));
    println!();
    println!("## Next steps");
    println!(
        "- Read the installed quickstart: `{}/references/quickstart.md`",
        result.installed_at
    );
    Ok(())
}

fn print_copilot_install(title: &str, result: &InstallCopilotExtensionResult) -> Result<()> {
    println!("# {title}");
    println!();
    println!("- Extension path: `{}`", result.installed_at);
    println!("- Version: `{}`", result.version);
    println!("- Reused existing install: {}", yes_no(result.skipped));
    print_warnings(&result.warnings);
    Ok(())
}

fn print_idea_plugin_install(result: &InstallIdeaPluginResult) -> Result<()> {
    println!("# Kast IDEA plugin install");
    println!();
    println!("- Cask token: `{}`", result.cask_token);
    println!("- Homebrew action: `{}`", result.brew_action);
    println!("- Dry run: {}", yes_no(result.dry_run));
    if !result.brew_command.is_empty() {
        println!("- Brew command: `{}`", result.brew_command.join(" "));
    }
    print_optional(
        "JetBrains config root",
        result.jetbrains_config_root.as_deref(),
    );
    if !result.plugin_directories.is_empty() {
        println!();
        println!("## Plugin directories");
        for path in &result.plugin_directories {
            println!("- `{path}`");
        }
    }
    print_warnings(&result.warnings);
    Ok(())
}

fn print_shell_install(result: &InstallShellResult) -> Result<()> {
    println!("# Kast shell install");
    println!();
    println!("- Shell: `{}`", result.shell);
    println!("- Command name: `{}`", result.command_name);
    println!("- Bin directory: `{}`", result.bin_dir);
    println!("- Config home: `{}`", result.config_home);
    println!("- Source file: `{}`", result.source_file);
    println!("- Profile: `{}`", result.profile);
    println!("- Profile updated: {}", yes_no(result.profile_updated));
    println!("- Dry run: {}", yes_no(result.dry_run));
    println!();
    println!("## Next steps");
    println!("- Open a fresh shell or run `{}`.", result.source_line);
    Ok(())
}

fn print_archive_install(result: &ArchiveInstallResult) -> Result<()> {
    println!("# Kast install");
    println!();
    println!("- Installed at: `{}`", result.installed_at);
    println!("- Instance: `{}`", result.instance);
    println!("- Reused existing install: {}", yes_no(result.skipped));
    Ok(())
}

fn print_affected_install(result: &InstallAffectedResult) -> Result<()> {
    println!("# Kast affected install repair");
    println!();
    println!("- Applied changes: {}", yes_no(result.applied));
    println!("- Config path: `{}`", result.config_path);
    if !result.applied {
        println!("- Default: no files were changed");
        println!("- Apply command: `{}`", result.apply_command);
    }
    if result.actions.is_empty() {
        println!();
        println!("No affected installs or stale paths were found.");
    } else {
        println!();
        println!("## Actions");
        for action in &result.actions {
            println!("- `{}` `{}`: {}", action.status, action.kind, action.target);
            println!("  {}", action.message);
            if let Some(command) = &action.command {
                println!("  Command: `{command}`");
            }
        }
    }
    print_messages("Backups", &result.backups);
    print_warnings(&result.warnings);
    Ok(())
}

fn print_candidate(title: &str, candidate: &RuntimeCandidateStatus) {
    println!("## {title}");
    println!("- Backend: `{}`", candidate.descriptor.backend_name);
    println!(
        "- Backend version: `{}`",
        candidate.descriptor.backend_version
    );
    println!("- PID: {}", candidate.descriptor.pid);
    println!("- PID alive: {}", yes_no(candidate.pid_alive));
    println!("- Reachable: {}", yes_no(candidate.reachable));
    println!("- Ready: {}", yes_no(candidate.ready));
    println!("- Socket: `{}`", candidate.descriptor.socket_path);
    if let Some(status) = &candidate.runtime_status {
        println!("- Runtime state: `{}`", runtime_state(status.state.clone()));
        println!("- Active: {}", yes_no(status.active));
        println!("- Healthy: {}", yes_no(status.healthy));
        println!("- Indexing: {}", yes_no(status.indexing));
        if !status.source_module_names.is_empty() {
            println!(
                "- Source modules: {}",
                status.source_module_names.join(", ")
            );
        }
        if let Some(message) = &status.message {
            println!("- Message: {message}");
        }
        print_warnings(&status.warnings);
    }
    if let Some(error_message) = &candidate.error_message {
        println!("- Error: {error_message}");
    }
}

fn runtime_state(state: RuntimeState) -> &'static str {
    match state {
        RuntimeState::Starting => "STARTING",
        RuntimeState::Indexing => "INDEXING",
        RuntimeState::Ready => "READY",
        RuntimeState::Degraded => "DEGRADED",
    }
}

fn print_optional(label: &str, value: Option<&str>) {
    if let Some(value) = value.filter(|value| !value.trim().is_empty()) {
        println!("- {label}: `{value}`");
    }
}

fn print_warnings(warnings: &[String]) {
    print_messages("Warnings", warnings);
}

fn print_messages(title: &str, messages: &[String]) {
    if messages.is_empty() {
        return;
    }
    println!();
    println!("## {title}");
    for message in messages {
        println!("- {message}");
    }
}

fn yes_no(value: bool) -> &'static str {
    if value { "yes" } else { "no" }
}

fn value_or_dash(value: &str) -> &str {
    if value.trim().is_empty() { "-" } else { value }
}
