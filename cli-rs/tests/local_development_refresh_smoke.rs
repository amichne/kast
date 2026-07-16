mod support;

use sha2::{Digest, Sha256};
use std::io::{Read, Write};
use support::*;

#[test]
fn local_wrapper_ready_uses_explicit_local_authority_even_with_invalid_homebrew_state() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("release-config");
    let repository = temp.path().join("repository with spaces");
    let prefix = temp.path().join("local-authority");
    std::fs::create_dir_all(&home).expect("home");
    std::fs::create_dir_all(&config_home).expect("config home");
    initialize_repository(&repository);

    let stale_homebrew_receipt =
        home.join("Library/Application Support/Kast/homebrew-install.json");
    write_file(
        &stale_homebrew_receipt,
        br#"{"schemaVersion":1,"authority":"macos-homebrew","plugin":{}}"#,
    );
    let backend = temp.path().join("backend-headless");
    write_backend_fixture(&backend);
    let snapshot = temp.path().join("source-snapshot.json");
    let cli_provenance = temp.path().join("cli-provenance.json");
    let backend_provenance = temp.path().join("backend-provenance.json");

    let snapshot_output = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "local",
            "snapshot",
            "--source-root",
        ])
        .arg(&repository)
        .arg("--output-file")
        .arg(&snapshot)
        .output()
        .expect("source snapshot");
    assert!(
        snapshot_output.status.success(),
        "snapshot failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&snapshot_output.stdout),
        String::from_utf8_lossy(&snapshot_output.stderr)
    );
    let snapshot_bytes = std::fs::read(&snapshot).expect("captured source snapshot");
    write_backend_source_snapshot_jar(&backend, &snapshot_bytes);

    let attest_cli = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "local",
            "attest",
            "--source-root",
        ])
        .arg(&repository)
        .arg("--expected-source-snapshot")
        .arg(&snapshot)
        .args(["--artifact-kind", "cli", "--artifact"])
        .arg(env!("CARGO_BIN_EXE_kast"))
        .arg("--output-file")
        .arg(&cli_provenance)
        .output()
        .expect("CLI provenance");
    assert!(
        !attest_cli.status.success(),
        "ordinary Cargo test bytes must not impersonate a source-bound local producer"
    );
    let unbound_failure: serde_json::Value =
        serde_json::from_slice(&attest_cli.stdout).expect("unbound CLI failure JSON");
    assert_eq!(
        unbound_failure["code"], "LOCAL_CLI_SOURCE_ATTESTATION_MISSING",
        "{unbound_failure}",
    );
    write_artifact_provenance(
        &cli_provenance,
        "cli",
        &snapshot_bytes,
        std::path::Path::new(env!("CARGO_BIN_EXE_kast")),
    );
    let attest_backend = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "local",
            "attest",
            "--source-root",
        ])
        .arg(&repository)
        .arg("--expected-source-snapshot")
        .arg(&snapshot)
        .args(["--artifact-kind", "headless-backend", "--artifact"])
        .arg(&backend)
        .arg("--output-file")
        .arg(&backend_provenance)
        .output()
        .expect("backend provenance");
    assert!(
        attest_backend.status.success(),
        "backend provenance failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&attest_backend.stdout),
        String::from_utf8_lossy(&attest_backend.stderr)
    );

    let refresh = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "local",
            "refresh",
            "--source-root",
        ])
        .arg(&repository)
        .arg("--workspace-root")
        .arg(&repository)
        .arg("--prefix")
        .arg(&prefix)
        .arg("--expected-source-snapshot")
        .arg(&snapshot)
        .arg("--cli-binary")
        .arg(env!("CARGO_BIN_EXE_kast"))
        .arg("--cli-provenance")
        .arg(&cli_provenance)
        .arg("--backend-directory")
        .arg(&backend)
        .arg("--backend-provenance")
        .arg(&backend_provenance)
        .output()
        .expect("local refresh");
    assert!(
        refresh.status.success(),
        "refresh failed: stdout={}, stderr={}",
        String::from_utf8_lossy(&refresh.stdout),
        String::from_utf8_lossy(&refresh.stderr)
    );

    let inactive_runtime = std::process::Command::new(prefix.join("bin/kast-dev"))
        .env("HOME", &home)
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "up",
            "--workspace-root",
        ])
        .arg(&repository)
        .args(["--backend=headless", "--no-auto-start=true"])
        .output()
        .expect("inactive local runtime");
    assert!(
        !inactive_runtime.status.success(),
        "the fixture backend is intentionally not running"
    );
    let inactive_payload: serde_json::Value =
        serde_json::from_slice(&inactive_runtime.stdout).expect("inactive local runtime JSON");
    let canonical_prefix = std::fs::canonicalize(&prefix).expect("canonical local prefix");
    assert_eq!(
        inactive_payload["details"]["startCommand"],
        format!(
            "'{}' developer runtime up --workspace-root '{}' --backend=headless",
            canonical_prefix.join("bin/kast-dev").display(),
            repository.display(),
        ),
        "inactive local authority must teach a shell-safe receipt-owned start command: {inactive_payload}"
    );
    assert!(
        !inactive_payload
            .to_string()
            .contains("Linux headless tarball"),
        "inactive local authority must not send agents to release installation: {inactive_payload}"
    );

    let ready = std::process::Command::new(prefix.join("bin/kast-dev"))
        .env("HOME", &home)
        .args(["--output", "json", "ready", "--for", "machine"])
        .output()
        .expect("local ready");
    assert!(
        ready.status.success(),
        "local readiness must not consume Homebrew state: stdout={}, stderr={}",
        String::from_utf8_lossy(&ready.stdout),
        String::from_utf8_lossy(&ready.stderr)
    );
    let payload: serde_json::Value =
        serde_json::from_slice(&ready.stdout).expect("local readiness JSON");
    assert_eq!(
        payload["installAuthority"], "local-development",
        "{payload}"
    );
    assert_eq!(
        payload["localDevelopment"]["source"]["canonicalRoot"],
        std::fs::canonicalize(&repository)
            .expect("canonical repository")
            .display()
            .to_string(),
        "{payload}"
    );
    assert_eq!(
        payload["binary"]["configuredMatchesRunning"], true,
        "{payload}"
    );
    assert!(
        payload["pathResolution"]["configFiles"]
            .as_array()
            .expect("config files")
            .iter()
            .all(|file| file["scope"] != "macos-homebrew-receipt"),
        "local readiness must not project inactive Homebrew state: {payload}"
    );
    let bin_source = payload["pathResolution"]["entries"]
        .as_array()
        .expect("path entries")
        .iter()
        .find(|entry| entry["key"] == "paths.binDir")
        .expect("binDir entry")["source"]
        .as_str();
    assert_eq!(bin_source, Some("local-development-receipt"), "{payload}");
    assert!(repository.join("AGENTS.local.md").is_file());
    assert_eq!(
        payload["localDevelopment"]["components"]["guidance"]["effectiveTarget"],
        std::fs::canonicalize(&repository)
            .expect("canonical repository")
            .join("AGENTS.local.md")
            .display()
            .to_string(),
        "{payload}"
    );
    let local_skill = std::fs::read_to_string(prefix.join("current/lib/skills/kast/SKILL.md"))
        .expect("local skill");
    assert!(
        local_skill.contains(&format!(
            "`{} agent",
            canonical_prefix.join("bin/kast-dev").display()
        )),
        "local skill must route every command through the receipt-owned entrypoint"
    );
    assert!(
        !local_skill.contains("`kast "),
        "local skill must not route agents back to release authority"
    );

    let release_receipt_before =
        std::fs::read(&stale_homebrew_receipt).expect("release receipt before repair");
    let rejected_repair = std::process::Command::new(prefix.join("bin/kast-dev"))
        .env("HOME", &home)
        .args(["--output", "json", "repair", "--for", "machine", "--apply"])
        .output()
        .expect("rejected local repair apply");
    assert!(
        !rejected_repair.status.success(),
        "local authority must reject release-state mutation"
    );
    let repair_failure: serde_json::Value =
        serde_json::from_slice(&rejected_repair.stdout).expect("local repair failure JSON");
    assert_eq!(
        repair_failure["code"], "LOCAL_AUTHORITY_REPAIR_UNSUPPORTED",
        "{repair_failure}"
    );
    assert_eq!(
        std::fs::read(&stale_homebrew_receipt).expect("preserved release receipt"),
        release_receipt_before,
    );
    assert!(
        !home.join(".local/share/kast/install.json").exists(),
        "rejected local repair must not create managed release state"
    );

    let installed_backend_jar =
        prefix.join("current/lib/backends/headless/current/runtime-libs/backend.jar");
    std::fs::write(&installed_backend_jar, b"tampered backend\n")
        .expect("tampered installed backend");
    let tampered_runtime = std::process::Command::new(prefix.join("bin/kast-dev"))
        .env("HOME", &home)
        .args([
            "--output",
            "json",
            "developer",
            "runtime",
            "up",
            "--workspace-root",
        ])
        .arg(&repository)
        .arg("--backend=headless")
        .output()
        .expect("tampered local runtime launch");
    assert!(
        !tampered_runtime.status.success(),
        "runtime launch must reject backend bytes changed after refresh"
    );
    let tampered_runtime_payload: serde_json::Value =
        serde_json::from_slice(&tampered_runtime.stdout).expect("tampered runtime JSON");
    assert_eq!(
        tampered_runtime_payload["code"], "LOCAL_COMPONENT_CHECKSUM_MISMATCH",
        "{tampered_runtime_payload}"
    );
    std::fs::write(&installed_backend_jar, b"backend\n")
        .expect("restored installed backend fixture");

    std::fs::write(
        prefix.join("current/lib/skills/kast/SKILL.md"),
        "tampered mixed authority\n",
    )
    .expect("tampered skill");
    let tampered = std::process::Command::new(prefix.join("bin/kast-dev"))
        .env("HOME", &home)
        .args(["--output", "json", "ready", "--for", "machine"])
        .output()
        .expect("tampered readiness");
    assert!(
        !tampered.status.success(),
        "tampered local authority must fail"
    );
    let failure: serde_json::Value =
        serde_json::from_slice(&tampered.stdout).expect("tamper failure JSON");
    assert_eq!(
        failure["code"], "LOCAL_COMPONENT_CHECKSUM_MISMATCH",
        "{failure}"
    );
}

