use std::os::unix::process::CommandExt;
use std::process::Command;

#[test]
fn user_callable_workspace_leases_are_hard_removed() {
    for command in ["acquire", "status", "release"] {
        let output = Command::new(env!("CARGO_BIN_EXE_kast"))
            .arg0("kastctl")
            .args(["agent", "lease", command, "--help"])
            .output()
            .expect("retired workspace lease command");

        assert!(
            !output.status.success(),
            "agent lease {command} remained callable: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
}
