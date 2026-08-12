const KAST_AGENT_SKILL: &str =
    include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/resources/kast/SKILL.md"));
const KAST_DEVELOPER_SKILL: &str = include_str!("../../../resources/kast/developer/SKILL.md");
const KAST_CODEX_MARKETPLACE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast/codex/marketplace.json"
));
const KAST_CODEX_PLUGIN: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast/codex/plugin.json"
));
const KAST_CODEX_HOOKS: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast/codex/hooks.json"
));
const KAST_CLAUDE_MARKETPLACE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast/claude/marketplace.json"
));
const KAST_CLAUDE_PLUGIN: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast/claude/plugin.json"
));
const KAST_CLAUDE_HOOKS: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast/claude/hooks.json"
));
const KAST_COPILOT_MARKETPLACE: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast/copilot/marketplace.json"
));
const KAST_COPILOT_PLUGIN: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast/copilot/plugin.json"
));
const KAST_COPILOT_HOOKS: &str = include_str!(concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/resources/kast/copilot/hooks.json"
));

const KAST_VERSION_PLACEHOLDER: &str = "${KAST_VERSION}";

#[derive(Debug, serde::Deserialize)]
struct InstalledAgentPluginIdentity {
    name: String,
    version: String,
}

struct AgentResourceDigestOverrides<'a> {
    provider: &'static str,
    hooks: &'a str,
    plugin: &'a str,
    kast_skill: &'a str,
    developer_skill: &'a str,
}

include!("bundle_entrypoint/agent_resource_install.rs");

fn run_agent_harness_command(
    harness: KastHarness,
    args: &[String],
) -> std::result::Result<(), String> {
    let output = ProcessCommand::new(harness_name(harness))
        .args(args)
        .output()
        .map_err(|error| format!("could not start {}: {error}", harness_name(harness)))?;
    if output.status.success() {
        return Ok(());
    }
    let detail = String::from_utf8_lossy(&output.stderr).trim().to_string();
    let detail = if detail.is_empty() {
        String::from_utf8_lossy(&output.stdout).trim().to_string()
    } else {
        detail
    };
    let status = output.status.code().unwrap_or(-1);
    if detail.is_empty() {
        Err(format!("installation command exited with status {status}"))
    } else {
        Err(format!(
            "installation command exited with status {status}: {detail}"
        ))
    }
}

fn materialize_agent_harness(harness: KastHarness) -> Result<PathBuf> {
    let digest = agent_resources_digest();
    let root = manifest::default_install_root()
        .join("state")
        .join("agent-resources")
        .join(digest)
        .join(harness_name(harness));
    let kast_skill = render_agent_resource(KAST_AGENT_SKILL);
    let developer_skill = render_agent_resource(KAST_DEVELOPER_SKILL);
    let (marketplace_path, plugin_path, hooks_path, marketplace, plugin, hooks) = match harness {
        KastHarness::Codex => (
            ".agents/plugins/marketplace.json",
            "plugins/kast/.codex-plugin/plugin.json",
            "plugins/kast/hooks/hooks.json",
            KAST_CODEX_MARKETPLACE,
            KAST_CODEX_PLUGIN,
            KAST_CODEX_HOOKS,
        ),
        KastHarness::Claude => (
            ".claude-plugin/marketplace.json",
            "plugins/kast/.claude-plugin/plugin.json",
            "plugins/kast/hooks/hooks.json",
            KAST_CLAUDE_MARKETPLACE,
            KAST_CLAUDE_PLUGIN,
            KAST_CLAUDE_HOOKS,
        ),
        KastHarness::Copilot => (
            ".github/plugin/marketplace.json",
            "plugins/kast/plugin.json",
            "plugins/kast/hooks.json",
            KAST_COPILOT_MARKETPLACE,
            KAST_COPILOT_PLUGIN,
            KAST_COPILOT_HOOKS,
        ),
    };
    for (relative, contents) in [
        (marketplace_path, render_agent_resource(marketplace)),
        (plugin_path, render_agent_resource(plugin)),
        (hooks_path, render_agent_resource(hooks)),
        ("plugins/kast/skills/kast/SKILL.md", kast_skill),
        (
            "plugins/kast/skills/developer/SKILL.md",
            developer_skill,
        ),
    ] {
        let target = root.join(relative);
        let parent = target.parent().ok_or_else(|| {
            CliError::new(
                "KAST_AGENT_RESOURCE_PATH_INVALID",
                format!("Embedded resource path has no parent: {relative}"),
            )
        })?;
        fs::create_dir_all(parent)?;
        if fs::read(&target).is_ok_and(|current| current == contents.as_bytes()) {
            continue;
        }
        fs::write(target, contents)?;
    }
    Ok(root)
}

