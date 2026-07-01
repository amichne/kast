fn required_package_resources(
    doctor: &self_mgmt::SelfDoctorResult,
    workspace_root: &Path,
    options: &AgentPackageVerifyOptions,
) -> Result<AgentPackageRequiredResources> {
    let copilot_package = package_resource_group(
        doctor.install.as_ref(),
        workspace_root,
        self_mgmt::ManagedResourceKind::CopilotPackage,
        options.require_copilot,
        options.copilot_target_dir.clone().into_iter().collect(),
    )?;
    let skills = package_resource_group(
        doctor.install.as_ref(),
        workspace_root,
        self_mgmt::ManagedResourceKind::Skill,
        options.require_skill,
        options.skill_target_dirs.clone(),
    )?;
    let instructions = package_resource_group(
        doctor.install.as_ref(),
        workspace_root,
        self_mgmt::ManagedResourceKind::Instructions,
        options.require_instructions,
        options.instructions_target_dirs.clone(),
    )?;
    let mut issues = Vec::new();
    issues.extend(package_resource_group_issues(&copilot_package));
    issues.extend(package_resource_group_issues(&skills));
    issues.extend(package_resource_group_issues(&instructions));
    Ok(AgentPackageRequiredResources {
        ok: issues.is_empty(),
        workspace_root: workspace_root.display().to_string(),
        copilot_package,
        skills,
        instructions,
        issues,
    })
}

fn package_resource_group(
    install: Option<&self_mgmt::InstallState>,
    workspace_root: &Path,
    kind: self_mgmt::ManagedResourceKind,
    required: bool,
    explicit_target_dirs: Vec<PathBuf>,
) -> Result<AgentPackageResourceGroup> {
    let has_explicit_targets = !explicit_target_dirs.is_empty();
    let targets = if has_explicit_targets {
        explicit_target_dirs
            .into_iter()
            .map(|target_dir| resource_target_from_target_dir(kind, target_dir))
            .collect::<Vec<_>>()
    } else if required {
        standard_resource_targets(workspace_root, kind)
    } else {
        Vec::new()
    };
    let mut checks = Vec::new();
    for target in targets {
        checks.push(package_resource_target(
            install,
            kind,
            config::normalize(target),
        )?);
    }
    Ok(AgentPackageResourceGroup {
        required,
        mode: if has_explicit_targets {
            "explicit"
        } else {
            "standard"
        },
        targets: checks,
    })
}

fn package_resource_target(
    install: Option<&self_mgmt::InstallState>,
    kind: self_mgmt::ManagedResourceKind,
    target: PathBuf,
) -> Result<AgentPackageResourceTarget> {
    let resource = managed_resource_for_target(install, kind, &target).cloned();
    let output_issues = match &resource {
        Some(resource) => manifest::verify_managed_resource_outputs(resource)?.issues,
        None => Vec::new(),
    };
    let version_matches_current = resource
        .as_ref()
        .is_some_and(|resource| resource.primitive_version == crate::cli::version());
    let current = resource.is_some() && version_matches_current && output_issues.is_empty();
    Ok(AgentPackageResourceTarget {
        kind,
        target_path: target.display().to_string(),
        exists: target.exists(),
        current,
        version_matches_current,
        manifest_resource: resource,
        output_issues,
    })
}

fn package_resource_group_issues(
    group: &AgentPackageResourceGroup,
) -> Vec<AgentPackageResourceIssue> {
    if !group.required {
        return Vec::new();
    }
    if group.mode == "explicit" {
        return group
            .targets
            .iter()
            .filter(|target| !target.current)
            .map(|target| required_resource_issue(target.kind, vec![target.target_path.clone()]))
            .collect();
    }
    if group.targets.iter().any(|target| target.current) {
        return Vec::new();
    }
    let Some(first) = group.targets.first() else {
        return Vec::new();
    };
    vec![required_resource_issue(
        first.kind,
        group
            .targets
            .iter()
            .map(|target| target.target_path.clone())
            .collect(),
    )]
}

fn required_resource_issue(
    kind: self_mgmt::ManagedResourceKind,
    target_paths: Vec<String>,
) -> AgentPackageResourceIssue {
    let label = required_resource_label(kind);
    AgentPackageResourceIssue {
        code: format!("AGENT_WORKFLOW_REQUIRED_{}_MISSING_OR_STALE", label),
        message: format!(
            "Required Kast {} resource is missing, stale, or not manifest-backed.",
            label.to_ascii_lowercase().replace('_', " ")
        ),
        kind,
        recovery_argv: required_resource_recovery_argv(kind, &target_paths),
        target_paths,
    }
}

