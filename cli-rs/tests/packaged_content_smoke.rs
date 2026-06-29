mod support;

use support::*;

#[test]
fn packaged_skill_targets_rust_kast_only() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let skill = std::fs::read_to_string(root.join("resources/kast-skill/SKILL.md"))
        .expect("packaged skill");
    let quickstart =
        std::fs::read_to_string(root.join("resources/kast-skill/references/quickstart.md"))
            .expect("packaged skill quickstart");
    let workflows =
        std::fs::read_to_string(root.join("resources/kast-skill/references/workflows.md"))
            .expect("packaged skill workflows");
    let routing_reference = std::fs::read_to_string(
        root.join("resources/kast-skill/references/routing-improvement.md"),
    )
    .expect("routing reference");
    let instruction_cli = std::fs::read_to_string(root.join("resources/kast-instructions/cli.md"))
        .expect("portable CLI instructions");
    let instruction_tools =
        std::fs::read_to_string(root.join("resources/kast-instructions/tools.md"))
            .expect("portable tool instructions");
    let instruction_rpc = std::fs::read_to_string(root.join("resources/kast-instructions/rpc.md"))
        .expect("portable RPC instructions");

    assert!(skill.contains("Rust `kast` CLI"));
    assert!(skill.contains("command -v kast"));
    assert!(skill.contains("kast agent --help"));
    assert!(skill.contains("kast agent tools"));
    assert!(skill.contains("kast agent workflow --help"));
    assert!(skill.contains("scripts/verify-kast-state.py"));
    assert!(skill.contains("scripts/kast-agent-call.py"));
    assert!(!skill.contains("scripts/kast-semantic-workflow.py"));
    assert!(skill.contains("kast agent workflow ..."));
    assert!(skill.contains("Use for Gradle project file work"));
    assert!(skill.contains("assume the binary installed it"));
    assert!(skill.contains("`kast` directly"));
    assert!(skill.contains("active binary are incompatible"));
    assert!(skill.contains("project file operations"));
    assert!(skill.contains("Use Kast to discover the owning module"));
    assert!(skill.contains("when the path is not already exact"));
    assert!(skill.contains("Unknown symbol"));
    assert!(skill.contains("symbol/query"));
    assert!(skill.contains("raw/workspace-files"));
    assert!(skill.contains("includeFiles=false"));
    assert!(skill.contains("kast inspect metrics fan-in"));
    assert!(skill.contains("kast inspect demo"));
    assert!(skill.contains("raw/type-hierarchy"));
    assert!(skill.contains("raw/semantic-insertion-point"));
    assert!(skill.contains("raw/completions"));
    assert!(skill.contains("raw/apply-edits"));
    assert!(skill.contains("kast runtime up --workspace-root \"$PWD\" --backend idea"));
    assert!(quickstart.contains("command -v kast"));
    assert!(quickstart.contains("kast agent --help"));
    assert!(quickstart.contains("kast agent tools"));
    assert!(quickstart.contains("kast agent workflow --help"));
    assert!(quickstart.contains("kast agent call"));
    assert!(quickstart.contains("result.invocation.argv"));
    assert!(quickstart.contains("schemaVersion >= 3"));
    assert!(quickstart.contains("matching `toolCount`"));
    assert!(quickstart.contains("scripts/verify-kast-state.py"));
    assert!(quickstart.contains("scripts/kast-agent-call.py"));
    assert!(!quickstart.contains("scripts/kast-semantic-workflow.py"));
    assert!(quickstart.contains("active binary are incompatible"));
    assert!(quickstart.contains("incompatible. Upgrade or reinstall Kast"));
    assert!(quickstart.contains("raw transport/debug escape hatch"));
    assert!(quickstart.contains("kast inspect metrics impact"));
    assert!(quickstart.contains("kast inspect demo"));
    assert!(quickstart.contains("INDEX_UNAVAILABLE"));
    assert!(quickstart.contains("kast runtime up --workspace-root \"$PWD\" --backend idea"));
    assert!(quickstart.contains("follow its recovery commands exactly"));
    assert!(quickstart.contains("preserve the selected executable token"));
    assert!(quickstart.contains("--skill-target-dir"));
    assert!(quickstart.contains("--instructions-target-dir"));
    assert!(workflows.contains("Execute recovery commands exactly as emitted"));
    assert!(workflows.contains("selected executable token"));
    assert!(workflows.contains("--copilot-target-dir"));
    assert!(workflows.contains("--skill-target-dir"));
    assert!(workflows.contains("--instructions-target-dir"));
    assert!(workflows.contains("nextCommandArgv"));
    assert!(routing_reference.contains("rust-kast-cli"));
    assert!(instruction_cli.contains("kast agent tools"));
    assert!(instruction_cli.contains("kast agent workflow --help"));
    assert!(instruction_cli.contains("stale instruction/binary install"));
    assert!(instruction_tools.contains("kast agent tools"));
    assert!(instruction_tools.contains("--copilot-target-dir"));
    assert!(instruction_tools.contains("nextCommandArgv"));
    assert!(instruction_tools.contains("result.invocation.argv"));
    assert!(instruction_tools.contains("schemaVersion >= 3"));
    assert!(instruction_tools.contains("matching `toolCount`"));
    assert!(instruction_rpc.contains("kast agent tools"));
    assert!(instruction_rpc.contains("catalog-backed tool names"));
    assert!(instruction_rpc.contains("result.invocation.argv"));
    assert!(instruction_rpc.contains("schemaVersion >= 3"));
    assert!(instruction_rpc.contains("matching `toolCount`"));
    assert!(
        root.join("resources/kast-skill/references/workflows.md")
            .is_file(),
        "packaged skill must include workflow ownership reference"
    );
    assert!(
        root.join("resources/kast-skill/scripts/verify-kast-state.py")
            .is_file(),
        "packaged skill must include state verifier"
    );
    assert!(
        root.join("resources/kast-skill/scripts/kast-agent-call.py")
            .is_file(),
        "packaged skill must include file-backed call harness"
    );
    assert!(
        !root
            .join("resources/kast-skill/scripts/kast-semantic-workflow.py")
            .exists(),
        "semantic workflow runner must live in the active kast binary"
    );

    assert!(
        root.join("resources/plugin/lsp.json").is_file(),
        "packaged Copilot LSP plugin source must live under cli-rs/resources/plugin"
    );
}

#[test]
fn repo_local_copilot_plugin_content_is_generated_not_tracked() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("repo root");

    assert!(
        root.join("cli-rs/resources/plugin/plugin.json").is_file(),
        "repo-local plugin source must live under cli-rs/resources/plugin"
    );
}
