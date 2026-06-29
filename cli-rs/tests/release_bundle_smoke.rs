mod support;

use support::*;

#[test]
fn package_ubuntu_debian_bundle_writes_manifest_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let repo_root = temp.path().join("repo");
    let output = temp
        .path()
        .join("dist/kast-ubuntu-debian-headless-x86_64-v9.8.7.tar.gz");
    std::fs::create_dir_all(repo_root.join("scripts")).expect("repo scripts");
    std::fs::write(
        repo_root.join("scripts/install-ubuntu-debian.sh"),
        "#!/usr/bin/env bash\n",
    )
    .expect("bootstrap script");
    set_executable_for_test(&repo_root.join("scripts/install-ubuntu-debian.sh"));

    let cli_archive = write_cli_archive(temp.path());
    let backend_archive = write_backend_archive(temp.path(), "headless", "v9.8.7");

    let package = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "release",
            "package",
            "ubuntu-debian-bundle",
            "--repo-root",
            repo_root.to_str().expect("repo root"),
            "--cli-archive",
            cli_archive.to_str().expect("cli archive"),
            "--backend-archive",
            backend_archive.to_str().expect("backend archive"),
            "--version",
            "v9.8.7",
            "--bundle-output",
            output.to_str().expect("output"),
        ])
        .output()
        .expect("package ubuntu debian bundle");

    assert!(
        package.status.success(),
        "package should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&package.stdout),
        String::from_utf8_lossy(&package.stderr)
    );
    let stdout: serde_json::Value = serde_json::from_slice(&package.stdout).expect("package json");
    assert_eq!(stdout["version"], "v9.8.7");
    assert_eq!(stdout["platform"], "ubuntu-debian-headless-x86_64");
    assert_eq!(stdout["manifestSchemaVersion"], 2);
    assert_eq!(stdout["output"], output.display().to_string());
    assert_eq!(
        stdout["sha256Sidecar"],
        format!("{}.sha256", output.display())
    );
    assert!(output.is_file(), "bundle tarball exists");
    assert!(PathBuf::from(format!("{}.sha256", output.display())).is_file());

    let extract_dir = temp.path().join("extract");
    std::fs::create_dir_all(&extract_dir).expect("extract dir");
    let file = std::fs::File::open(&output).expect("bundle tarball");
    let decoder = flate2::read::GzDecoder::new(file);
    let mut archive = tar::Archive::new(decoder);
    archive.unpack(&extract_dir).expect("unpack bundle");
    let bundle_root = extract_dir.join("kast-ubuntu-debian-headless_x86_64-v9.8.7");
    assert!(
        !bundle_root.exists(),
        "bundle root must use the canonical hyphenated/underscored platform id"
    );
    let bundle_root = extract_dir.join("kast-ubuntu-debian-headless-x86_64-v9.8.7");
    assert!(bundle_root.join("bin/kast").is_file());
    assert!(
        bundle_root
            .join("lib/backends/headless-v9.8.7/kast-headless")
            .is_file()
    );
    assert!(
        bundle_root
            .join("scripts/install-ubuntu-debian.sh")
            .is_file()
    );

    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(bundle_root.join("manifest.json")).expect("manifest"),
    )
    .expect("manifest json");
    assert_eq!(manifest["schemaVersion"], 2);
    assert_eq!(manifest["kind"], "KAST_INSTALL_BUNDLE");
    assert_eq!(manifest["profile"], "ubuntu-debian-headless");
    assert_eq!(manifest["version"], "v9.8.7");
    assert_eq!(manifest["platform"], "ubuntu-debian-headless-x86_64");
    assert_eq!(manifest["entrypoint"], "scripts/install-ubuntu-debian.sh");
    assert_eq!(manifest["activation"]["cli"]["path"], "bin/kast");
    assert_eq!(manifest["activation"]["backend"]["kind"], "headless");
    assert_eq!(manifest["activation"]["backend"]["name"], "headless");
    assert_eq!(manifest["activation"]["backend"]["version"], "9.8.7");
    assert_eq!(
        manifest["activation"]["backend"]["installDir"],
        "lib/backends/headless-v9.8.7"
    );
    assert_eq!(
        manifest["activation"]["backend"]["requiredPlugin"],
        "idea-home/plugins/kast-headless"
    );
    assert_eq!(
        manifest["activation"]["shim"]["javaOpts"][0],
        "-Didea.force.use.core.classloader=true"
    );
    assert_eq!(manifest["artifacts"][0]["role"], "cli");
    assert_eq!(manifest["artifacts"][1]["role"], "headless-backend");
}

