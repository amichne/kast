use crate::cli::{AgentSetupHarness, OutputFormat, ReadyTarget};
use crate::config::PathResolutionReport;
use crate::error::{CliError, Result};
use crate::install::{
    ActivateBundleResult, AgentGuidanceSetupPlan, AgentGuidanceSetupResult, AgentSetupAutoPlan,
    AgentSetupSelectionSource, InstallCopilotPackageResult, InstallIdeaPluginResult,
    InstallInstructionsResult, InstallResult, InstallShellResult, InstallSkillResult,
};
use crate::orchestration::AgentUpResult;
use crate::package::{PackageResult, UbuntuDebianBundlePackageResult};
use crate::runtime::{
    DaemonStopResult, RuntimeCandidateStatus, RuntimeState, WorkspaceEnsureResult,
    WorkspaceRestartResult, WorkspaceStatusResult,
};
use crate::self_mgmt::SelfDoctorResult;
use glamour::{Renderer, Style as GlamourStyle};
use serde::Serialize;
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::{self, Write as FmtWrite};
use std::io::{self, IsTerminal, Write as IoWrite};
use tabled::{Table, Tabled, settings::Style as TableStyle};

const SOURCE_MODULE_DISPLAY_LIMIT: usize = 30;

macro_rules! mdln {
    ($document:expr) => {
        $document.blank()
    };
    ($document:expr, $($arg:tt)*) => {
        $document.line(format_args!($($arg)*))
    };
}

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

    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast error");
    mdln!(document);
    mdln!(document, "- Code: {}", error.code);
    mdln!(document, "- Message: {}", error.message);
    if !error.details.is_empty() {
        mdln!(document);
        mdln!(document, "## Details");
        for (key, value) in &error.details {
            mdln!(document, "- {key}: `{value}`");
        }
    }
    mdln!(document);
    mdln!(
        document,
        "Use `kast --output json ...` for the machine-readable error payload."
    );
    print_markdown_stderr(&document.into_string())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RenderStyle {
    Plain,
    Ansi,
}

#[derive(Default)]
struct MarkdownDocument {
    text: String,
}

impl MarkdownDocument {
    fn line(&mut self, args: fmt::Arguments<'_>) {
        self.text
            .write_fmt(args)
            .expect("writing to a String cannot fail");
        self.text.push('\n');
    }

    fn blank(&mut self) {
        self.text.push('\n');
    }

    fn block(&mut self, block: &str) {
        self.text.push_str(block);
        self.text.push('\n');
    }

    fn into_string(self) -> String {
        self.text
    }
}

pub(crate) fn print_markdown(markdown: &str) -> Result<()> {
    write_rendered_markdown(io::stdout().lock(), markdown, stdout_render_style())
}

fn print_markdown_stderr(markdown: &str) -> Result<()> {
    write_rendered_markdown(io::stderr().lock(), markdown, stderr_render_style())
}

fn write_rendered_markdown(
    mut writer: impl IoWrite,
    markdown: &str,
    style: RenderStyle,
) -> Result<()> {
    writer.write_all(render_markdown(markdown, style).as_bytes())?;
    Ok(())
}

fn stdout_render_style() -> RenderStyle {
    terminal_render_style(io::stdout().is_terminal())
}

fn stderr_render_style() -> RenderStyle {
    terminal_render_style(io::stderr().is_terminal())
}

fn terminal_render_style(is_terminal: bool) -> RenderStyle {
    let color_disabled = std::env::var_os("NO_COLOR").is_some()
        || std::env::var("TERM").is_ok_and(|terminal| terminal.eq_ignore_ascii_case("dumb"));
    if is_terminal && !color_disabled {
        RenderStyle::Ansi
    } else {
        RenderStyle::Plain
    }
}

fn render_markdown(markdown: &str, style: RenderStyle) -> String {
    match style {
        RenderStyle::Plain => render_plain_markdown(markdown),
        RenderStyle::Ansi => Renderer::new()
            .with_style(GlamourStyle::Dark)
            .render(markdown),
    }
}

fn render_plain_markdown(markdown: &str) -> String {
    let mut rendered = String::new();
    for line in markdown.lines() {
        if let Some(heading) = line.strip_prefix("# ") {
            push_heading(&mut rendered, heading, '=');
        } else if let Some(heading) = line.strip_prefix("## ") {
            push_heading(&mut rendered, heading, '-');
        } else if let Some(item) = line.strip_prefix("- ") {
            rendered.push_str("- ");
            rendered.push_str(&render_inline_plain(item));
            rendered.push('\n');
        } else {
            rendered.push_str(&render_inline_plain(line));
            rendered.push('\n');
        }
    }
    if markdown.is_empty() {
        rendered.push('\n');
    }
    rendered
}