fn required_resource_recovery_argv(
    kind: self_mgmt::ManagedResourceKind,
    target_paths: &[String],
) -> Vec<String> {
    if kind == self_mgmt::ManagedResourceKind::AgentGuidance {
        let mut argv = vec![
            current_executable_argument(),
            "agent".to_string(),
            "setup".to_string(),
        ];
        if let Some(target) = target_paths.first() {
            argv.push("--agents-md".to_string());
            argv.push(target.clone());
        }
        argv.push("--force".to_string());
        return argv;
    }
    let mut argv = vec![
        current_executable_argument(),
        "agent".to_string(),
        "setup".to_string(),
        required_resource_harness(kind).to_string(),
    ];
    if let Some(target_dir) = required_resource_recovery_target_dir(kind, target_paths) {
        argv.push("--target-dir".to_string());
        argv.push(target_dir);
    }
    argv.push("--force".to_string());
    argv
}

fn required_resource_recovery_target_dir(
    kind: self_mgmt::ManagedResourceKind,
    target_paths: &[String],
) -> Option<String> {
    let target = target_paths.first()?;
    match kind {
        self_mgmt::ManagedResourceKind::CopilotPackage => Some(target.clone()),
        self_mgmt::ManagedResourceKind::Skill | self_mgmt::ManagedResourceKind::Instructions => {
            Path::new(target)
                .parent()
                .map(|parent| parent.display().to_string())
        }
        self_mgmt::ManagedResourceKind::AgentGuidance => Some(target.clone()),
    }
}

fn required_resource_harness(kind: self_mgmt::ManagedResourceKind) -> &'static str {
    match kind {
        self_mgmt::ManagedResourceKind::CopilotPackage => "copilot",
        self_mgmt::ManagedResourceKind::Skill => "skill",
        self_mgmt::ManagedResourceKind::Instructions => "instructions",
        self_mgmt::ManagedResourceKind::AgentGuidance => "agent-guidance",
    }
}

fn required_resource_label(kind: self_mgmt::ManagedResourceKind) -> &'static str {
    match kind {
        self_mgmt::ManagedResourceKind::CopilotPackage => "COPILOT_PACKAGE",
        self_mgmt::ManagedResourceKind::Skill => "SKILL",
        self_mgmt::ManagedResourceKind::Instructions => "INSTRUCTIONS",
        self_mgmt::ManagedResourceKind::AgentGuidance => "AGENT_GUIDANCE",
    }
}

fn append_required_resource_issues(
    summary: &mut Map<String, Value>,
    issues: &[AgentPackageResourceIssue],
) {
    if issues.is_empty() {
        return;
    }
    let summary_issues = summary
        .entry("issues".to_string())
        .or_insert_with(|| Value::Array(Vec::new()));
    let Some(summary_issues) = summary_issues.as_array_mut() else {
        return;
    };
    for issue in issues {
        summary_issues.push(Value::String(format!("{}: {}", issue.code, issue.message)));
    }
}

fn managed_resource_for_target<'a>(
    install: Option<&'a self_mgmt::InstallState>,
    kind: self_mgmt::ManagedResourceKind,
    target: &Path,
) -> Option<&'a self_mgmt::ManagedRepoResource> {
    let normalized_target = config::normalize(target.to_path_buf());
    install.and_then(|install| {
        install.repos.iter().find_map(|repo| {
            repo.resources.iter().find(|resource| {
                resource.kind == kind
                    && config::normalize(PathBuf::from(&resource.target_path)) == normalized_target
            })
        })
    })
}

fn standard_resource_targets(
    workspace_root: &Path,
    kind: self_mgmt::ManagedResourceKind,
) -> Vec<PathBuf> {
    match kind {
        self_mgmt::ManagedResourceKind::CopilotPackage => vec![workspace_root.join(".github")],
        self_mgmt::ManagedResourceKind::Skill => standard_named_resource_targets(
            workspace_root,
            &[
                ".agents/skills",
                ".codex/skills",
                ".github/skills",
                ".claude/skills",
            ],
        ),
        self_mgmt::ManagedResourceKind::Instructions => standard_named_resource_targets(
            workspace_root,
            &[
                ".agents/instructions",
                ".codex/instructions",
                ".github/instructions",
                ".claude/instructions",
            ],
        ),
        self_mgmt::ManagedResourceKind::AgentGuidance => {
            vec![workspace_root.join("AGENTS.local.md")]
        }
    }
}

fn standard_named_resource_targets(workspace_root: &Path, roots: &[&str]) -> Vec<PathBuf> {
    roots
        .iter()
        .map(|root| workspace_root.join(root).join("kast"))
        .collect()
}

fn resource_target_from_target_dir(
    kind: self_mgmt::ManagedResourceKind,
    target_dir: PathBuf,
) -> PathBuf {
    let target_dir = config::normalize(target_dir);
    if kind == self_mgmt::ManagedResourceKind::CopilotPackage {
        return target_dir;
    }
    if target_dir
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == "kast")
    {
        target_dir
    } else {
        target_dir.join("kast")
    }
}

fn path_values(paths: &[PathBuf]) -> Vec<String> {
    paths
        .iter()
        .map(|path| config::normalize(path.clone()).display().to_string())
        .collect()
}