fn initialize_repository(root: &std::path::Path) {
    std::fs::create_dir_all(root).expect("repository");
    run_git(root, &["init", "--quiet"]);
    run_git(root, &["config", "user.email", "test@example.com"]);
    run_git(root, &["config", "user.name", "Kast Test"]);
    write_file(
        &root.join("settings.gradle.kts"),
        b"rootProject.name = \"fixture\"\n",
    );
    write_file(&root.join(".gitignore"), b"/AGENTS.local.md\n/.kast/\n");
    write_file(
        &root.join("cli-rs/resources/kast-skill/SKILL.md"),
        b"---\nname: kast\ndescription: fixture\n---\nUse `kast agent verify`.\n",
    );
    run_git(root, &["add", "."]);
    run_git(root, &["commit", "--quiet", "-m", "initial"]);
}

fn write_backend_fixture(root: &std::path::Path) {
    write_file(&root.join("runtime-libs/classpath.txt"), b"backend.jar\n");
    write_file(&root.join("runtime-libs/backend.jar"), b"backend\n");
    write_file(
        &root.join("runtime-libs/backend-headless-test-launcher.jar"),
        b"headless launcher\n",
    );
    write_file(&root.join("idea-home/lib/nio-fs.jar"), b"nio\n");
    write_file(
        &root.join("idea-home/modules/module-descriptors.dat"),
        b"modules\n",
    );
    let plugin_lib = root.join("idea-home/plugins/kast-headless/lib");
    for (name, bytes) in [
        ("analysis-api-test.jar", b"analysis api\n".as_slice()),
        ("analysis-server-test.jar", b"analysis server\n".as_slice()),
        (
            "backend-headless-test-plugin-descriptor.jar",
            b"plugin descriptor\n".as_slice(),
        ),
        (
            "backend-idea-test-headless-runtime.jar",
            b"backend idea\n".as_slice(),
        ),
        ("backend-shared-test.jar", b"backend shared\n".as_slice()),
        ("index-store-test.jar", b"index store\n".as_slice()),
    ] {
        write_file(&plugin_lib.join(name), bytes);
    }
}

