#![cfg(target_os = "macos")]

#[path = "../../support/mod.rs"]
mod support;

use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Command, Stdio};

use support::{
    api_schema_version, available_current_lane, available_retained_lane, blocked_retained_lane,
    default_install_root, default_socket_path_for_test, spawn_ready_indexer_backend_after_marker,
    spawn_sequenced_indexer_backend,
};

const SIDECAR_LAUNCH_MARKER: &str = "__KAST_SIDECAR_LAUNCH__";

#[test]
fn semantic_demand_launches_an_isolated_sidecar_from_a_supported_installed_idea() {
    let fixture = tempfile::tempdir().expect("fixture");
    let fixture_root = fixture.path().canonicalize().expect("canonical fixture");
    let home = fixture_root.join("home");
    let config_home = fixture_root.join("config");
    let workspace = fixture_root.join("workspace");
    let app = home.join("Applications/IntelliJ IDEA.app");
    let contents = app.join("Contents");
    let java = contents.join("jbr/Contents/Home/bin/java");
    let marker = fixture_root.join("sidecar-launch.txt");
    let stop = fixture_root.join("sidecar-stop.txt");
    let _stop_guard = MarkerOnDrop(stop.clone());
    let service_manager_root = fixture_root.join("service-manager");
    let sidecar_home =
        default_install_root(&home).join("current/lib/backends/indexer/current/idea-home");

    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let socket = default_socket_path_for_test(&workspace);
    std::fs::create_dir_all(contents.join("Resources")).expect("resources");
    std::fs::create_dir_all(contents.join("bin")).expect("bin");
    std::fs::create_dir_all(contents.join("lib")).expect("lib");
    let kotlin_jps = contents.join("plugins/Kotlin/lib/jps/kotlin-jps-plugin.jar");
    let compiler_common = contents.join("plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar");
    std::fs::create_dir_all(kotlin_jps.parent().expect("Kotlin JPS directory"))
        .expect("Kotlin JPS directory");
    std::fs::create_dir_all(java.parent().expect("JBR bin")).expect("JBR");
    std::fs::write(contents.join("Resources/build.txt"), "IU-262.1\n").expect("build");
    std::fs::write(contents.join("lib/platform-loader.jar"), b"fixture").expect("boot jar");
    std::fs::write(
        contents.join("bin/idea.vmoptions"),
        format!(
            "-c\n{{ printf '%s\\n' '{SIDECAR_LAUNCH_MARKER}'; printf '%s\\n' \"$KAST_TEST_IDEA_JAVA_PATH\"; printf '%s\\n' \"$@\"; }} >> \"$KAST_TEST_IDEA_LAUNCH_MARKER\"; while [ ! -f \"$KAST_TEST_IDEA_STOP_MARKER\" ]; do sleep 1; done\n"
        ),
    )
    .expect("vmoptions");
    std::fs::write(
        contents.join("Resources/product-info.json"),
        serde_json::to_vec_pretty(&serde_json::json!({
            "productCode": "IU",
            "dataDirectoryName": "IntelliJIdea2026.2",
            "launch": [{
                "os": "macOS",
                "arch": if cfg!(target_arch = "aarch64") { "aarch64" } else { "x86_64" },
                "javaExecutablePath": "../jbr/Contents/Home/bin/java",
                "vmOptionsFilePath": "../bin/idea.vmoptions",
                "bootClassPathJarNames": ["platform-loader.jar"],
                "additionalJvmArguments": ["-Dfixture.product=true"],
                "mainClass": "com.intellij.idea.Main"
            }]
        }))
        .expect("product info"),
    )
    .expect("product info file");
    std::os::unix::fs::symlink("/bin/zsh", &java).expect("fake native JBR");
    std::fs::create_dir_all(sidecar_home.join("plugins/kast-indexer/lib"))
        .expect("sidecar payload");
    write_jar_fixture(
        &sidecar_home.join("plugins/kast-indexer/lib/kast-indexer.jar"),
        &["io/github/amichne/kast/idea/IndexerServerRuntime.class"],
    );
    write_jar_fixture(
        &kotlin_jps,
        &["org/jetbrains/kotlin/jps/build/KotlinBuilder.class"],
    );
    write_jar_fixture(
        &compiler_common,
        &["org/jetbrains/kotlin/cli/common/arguments/Freezable.class"],
    );
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        config_home.join("config.toml"),
        format!(
            "[indexer]\nhostCommand = \"{}\"\nmaxHeapMegabytes = 3072\n",
            toml_path(&app),
        ),
    )
    .expect("config");

    let backend = spawn_ready_indexer_backend_after_marker(
        &home,
        &config_home,
        &workspace,
        &socket,
        &marker,
        2,
    );
    let first = sidecar_semantic_demand_command(
        &home,
        &config_home,
        &workspace,
        &marker,
        &stop,
        &service_manager_root,
    )
    .spawn()
    .expect("first kast up");
    let second = sidecar_semantic_demand_command(
        &home,
        &config_home,
        &workspace,
        &marker,
        &stop,
        &service_manager_root,
    )
    .spawn()
    .expect("second kast up");
    let first_output = first.wait_with_output().expect("first kast up output");
    let second_output = second.wait_with_output().expect("second kast up output");
    std::fs::write(&stop, b"").expect("stop sidecar");

    for (label, output) in [("first", &first_output), ("second", &second_output)] {
        assert!(
            output.status.success(),
            "{label} kast up failed: first stdout={}, second stdout={}, marker={}, stderr={}",
            String::from_utf8_lossy(&first_output.stdout),
            String::from_utf8_lossy(&second_output.stdout),
            std::fs::read_to_string(&marker).unwrap_or_default(),
            String::from_utf8_lossy(&output.stderr),
        );
    }
    let backend = backend.join().expect("backend launcher watcher");
    assert!(backend.is_some(), "installed IDEA JBR was not launched");
    for output in [&first_output, &second_output] {
        let stdout = String::from_utf8_lossy(&output.stdout);
        assert!(stdout.contains("ready: true"), "{stdout}");
        assert!(stdout.contains("backend: indexer"), "{stdout}");
    }
    let launch = std::fs::read_to_string(&marker).expect("launch marker");
    assert_eq!(
        launch
            .lines()
            .filter(|line| *line == SIDECAR_LAUNCH_MARKER)
            .count(),
        1,
        "concurrent semantic demands must launch one sidecar: {launch}",
    );
    assert!(
        launch.contains(&format!("\n{}\n", java.canonicalize().unwrap().display())),
        "{launch}",
    );
    assert!(launch.contains("\nkast-indexer\n"), "{launch}");
    assert!(launch.contains("\n-Xmx3072m\n"), "{launch}");
    assert!(launch.contains("--workspace-root="), "{launch}");
    assert!(launch.contains("--runtime-config-file="), "{launch}");
    for path in [
        "idea-config",
        "idea-system",
        "idea-log",
        "plugins/kast-indexer",
    ] {
        assert!(launch.contains(path), "missing isolated {path}: {launch}");
    }
    assert!(
        !sidecar_home.join("../runtime-libs/classpath.txt").is_file(),
        "the macOS sidecar must not require packaged IDEA runtime libraries",
    );
}

