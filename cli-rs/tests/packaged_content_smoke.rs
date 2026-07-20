#[test]
fn packaged_skill_describes_public_synchronous_mutations() {
    let skill = include_str!("../resources/kast-skill/SKILL.md");
    assert!(skill.contains("kast-cli-dialect-revision: \"3\""));
    assert!(skill.contains("`kast agent --help`"));
    assert!(skill.contains("synchronously"));
    for retired in [
        "agent task",
        "task lifecycle",
        "finish barrier",
        "workspaceTaskId",
    ] {
        assert!(!skill.contains(retired), "found retired contract {retired}");
    }
}

#[test]
fn codex_skill_uses_the_same_terminal_contract() {
    let skill = include_str!("../resources/codex-plugin/plugins/kast/skills/kast-codex/SKILL.md");
    assert!(skill.contains("`kast agent`"));
    assert!(skill.contains("synchronously"));
    assert!(!skill.contains("hook"));
}
