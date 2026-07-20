use serde_json::Value;
use std::path::Path;

#[test]
fn generated_codex_plugin_is_skill_only() {
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
    assert!(!root.join("plugins/kast/hooks").exists());
}