#[test]
fn workspace_up_waits_for_reference_ready_epoch() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let socket = fixture.path().join("indexer.sock");
    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("Gradle settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    let backend = spawn_sequenced_indexer_backend(
        &home,
        &config_home,
        &workspace,
        &socket,
        vec![
            ("runtime/status", runtime_status(&workspace, false)),
            ("capabilities", semantic_capabilities(&workspace)),
            ("runtime/status", runtime_status(&workspace, true)),
            ("capabilities", semantic_capabilities(&workspace)),
            ("runtime/status", runtime_status(&workspace, true)),
        ],
    );

    let output = kast_public(&home, &config_home, &workspace)
        .args(["--output", "json", "up"])
        .output()
        .expect("kast up");
    let requests = backend.join().expect("sequenced backend");

    assert!(
        output.status.success(),
        "up failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    let output: serde_json::Value = serde_json::from_slice(&output.stdout).expect("up JSON");
    assert_eq!(output["result"]["referenceIndexReady"], true, "{output:#}");
    assert_eq!(
        requests
            .iter()
            .map(|request| request["method"].as_str())
            .collect::<Vec<_>>(),
        vec![
            Some("runtime/status"),
            Some("capabilities"),
            Some("runtime/status"),
            Some("capabilities"),
        ],
    );
}

