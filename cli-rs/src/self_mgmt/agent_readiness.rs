use super::*;

const KAST_SKILL_DIALECT_METADATA_KEY: &str = "kast-cli-dialect-revision";
const PACKAGED_KAST_SKILL: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast-skill/SKILL.md"
));
const CODEX_ADMIN_SKILL: &str = "/etc/codex/skills/kast/SKILL.md";
const WORKSPACE_SKILL_RELATIVES: &[&str] = &[
    ".agents/skills/kast/SKILL.md",
    ".codex/skills/kast/SKILL.md",
    ".github/skills/kast/SKILL.md",
    ".claude/skills/kast/SKILL.md",
];
const WORKSPACE_CONTEXT_RELATIVES: &[&str] =
    &["AGENTS.md", "CODEX.md", "CLAUDE.md", "AGENTS.local.md"];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum AgentResourceState {
    Missing,
    Modified,
    UserOwned,
    Managed,
    Foreign,
}

impl AgentResourceState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Missing => "missing",
            Self::Modified => "modified",
            Self::UserOwned => "user-owned",
            Self::Managed => "managed",
            Self::Foreign => "foreign",
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorAgentBinaryDiagnostic {
    pub path: String,
    pub version: String,
    pub revision: String,
    pub source_path: String,
    pub dialect_revision: u32,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorAgentBackendDiagnostic {
    pub state: AgentResourceState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub revision: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_path: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorAgentSkillCandidateDiagnostic {
    pub path: String,
    pub source: String,
    pub state: AgentResourceState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dialect_revision: Option<u32>,
    pub compatible: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub repair_command: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorAgentSkillDiagnostic {
    pub compatible: bool,
    pub candidates: Vec<DoctorAgentSkillCandidateDiagnostic>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorAgentGuidanceDiagnostic {
    pub path: String,
    pub source: String,
    pub state: AgentResourceState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub repair_command: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DoctorAgentEnvironmentDiagnostic {
    pub install_authority: InstallAuthority,
    pub binary: DoctorAgentBinaryDiagnostic,
    pub backend: DoctorAgentBackendDiagnostic,
    pub skills: DoctorAgentSkillDiagnostic,
    pub guidance: DoctorAgentGuidanceDiagnostic,
    pub ok: bool,
}

#[derive(Debug, serde::Deserialize)]
struct KastSkillFrontMatter {
    name: String,
    #[serde(default)]
    metadata: std::collections::BTreeMap<String, String>,
}

#[derive(Debug)]
struct PluginWorkspaceEvidence {
    metadata_path: PathBuf,
    required_artifacts: Vec<PathBuf>,
    trusted: bool,
    cli_binary: Option<String>,
    cli_version: Option<String>,
    plugin_version: Option<String>,
    backend_kind: Option<String>,
    backend_version: Option<String>,
    protocol_revision: Option<String>,
}

pub(super) fn agent_environment_diagnostic(
    workspace_root: Option<&Path>,
    install_authority: InstallAuthority,
    local_development: Option<&crate::local_development::LocalDevelopmentReceipt>,
    install: Option<&InstallState>,
    binary: &DoctorBinaryDiagnostic,
    issues: &mut Vec<String>,
) -> Result<DoctorAgentEnvironmentDiagnostic> {
    let dialect_revision = packaged_skill_dialect_revision()?;
    let plugin = workspace_root.and_then(plugin_workspace_evidence);
    let backend =
        effective_backend_diagnostic(workspace_root, local_development, install, plugin.as_ref());
    let skills = effective_skill_diagnostic(
        workspace_root,
        local_development,
        install,
        plugin.as_ref(),
        &binary.running_binary,
        dialect_revision,
    )?;
    let guidance = effective_guidance_diagnostic(
        workspace_root,
        local_development,
        install,
        plugin.as_ref(),
        &binary.running_binary,
    )?;
    if backend.state != AgentResourceState::Managed {
        issues.push(
            "Agent readiness could not identify one managed effective semantic backend".to_string(),
        );
    }
    for candidate in skills
        .candidates
        .iter()
        .filter(|candidate| !candidate.compatible)
    {
        let repair = candidate
            .repair_command
            .as_deref()
            .map(|command| format!(" Repair with: {command}"))
            .unwrap_or_default();
        issues.push(format!(
            "Kast skill at {} is {} and does not declare compatible CLI dialect revision {}.{}",
            candidate.path,
            candidate.state.as_str(),
            dialect_revision,
            repair
        ));
    }
    if guidance.state != AgentResourceState::Managed {
        let repair = guidance
            .repair_command
            .as_deref()
            .map(|command| format!(" Repair with: {command}"))
            .unwrap_or_default();
        issues.push(format!(
            "Kast guidance at {} is {}; agent readiness requires managed guidance without replacing user content implicitly.{}",
            guidance.path, guidance.state.as_str(), repair
        ));
    }
    let ok = backend.state == AgentResourceState::Managed
        && skills.compatible
        && guidance.state == AgentResourceState::Managed;
    Ok(DoctorAgentEnvironmentDiagnostic {
        install_authority,
        binary: DoctorAgentBinaryDiagnostic {
            path: binary.running_binary.clone(),
            version: cli::version().to_string(),
            revision: local_development.map_or_else(
                || cli::version().to_string(),
                |receipt| receipt.source.git_commit.as_str().to_string(),
            ),
            source_path: local_development.map_or_else(
                || binary.running_binary.clone(),
                |receipt| receipt.source.canonical_root.display().to_string(),
            ),
            dialect_revision,
        },
        backend,
        skills,
        guidance,
        ok,
    })
}

fn packaged_skill_dialect_revision() -> Result<u32> {
    let identity = parse_skill_front_matter(PACKAGED_KAST_SKILL).map_err(|message| {
        CliError::new(
            "PACKAGED_SKILL_DIALECT_INVALID",
            format!("Packaged Kast skill does not declare a valid CLI dialect: {message}"),
        )
    })?;
    skill_dialect_revision(&identity).ok_or_else(|| {
        CliError::new(
            "PACKAGED_SKILL_DIALECT_INVALID",
            format!("Packaged Kast skill is missing metadata.{KAST_SKILL_DIALECT_METADATA_KEY}"),
        )
    })
}

fn parse_skill_front_matter(content: &str) -> std::result::Result<KastSkillFrontMatter, String> {
    let content = content
        .strip_prefix("---\n")
        .ok_or_else(|| "frontmatter must start with `---`".to_string())?;
    let (front_matter, _) = content
        .split_once("\n---")
        .ok_or_else(|| "frontmatter must end with `---`".to_string())?;
    serde_yaml::from_str(front_matter).map_err(|error| error.to_string())
}

fn skill_dialect_revision(identity: &KastSkillFrontMatter) -> Option<u32> {
    identity
        .metadata
        .get(KAST_SKILL_DIALECT_METADATA_KEY)
        .and_then(|value| value.parse().ok())
}

fn effective_skill_diagnostic(
    workspace_root: Option<&Path>,
    local_development: Option<&crate::local_development::LocalDevelopmentReceipt>,
    install: Option<&InstallState>,
    plugin: Option<&PluginWorkspaceEvidence>,
    running_binary: &str,
    dialect_revision: u32,
) -> Result<DoctorAgentSkillDiagnostic> {
    let mut discovered = Vec::<(PathBuf, String)>::new();
    let mut seen = std::collections::BTreeSet::<PathBuf>::new();
    let mut push = |path: PathBuf, source: &str| {
        let path = config::normalize(path);
        if seen.insert(path.clone()) {
            discovered.push((path, source.to_string()));
        }
    };
    if let Some(local) = local_development {
        push(
            local.components.skill.effective_target.clone(),
            "local-development-receipt",
        );
    }
    if let Some(workspace_root) = workspace_root {
        for relative in WORKSPACE_SKILL_RELATIVES {
            let path = workspace_root.join(relative);
            if path.exists() || plugin_owns_relative(plugin, Path::new(relative)) {
                push(path, "workspace");
            }
        }
        if let Ok(current_directory) = env::current_dir()
            && current_directory.starts_with(workspace_root)
        {
            for ancestor in current_directory.ancestors() {
                push(
                    ancestor.join(WORKSPACE_SKILL_RELATIVES[0]),
                    "workspace-ancestor",
                );
                if ancestor == workspace_root {
                    break;
                }
            }
        }
    }
    let home = config::home_dir();
    push(home.join(".agents/skills/kast/SKILL.md"), "user-home");
    if let Some(codex_home) = env::var_os("CODEX_HOME").filter(|value| !value.is_empty()) {
        push(
            PathBuf::from(codex_home).join("skills/kast/SKILL.md"),
            "codex-home",
        );
    }
    push(
        home.join(".codex/skills/kast/SKILL.md"),
        "legacy-codex-home",
    );
    push(PathBuf::from(CODEX_ADMIN_SKILL), "codex-admin");
    if let Some(install) = install {
        for resource in install
            .repos
            .iter()
            .flat_map(|repo| &repo.resources)
            .filter(|resource| resource.kind == ManagedResourceKind::Skill)
        {
            push(
                PathBuf::from(&resource.target_path).join("SKILL.md"),
                "install-manifest",
            );
        }
    }
    discovered.retain(|(path, _)| path.exists() || managed_skill_resource(install, path).is_some());
    if discovered.is_empty() {
        let path = workspace_root
            .map(|root| root.join(WORKSPACE_SKILL_RELATIVES[0]))
            .unwrap_or_else(|| home.join(".agents/skills/kast/SKILL.md"));
        discovered.push((config::normalize(path), "expected".to_string()));
    }

    let mut candidates = Vec::with_capacity(discovered.len());
    for (path, source) in discovered {
        let managed_resource = managed_skill_resource(install, &path);
        let plugin_owned = workspace_relative(workspace_root, &path)
            .is_some_and(|relative| plugin_owns_relative(plugin, &relative));
        let expected_plugin_skill = plugin_owned
            .then(|| plugin.and_then(|plugin| render_plugin_skill(plugin, dialect_revision)))
            .flatten();
        let local_owned = local_development
            .is_some_and(|local| same_binary_path(&local.components.skill.effective_target, &path));
        let mut state = agent_resource_state(
            &path,
            managed_resource,
            local_owned,
            plugin_owned,
            expected_plugin_skill.as_deref(),
        )?;
        let identity = fs::read_to_string(&path)
            .ok()
            .and_then(|content| parse_skill_front_matter(&content).ok());
        if identity
            .as_ref()
            .is_some_and(|identity| identity.name != "kast")
        {
            state = AgentResourceState::Foreign;
        }
        let name_matches = identity
            .as_ref()
            .is_some_and(|identity| identity.name == "kast");
        let candidate_revision = identity.as_ref().and_then(skill_dialect_revision);
        let compatible = path.is_file()
            && state != AgentResourceState::Modified
            && name_matches
            && candidate_revision == Some(dialect_revision);
        let repair_command = (!compatible).then(|| {
            skill_repair_command(
                workspace_root,
                local_development,
                &path,
                managed_resource.is_some(),
                plugin_owned,
                running_binary,
            )
        });
        candidates.push(DoctorAgentSkillCandidateDiagnostic {
            path: path.display().to_string(),
            source,
            state,
            name: identity.map(|identity| identity.name),
            dialect_revision: candidate_revision,
            compatible,
            repair_command,
        });
    }
    candidates.sort_by(|left, right| left.path.cmp(&right.path));
    Ok(DoctorAgentSkillDiagnostic {
        compatible: candidates.iter().all(|candidate| candidate.compatible),
        candidates,
    })
}

fn managed_skill_resource<'a>(
    install: Option<&'a InstallState>,
    skill_path: &Path,
) -> Option<&'a ManagedRepoResource> {
    install?
        .repos
        .iter()
        .flat_map(|repo| &repo.resources)
        .filter(|resource| resource.kind == ManagedResourceKind::Skill)
        .find(|resource| resource_contains_path(resource, skill_path))
}

fn managed_guidance_resource<'a>(
    install: Option<&'a InstallState>,
    guidance_path: &Path,
) -> Option<&'a ManagedRepoResource> {
    install?
        .repos
        .iter()
        .flat_map(|repo| &repo.resources)
        .filter(|resource| resource.kind == ManagedResourceKind::AgentGuidance)
        .find(|resource| resource_contains_path(resource, guidance_path))
}

fn resource_contains_path(resource: &ManagedRepoResource, path: &Path) -> bool {
    let normalized = config::normalize(path.to_path_buf());
    resource
        .output_paths
        .iter()
        .map(PathBuf::from)
        .map(config::normalize)
        .any(|output| output == normalized)
        || (resource.kind == ManagedResourceKind::Skill
            && config::normalize(PathBuf::from(&resource.target_path).join("SKILL.md"))
                == normalized)
}

fn agent_resource_state(
    path: &Path,
    managed_resource: Option<&ManagedRepoResource>,
    local_owned: bool,
    plugin_owned: bool,
    expected_plugin_skill: Option<&str>,
) -> Result<AgentResourceState> {
    if !path.is_file() {
        return Ok(AgentResourceState::Missing);
    }
    if local_owned {
        return Ok(AgentResourceState::Managed);
    }
    if plugin_owned {
        return Ok(match expected_plugin_skill {
            Some(expected) if fs::read_to_string(path).is_ok_and(|content| content == expected) => {
                AgentResourceState::Managed
            }
            Some(_) => AgentResourceState::Modified,
            None => AgentResourceState::Foreign,
        });
    }
    if let Some(resource) = managed_resource {
        return Ok(if manifest::verify_managed_resource_outputs(resource)?.ok {
            AgentResourceState::Managed
        } else {
            AgentResourceState::Modified
        });
    }
    if path.starts_with("/etc/codex") {
        Ok(AgentResourceState::Foreign)
    } else {
        Ok(AgentResourceState::UserOwned)
    }
}

fn skill_repair_command(
    workspace_root: Option<&Path>,
    local_development: Option<&crate::local_development::LocalDevelopmentReceipt>,
    skill_path: &Path,
    manifest_managed: bool,
    plugin_owned: bool,
    running_binary: &str,
) -> String {
    if let Some(local) = local_development
        && same_binary_path(&local.components.skill.effective_target, skill_path)
    {
        return format!(
            "cd {} && ./gradlew refreshDevelopmentLocal",
            shell_quote_for_remediation(&local.source.canonical_root.display().to_string())
        );
    }
    if plugin_owned {
        return workspace_root.map_or_else(
            || "Open the exact project in IntelliJ IDEA with the Kast plugin enabled".to_string(),
            |workspace_root| {
                format!(
                    "open -a 'IntelliJ IDEA' {}",
                    shell_quote_for_remediation(&workspace_root.display().to_string())
                )
            },
        );
    }
    if manifest_managed
        && let Some(workspace_root) = workspace_root
        && let Some(skill_root) = skill_path.parent().and_then(Path::parent)
    {
        return format!(
            "{} setup --workspace-root {} --skill-target-dir {} --force",
            shell_quote_for_remediation(running_binary),
            shell_quote_for_remediation(&workspace_root.display().to_string()),
            shell_quote_for_remediation(&skill_root.display().to_string()),
        );
    }
    let skill_directory = skill_path.parent().unwrap_or(skill_path);
    let incompatible = unique_quarantine_path(skill_directory);
    format!(
        "mv {} {}",
        shell_quote_for_remediation(&skill_directory.display().to_string()),
        shell_quote_for_remediation(&incompatible.display().to_string()),
    )
}

fn unique_quarantine_path(skill_directory: &Path) -> PathBuf {
    let first = skill_directory.with_extension("incompatible");
    if !first.exists() {
        return first;
    }
    loop {
        let candidate = skill_directory
            .with_extension(format!("incompatible.{}", uuid::Uuid::new_v4().as_simple()));
        if !candidate.exists() {
            return candidate;
        }
    }
}

fn effective_guidance_diagnostic(
    workspace_root: Option<&Path>,
    local_development: Option<&crate::local_development::LocalDevelopmentReceipt>,
    install: Option<&InstallState>,
    plugin: Option<&PluginWorkspaceEvidence>,
    running_binary: &str,
) -> Result<DoctorAgentGuidanceDiagnostic> {
    if let Some(local) = local_development {
        return Ok(DoctorAgentGuidanceDiagnostic {
            path: local
                .components
                .guidance
                .effective_target
                .display()
                .to_string(),
            source: "local-development-receipt".to_string(),
            state: if local.components.guidance.effective_target.is_file() {
                AgentResourceState::Managed
            } else {
                AgentResourceState::Missing
            },
            repair_command: (!local.components.guidance.effective_target.is_file()).then(|| {
                format!(
                    "cd {} && ./gradlew refreshDevelopmentLocal",
                    shell_quote_for_remediation(&local.source.canonical_root.display().to_string())
                )
            }),
        });
    }
    let Some(workspace_root) = workspace_root else {
        return Ok(DoctorAgentGuidanceDiagnostic {
            path: "-".to_string(),
            source: "workspace-root-missing".to_string(),
            state: AgentResourceState::Missing,
            repair_command: None,
        });
    };
    let plugin_path = plugin.filter(|plugin| plugin.trusted).and_then(|plugin| {
        plugin.required_artifacts.iter().find_map(|relative| {
            WORKSPACE_CONTEXT_RELATIVES
                .iter()
                .any(|candidate| relative == Path::new(candidate))
                .then(|| workspace_root.join(relative))
        })
    });
    let manifest_path = install.and_then(|install| {
        install
            .repos
            .iter()
            .filter(|repo| {
                config::normalize(PathBuf::from(&repo.path))
                    == config::normalize(workspace_root.to_path_buf())
            })
            .flat_map(|repo| &repo.resources)
            .filter(|resource| resource.kind == ManagedResourceKind::AgentGuidance)
            .find_map(|resource| resource.output_paths.first().map(PathBuf::from))
    });
    let path = plugin_path
        .or(manifest_path)
        .or_else(|| {
            WORKSPACE_CONTEXT_RELATIVES
                .iter()
                .map(|relative| workspace_root.join(relative))
                .find(|candidate| candidate.exists())
        })
        .unwrap_or_else(|| workspace_root.join("AGENTS.local.md"));
    let managed_resource = managed_guidance_resource(install, &path);
    let plugin_owned = workspace_relative(Some(workspace_root), &path)
        .is_some_and(|relative| plugin_owns_relative(plugin, &relative));
    let managed_resource_matches = managed_resource
        .map(manifest::verify_managed_resource_outputs)
        .transpose()?
        .map(|verification| verification.ok);
    let expected_plugin_region =
        plugin_owned.then(|| render_plugin_guidance_region(workspace_root));
    let state = guidance_resource_state(
        &path,
        managed_resource_matches,
        expected_plugin_region.as_deref(),
    );
    let repair_command = (state != AgentResourceState::Managed).then(|| {
        if cfg!(target_os = "macos") {
            format!(
                "open -a 'IntelliJ IDEA' {}",
                shell_quote_for_remediation(&workspace_root.display().to_string())
            )
        } else {
            format!(
                "{} setup --workspace-root {}",
                shell_quote_for_remediation(running_binary),
                shell_quote_for_remediation(&workspace_root.display().to_string()),
            )
        }
    });
    Ok(DoctorAgentGuidanceDiagnostic {
        path: path.display().to_string(),
        source: if plugin_owned {
            "plugin-workspace-metadata"
        } else if managed_resource.is_some() {
            "install-manifest"
        } else {
            "workspace-context"
        }
        .to_string(),
        state,
        repair_command,
    })
}

fn guidance_resource_state(
    path: &Path,
    managed_resource_matches: Option<bool>,
    expected_plugin_region: Option<&str>,
) -> AgentResourceState {
    if !path.is_file() {
        return AgentResourceState::Missing;
    }
    if let Some(matches) = managed_resource_matches {
        return if matches {
            AgentResourceState::Managed
        } else {
            AgentResourceState::Modified
        };
    }
    let content = fs::read_to_string(path).unwrap_or_default();
    let has_managed_region = content.contains("<kast>") && content.contains("</kast>");
    if let Some(expected) = expected_plugin_region {
        if !has_managed_region {
            AgentResourceState::Foreign
        } else if content.contains(expected) {
            AgentResourceState::Managed
        } else {
            AgentResourceState::Modified
        }
    } else if has_managed_region {
        AgentResourceState::Foreign
    } else {
        AgentResourceState::UserOwned
    }
}

fn render_plugin_guidance_region(workspace_root: &Path) -> String {
    let skill_path = workspace_root.join(WORKSPACE_SKILL_RELATIVES[0]);
    [
        "<kast>".to_string(),
        "## Kast routing".to_string(),
        format!(
            "Use `{}` before Kotlin or Gradle semantic work.",
            skill_path.display()
        ),
        "Use `kast agent verify --workspace-root \"$PWD\"` to verify the plugin-prepared workspace."
            .to_string(),
        "Use typed commands such as `kast agent symbol --query <name>`, `kast agent diagnostics --file-path <path>`, and `kast agent rename --symbol <fq-name> --new-name <name> --apply`.".to_string(),
        "Do not run `kast setup` on macOS; the IntelliJ plugin owns workspace bootstrap."
            .to_string(),
        "Before each linked worker starts, open the exact worktree root as its own IDE project and run `kast agent verify --workspace-root \"$PWD\"` from that worktree.".to_string(),
        "Never reuse another worktree's Kast runtime, metadata, or semantic evidence."
            .to_string(),
        "Keep the IDE project open while active; close its exact IDE project or window before removing the worktree.".to_string(),
        "</kast>".to_string(),
    ]
    .join("\n")
}

fn render_plugin_skill(plugin: &PluginWorkspaceEvidence, dialect_revision: u32) -> Option<String> {
    let plugin_version = plugin.plugin_version.as_deref()?;
    let cli_version = plugin.cli_version.as_deref()?;
    let cli_binary = plugin.cli_binary.as_deref()?;
    Some(format!(
        "---\nname: kast\ndescription: Kotlin semantic work and linked-worktree lifecycle in Gradle repositories prepared by the Kast IntelliJ plugin.\nmetadata:\n  kast-cli-dialect-revision: \"{dialect_revision}\"\n---\n\n# Kast\n\nThis workspace was prepared by the Kast IntelliJ plugin. JetBrains owns plugin installation and updates; Homebrew owns only the CLI.\n\nUse `kast agent verify --workspace-root \"$PWD\"` before Kotlin semantic work when state is uncertain.\nUse typed commands such as `kast agent symbol`, `kast agent diagnostics`, `kast agent impact`, and `kast agent rename`.\nDo not run `kast setup` or install runtime resources separately on macOS; update the CLI and plugin, reopen this exact project, and refresh metadata when compatibility fails.\n\n## Linked Worktrees\n\nFor every delegated worker using a linked Git worktree:\n\n1. Before the worker starts, open the exact worktree root as its own IntelliJ IDEA or Android Studio project with the Kast plugin enabled.\n2. Wait for `.kast/setup/workspace.json`, then run `kast agent verify --workspace-root \"$PWD\"` from that worktree.\n3. Never reuse another worktree's Kast runtime, metadata, or semantic evidence.\n4. Keep that IDE project open while the worker and worktree are active.\n5. Before retiring or deleting the worktree, close that exact IDE project or window before removing the worktree.\n\nPrepared plugin version: {plugin_version}\nCLI version: {cli_version}\nCLI invocation: `{cli_binary}`\n"
    ))
}

fn effective_backend_diagnostic(
    workspace_root: Option<&Path>,
    local_development: Option<&crate::local_development::LocalDevelopmentReceipt>,
    install: Option<&InstallState>,
    plugin: Option<&PluginWorkspaceEvidence>,
) -> DoctorAgentBackendDiagnostic {
    if let Some(local) = local_development {
        return DoctorAgentBackendDiagnostic {
            state: AgentResourceState::Managed,
            kind: Some("headless".to_string()),
            version: Some(local.backend.implementation_version.clone()),
            revision: Some(local.source.git_commit.as_str().to_string()),
            source_path: Some(
                local
                    .components
                    .backend
                    .effective_target
                    .display()
                    .to_string(),
            ),
        };
    }
    if let Some(plugin) = plugin
        && plugin.trusted
        && plugin.backend_kind.is_some()
        && plugin.backend_version.is_some()
    {
        return DoctorAgentBackendDiagnostic {
            state: AgentResourceState::Managed,
            kind: plugin.backend_kind.clone(),
            version: plugin.backend_version.clone(),
            revision: plugin.protocol_revision.clone(),
            source_path: Some(plugin.metadata_path.display().to_string()),
        };
    }
    if let Some(backend) = install.and_then(|install| install.backends.first()) {
        return DoctorAgentBackendDiagnostic {
            state: if Path::new(&backend.runtime_libs_dir)
                .join("classpath.txt")
                .is_file()
            {
                AgentResourceState::Managed
            } else {
                AgentResourceState::Missing
            },
            kind: Some(backend.name.clone()),
            version: Some(backend.version.clone()),
            revision: None,
            source_path: Some(backend.runtime_libs_dir.clone()),
        };
    }
    DoctorAgentBackendDiagnostic {
        state: AgentResourceState::Missing,
        kind: None,
        version: None,
        revision: None,
        source_path: workspace_root.map(|root| root.display().to_string()),
    }
}

fn plugin_workspace_evidence(workspace_root: &Path) -> Option<PluginWorkspaceEvidence> {
    let metadata_path = workspace_root.join(".kast/setup/workspace.json");
    let raw = fs::read_to_string(&metadata_path).ok()?;
    let metadata: serde_json::Value = serde_json::from_str(&raw).ok()?;
    if metadata
        .get("preparedBy")
        .and_then(serde_json::Value::as_str)
        != Some("kast-intellij-plugin")
    {
        return None;
    }
    let required_artifacts = metadata
        .get("requiredArtifacts")
        .and_then(serde_json::Value::as_array)?
        .iter()
        .filter_map(serde_json::Value::as_str)
        .map(PathBuf::from)
        .collect();
    let compatibility = metadata.get("compatibility");
    let runtime_identity = compatibility.and_then(|value| value.get("runtimeIdentity"));
    #[cfg(target_os = "macos")]
    let trusted = validate_macos_plugin_workspace(workspace_root).is_ok();
    #[cfg(not(target_os = "macos"))]
    let trusted = false;
    Some(PluginWorkspaceEvidence {
        metadata_path,
        required_artifacts,
        trusted,
        cli_binary: metadata
            .get("cliBinary")
            .and_then(serde_json::Value::as_str)
            .map(str::to_string),
        cli_version: compatibility
            .and_then(|value| value.get("cliVersion"))
            .and_then(serde_json::Value::as_str)
            .map(str::to_string),
        plugin_version: compatibility
            .and_then(|value| value.get("pluginVersion"))
            .and_then(serde_json::Value::as_str)
            .map(str::to_string),
        backend_kind: runtime_identity
            .and_then(|value| value.get("backendKind"))
            .and_then(serde_json::Value::as_str)
            .map(str::to_ascii_lowercase),
        backend_version: runtime_identity
            .and_then(|value| value.get("implementationVersion"))
            .and_then(serde_json::Value::as_str)
            .map(str::to_string),
        protocol_revision: compatibility
            .and_then(|value| value.get("protocolRevision"))
            .and_then(serde_json::Value::as_u64)
            .map(|revision| revision.to_string()),
    })
}

fn plugin_owns_relative(plugin: Option<&PluginWorkspaceEvidence>, relative: &Path) -> bool {
    plugin.is_some_and(|plugin| {
        plugin.trusted
            && plugin
                .required_artifacts
                .iter()
                .any(|artifact| artifact == relative)
    })
}

fn workspace_relative(workspace_root: Option<&Path>, path: &Path) -> Option<PathBuf> {
    path.strip_prefix(workspace_root?)
        .ok()
        .map(Path::to_path_buf)
}

#[cfg(test)]
mod agent_readiness_tests {
    use super::*;

    #[test]
    fn guidance_states_distinguish_ownership_and_integrity() {
        let temp = tempfile::tempdir().expect("tempdir");
        let missing = temp.path().join("missing.md");
        assert_eq!(
            guidance_resource_state(&missing, None, None),
            AgentResourceState::Missing
        );

        let guidance = temp.path().join("AGENTS.local.md");
        fs::write(&guidance, "user guidance\n").expect("user guidance");
        assert_eq!(
            guidance_resource_state(&guidance, None, None),
            AgentResourceState::UserOwned
        );
        assert_eq!(
            guidance_resource_state(&guidance, None, Some("<kast>\nmanaged guidance\n</kast>")),
            AgentResourceState::Foreign
        );
        assert_eq!(
            guidance_resource_state(&guidance, Some(false), None),
            AgentResourceState::Modified
        );

        fs::write(&guidance, "<kast>\nmanaged guidance\n</kast>\n").expect("managed guidance");
        assert_eq!(
            guidance_resource_state(&guidance, None, Some("<kast>\nmanaged guidance\n</kast>"),),
            AgentResourceState::Managed
        );
        assert_eq!(
            guidance_resource_state(&guidance, None, None),
            AgentResourceState::Foreign
        );
        assert_eq!(
            guidance_resource_state(&guidance, None, Some("<kast>\nchanged guidance\n</kast>"),),
            AgentResourceState::Modified
        );
    }
}
