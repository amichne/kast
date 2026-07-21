mod support;

use support::*;

#[test]
fn setup_defers_to_the_codex_plugin_without_touching_existing_guidance() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let workspace = temp.path().join("workspace");
    let agents = workspace.join("AGENTS.md");
    let local = workspace.join("AGENTS.local.md");
    let skill = workspace.join(".agents/skills/kast/SKILL.md");
    std::fs::create_dir_all(skill.parent().expect("skill parent")).expect("skill parent");
    std::fs::write(&agents, "repository guidance\n").expect("agents");
    std::fs::write(&local, "legacy local guidance\n").expect("local guidance");
    std::fs::write(&skill, "legacy skill\n").expect("legacy skill");

    let setup = kast(&home, &config_home)
        .current_dir(&workspace)
        .args(["--output", "json", "setup"])
        .output()
        .expect("setup");

    assert!(!setup.status.success());
    let payload: serde_json::Value = serde_json::from_slice(&setup.stdout).expect("setup JSON");
    assert_eq!(payload["error"]["code"], "AGENT_COMMAND_REMOVED");
    assert!(
        payload["error"]["message"]
            .as_str()
            .expect("message")
            .contains("Kast Codex plugin")
    );
    let replacements = payload["error"]["details"]["replacements"]
        .as_array()
        .expect("replacements");
    assert!(replacements.iter().any(|replacement| {
        replacement == "codex plugin marketplace add amichne/kast-marketplace --ref main"
    }));
    assert_eq!(
        std::fs::read_to_string(agents).expect("agents"),
        "repository guidance\n"
    );
    assert_eq!(
        std::fs::read_to_string(local).expect("local guidance"),
        "legacy local guidance\n"
    );
    assert_eq!(
        std::fs::read_to_string(skill).expect("legacy skill"),
        "legacy skill\n"
    );
}