fn sidecar_semantic_demand_command(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    marker: &Path,
    stop: &Path,
    service_manager_root: &Path,
) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .arg0("kast")
        .current_dir(workspace)
        .env("HOME", home)
        .env("KAST_HOME", default_install_root(home))
        .env("KAST_CONFIG_HOME", config_home)
        .env("KAST_TEST_IDEA_LAUNCH_MARKER", marker)
        .env("KAST_TEST_IDEA_STOP_MARKER", stop)
        .env("KAST_TEST_IDEA_JAVA_PATH", "/bin/zsh")
        .env("KAST_TEST_ALLOW_RUNTIME_SERVICE_MANAGER", "1")
        .env(
            "KAST_TEST_RUNTIME_SERVICE_MANAGER_ROOT",
            service_manager_root,
        )
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .arg("up");
    command
}

fn kast_public(home: &Path, config_home: &Path, workspace: &Path) -> Command {
    let mut command = support::kast(home, config_home);
    command.arg0("kast").current_dir(workspace);
    command
}

fn runtime_status(workspace: &Path, references_ready: bool) -> serde_json::Value {
    serde_json::json!({
        "state": "READY",
        "backendName": "indexer",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "sourceModuleNames": [":fixture"],
        "readiness": {
            "runtime": available_current_lane(1),
            "model": available_current_lane(1),
            "workspaceFiles": available_current_lane(1),
            "compiler": available_current_lane(1),
            "sourceIndex": available_retained_lane(1),
            "references": if references_ready {
                available_retained_lane(1)
            } else {
                blocked_retained_lane()
            },
            "semanticGraph": available_retained_lane(1),
            "mutation": available_current_lane(1)
        },
        "schemaVersion": api_schema_version()
    })
}

fn semantic_capabilities(workspace: &Path) -> serde_json::Value {
    serde_json::json!({
        "backendName": "indexer",
        "backendVersion": "scripted-test",
        "workspaceRoot": workspace.display().to_string(),
        "readCapabilities": ["SEMANTIC_GRAPH"],
        "mutationCapabilities": [],
        "limits": {
            "requestTimeoutMillis": 60000,
            "maxResults": 1000,
            "maxConcurrentRequests": 4
        },
        "schemaVersion": api_schema_version()
    })
}

struct MarkerOnDrop(std::path::PathBuf);

impl Drop for MarkerOnDrop {
    fn drop(&mut self) {
        let _ = std::fs::write(&self.0, b"");
    }
}

fn toml_path(path: &Path) -> String {
    path.display()
        .to_string()
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
}

fn write_jar_fixture(path: &Path, entry_names: &[&str]) {
    use std::io::Write as _;

    let file = std::fs::File::create(path).expect("Kotlin JPS fixture");
    let mut archive = zip::ZipWriter::new(file);
    for entry_name in entry_names {
        archive
            .start_file(*entry_name, zip::write::SimpleFileOptions::default())
            .expect("jar fixture entry");
        archive.write_all(b"fixture").expect("jar fixture bytes");
    }
    archive.finish().expect("Kotlin JPS archive");
}
