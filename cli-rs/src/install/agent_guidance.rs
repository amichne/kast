pub fn agent_guidance_setup_plan(
    args: &cli::AgentGuidanceSetupArgs,
    install_command: Vec<String>,
) -> Result<AgentGuidanceSetupPlan> {
    let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
    let skill_target_dir = agent_guidance_skill_target_dir(args, &workspace_root);
    let skill_target = skill_target_dir.join("kast");
    let agents_md_targets = resolve_agents_md_targets(&workspace_root, args)?;
    let agents_md_targets = agents_md_targets
        .iter()
        .map(|target| agents_md_target_plan(target, &skill_target))
        .collect::<Result<Vec<_>>>()?;
    Ok(AgentGuidanceSetupPlan {
        result_type: "AGENT_SETUP_PLAN",
        skill_target: skill_target.display().to_string(),
        agents_md_targets,
        install_command,
        force: args.force,
        dry_run: args.dry_run,
        schema_version: SCHEMA_VERSION,
    })
}

pub fn install_agent_guidance(
    args: cli::AgentGuidanceSetupArgs,
    install_command: Vec<String>,
) -> Result<AgentGuidanceSetupResult> {
    let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
    let skill_target_dir = agent_guidance_skill_target_dir(&args, &workspace_root);
    let skill = install_skill(ResourceInstallArgs {
        target_dir: Some(skill_target_dir),
        name: Some("kast".to_string()),
        source_dir: None,
        force: args.force,
        no_auto_exclude_git: args.no_auto_exclude_git,
    })?;
    let agents_md_targets = resolve_agents_md_targets(&workspace_root, &args)?;
    let mut agent_results = Vec::with_capacity(agents_md_targets.len());
    for target in &agents_md_targets {
        agent_results.push(install_agents_md_guidance(
            target,
            &PathBuf::from(&skill.installed_at).join("SKILL.md"),
            args.force,
            args.no_auto_exclude_git,
        )?);
    }
    let skipped = skill.skipped && agent_results.iter().all(|target| target.skipped);
    Ok(AgentGuidanceSetupResult {
        result_type: "AGENT_SETUP",
        skill,
        agents_md_targets: agent_results,
        install_command,
        skipped,
        schema_version: SCHEMA_VERSION,
    })
}

#[derive(Debug, Clone)]
struct AgentsMdTarget {
    path: PathBuf,
    explicit: bool,
    local_only: bool,
}

fn resolve_agents_md_targets(
    workspace_root: &Path,
    args: &cli::AgentGuidanceSetupArgs,
) -> Result<Vec<AgentsMdTarget>> {
    let mut targets = Vec::new();
    let explicit_targets = merge_context_files(args.context_files.clone(), args.agents_md.clone());
    targets.push(default_context_target(workspace_root));
    for target in &explicit_targets {
        let path = if target.is_absolute() {
            target.clone()
        } else {
            workspace_root.join(target)
        };
        let path = config::normalize(path);
        if !is_supported_context_file(workspace_root, &path) {
            return Err(CliError::new(
                "AGENT_GUIDANCE_TARGET_INVALID",
                format!(
                    "Kast context files must be AGENTS.md, CODEX.md, CLAUDE.md, or AGENTS.local.md: {}",
                    path.display()
                ),
            ));
        }
        if !targets.iter().any(|existing| existing.path == path) {
            targets.push(AgentsMdTarget {
                local_only: is_local_context_file(&path),
                explicit: true,
                path,
            });
        }
    }
    Ok(targets)
}

fn merge_context_files(mut context_files: Vec<PathBuf>, agents_md: Vec<PathBuf>) -> Vec<PathBuf> {
    for target in agents_md {
        if !context_files.iter().any(|existing| existing == &target) {
            context_files.push(target);
        }
    }
    context_files
}

fn agent_guidance_skill_target_dir(
    args: &cli::AgentGuidanceSetupArgs,
    workspace_root: &Path,
) -> PathBuf {
    args.skill_target_dir
        .clone()
        .unwrap_or_else(|| workspace_root.join(".agents/skills"))
}

fn default_context_target(workspace_root: &Path) -> AgentsMdTarget {
    for candidate in [
        "AGENTS.md",
        "CODEX.md",
        "CLAUDE.md",
        DEFAULT_AGENT_GUIDANCE_FILE,
    ] {
        let path = workspace_root.join(candidate);
        if path.exists() {
            return AgentsMdTarget {
                local_only: candidate == DEFAULT_AGENT_GUIDANCE_FILE,
                explicit: false,
                path: config::normalize(path),
            };
        }
    }
    AgentsMdTarget {
        path: config::normalize(workspace_root.join(DEFAULT_AGENT_GUIDANCE_FILE)),
        explicit: false,
        local_only: true,
    }
}

