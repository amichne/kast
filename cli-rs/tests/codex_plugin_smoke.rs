use serde_json::Value;
use std::collections::BTreeSet;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

#[test]
fn generated_codex_plugin_has_only_advisory_session_and_write_hooks() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("resources/codex-plugin");
    let manifest: Value = serde_json::from_slice(
        &std::fs::read(root.join("plugins/kast/.codex-plugin/plugin.json")).expect("manifest"),
    )
    .expect("manifest JSON");
    assert!(manifest.get("hooks").is_none());
    assert!(
        root.join("plugins/kast/skills/kast-codex/SKILL.md")
            .is_file()
    );
    let hooks: Value = serde_json::from_slice(
        &std::fs::read(root.join("plugins/kast/hooks/hooks.json")).expect("hooks"),
    )
    .expect("hooks JSON");
    let events = hooks["hooks"]
        .as_object()
        .expect("hook events")
        .keys()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    assert_eq!(events, BTreeSet::from(["PostToolUse", "SessionStart"]));
    assert_eq!(hooks["hooks"]["SessionStart"][0]["matcher"], "startup");
    assert_eq!(
        hooks["hooks"]["PostToolUse"][0]["matcher"],
        "apply_patch|Edit|Write"
    );
    let launcher = root.join("plugins/kast/scripts/kast-codex-hook");
    assert!(
        std::fs::metadata(launcher)
            .expect("launcher")
            .permissions()
            .mode()
            & 0o111
            != 0
    );
}