fn push_heading(rendered: &mut String, heading: &str, underline: char) {
    rendered.push_str(heading);
    rendered.push('\n');
    rendered.push_str(&underline.to_string().repeat(heading.chars().count().max(1)));
    rendered.push('\n');
}

fn render_inline_plain(line: &str) -> String {
    let mut rendered = String::new();
    for segment in line.split('`') {
        rendered.push_str(segment);
    }
    rendered
}

#[cfg(test)]
fn render_markdown_for_test(markdown: &str, style: RenderStyle) -> String {
    render_markdown(markdown, style)
}

pub fn print_install_result(result: &InstallResult) -> Result<()> {
    match result {
        InstallResult::ActivateBundle(result) => print_activate_bundle_install(result),
        InstallResult::AgentGuidance(result) => print_agent_guidance_setup_result(result),
        InstallResult::Skill(result) => print_skill_install(result),
        InstallResult::Instructions(result) => print_instructions_install(result),
        InstallResult::Copilot(result) => print_copilot_install("Kast Copilot install", result),
        InstallResult::IdeaPlugin(result) => print_idea_plugin_install(result),
        InstallResult::Shell(result) => print_shell_install(result),
    }
}

pub fn print_agent_guidance_setup_plan(result: &AgentGuidanceSetupPlan) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast agent setup plan");
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
        mdln!(document, "## AGENTS.md targets");
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
    mdln!(document, "# Kast agent setup");
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
        mdln!(document, "## AGENTS.md targets");
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

pub fn print_agent_setup_auto_plan(result: &AgentSetupAutoPlan) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast agent setup plan");
    mdln!(document);
    mdln!(
        document,
        "- Harness: `{}`",
        agent_setup_harness_label(result.harness)
    );
    mdln!(
        document,
        "- Selection source: `{}`",
        agent_setup_source_label(result.selection_source)
    );
    mdln!(document, "- Reason: {}", result.reason);
    if let Some(target_dir) = &result.target_dir {
        mdln!(document, "- Target directory: `{target_dir}`");
    }
    mdln!(
        document,
        "- Would run: `{}`",
        result.install_command.join(" ")
    );
    mdln!(document, "- Dry run: {}", yes_no(result.dry_run));
    print_markdown(&document.into_string())
}

pub fn print_agent_up_result(result: &AgentUpResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast agent up");
    mdln!(document);
    mdln!(document, "- Ready: {}", yes_no(result.ok));
    mdln!(document, "- Dry run: {}", yes_no(result.dry_run));
    mdln!(document, "- Skill target: `{}`", result.setup.skill_target);
    mdln!(
        document,
        "- Setup command: `{}`",
        result.setup.install_command.join(" ")
    );
    if !result.setup.agents_md_targets.is_empty() {
        mdln!(
            document,
            "- AGENTS.md targets: {}",
            result.setup.agents_md_targets.len()
        );
    }
    mdln!(
        document,
        "- Runtime command: `{}`",
        result.runtime_command.join(" ")
    );
    if let Some(install) = &result.install {
        let summary = install_summary(install);
        mdln!(
            document,
            "- Installed {}: `{}`",
            summary.kind,
            summary.target
        );
        if let Some(skipped) = summary.skipped {
            mdln!(document, "- Setup skipped: {}", yes_no(skipped));
        }
    }
    if let Some(runtime) = &result.runtime {
        mdln!(document, "- Workspace: `{}`", runtime.workspace_root);
        mdln!(
            document,
            "- Runtime backend: `{}`",
            runtime.selected.descriptor.backend_name
        );
        mdln!(document, "- Started runtime: {}", yes_no(runtime.started));
        if let Some(note) = &runtime.note {
            mdln!(document, "- Runtime note: {note}");
        }
    }
    if let Some(error) = &result.error {
        mdln!(document);
        mdln!(document, "## Error");
        mdln!(document, "- Code: {}", error.code);
        mdln!(document, "- Message: {}", error.message);
    }
    print_markdown(&document.into_string())
}

struct InstallSummary<'a> {
    kind: &'static str,
    target: &'a str,
    skipped: Option<bool>,
}