fn is_supported_context_file(_workspace_root: &Path, path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| {
            matches!(
                name,
                "AGENTS.md" | "CODEX.md" | "CLAUDE.md" | DEFAULT_AGENT_GUIDANCE_FILE
            )
        })
}

fn is_local_context_file(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == DEFAULT_AGENT_GUIDANCE_FILE)
}

fn agents_md_target_plan(target: &AgentsMdTarget, skill_path: &Path) -> Result<AgentsMdTargetPlan> {
    let exists = target.path.exists();
    let content = if exists {
        Some(fs::read_to_string(&target.path)?)
    } else {
        None
    };
    let managed_region_present = content
        .as_deref()
        .is_some_and(|content| find_kast_managed_fence(content).is_some());
    let expected = render_agents_md_guidance_block(skill_path);
    let will_modify = match content.as_deref() {
        Some(content) => find_kast_managed_fence(content)
            .map(|range| content[range] != expected)
            .unwrap_or(true),
        None => true,
    };
    let reason = if exists {
        if target.explicit {
            "explicit context file target".to_string()
        } else {
            "workspace context file exists".to_string()
        }
    } else if target.explicit {
        "explicit context file target will be created".to_string()
    } else {
        "workspace context fallback will be created".to_string()
    };
    Ok(AgentsMdTargetPlan {
        path: target.path.display().to_string(),
        exists,
        will_create: !exists,
        managed_region_present,
        will_modify,
        reason,
    })
}

fn install_agents_md_guidance(
    target: &AgentsMdTarget,
    skill_path: &Path,
    force: bool,
    no_auto_exclude_git: bool,
) -> Result<AgentsMdTargetResult> {
    let expected = render_agents_md_guidance_block(skill_path);
    let existed = target.path.exists();
    let original = if existed {
        fs::read_to_string(&target.path)?
    } else {
        String::new()
    };
    let current_region =
        find_kast_managed_fence(&original).map(|range| original[range].to_string());
    if let Some(region) = &current_region {
        let current_sha = manifest::sha256_bytes(region.as_bytes());
        let expected_sha = manifest::sha256_bytes(expected.as_bytes());
        let manifest_sha = managed_region_manifest_checksum(&target.path)?;
        let changed_from_manifest = manifest_sha
            .as_deref()
            .is_some_and(|recorded| recorded != current_sha);
        let unmanaged_different_region = manifest_sha.is_none() && current_sha != expected_sha;
        if (changed_from_manifest || unmanaged_different_region) && !force {
            backup_context_file(&target.path, &original)?;
        }
    }

    let updated_content = replace_or_append_kast_managed_fence(&original, &expected);
    let updated = updated_content != original;
    if updated {
        write_file_atomically(&target.path, updated_content.as_bytes())?;
    }
    let region_sha = manifest::kast_managed_fence_sha256(&target.path)?;
    let repo_root = resource_repo_root(&target.path);
    let git_exclude = match (&repo_root, target.local_only) {
        (Some(repo_root), true) => update_resource_git_exclude(
            ManagedResourceKind::AgentGuidance,
            repo_root,
            &target.path,
            std::slice::from_ref(&target.path),
            no_auto_exclude_git,
        )?,
        (Some(repo_root), false) => update_context_git_filter(repo_root, &target.path, no_auto_exclude_git)?,
        (None, _) => git_exclude_not_repository(),
    };
    if let Some(repo_root) = &repo_root {
        self_mgmt::record_repo_resource(
            repo_root,
            ManagedRepoResource {
                kind: ManagedResourceKind::AgentGuidance,
                target_path: target.path.display().to_string(),
                primitive_version: cli::version().to_string(),
                source_bundle_sha256: manifest::sha256_bytes(expected.as_bytes()),
                output_paths: vec![target.path.display().to_string()],
                output_checksums: vec![ManagedResourceOutputChecksum {
                    path: target.path.display().to_string(),
                    sha256: region_sha.clone(),
                    region: Some(ManagedResourceChecksumRegion::KastManagedFence),
                }],
                installed_at: current_timestamp(),
                history: vec![],
            },
        )?;
    }
    Ok(AgentsMdTargetResult {
        path: target.path.display().to_string(),
        created: !existed,
        updated,
        skipped: !updated && !git_exclude.updated,
        managed_region_sha256: region_sha,
        git_exclude,
    })
}

