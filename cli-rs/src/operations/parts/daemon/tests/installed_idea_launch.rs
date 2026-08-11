use super::tests::{write_installed_kotlin_jps_fixture, write_jar_fixture};
use super::*;

#[test]
fn installed_idea_sidecar_uses_product_jbr_boot_classpath_and_isolated_paths() {
    let temp = tempfile::tempdir().unwrap();
    let workspace = temp.path().join("workspace");
    let app = temp.path().join("IntelliJ IDEA.app");
    let contents = app.join("Contents");
    let resources = contents.join("Resources");
    let java = contents.join("jbr/Contents/Home/bin/java");
    let boot_jar = contents.join("lib/platform-loader.jar");
    std::fs::create_dir(&workspace).unwrap();
    std::fs::create_dir_all(&resources).unwrap();
    std::fs::create_dir_all(java.parent().unwrap()).unwrap();
    std::fs::create_dir_all(boot_jar.parent().unwrap()).unwrap();
    std::fs::write(&java, "fixture").unwrap();
    std::fs::write(&boot_jar, "fixture").unwrap();
    write_installed_kotlin_jps_fixture(&contents);
    std::fs::write(
        resources.join("product-info.json"),
        serde_json::to_vec(&serde_json::json!({
            "productCode": "IU",
            "dataDirectoryName": "IntelliJIdea2026.2",
            "launch": [{
                "os": "macOS",
                "arch": if cfg!(target_arch = "aarch64") { "aarch64" } else { "x86_64" },
                "javaExecutablePath": "../jbr/Contents/Home/bin/java",
                "bootClassPathJarNames": ["platform-loader.jar"],
                "additionalJvmArguments": ["-Dfixture.product=true", "-Xmx8192m"],
                "mainClass": "com.intellij.idea.Main"
            }]
        }))
        .unwrap(),
    )
    .unwrap();
    let mut config = KastConfig::defaults();
    config.paths.install_root = temp.path().join("install");
    config.paths.cache_dir = temp.path().join("cache");
    config.paths.logs_dir = temp.path().join("logs");
    let payload = config
        .paths
        .install_root
        .join("current/lib/backends/indexer/current/idea-home/plugins/kast-indexer/lib");
    std::fs::create_dir_all(&payload).unwrap();
    write_jar_fixture(
        &payload.join("kast-indexer.jar"),
        &["io/github/amichne/kast/idea/IndexerServerRuntime.class"],
    );
    let args = DaemonStartArgs {
        workspace_root: Some(workspace.clone()),
        runtime_libs_dir: None,
        idea_home: None,
        socket_path: Some(temp.path().join("kast.sock")),
        module_name: None,
        source_roots: None,
        classpath: None,
        request_timeout_ms: None,
        max_results: None,
        max_concurrent_requests: None,
        stdio: false,
        profile: false,
        profile_modes: None,
        profile_duration: None,
        profile_otlp_endpoint: None,
        runtime_instance_id: None,
    };

    let command = installed_idea_sidecar_java_command(&args, &config, &app).unwrap();

    assert_eq!(
        command.first(),
        Some(&std::fs::canonicalize(&java).unwrap().display().to_string()),
    );
    assert!(command.contains(&"-Dfixture.product=true".to_string()));
    let classpath = command.iter().position(|arg| arg == "-cp").unwrap() + 1;
    assert_eq!(
        command[classpath],
        std::fs::canonicalize(&boot_jar)
            .unwrap()
            .display()
            .to_string(),
    );
    assert!(command.contains(&"com.intellij.idea.Main".to_string()));
    assert!(command.contains(&"kast-indexer".to_string()));
    assert_eq!(
        command
            .iter()
            .filter(|argument| argument.starts_with("-Xmx"))
            .map(String::as_str)
            .collect::<Vec<_>>(),
        vec!["-Xmx2048m"],
    );
    assert!(!command.contains(&"-Didea.force.use.core.classloader=true".to_string()));
    let sidecar_root = config
        .paths
        .cache_dir
        .join("idea-sidecars")
        .join(config::workspace_hash(&workspace));
    for name in ["idea-config", "idea-system", "idea-log", "plugins"] {
        assert!(
            command
                .iter()
                .any(|arg| arg.contains(&sidecar_root.join(name).display().to_string()))
        );
    }
    assert_eq!(
        std::fs::canonicalize(sidecar_root.join("plugins/kast-indexer")).unwrap(),
        std::fs::canonicalize(payload.parent().unwrap()).unwrap(),
    );
}