#[test]
fn activate_bundle_installs_from_v2_manifest_projection() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let bin_dir = temp.path().join("bin");
    std::fs::create_dir_all(&home).expect("home");
    let bundle = write_install_bundle_source(temp.path(), "v0.7.11-ci");

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "release",
            "activate",
            "bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
            "--bin-dir",
            bin_dir.to_str().expect("bin dir"),
            "--config-home",
            config_home.to_str().expect("config home"),
        ])
        .output()
        .expect("activate bundle");

    assert!(
        install.status.success(),
        "activate bundle should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    let stdout: serde_json::Value =
        serde_json::from_slice(&install.stdout).expect("activate bundle json");
    assert_eq!(stdout["version"], "v0.7.11-ci");
    assert_eq!(stdout["platform"], "ubuntu-debian-headless-x86_64");
    assert_eq!(stdout["profile"], "ubuntu-debian-headless");
    assert_eq!(stdout["skipped"], false);
    assert_eq!(stdout["verifyOnly"], false);

    let installed_home = install_root.join("versions/v0.7.11-ci");
    let manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(install_root.join("install.json")).unwrap())
            .expect("install manifest json");
    assert_eq!(manifest["tool"], "kast");
    assert_eq!(manifest["activeVersion"], "v0.7.11-ci");
    assert_eq!(manifest["version"], "0.7.11-ci");
    assert_eq!(manifest["backendVersion"], "0.7.11-ci");
    assert_eq!(
        manifest["entrypoints"]["activeBinary"],
        installed_home.join("bin/kast").display().to_string()
    );
    assert_eq!(
        manifest["backends"][0]["runtimeLibsDir"],
        installed_home
            .join("lib/backends/headless/current/runtime-libs")
            .display()
            .to_string()
    );
    assert!(install_root.join("current").exists());
    assert!(bin_dir.join("kast").is_file());
    let shim = std::fs::read_to_string(bin_dir.join("kast")).expect("shim");
    assert!(shim.contains("KAST_INSTALL_ROOT"));
    assert!(shim.contains("KAST_CONFIG_HOME"));
    assert!(shim.contains("-Didea.force.use.core.classloader=true"));
    let config = std::fs::read_to_string(config_home.join("config.toml")).expect("config");
    assert!(config.contains("defaultBackend = \"headless\""));
    assert!(!config.contains("runtimeLibsDir"));

    let reinstall = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "release",
            "activate",
            "bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
            "--bin-dir",
            bin_dir.to_str().expect("bin dir"),
            "--config-home",
            config_home.to_str().expect("config home"),
        ])
        .output()
        .expect("reactivate bundle");
    assert!(
        reinstall.status.success(),
        "reactivate bundle should be idempotent: stdout={}, stderr={}",
        String::from_utf8_lossy(&reinstall.stdout),
        String::from_utf8_lossy(&reinstall.stderr)
    );
    let reinstall_stdout: serde_json::Value =
        serde_json::from_slice(&reinstall.stdout).expect("reinstall json");
    assert_eq!(reinstall_stdout["skipped"], true);
}

