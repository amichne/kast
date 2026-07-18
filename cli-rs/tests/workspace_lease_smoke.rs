use std::process::Command;

#[test]
fn agent_exposes_the_typed_workspace_lease_lifecycle() {
    for command in ["acquire", "status", "release"] {
        let output = Command::new(env!("CARGO_BIN_EXE_kast"))
            .args(["agent", "lease", command, "--help"])
            .output()
            .expect("workspace lease help");

        assert!(
            output.status.success(),
            "agent lease {command} must be a typed command: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}
