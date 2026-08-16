use std::os::unix::fs::PermissionsExt;
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use crate::support::default_install_root;

#[test]
fn background_deadline_reaps_an_unacknowledged_detached_sidecar() {
    let fixture = tempfile::tempdir().expect("fixture");
    let home = fixture.path().join("home");
    let config_home = fixture.path().join("config");
    let workspace = fixture.path().join("workspace");
    let app = home.join("Applications/IntelliJ IDEA.app");
    let contents = app.join("Contents");
    let java = contents.join("jbr/Contents/Home/bin/java");
    let pid_marker = fixture.path().join("unacknowledged-sidecar.pid");
    let fake_bin = fixture.path().join("bin");
    let fake_git = fake_bin.join("git");
    let git_delay_marker = fake_bin.join("git.delay-complete");
    let sidecar_home =
        default_install_root(&home).join("current/lib/backends/indexer/current/idea-home");

    std::fs::create_dir_all(&workspace).expect("workspace");
    std::fs::write(workspace.join("settings.gradle.kts"), "").expect("settings");
    assert!(
        Command::new("/usr/bin/git")
            .args(["init", "--quiet"])
            .arg(&workspace)
            .status()
            .expect("initialize exact-worktree fixture")
            .success(),
        "exact-worktree fixture initialization failed",
    );
    let workspace = workspace.canonicalize().expect("canonical workspace");
    std::fs::create_dir(&fake_bin).expect("fake command directory");
    std::fs::write(
        &fake_git,
        "#!/bin/sh\nmarker=\"$0.delay-complete\"\nif [ ! -e \"$marker\" ]; then\n  : > \"$marker\"\n  /bin/sleep 2\nfi\nexec /usr/bin/git \"$@\"\n",
    )
    .expect("delayed git wrapper");
    std::fs::set_permissions(&fake_git, std::fs::Permissions::from_mode(0o755))
        .expect("delayed git mode");
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
                "mainClass": "com.intellij.idea.Main"
            }]
        }))
        .expect("product info"),
    )
    .expect("product info file");
    std::fs::write(
        &java,
        "#!/bin/sh\nprintf '%s\\n' \"$$\" > \"$KAST_TEST_UNACKNOWLEDGED_PID\"\ntrap 'exit 0' TERM INT\nwhile :; do sleep 1; done\n",
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
        format!("[indexer]\nhostCommand = \"{}\"\n", super::toml_path(&app)),
    )
    .expect("config");

    let guard = super::FixtureProcessGuard::new(&pid_marker, &java, &workspace);
    let started = Instant::now();
    let absolute_budget = Duration::from_millis(6_000);
    let deadline_unix_epoch_millis = SystemTime::now()
        .checked_add(absolute_budget)
        .expect("absolute runtime deadline")
        .duration_since(UNIX_EPOCH)
        .expect("post-epoch runtime deadline")
        .as_millis();
    let deadline_unix_epoch_millis =
        u64::try_from(deadline_unix_epoch_millis).expect("runtime deadline milliseconds");
    let mut controller = Command::new(env!("CARGO_BIN_EXE_kast"));
    controller
        .arg0("kastctl")
        .current_dir(&workspace)
        .env("HOME", &home)
        .env("KAST_HOME", default_install_root(&home))
        .env("KAST_CONFIG_HOME", &config_home)
        .env("KAST_TEST_UNACKNOWLEDGED_PID", &pid_marker)
        .env(
            "PATH",
            format!(
                "{}:{}",
                fake_bin.display(),
                std::env::var("PATH").expect("test PATH"),
            ),
        )
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "start-background",
            "--wait-timeout-ms",
            "8000",
            "--accept-indexing",
        ])
        .arg("--start-deadline-unix-epoch-millis")
        .arg(deadline_unix_epoch_millis.to_string())
        .arg("--workspace-root")
        .arg(&workspace);
    let mut controller = Some(controller.spawn().expect("background controller"));
    let marker_deadline = Instant::now() + Duration::from_secs(6);
    while !pid_marker.is_file() && Instant::now() < marker_deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    if !pid_marker.is_file() {
        let output = controller
            .take()
            .expect("background controller")
            .wait_with_output()
            .expect("background controller output");
        panic!(
            "detached sidecar did not start: stdout={}, stderr={}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }
    let pid = guard.pid().expect("detached sidecar PID marker");
    assert_eq!(
        unsafe { libc::getsid(pid as libc::pid_t) },
        pid as libc::pid_t,
        "fixture sidecar must be detached before cancellation",
    );

    let output = controller
        .take()
        .expect("background controller")
        .wait_with_output()
        .expect("background controller output");

    assert!(!output.status.success());
    assert!(
        git_delay_marker.is_file(),
        "the successful pre-admission Git delay did not run",
    );
    assert!(
        started.elapsed() < Duration::from_millis(7_500),
        "pre-admission work received a fresh post-resolution deadline",
    );
    assert!(
        !super::process_is_alive(pid),
        "an unacknowledged detached sidecar survived controller timeout",
    );
    let diagnostics = format!(
        "{}\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
    assert!(
        diagnostics.contains("INDEXER_BOOTSTRAP_TIMEOUT"),
        "{diagnostics}"
    );
    guard.terminate();
}
