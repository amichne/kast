#![cfg(target_os = "macos")]

mod fixture_process_guard;
#[path = "../../support/mod.rs"]
mod support;
mod timeout;

use std::fs::OpenOptions;
use std::os::fd::AsRawFd;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Command, Stdio};

use fixture_process_guard::FixtureProcessGuard;
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
    let pid_marker = fixture.path().join("sidecar-pid.txt");
    let socket = fixture.path().join("indexer.sock");
    let sidecar_home =
        default_install_root(&home).join("current/lib/backends/indexer/current/idea-home");

    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(
        workspace.join("settings.gradle.kts"),
        "rootProject.name = \"fixture\"\n",
    )
    .expect("settings");
    let source_idea = workspace.join(".idea");
    std::fs::create_dir(&source_idea).expect("foreground IDEA metadata");
    let source_idea_marker = source_idea.join("workspace.xml");
    std::fs::write(&source_idea_marker, b"foreground-owned").expect("IDEA marker");
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
            "#!/bin/sh\n\
             {{ printf '%s\\n' '{SIDECAR_LAUNCH_MARKER}'; printf '%s\\n' \"$0\"; printf '%s\\n' \"$@\"; }} >> \"$KAST_TEST_IDEA_LAUNCH_MARKER\"\n\
             storage_root=''\n\
             workspace_root=''\n\
             bootstrap_token=''\n\
             for argument in \"$@\"; do\n\
               case \"$argument\" in\n\
                 --indexer-storage-root=*) storage_root=${{argument#*=}} ;;\n\
                 --workspace-root=*) workspace_root=${{argument#*=}} ;;\n\
                 --bootstrap-token=*) bootstrap_token=${{argument#*=}} ;;\n\
               esac\n\
             done\n\
             if [ -n \"$storage_root\" ] && [ -n \"$workspace_root\" ] && [ -n \"$bootstrap_token\" ]; then\n\
               receipt_directory=\"$storage_root/bootstrap\"\n\
               receipt=\"$receipt_directory/$bootstrap_token.json\"\n\
               temporary=\"$receipt.tmp.$$\"\n\
               mkdir -p \"$receipt_directory\"\n\
               printf '%s\\n' \"$$\" > \"$KAST_TEST_IDEA_PID_MARKER\"\n\
               printf '{{\"schemaVersion\":1,\"token\":\"%s\",\"pid\":%s,\"canonicalWorkspaceRoot\":\"%s\",\"canonicalStorageRoot\":\"%s\"}}\\n' \"$bootstrap_token\" \"$$\" \"$workspace_root\" \"$storage_root\" > \"$temporary\"\n\
               mv \"$temporary\" \"$receipt\"\n\
             fi\n\
             trap 'exit 0' TERM INT\n\
             while :; do sleep 1; done\n"
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

    let indexer = FixtureProcessGuard::new(&pid_marker, &java, &workspace);
    let backend = spawn_ready_indexer_backend_after_marker(
        &home,
        &config_home,
        &workspace,
        &socket,
        &marker,
        2,
    );
    let first = sidecar_up_command(&home, &config_home, &workspace, &marker, &pid_marker)
        .spawn()
        .expect("first kast up");
    let second = sidecar_up_command(&home, &config_home, &workspace, &marker, &pid_marker)
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
    assert!(launch.contains("--indexer-storage-root="), "{launch}");
    assert!(launch.contains("--storage-lease-fd="), "{launch}");
    assert!(launch.contains("--bootstrap-token="), "{launch}");
    let storage_root = launch
        .lines()
        .find_map(|line| line.strip_prefix("--indexer-storage-root="))
        .map(std::path::PathBuf::from)
        .expect("canonical storage root launch argument");
    let indexer_pid = indexer.pid().expect("detached indexer PID marker");
    assert!(
        process_is_alive(indexer_pid),
        "the admitted indexer must outlive both `kast up` controller processes",
    );
    let session_id = unsafe { libc::getsid(indexer_pid as libc::pid_t) };
    assert_eq!(
        session_id, indexer_pid as libc::pid_t,
        "the admitted indexer must own a detached session",
    );
    assert_eq!(
        storage_lock_owner(&storage_root.join("storage.lease")),
        Some(indexer_pid),
        "the detached indexer must retain the exact storage lease after both controllers exit",
    );
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
    assert_eq!(
        std::fs::read(&source_idea_marker).expect("foreground IDEA marker"),
        b"foreground-owned",
        "sidecar launch must not modify source .idea metadata",
    );
    indexer.terminate();
}

#[test]
fn fixture_cleanup_refuses_to_signal_an_unverified_pid() {
    let fixture = tempfile::tempdir().expect("fixture");
    let pid_marker = fixture.path().join("sidecar-pid.txt");
    let java = fixture.path().join("java");
    let workspace = fixture.path().join("workspace");
    std::fs::write(&java, b"fixture").expect("fake JBR");
    std::fs::create_dir(&workspace).expect("workspace");
    let mut unrelated = Command::new("sleep")
        .arg("30")
        .spawn()
        .expect("unrelated fixture process");
    std::fs::write(&pid_marker, unrelated.id().to_string()).expect("PID marker");
    let guard = FixtureProcessGuard::new(&pid_marker, &java, &workspace);

    guard.terminate();

    let remained_alive = unrelated
        .try_wait()
        .expect("inspect unrelated fixture process")
        .is_none();
    if remained_alive {
        unrelated.kill().expect("stop unrelated fixture process");
    }
    unrelated.wait().expect("reap unrelated fixture process");
    assert!(
        remained_alive,
        "fixture cleanup must not signal a PID without an exact fixture identity",
    );
}

fn sidecar_up_command(
    home: &Path,
    config_home: &Path,
    workspace: &Path,
    marker: &Path,
    pid_marker: &Path,
) -> Command {
    let mut command = Command::new(env!("CARGO_BIN_EXE_kast"));
    command
        .arg0("kast")
        .current_dir(workspace)
        .env("HOME", home)
        .env("KAST_HOME", default_install_root(home))
        .env("KAST_CONFIG_HOME", config_home)
        .env("KAST_TEST_IDEA_LAUNCH_MARKER", marker)
        .env("KAST_TEST_IDEA_PID_MARKER", pid_marker)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .arg("up");
    command
}

fn process_is_alive(pid: u32) -> bool {
    unsafe { libc::kill(pid as libc::pid_t, 0) == 0 }
}

fn storage_lock_owner(path: &Path) -> Option<u32> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .expect("storage lease file");
    let mut query = unsafe { std::mem::zeroed::<libc::flock>() };
    query.l_type = libc::F_WRLCK as _;
    query.l_whence = libc::SEEK_SET as _;
    assert_ne!(
        unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETLK, &mut query) },
        -1,
        "storage lease owner query failed: {}",
        std::io::Error::last_os_error(),
    );
    (query.l_type == libc::F_WRLCK as libc::c_short && query.l_pid > 0)
        .then(|| u32::try_from(query.l_pid).expect("positive storage lease owner PID"))
}

fn toml_path(path: &Path) -> String {
    path.display()
        .to_string()
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
}
