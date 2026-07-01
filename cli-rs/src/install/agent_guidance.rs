pub fn agent_guidance_setup_plan(
    args: &cli::AgentGuidanceSetupArgs,
    install_command: Vec<String>,
) -> Result<AgentGuidanceSetupPlan> {
    let workspace_root = config::resolve_workspace_root(args.workspace_root.clone())?;
    let skill_target = workspace_root.join(".agents/skills/kast");
    let agents_md_targets = resolve_agents_md_targets(&workspace_root, &args.agents_md)?;
    let agents_md_targets = agents_md_targets
        .iter()
        .map(agents_md_target_plan)
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
    let skill = install_skill(ResourceInstallArgs {
        target_dir: Some(workspace_root.join(".agents/skills")),
        name: Some("kast".to_string()),
        source_dir: None,
        force: args.force,
        no_auto_exclude_git: args.no_auto_exclude_git,
        dry_run: false,
    })?;
    let agents_md_targets = resolve_agents_md_targets(&workspace_root, &args.agents_md)?;
    let mut agent_results = Vec::with_capacity(agents_md_targets.len());
    for target in &agents_md_targets {
        agent_results.push(install_agents_md_guidance(
            target,
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
}

fn resolve_agents_md_targets(
    workspace_root: &Path,
    explicit_targets: &[PathBuf],
) -> Result<Vec<AgentsMdTarget>> {
    let mut targets = Vec::new();
    targets.push(AgentsMdTarget {
        path: config::normalize(workspace_root.join(DEFAULT_AGENT_GUIDANCE_FILE)),
        explicit: false,
    });
    for target in explicit_targets {
        let path = if target.is_absolute() {
            target.clone()
        } else {
            workspace_root.join(target)
        };
        let path = config::normalize(path);
        if !is_agent_guidance_file_name(&path) {
            return Err(CliError::new(
                "AGENT_GUIDANCE_TARGET_INVALID",
                format!(
                    "Kast agent guidance targets must be AGENTS.md or AGENTS.local.md files: {}",
                    path.display()
                ),
            ));
        }
        if !targets.iter().any(|existing| existing.path == path) {
            targets.push(AgentsMdTarget {
                path,
                explicit: true,
            });
        }
    }
    Ok(targets)
}

fn is_agent_guidance_file_name(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name == "AGENTS.md" || name == DEFAULT_AGENT_GUIDANCE_FILE)
}

fn agents_md_target_plan(target: &AgentsMdTarget) -> Result<AgentsMdTargetPlan> {
    let exists = target.path.exists();
    let content = if exists {
        Some(fs::read_to_string(&target.path)?)
    } else {
        None
    };
    let managed_region_present = content
        .as_deref()
        .is_some_and(|content| find_kast_managed_fence(content).is_some());
    let expected = render_agents_md_guidance_block();
    let will_modify = match content.as_deref() {
        Some(content) => find_kast_managed_fence(content)
            .map(|range| content[range] != expected)
            .unwrap_or(true),
        None => true,
    };
    let reason = if exists {
        if target.explicit {
            "explicit agent guidance target".to_string()
        } else {
            "workspace AGENTS.local.md exists".to_string()
        }
    } else if target.explicit {
        "explicit agent guidance target will be created".to_string()
    } else {
        "workspace AGENTS.local.md will be created".to_string()
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
    force: bool,
    no_auto_exclude_git: bool,
) -> Result<AgentsMdTargetResult> {
    let expected = render_agents_md_guidance_block();
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
            return Err(CliError::new(
                "INSTALL_MANAGED_OUTPUT_MODIFIED",
                format!(
                    "Kast managed guidance in {} was modified. Move custom content outside the Kast fence or pass --force to replace only the fenced region.",
                    target.path.display()
                ),
            ));
        }
    }

    let updated_content = replace_or_append_kast_managed_fence(&original, &expected);
    let updated = updated_content != original;
    if updated {
        write_file_atomically(&target.path, updated_content.as_bytes())?;
    }
    let region_sha = manifest::kast_managed_fence_sha256(&target.path)?;
    let repo_root = resource_repo_root(&target.path);
    let git_exclude = match &repo_root {
        Some(repo_root) => update_resource_git_exclude(
            ManagedResourceKind::AgentGuidance,
            repo_root,
            &target.path,
            std::slice::from_ref(&target.path),
            no_auto_exclude_git,
        )?,
        None => git_exclude_not_repository(),
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

fn render_agents_md_guidance_block() -> String {
    [
        KAST_MANAGED_FENCE_START,
        "## Kast routing",
        "When touching Kotlin or Gradle files, check readiness with `kast agent workflow verify --workspace-root \"$PWD\"` if current state is unknown.",
        "Use `.agents/skills/kast/SKILL.md` and `kast agent` for Kotlin semantic navigation, edits, references, diagnostics, and source-index impact.",
        "Prefer `kast agent workflow ...` for repeatable proof; use `kast agent call <method>` only when no workflow fits.",
        "After updating packaged skills, instructions, Copilot assets, or managed guidance, run `kast agent workflow package-verify --workspace-root \"$PWD\"` and follow emitted recovery commands.",
        KAST_MANAGED_FENCE_END,
    ]
    .join("\n")
}

fn find_kast_managed_fence(content: &str) -> Option<std::ops::Range<usize>> {
    find_managed_fence_with_markers(content, KAST_MANAGED_FENCE_START, KAST_MANAGED_FENCE_END)
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
