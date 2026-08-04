use std::fs;
use std::path::Path;

pub(super) fn assert_developer_route_sources(root: &Path) {
    let kast_skill = fs::read_to_string(root.join("SKILL.md")).expect("read Kast skill");
    assert!(kast_skill.contains("/kast:developer"), "{kast_skill}");
    assert!(
        kast_skill.contains("developerOperations.cli"),
        "{kast_skill}"
    );
    let developer_skill =
        fs::read_to_string(root.join("developer/SKILL.md")).expect("read developer skill");
    for instruction in [
        "developerOperations.cli",
        "developerOperations.helpArgs",
        "Do not assume `kastctl` is on `PATH`",
    ] {
        assert!(developer_skill.contains(instruction), "{developer_skill}");
    }
}