fn managed_region_manifest_checksum(path: &Path) -> Result<Option<String>> {
    let normalized = config::normalize(path.to_path_buf());
    Ok(self_mgmt::read_global_install_state()?.and_then(|install| {
        install.repos.into_iter().find_map(|repo| {
            repo.resources.into_iter().find_map(|resource| {
                if resource.kind == ManagedResourceKind::AgentGuidance
                    && config::normalize(PathBuf::from(&resource.target_path)) == normalized
                {
                    resource.output_checksums.into_iter().find_map(|checksum| {
                        (checksum.region == Some(ManagedResourceChecksumRegion::KastManagedFence))
                            .then_some(checksum.sha256)
                    })
                } else {
                    None
                }
            })
        })
    }))
}

fn render_agents_md_guidance_block(skill_path: &Path) -> String {
    let skill_path = skill_path.display().to_string();
    [
        KAST_MANAGED_FENCE_START,
        "## Kast routing",
        &format!("Use `{skill_path}` before Kotlin or Gradle semantic work."),
        "Use `kast`, `kast help`, `kast ready`, and `kast agent verify --workspace-root \"$PWD\"` to orient.",
        "Use typed commands such as `kast agent symbol --query <name>`, `kast agent diagnostics --file-path <path>`, and `kast agent rename --symbol <fq-name> --new-name <name> --apply`.",
        "Run `kast repair --apply` only when readiness output asks for install-state repair.",
        KAST_MANAGED_FENCE_END,
    ]
    .join("\n")
}

fn update_context_git_filter(
    repo_root: &Path,
    target: &Path,
    disabled: bool,
) -> Result<GitExcludeResult> {
    if disabled {
        return Ok(GitExcludeResult {
            attempted: false,
            updated: false,
            exclude_file: None,
            reason: Some("disabled".to_string()),
            schema_version: SCHEMA_VERSION,
        });
    }
    let Some(attributes_file) = git_info_attributes_path(repo_root) else {
        return Ok(git_exclude_not_repository());
    };
    let tools_dir = attributes_file
        .parent()
        .and_then(Path::parent)
        .map(|git_dir| git_dir.join("tools"))
        .unwrap_or_else(|| repo_root.join(".git/tools"));
    let filter_script = tools_dir.join("kast-context-region-filter");
    fs::create_dir_all(&tools_dir)?;
    let start = KAST_MANAGED_FENCE_START.replace('"', "\\\"");
    let attribute_start = ATTRIBUTE_KAST_MANAGED_FENCE_START.replace('"', "\\\"");
    let end = KAST_MANAGED_FENCE_END.replace('"', "\\\"");
    let filter_script_contents = format!(
        r#"#!/usr/bin/env sh
exec awk '
  function flush_blank_lines(    i) {{
    for (i = 0; i < blank_line_count; i++) {{
      print ""
    }}
    blank_line_count = 0
  }}

  in_kast {{
    if ($0 == "{end}") {{
      in_kast = 0
    }}
    next
  }}

  $0 == "{start}" || $0 == "{attribute_start}" {{
    in_kast = 1
    next
  }}

  $0 == "" {{
    blank_line_count++
    next
  }}

  {{
    flush_blank_lines()
    print
  }}
'
"#
    );
    write_file_atomically(
        &filter_script,
        filter_script_contents.as_bytes(),
    )?;
    set_context_filter_executable(&filter_script)?;
    let relative = target
        .strip_prefix(repo_root)
        .unwrap_or(target)
        .display()
        .to_string();
    let block = format!("{relative} filter=kast-context-region diff=kast-context-region\n");
    let original = match fs::read_to_string(&attributes_file) {
        Ok(content) => content,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => String::new(),
        Err(error) => return Err(error.into()),
    };
    let start_marker = "# >>> kast context filter >>>";
    let end_marker = "# <<< kast context filter <<<";
    let mut lines = context_filter_lines(&original, start_marker, end_marker);
    lines.insert(block.trim_end().to_string());
    let block = lines.into_iter().collect::<Vec<_>>().join("\n");
    let updated_content = replace_managed_block_with_markers(
        &original,
        &format!("{start_marker}\n{block}\n{end_marker}\n"),
        start_marker,
        end_marker,
    );
    let updated = updated_content != original;
    if updated {
        write_file_atomically(&attributes_file, updated_content.as_bytes())?;
    }
    configure_context_filter(repo_root, &filter_script)?;
    Ok(GitExcludeResult {
        attempted: true,
        updated,
        exclude_file: Some(attributes_file.display().to_string()),
        reason: Some("context git filter".to_string()),
        schema_version: SCHEMA_VERSION,
    })
}

