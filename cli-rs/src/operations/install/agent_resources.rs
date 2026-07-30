const KAST_AGENT_SKILL: &str =
    include_str!(concat!(env!("CARGO_MANIFEST_DIR"), "/resources/kast/SKILL.md"));
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

pub fn install_agent_resources(requested: &[KastHarness]) -> Result<()> {
    let selected = [KastHarness::Codex, KastHarness::Claude, KastHarness::Copilot]
        .into_iter()
        .filter(|harness| requested.contains(harness))
        .collect::<Vec<_>>();
    let mut failures = Vec::new();
    for harness in selected {
        if let Err(message) = install_agent_harness(harness) {
            failures.push(format!("{}: {message}", harness_name(harness)));
        }
    }
    if failures.is_empty() {
        return Ok(());
    }
    Err(CliError::new(
        "KAST_AGENT_RESOURCE_INSTALL_FAILED",
        format!(
            "Agent resources could not be installed for {}.",
            failures.join("; ")
        ),
    ))
}

fn install_agent_harness(harness: KastHarness) -> std::result::Result<(), String> {
    let marketplace_root = materialize_agent_harness(harness).map_err(|error| error.to_string())?;
    let root = marketplace_root.display().to_string();
    let commands = match harness {
        KastHarness::Codex => vec![
            vec![
                "plugin".to_string(),
                "marketplace".to_string(),
                "add".to_string(),
                root,
                "--json".to_string(),
            ],
            vec![
                "plugin".to_string(),
                "add".to_string(),
                "kast@kast".to_string(),
                "--json".to_string(),
            ],
        ],
        KastHarness::Claude => vec![
            vec![
                "plugin".to_string(),
                "marketplace".to_string(),
                "add".to_string(),
                root,
                "--scope".to_string(),
                "user".to_string(),
            ],
            vec![
                "plugin".to_string(),
                "install".to_string(),
                "kast@kast".to_string(),
                "--scope".to_string(),
                "user".to_string(),
            ],
        ],
        KastHarness::Copilot => vec![
            vec![
                "plugin".to_string(),
                "marketplace".to_string(),
                "add".to_string(),
                root,
            ],
            vec![
                "plugin".to_string(),
                "install".to_string(),
                "kast@kast".to_string(),
            ],
        ],
    };
    for args in commands {
        run_agent_harness_command(harness, &args)?;
    }
    Ok(())
}

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
    let skill = render_agent_resource(KAST_AGENT_SKILL);
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
        ("plugins/kast/skills/kast/SKILL.md", skill),
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

fn agent_resources_digest() -> String {
    let mut digest = Sha256::new();
    for (path, contents) in [
        ("SKILL.md", KAST_AGENT_SKILL),
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
        digest.update(path.as_bytes());
        digest.update([0]);
        digest.update(render_agent_resource(contents).as_bytes());
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