fn install_summary(result: &InstallResult) -> InstallSummary<'_> {
    match result {
        InstallResult::ActivateBundle(result) => InstallSummary {
            kind: "bundle",
            target: &result.installed_at,
            skipped: Some(result.skipped),
        },
        InstallResult::AgentGuidance(result) => InstallSummary {
            kind: "agent guidance",
            target: &result.skill.installed_at,
            skipped: Some(result.skipped),
        },
        InstallResult::Skill(result) => InstallSummary {
            kind: "skill",
            target: &result.installed_at,
            skipped: Some(result.skipped),
        },
        InstallResult::Instructions(result) => InstallSummary {
            kind: "instructions",
            target: &result.installed_at,
            skipped: Some(result.skipped),
        },
        InstallResult::Copilot(result) => InstallSummary {
            kind: "copilot",
            target: &result.installed_at,
            skipped: Some(result.skipped),
        },
        InstallResult::IdeaPlugin(result) => InstallSummary {
            kind: "idea plugin",
            target: &result.cask_token,
            skipped: None,
        },
        InstallResult::Shell(result) => InstallSummary {
            kind: "shell",
            target: &result.source_file,
            skipped: None,
        },
    }
}

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
        mdln!(document, "- Start a backend: `kast runtime up`");
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

fn agent_setup_harness_label(harness: AgentSetupHarness) -> &'static str {
    match harness {
        AgentSetupHarness::Auto => "auto",
        AgentSetupHarness::Copilot => "copilot",
        AgentSetupHarness::Skill => "skill",
        AgentSetupHarness::Instructions => "instructions",
    }
}

fn agent_setup_source_label(source: AgentSetupSelectionSource) -> &'static str {
    match source {
        AgentSetupSelectionSource::Explicit => "explicit",
        AgentSetupSelectionSource::Config => "config",
        AgentSetupSelectionSource::TargetDirectory => "target-directory",
        AgentSetupSelectionSource::Repository => "repository",
    }
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
    mdln!(document, "- Check state again: `kast runtime status`");
    mdln!(document, "- Check agent health: `kast agent health`");
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
    mdln!(document);
    mdln!(document, "## Path resolution");
    mdln!(document, "- Root: `{}`", report.root);
    if !report.config_files.is_empty() {
        mdln!(document);
        mdln!(document, "Config files:");
        document.block(&render_table(report.config_files.iter().map(
            |config_file| PathConfigFileRow {
                scope: config_file.scope.clone(),
                state: exists_label(config_file.exists).to_string(),
                path: config_file.path.clone(),
            },
        )));
    }
    if !report.entries.is_empty() {
        mdln!(document);
        mdln!(document, "Path entries:");
        document.block(&render_table(report.entries.iter().map(|entry| {
            PathEntryRow {
                key: entry.key.clone(),
                source: entry.source.to_string(),
                kind: entry.expected_kind.clone(),
                from: entry
                    .derived_from
                    .clone()
                    .unwrap_or_else(|| "-".to_string()),
                state: exists_label(entry.exists).to_string(),
                value: entry.value.clone(),
            }
        })));
    }
    print_messages(document, "Path warnings", &report.warnings);
}

#[derive(Tabled)]
struct PathConfigFileRow {
    #[tabled(rename = "Scope")]
    scope: String,
    #[tabled(rename = "State")]
    state: String,
    #[tabled(rename = "Path")]
    path: String,
}

#[derive(Tabled)]
struct PathEntryRow {
    #[tabled(rename = "Key")]
    key: String,
    #[tabled(rename = "Source")]
    source: String,
    #[tabled(rename = "Kind")]
    kind: String,
    #[tabled(rename = "From")]
    from: String,
    #[tabled(rename = "State")]
    state: String,
    #[tabled(rename = "Value")]
    value: String,
}

fn render_table<Row>(rows: impl IntoIterator<Item = Row>) -> String
where
    Row: Tabled,
{
    let mut table = Table::new(rows);
    table.with(TableStyle::ascii_rounded());
    table.to_string()
}

fn exists_label(exists: bool) -> &'static str {
    if exists { "exists" } else { "missing" }
}

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
    mdln!(document, "# Kast IDEA plugin install");
    mdln!(document);
    mdln!(document, "- Cask token: `{}`", result.cask_token);
    mdln!(document, "- Plugin version: `{}`", result.plugin_version);
    mdln!(document, "- Download cache: `{}`", result.download_cache);
    mdln!(document, "- Downloaded bytes: {}", result.downloaded_bytes);
    mdln!(document, "- Homebrew action: `{}`", result.brew_action);
    mdln!(document, "- Dry run: {}", yes_no(result.dry_run));
    if !result.brew_command.is_empty() {
        mdln!(
            document,
            "- Brew command: `{}`",
            result.brew_command.join(" ")
        );
    }
    print_optional(
        &mut document,
        "JetBrains config root",
        result.jetbrains_config_root.as_deref(),
    );
    if !result.plugin_directories.is_empty() {
        mdln!(document);
        mdln!(document, "## Plugin directories");
        for path in &result.plugin_directories {
            mdln!(document, "- `{path}`");
        }
    }
    print_warnings(&mut document, &result.warnings);
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