fn write_backend_source_snapshot_jar(root: &std::path::Path, snapshot: &[u8]) {
    let plugin_jar =
        root.join("idea-home/plugins/kast-headless/lib/backend-headless-test-plugin.jar");
    let file = std::fs::File::create(plugin_jar).expect("plugin jar");
    let mut archive = zip::ZipWriter::new(file);
    archive
        .start_file(
            "META-INF/kast/local-source-snapshot.json",
            zip::write::SimpleFileOptions::default(),
        )
        .expect("snapshot jar entry");
    archive.write_all(snapshot).expect("snapshot jar bytes");
    let source: serde_json::Value = serde_json::from_slice(snapshot).expect("source snapshot JSON");
    let components = [
        (
            "analysis-api",
            "idea-home/plugins/kast-headless/lib/analysis-api-test.jar",
        ),
        (
            "analysis-server",
            "idea-home/plugins/kast-headless/lib/analysis-server-test.jar",
        ),
        (
            "backend-headless-launcher",
            "runtime-libs/backend-headless-test-launcher.jar",
        ),
        (
            "backend-headless-plugin-descriptor",
            "idea-home/plugins/kast-headless/lib/backend-headless-test-plugin-descriptor.jar",
        ),
        (
            "backend-idea",
            "idea-home/plugins/kast-headless/lib/backend-idea-test-headless-runtime.jar",
        ),
        (
            "backend-shared",
            "idea-home/plugins/kast-headless/lib/backend-shared-test.jar",
        ),
        (
            "index-store",
            "idea-home/plugins/kast-headless/lib/index-store-test.jar",
        ),
    ]
    .map(|(kind, path)| {
        serde_json::json!({
            "kind": kind,
            "path": path,
            "sha256": component_sha256(&root.join(path)),
        })
    });
    archive
        .start_file(
            "META-INF/kast/local-backend-components.json",
            zip::write::SimpleFileOptions::default(),
        )
        .expect("component manifest jar entry");
    archive
        .write_all(
            &serde_json::to_vec(&serde_json::json!({
                "schemaVersion": 1,
                "sourceTreeSha256": source["sourceTreeSha256"],
                "components": components,
            }))
            .expect("component manifest JSON"),
        )
        .expect("component manifest jar bytes");
    archive.finish().expect("finish plugin jar");
}

