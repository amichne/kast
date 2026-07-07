mod support;

use support::*;

#[test]
fn packaged_agent_call_helper_reports_removed_surface() {
    let temp = tempfile::tempdir().expect("tempdir");
    let workspace = temp.path().join("workspace");
    let fake_bin = temp.path().join("kast");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(&fake_bin, "#!/bin/sh\nexit 64\n").expect("fake kast");
    set_executable_for_test(&fake_bin);

    let script = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("resources/kast-skill/scripts/kast-agent-call.py");
    let call = Command::new("python3")
        .arg(&script)
        .arg("symbol/query")
        .arg("--params-json")
        .arg(r#"{"query":"Widget"}"#)
        .arg("--workspace-root")
        .arg(&workspace)
        .arg("--kast-bin")
        .arg(&fake_bin)
        .output()
        .expect("run packaged call helper");

    assert!(
        !call.status.success(),
        "removed helper should fail closed: stdout={}, stderr={}",
        String::from_utf8_lossy(&call.stdout),
        String::from_utf8_lossy(&call.stderr)
    );
    assert!(
        call.stderr.is_empty(),
        "removed helper should report structured stdout only: {}",
        String::from_utf8_lossy(&call.stderr)
    );

    let stdout: serde_json::Value = serde_json::from_slice(&call.stdout).expect("helper json");
    assert_eq!(stdout["ok"], false, "{stdout:#}");
    assert_eq!(
        stdout["type"], "KAST_AGENT_CALL_HELPER_REMOVED",
        "{stdout:#}"
    );
    assert_eq!(
        stdout["issue"]["code"], "KAST_AGENT_CALL_HELPER_REMOVED",
        "{stdout:#}"
    );
    let replacements = stdout["replacements"]
        .as_array()
        .expect("replacement commands");
    for command in [
        "kast agent symbol --query <name> --workspace-root <repo>",
        "kast agent diagnostics --file-path <path> --workspace-root <repo>",
        "kast agent impact --symbol <fq-name> --workspace-root <repo>",
        "kast agent rename --symbol <fq-name> --new-name <name> --workspace-root <repo>",
        "kast help agent",
    ] {
        assert!(
            replacements
                .iter()
                .any(|replacement| replacement == command),
            "missing replacement {command}: {stdout:#}"
        );
    }
}