pub(crate) fn validate_agent_harness_activation(
    harness: KastHarness,
    plugin_root: &Path,
) -> Result<()> {
    let (plugin_path, hooks_path, expected_plugin, expected_hooks) = match harness {
        KastHarness::Codex => (
            ".codex-plugin/plugin.json",
            "hooks/hooks.json",
            KAST_CODEX_PLUGIN,
            KAST_CODEX_HOOKS,
        ),
        KastHarness::Claude => (
            ".claude-plugin/plugin.json",
            "hooks/hooks.json",
            KAST_CLAUDE_PLUGIN,
            KAST_CLAUDE_HOOKS,
        ),
        KastHarness::Copilot => (
            "plugin.json",
            "hooks.json",
            KAST_COPILOT_PLUGIN,
            KAST_COPILOT_HOOKS,
        ),
    };
    let expected_version = crate::cli::version();
    let expected_digest = agent_resources_digest();
    let repair_command = format!(
        "kast __internal resources install --force --harness {}",
        harness_name(harness)
    );
    let read_resource = |relative: &str| {
        fs::read_to_string(plugin_root.join(relative)).map_err(|error| {
            activation_error(
                harness,
                format!("{relative} is unavailable: {error}"),
                "UNAVAILABLE",
                "UNAVAILABLE",
                expected_version,
                &expected_digest,
                &repair_command,
            )
        })
    };
    let plugin = read_resource(plugin_path)?;
    let hooks = read_resource(hooks_path)?;
    let kast_skill = read_resource("skills/kast/SKILL.md")?;
    let developer_skill = read_resource("skills/developer/SKILL.md")?;
    let detected_identity = serde_json::from_str::<InstalledAgentPluginIdentity>(&plugin).ok();
    let detected_version = detected_identity
        .as_ref()
        .map(|identity| identity.version.as_str())
        .unwrap_or("INVALID");
    let detected_digest = agent_resources_digest_with(Some(&AgentResourceDigestOverrides {
        provider: harness_name(harness),
        hooks: &hooks,
        plugin: &plugin,
        kast_skill: &kast_skill,
        developer_skill: &developer_skill,
    }));
    let expected_plugin = render_agent_resource(expected_plugin);
    let expected_hooks = render_agent_resource(expected_hooks);
    let expected_kast_skill = render_agent_resource(KAST_AGENT_SKILL);
    let expected_developer_skill = render_agent_resource(KAST_DEVELOPER_SKILL);
    let mut mismatches = Vec::new();
    if detected_identity
        .as_ref()
        .is_none_or(|identity| identity.name != "kast" || identity.version != expected_version)
    {
        mismatches.push("version mismatch");
    }
    if plugin != expected_plugin {
        mismatches.push("plugin digest mismatch");
    }
    if hooks != expected_hooks {
        mismatches.push("hook digest mismatch");
    }
    if kast_skill != expected_kast_skill || developer_skill != expected_developer_skill {
        mismatches.push("skill digest mismatch");
    }
    if mismatches.is_empty() && detected_digest == expected_digest {
        return Ok(());
    }
    if mismatches.is_empty() {
        mismatches.push("resource digest mismatch");
    }
    Err(activation_error(
        harness,
        mismatches.join(", "),
        detected_version,
        &detected_digest,
        expected_version,
        &expected_digest,
        &repair_command,
    ))
}

fn activation_error(
    harness: KastHarness,
    mismatch: impl Into<String>,
    detected_version: &str,
    detected_digest: &str,
    expected_version: &str,
    expected_digest: &str,
    repair_command: &str,
) -> CliError {
    let mismatch = mismatch.into();
    let mut error = CliError::new(
        "KAST_AGENT_RESOURCES_INCOMPATIBLE",
        format!(
            "{} harness activation rejected: {mismatch}.",
            harness_name(harness)
        ),
    );
    for (key, value) in [
        ("detectedVersion", detected_version),
        ("expectedVersion", expected_version),
        ("detectedDigest", detected_digest),
        ("expectedDigest", expected_digest),
        ("repairCommand", repair_command),
    ] {
        error.details.insert(key.to_string(), value.to_string());
    }
    error
}

fn agent_resources_digest() -> String {
    agent_resources_digest_with(None)
}

fn agent_resources_digest_with(overrides: Option<&AgentResourceDigestOverrides<'_>>) -> String {
    let mut digest = Sha256::new();
    for (path, contents) in [
        ("skills/kast/SKILL.md", KAST_AGENT_SKILL),
        ("skills/developer/SKILL.md", KAST_DEVELOPER_SKILL),
        ("claude/hooks.json", KAST_CLAUDE_HOOKS),
        ("claude/marketplace.json", KAST_CLAUDE_MARKETPLACE),
        ("claude/plugin.json", KAST_CLAUDE_PLUGIN),
        ("codex/hooks.json", KAST_CODEX_HOOKS),
        ("codex/marketplace.json", KAST_CODEX_MARKETPLACE),
        ("codex/plugin.json", KAST_CODEX_PLUGIN),
        ("copilot/hooks.json", KAST_COPILOT_HOOKS),
        ("copilot/marketplace.json", KAST_COPILOT_MARKETPLACE),
        ("copilot/plugin.json", KAST_COPILOT_PLUGIN),
    ] {
        let overridden = overrides.and_then(|overrides| match path {
            "skills/kast/SKILL.md" => Some(overrides.kast_skill),
            "skills/developer/SKILL.md" => Some(overrides.developer_skill),
            path if path == format!("{}/hooks.json", overrides.provider) => {
                Some(overrides.hooks)
            }
            path if path == format!("{}/plugin.json", overrides.provider) => {
                Some(overrides.plugin)
            }
            _ => None,
        });
        let contents = overridden
            .map(str::to_string)
            .unwrap_or_else(|| render_agent_resource(contents));
        digest.update(path.as_bytes());
        digest.update([0]);
        digest.update(contents.as_bytes());
        digest.update([0]);
    }
    hex::encode(digest.finalize())
}

fn render_agent_resource(contents: &str) -> String {
    contents.replace(KAST_VERSION_PLACEHOLDER, crate::cli::version())
}

fn harness_name(harness: KastHarness) -> &'static str {
    match harness {
        KastHarness::Codex => "codex",
        KastHarness::Claude => "claude",
        KastHarness::Copilot => "copilot",
    }
}