fn write_artifact_provenance(
    output: &std::path::Path,
    kind: &str,
    snapshot: &[u8],
    artifact: &std::path::Path,
) {
    let source: serde_json::Value = serde_json::from_slice(snapshot).expect("source snapshot JSON");
    let artifact = std::fs::canonicalize(artifact).expect("canonical artifact");
    write_file(
        output,
        &serde_json::to_vec_pretty(&serde_json::json!({
            "schemaVersion": 1,
            "kind": kind,
            "source": source,
            "artifact": artifact,
            "sha256": component_sha256(&artifact),
            "implementationVersion": env!("CARGO_PKG_VERSION"),
        }))
        .expect("artifact provenance JSON"),
    );
}

fn component_sha256(root: &std::path::Path) -> String {
    if root.is_file() {
        let mut digest = Sha256::new();
        digest.update(std::fs::read(root).expect("artifact bytes"));
        return hex::encode(digest.finalize());
    }
    let mut paths = Vec::new();
    collect_component_paths(root, root, &mut paths);
    paths.sort_by_key(|path| path_bytes(path));
    let mut digest = Sha256::new();
    digest.update(b"kast-local-component-v1\0");
    digest.update((paths.len() as u64).to_be_bytes());
    for relative in paths {
        let identity = path_bytes(&relative);
        digest.update((identity.len() as u64).to_be_bytes());
        digest.update(identity);
        let path = root.join(relative);
        let metadata = std::fs::symlink_metadata(&path).expect("component metadata");
        let mut entry = Sha256::new();
        if metadata.is_dir() {
            entry.update(b"directory\0");
        } else {
            entry.update(b"file\0");
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                entry.update([u8::from(metadata.permissions().mode() & 0o111 != 0)]);
            }
            #[cfg(not(unix))]
            entry.update([0]);
            entry.update(metadata.len().to_be_bytes());
            let mut file = std::fs::File::open(&path).expect("component file");
            let mut buffer = [0_u8; 64 * 1024];
            loop {
                let read = file.read(&mut buffer).expect("component read");
                if read == 0 {
                    break;
                }
                entry.update(&buffer[..read]);
            }
        }
        digest.update(entry.finalize());
    }
    hex::encode(digest.finalize())
}

fn collect_component_paths(
    root: &std::path::Path,
    current: &std::path::Path,
    paths: &mut Vec<std::path::PathBuf>,
) {
    let mut entries = std::fs::read_dir(current)
        .expect("component directory")
        .collect::<std::io::Result<Vec<_>>>()
        .expect("component entries");
    entries.sort_by_key(std::fs::DirEntry::file_name);
    for entry in entries {
        let path = entry.path();
        paths.push(
            path.strip_prefix(root)
                .expect("component relative")
                .to_path_buf(),
        );
        if entry.file_type().expect("component type").is_dir() {
            collect_component_paths(root, &path, paths);
        }
    }
}

fn path_bytes(path: &std::path::Path) -> Vec<u8> {
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        path.as_os_str().as_bytes().to_vec()
    }
    #[cfg(not(unix))]
    {
        path.to_string_lossy().as_bytes().to_vec()
    }
}

fn write_file(path: &std::path::Path, bytes: &[u8]) {
    std::fs::create_dir_all(path.parent().expect("file parent")).expect("parent");
    std::fs::write(path, bytes).expect("file");
}

fn run_git(root: &std::path::Path, args: &[&str]) {
    let output = std::process::Command::new("git")
        .arg("-C")
        .arg(root)
        .args(args)
        .output()
        .expect("git");
    assert!(
        output.status.success(),
        "git {:?} failed: {}",
        args,
        String::from_utf8_lossy(&output.stderr)
    );
}
