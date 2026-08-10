use std::os::unix::process::CommandExt;

#[test]
fn interactive_runtime_repair_is_not_callable() {
    let output = std::process::Command::new(env!("CARGO_BIN_EXE_kast"))
        .arg0("kastctl")
        .args(["developer", "runtime", "repair", "--help"])
        .output()
        .expect("retired repair command");

    assert!(!output.status.success(), "runtime repair remained callable");
}