fn print_candidate(
    document: &mut MarkdownDocument,
    title: &str,
    candidate: &RuntimeCandidateStatus,
) {
    mdln!(document, "## {title}");
    mdln!(
        document,
        "- Backend: `{}`",
        candidate.descriptor.backend_name
    );
    mdln!(
        document,
        "- Backend version: `{}`",
        candidate.descriptor.backend_version
    );
    mdln!(document, "- PID: {}", candidate.descriptor.pid);
    mdln!(document, "- PID alive: {}", yes_no(candidate.pid_alive));
    mdln!(document, "- Reachable: {}", yes_no(candidate.reachable));
    mdln!(document, "- Ready: {}", yes_no(candidate.ready));
    mdln!(document, "- Socket: `{}`", candidate.descriptor.socket_path);
    if let Some(status) = &candidate.runtime_status {
        mdln!(
            document,
            "- Runtime state: `{}`",
            runtime_state(status.state.clone())
        );
        mdln!(document, "- Active: {}", yes_no(status.active));
        mdln!(document, "- Healthy: {}", yes_no(status.healthy));
        mdln!(document, "- Indexing: {}", yes_no(status.indexing));
        print_source_modules(document, &status.source_module_names);
        if let Some(message) = &status.message {
            mdln!(document, "- Message: {message}");
        }
        print_warnings(document, &status.warnings);
    }
    if let Some(error_message) = &candidate.error_message {
        mdln!(document, "- Error: {error_message}");
    }
}

fn print_source_modules(document: &mut MarkdownDocument, module_names: &[String]) {
    let modules = normalized_modules(module_names);
    if modules.is_empty() {
        return;
    }

    let displayed = modules
        .iter()
        .take(SOURCE_MODULE_DISPLAY_LIMIT)
        .cloned()
        .collect::<Vec<_>>();
    let remaining = modules.len().saturating_sub(displayed.len());

    let mut tree = ModuleTree::default();
    for module in displayed {
        tree.insert(&module);
    }

    mdln!(document);
    mdln!(document, "## Source modules");
    tree.print(document);
    if remaining > 0 {
        mdln!(document, "- ... {remaining} more modules");
    }
}

