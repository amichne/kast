use super::*;

pub(crate) fn spawn_verified_add_file_binding_backend(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    socket_path: &Path,
    plan_result: serde_json::Value,
    verified_receipt: serde_json::Value,
) -> std::thread::JoinHandle<Vec<serde_json::Value>> {
    std::fs::create_dir_all(workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    spawn_strictly_scripted_backend(
        home,
        config_home,
        workspace,
        socket_path,
        2,
        true,
        unified_mutation_capabilities(),
        vec![
            ("change/plan-add-file", plan_result),
            ("change/apply-add-file", verified_receipt),
        ],
    )
}
