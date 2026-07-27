#[test]
fn workspace_files_is_public() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let output = kast(&home, &config_home)
        .args(["agent", "workspace-files", "--help"])
        .output()
        .expect("workspace-files help");

    assert!(
        output.status.success(),
        "stdout={} stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let help = String::from_utf8_lossy(&output.stdout);
    for example in [
        "kast agent workspace-files --workspace-root /workspace --module backend:kast.analysis-api.main --package root",
        "kast agent workspace-files --workspace-root /workspace --module gradle:included/tools#:app --package named:com.example",
        "kast agent workspace-files --workspace-root /workspace --kind script --fields path,module",
    ] {
        assert!(
            help.contains(example),
            "missing example `{example}`: {help}"
        );
    }
    for selector_grammar in [
        "backend:<name>",
        "gradle:<root>#<path>",
        "root",
        "named:<fq-name>",
    ] {
        assert!(
            help.contains(selector_grammar),
            "missing selector grammar `{selector_grammar}`: {help}"
        );
    }
}