fn normalized_modules(module_names: &[String]) -> Vec<Vec<String>> {
    module_names
        .iter()
        .filter_map(|module_name| normalize_module_name(module_name))
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

fn normalize_module_name(module_name: &str) -> Option<Vec<String>> {
    let trimmed = module_name.trim();
    if trimmed.is_empty() {
        return None;
    }

    let without_root = trimmed.trim_start_matches(':');
    let parts = without_root
        .split(':')
        .filter(|part| !part.is_empty())
        .map(ToOwned::to_owned)
        .collect::<Vec<_>>();

    if parts.is_empty() {
        Some(vec![trimmed.to_string()])
    } else {
        Some(parts)
    }
}

#[derive(Default)]
struct ModuleTree {
    children: BTreeMap<String, ModuleTree>,
}

impl ModuleTree {
    fn insert(&mut self, path: &[String]) {
        let Some((first, rest)) = path.split_first() else {
            return;
        };
        self.children.entry(first.clone()).or_default().insert(rest);
    }

    fn print(&self, document: &mut MarkdownDocument) {
        self.print_at_depth(document, 0);
    }

    fn print_at_depth(&self, document: &mut MarkdownDocument, depth: usize) {
        let indent = "  ".repeat(depth);
        for (name, child) in &self.children {
            mdln!(document, "{indent}- `{name}`");
            child.print_at_depth(document, depth + 1);
        }
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

fn print_optional(document: &mut MarkdownDocument, label: &str, value: Option<&str>) {
    if let Some(value) = value.filter(|value| !value.trim().is_empty()) {
        mdln!(document, "- {label}: `{value}`");
    }
}

fn print_warnings(document: &mut MarkdownDocument, warnings: &[String]) {
    print_messages(document, "Warnings", warnings);
}

fn print_messages(document: &mut MarkdownDocument, title: &str, messages: &[String]) {
    if messages.is_empty() {
        return;
    }
    mdln!(document);
    mdln!(document, "## {title}");
    for message in messages {
        mdln!(document, "- {message}");
    }
}

fn yes_no(value: bool) -> &'static str {
    if value { "yes" } else { "no" }
}

fn value_or_dash(value: &str) -> &str {
    if value.trim().is_empty() { "-" } else { value }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rendered_human_output_plain_text_does_not_dump_raw_markdown_tokens() {
        let rendered = render_markdown_for_test(
            "# Kast status\n\n- Workspace: `/tmp/kast`\n\n## Next steps\n- Run `kast runtime up`\n",
            RenderStyle::Plain,
        );

        assert!(
            rendered.starts_with("Kast status\n==========="),
            "primary heading should be rendered as text with an underline: {rendered}"
        );
        assert!(
            rendered.contains("Workspace: /tmp/kast"),
            "inline code markers should be rendered away: {rendered}"
        );
        assert!(
            rendered.contains("Next steps\n----------"),
            "secondary headings should be rendered as sections: {rendered}"
        );
        assert!(
            !rendered.contains("# Kast status") && !rendered.contains("`/tmp/kast`"),
            "raw Markdown control tokens should not leak into rendered output: {rendered}"
        );
    }

    #[test]
    fn rendered_human_output_ansi_styles_headings_and_inline_code() {
        let rendered = render_markdown_for_test(
            "# Kast status\n- Workspace: `/tmp/kast`\n",
            RenderStyle::Ansi,
        );

        assert!(
            rendered.contains("\x1b["),
            "ANSI rendering should style headings or inline code: {rendered:?}"
        );
        assert!(
            !rendered.contains("# Kast status") && !rendered.contains("`/tmp/kast`"),
            "ANSI rendering should still remove raw Markdown control tokens: {rendered:?}"
        );
    }

    #[test]
    fn path_resolution_human_output_uses_tables_for_dense_rows() {
        let report = crate::config::PathResolutionReport {
            root: "/tmp/kast".to_string(),
            config_files: vec![crate::config::PathResolutionConfigFile {
                scope: "global".to_string(),
                path: "/tmp/config.toml".to_string(),
                exists: true,
            }],
            entries: vec![crate::config::PathResolutionEntry {
                key: "paths.installRoot".to_string(),
                value: "/tmp/kast".to_string(),
                source: crate::config::PathResolutionSource::Manifest,
                owner: "install".to_string(),
                derived_from: None,
                exists: true,
                expected_kind: "directory".to_string(),
                used_by_idea: true,
            }],
            warnings: vec![],
            schema_version: 3,
        };
        let mut document = MarkdownDocument::default();
        print_path_resolution(&mut document, &report);
        let rendered = render_markdown_for_test(&document.into_string(), RenderStyle::Plain);

        assert!(rendered.contains("Config files:"), "{rendered}");
        assert!(rendered.contains("Path entries:"), "{rendered}");
        assert!(rendered.contains("Scope"), "{rendered}");
        assert!(rendered.contains("State"), "{rendered}");
        assert!(rendered.contains("paths.installRoot"), "{rendered}");
        assert!(
            !rendered.contains("- paths.installRoot ->"),
            "path entries should render as a table, not dense bullets: {rendered}"
        );
    }

    #[test]
    fn source_modules_render_as_plain_text_tree() {
        let rendered = render_source_modules_for_test(&[
            ":backend:idea",
            ":analysis-api",
            ":backend:headless",
            "secondary",
        ]);

        assert!(
            rendered.contains(
                "Source modules\n--------------\n- analysis-api\n- backend\n  - headless\n  - idea\n- secondary\n"
            ),
            "source modules should render as a sorted tree: {rendered}"
        );
        assert!(
            !rendered.contains("Source modules:"),
            "source modules should not render as a comma-separated list: {rendered}"
        );
    }

    #[test]
    fn source_modules_truncate_after_display_limit() {
        let modules = (0..32)
            .map(|index| format!(":module-{index:02}"))
            .collect::<Vec<_>>();
        let rendered = render_source_modules_for_owned_test(&modules);

        assert!(
            rendered.contains("- module-29"),
            "the thirtieth module should still render: {rendered}"
        );
        assert!(
            !rendered.contains("- module-30"),
            "modules after the display limit should be omitted: {rendered}"
        );
        assert!(
            rendered.contains("- ... 2 more modules"),
            "truncation summary should report hidden modules: {rendered}"
        );
    }

    fn render_source_modules_for_test(module_names: &[&str]) -> String {
        let module_names = module_names
            .iter()
            .map(|module_name| module_name.to_string())
            .collect::<Vec<_>>();
        render_source_modules_for_owned_test(&module_names)
    }

    fn render_source_modules_for_owned_test(module_names: &[String]) -> String {
        let mut document = MarkdownDocument::default();
        print_source_modules(&mut document, module_names);
        render_markdown_for_test(&document.into_string(), RenderStyle::Plain)
    }
}
