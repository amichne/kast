#![cfg(target_os = "macos")]

#[path = "../../support/mod.rs"]
mod support;

use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Command, Stdio};

use support::{default_install_root, spawn_ready_indexer_backend_after_marker};

const SIDECAR_LAUNCH_MARKER: &str = "__KAST_SIDECAR_LAUNCH__";

#[test]
fn public_up_launches_an_isolated_sidecar_from_a_supported_installed_idea() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let app = home.join("Applications/IntelliJ IDEA.app");
    let contents = app.join("Contents");
    let java = contents.join("jbr/Contents/Home/bin/java");
    let marker = fixture.path().join("sidecar-launch.txt");
    let socket = fixture.path().join("indexer.sock");
    let sidecar_home =
        default_install_root(&home).join("current/lib/backends/indexer/current/idea-home");

    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("settings");
    let workspace = workspace.canonicalize().expect("canonical workspace");
    std::fs::create_dir_all(contents.join("Resources")).expect("resources");
    std::fs::create_dir_all(contents.join("bin")).expect("bin");
    std::fs::create_dir_all(contents.join("lib")).expect("lib");
    std::fs::create_dir_all(java.parent().expect("JBR bin")).expect("JBR");
    std::fs::write(contents.join("Resources/build.txt"), "IU-262.1\n").expect("build");
    std::fs::write(contents.join("lib/platform-loader.jar"), b"fixture").expect("boot jar");
    std::fs::write(contents.join("bin/idea.vmoptions"), "-Xms64m\n").expect("vmoptions");
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
    std::fs::write(
        &java,
        format!(
            "#!/bin/sh\n{{ printf '%s\\n' '{SIDECAR_LAUNCH_MARKER}'; printf '%s\\n' \"$0\"; printf '%s\\n' \"$@\"; }} >> \"$KAST_TEST_IDEA_LAUNCH_MARKER\"\n"
        ),
    )
    .expect("fake JBR");
    std::fs::set_permissions(&java, std::fs::Permissions::from_mode(0o755)).expect("JBR mode");
    std::fs::create_dir_all(sidecar_home.join("plugins/kast-indexer/lib"))
        .expect("sidecar payload");
    std::fs::write(
        sidecar_home.join("plugins/kast-indexer/lib/kast-indexer.jar"),
        b"fixture",
    )
    .expect("sidecar jar");
    std::fs::create_dir_all(&config_home).expect("config home");
    std::fs::write(
        config_home.join("config.toml"),
        format!("[indexer]\nhostCommand = \"{}\"\n", toml_path(&app)),
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
    let first = sidecar_up_command(&home, &config_home, &workspace, &marker)
        .spawn()
        .expect("first kast up");
    let second = sidecar_up_command(&home, &config_home, &workspace, &marker)
        .spawn()
        .expect("second kast up");
    let first_output = first.wait_with_output().expect("first kast up output");
    let second_output = second.wait_with_output().expect("second kast up output");
    let backend = backend.join().expect("backend launcher watcher");

    for output in [&first_output, &second_output] {
        assert!(
            output.status.success(),
            "kast up failed: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
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
        "concurrent `kast up` calls must launch one sidecar: {launch}",
    );
    assert!(
        launch.contains(&format!("\n{}\n", java.canonicalize().unwrap().display())),
        "{launch}",
    );
    assert!(launch.contains("\nkast-indexer\n"), "{launch}");
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

fn sidecar_up_command(home: &Path, config_home: &Path, workspace: &Path, marker: &Path) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .arg0("kast")
        .current_dir(workspace)
        .env("HOME", home)
        .env("KAST_HOME", default_install_root(home))
        .env("KAST_CONFIG_HOME", config_home)
        .env("KAST_TEST_IDEA_LAUNCH_MARKER", marker)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .arg("up");
    command
}

fn toml_path(path: &Path) -> String {
    path.display()
        .to_string()
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
}
