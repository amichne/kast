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

pub fn print_setup_runtime_result(result: &SetupRuntimeResult) -> Result<()> {
    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast setup");
    mdln!(document);
    mdln!(document, "## What happened");
    mdln!(document, "- Ready: {}", yes_no(result.ok));
    mdln!(
        document,
        "- Stage: `{}`",
        setup_runtime_stage_label(result.stage)
    );
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
            "- Agent guidance targets: {}",
            result.setup.agents_md_targets.len()
        );
    }
    if result.runtime_command != result.setup.install_command {
        mdln!(
            document,
            "- Runtime command: `{}`",
            result.runtime_command.join(" ")
        );
    }
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
    print_setup_runtime_next_steps(&mut document, result);
    print_markdown(&document.into_string())
}

fn print_setup_runtime_next_steps(document: &mut MarkdownDocument, result: &SetupRuntimeResult) {
    if !result.next_actions.is_empty() {
        mdln!(document);
        mdln!(document, "## Next step");
        for action in &result.next_actions {
            print_setup_runtime_next_action(document, action);
        }
    } else if result.ok {
        mdln!(document);
        mdln!(document, "## Next step");
        mdln!(
            document,
            "- Run typed semantic requests such as `kast agent symbol --query <name> --workspace-root <repo>`."
        );
    }
    if !result.manual_steps.is_empty() {
        mdln!(document);
        if result.ok {
            mdln!(document, "## Manual steps");
        } else {
            mdln!(document, "## If that fails");
        }
        for step in &result.manual_steps {
            mdln!(document, "- {step}");
        }
    }
}

fn print_setup_runtime_next_action(
    document: &mut MarkdownDocument,
    action: &SetupRuntimeNextAction,
) {
    mdln!(document, "- {}: `{}`", action.label, action.argv.join(" "));
    mdln!(document, "  Reason: {}", action.reason);
}

fn setup_runtime_stage_label(stage: SetupRuntimeStage) -> &'static str {
    match stage {
        SetupRuntimeStage::Onboarding => "onboarding",
        SetupRuntimeStage::DryRun => "dry-run",
        SetupRuntimeStage::SetupDone => "setup-done",
        SetupRuntimeStage::RuntimeReady => "runtime-ready",
        SetupRuntimeStage::RuntimeBlocked => "runtime-blocked",
        SetupRuntimeStage::RepairRequired => "repair-required",
    }
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