#[test]
fn activate_bundle_installs_from_tarball_source() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let bin_dir = temp.path().join("bin");
    std::fs::create_dir_all(&home).expect("home");
    let bundle = write_install_bundle_source(temp.path(), "v9.8.7");
    let tarball = write_bundle_tarball(temp.path(), &bundle);

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "release",
            "activate",
            "bundle",
            "--source",
            tarball.to_str().expect("tarball path"),
            "--install-root",
            install_root.to_str().expect("install root"),
            "--bin-dir",
            bin_dir.to_str().expect("bin dir"),
            "--config-home",
            config_home.to_str().expect("config home"),
        ])
        .output()
        .expect("activate bundle tarball");

    assert!(
        install.status.success(),
        "tarball activation should succeed: stdout={}, stderr={}",
        String::from_utf8_lossy(&install.stdout),
        String::from_utf8_lossy(&install.stderr)
    );
    assert!(install_root.join("install.json").is_file());
    assert!(bin_dir.join("kast").is_file());
}

#[test]
fn activate_bundle_rejects_unsupported_manifest_without_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let bundle = write_install_bundle_source(temp.path(), "v9.8.7");
    let manifest_path = bundle.join("manifest.json");
    let mut manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&manifest_path).unwrap()).unwrap();
    manifest["schemaVersion"] = serde_json::json!(1);
    std::fs::write(
        &manifest_path,
        serde_json::to_string_pretty(&manifest).unwrap(),
    )
    .unwrap();

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "release",
            "activate",
            "bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
        ])
        .output()
        .expect("activate unsupported bundle");

    assert!(!install.status.success(), "unsupported bundle should fail");
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(stderr.contains("BUNDLE_MANIFEST_UNSUPPORTED"), "{stderr}");
    assert!(!install_root.join("install.json").exists());
}

#[test]
fn activate_bundle_rejects_unsafe_manifest_version_without_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let victim = temp.path().join("victim");
    std::fs::create_dir_all(&victim).expect("victim dir");
    std::fs::write(victim.join("marker"), b"do not replace").expect("victim marker");
    let bundle = write_install_bundle_source(temp.path(), "v9.8.7");
    let manifest_path = bundle.join("manifest.json");
    let mut manifest: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&manifest_path).unwrap()).unwrap();
    manifest["version"] = serde_json::json!("../../victim");
    std::fs::write(
        &manifest_path,
        serde_json::to_string_pretty(&manifest).unwrap(),
    )
    .unwrap();

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "release",
            "activate",
            "bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
        ])
        .output()
        .expect("activate unsafe-version bundle");

    assert!(!install.status.success(), "unsafe version should fail");
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(stderr.contains("BUNDLE_MANIFEST_INVALID"), "{stderr}");
    assert!(
        victim.join("marker").is_file(),
        "unsafe version must not delete or replace paths outside the install root"
    );
    assert!(!install_root.join("install.json").exists());
}

#[test]
fn activate_bundle_rejects_unsafe_tar_member_without_mutation() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let tarball = write_malicious_bundle_tarball(temp.path());

    let install = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "release",
            "activate",
            "bundle",
            "--source",
            tarball.to_str().expect("tarball path"),
            "--install-root",
            install_root.to_str().expect("install root"),
        ])
        .output()
        .expect("activate malicious tarball");

    assert!(!install.status.success(), "unsafe tarball should fail");
    let stderr = String::from_utf8_lossy(&install.stderr);
    assert!(stderr.contains("BUNDLE_ARCHIVE_INVALID"), "{stderr}");
    assert!(!install_root.join("install.json").exists());
    assert!(!temp.path().join("outside").exists());
}

#[test]
fn activate_bundle_verify_only_is_read_only_when_missing_install() {
    let temp = tempfile::tempdir().expect("tempdir");
    let home = temp.path().join("home");
    let config_home = temp.path().join("config");
    let install_root = temp.path().join("install-root");
    let bundle = write_install_bundle_source(temp.path(), "v9.8.7");

    let verify = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "release",
            "activate",
            "bundle",
            "--source",
            bundle.to_str().expect("bundle path"),
            "--install-root",
            install_root.to_str().expect("install root"),
            "--verify-only",
        ])
        .output()
        .expect("verify missing activation");

    assert!(
        !verify.status.success(),
        "verify-only should fail without install"
    );
    assert!(!install_root.join("install.json").exists());
    assert!(!install_root.join("versions").exists());
}