fn context_filter_lines(original: &str, start_marker: &str, end_marker: &str) -> BTreeSet<String> {
    let Some(start) = original.find(start_marker) else {
        return BTreeSet::new();
    };
    let Some(end_offset) = original[start..].find(end_marker) else {
        return BTreeSet::new();
    };
    let block_start = start + start_marker.len();
    let block_end = start + end_offset;
    original[block_start..block_end]
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(ToOwned::to_owned)
        .collect()
}

fn git_info_attributes_path(repo_root: &Path) -> Option<PathBuf> {
    let output = ProcessCommand::new("git")
        .arg("-C")
        .arg(repo_root)
        .args(["rev-parse", "--git-path", "info/attributes"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let raw = String::from_utf8(output.stdout).ok()?;
    Some(repo_root.join(raw.trim()))
}

fn configure_context_filter(repo_root: &Path, filter_script: &Path) -> Result<()> {
    let filter_command = shell_command(&[filter_script.display().to_string()]);
    for args in [
        vec![
            "config".to_string(),
            "--local".to_string(),
            "filter.kast-context-region.clean".to_string(),
            filter_command.clone(),
        ],
        vec![
            "config".to_string(),
            "--local".to_string(),
            "filter.kast-context-region.smudge".to_string(),
            "cat".to_string(),
        ],
        vec![
            "config".to_string(),
            "--local".to_string(),
            "diff.kast-context-region.textconv".to_string(),
            filter_command.clone(),
        ],
    ] {
        let output = ProcessCommand::new("git")
            .arg("-C")
            .arg(repo_root)
            .args(args)
            .output()?;
        if !output.status.success() {
            return Err(CliError::new(
                "GIT_FILTER_CONFIG_FAILED",
                "Could not configure clone-local Kast context Git filter.",
            ));
        }
    }
    Ok(())
}

fn set_context_filter_executable(path: &Path) -> Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = fs::metadata(path)?.permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(path, permissions)?;
    }
    Ok(())
}

fn shell_command(argv: &[String]) -> String {
    argv.iter()
        .map(|arg| {
            if arg.chars().all(|ch| ch.is_ascii_alphanumeric() || "-_./".contains(ch)) {
                arg.clone()
            } else {
                format!("'{}'", arg.replace('\'', "'\\''"))
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

fn find_kast_managed_fence(content: &str) -> Option<std::ops::Range<usize>> {
    find_managed_fence_with_markers(content, KAST_MANAGED_FENCE_START, KAST_MANAGED_FENCE_END)
        .or_else(|| {
            find_managed_fence_with_markers(
                content,
                ATTRIBUTE_KAST_MANAGED_FENCE_START,
                KAST_MANAGED_FENCE_END,
            )
        })
        .or_else(|| {
            find_managed_fence_with_markers(
                content,
                LEGACY_KAST_MANAGED_FENCE_START,
                LEGACY_KAST_MANAGED_FENCE_END,
            )
        })
}

fn find_managed_fence_with_markers(
    content: &str,
    start_marker: &str,
    end_marker: &str,
) -> Option<std::ops::Range<usize>> {
    let start = content.find(start_marker)?;
    let after_start = start + start_marker.len();
    let relative_end = content[after_start..].find(end_marker)?;
    let end = after_start + relative_end + end_marker.len();
    Some(start..end)
}

fn replace_or_append_kast_managed_fence(original: &str, block: &str) -> String {
    if let Some(range) = find_kast_managed_fence(original) {
        let mut updated = String::with_capacity(original.len() + block.len());
        updated.push_str(&original[..range.start]);
        updated.push_str(block.trim_end());
        updated.push_str(&original[range.end..]);
        return updated;
    }
    let mut updated = original.to_string();
    if !updated.is_empty() && !updated.ends_with('\n') {
        updated.push('\n');
    }
    if !updated.is_empty() {
        updated.push('\n');
    }
    updated.push_str(block);
    updated
}

fn write_file_atomically(path: &Path, contents: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let file_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or(DEFAULT_AGENT_GUIDANCE_FILE);
    let tmp = path.with_file_name(format!(".{file_name}.kast-tmp-{}", std::process::id()));
    fs::write(&tmp, contents)?;
    fs::rename(&tmp, path)?;
    Ok(())
}

fn backup_context_file(path: &Path, original: &str) -> Result<()> {
    if original.is_empty() {
        return Ok(());
    }
    let backup = path.with_extension(format!(
        "{}.kast-backup",
        current_timestamp().replace([':', 'T', 'Z'], "-")
    ));
    write_file_atomically(&backup, original.as_bytes())
}
