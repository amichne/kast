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
    std::fs::create_dir_all(repo_root.join("cli-rs/resources/kast-skill")).expect("skill source");
    std::fs::create_dir_all(repo_root.join("cli-rs/resources/codex-plugin"))
        .expect("guidance source");
    std::fs::write(repo_root.join("install.sh"), "#!/usr/bin/env bash\n")
        .expect("bootstrap script");
    set_executable_for_test(&repo_root.join("install.sh"));
    std::fs::write(
        repo_root.join("cli-rs/resources/kast-skill/SKILL.md"),
        "skill",
    )
    .expect("skill");
    std::fs::write(
        repo_root.join("cli-rs/resources/codex-plugin/plugin.json"),
        "guidance",
    )
    .expect("guidance");

    let cli_archive = write_cli_archive(temp.path());
    let backend_archive = write_backend_archive(temp.path(), "headless", "v9.8.7");
    let plugin_archive = temp.path().join("kast-idea.zip");
    std::fs::write(&plugin_archive, "plugin").expect("plugin");

    let package = kast(&home, &config_home)
        .args([
            "--output",
            "json",
            "developer",
            "release",
            "package",
            "ubuntu-debian-bundle",
            "--repo-root",
            repo_root.to_str().expect("repo root"),
            "--cli-archive",
            cli_archive.to_str().expect("cli archive"),
            "--backend-archive",
            backend_archive.to_str().expect("backend archive"),
            "--plugin-archive",
            plugin_archive.to_str().expect("plugin archive"),
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
    assert_eq!(stdout["manifestSchemaVersion"], 3);
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
    assert!(bundle_root.join("install.sh").is_file());

    let manifest: serde_json::Value = serde_json::from_str(
        &std::fs::read_to_string(bundle_root.join("manifest.json")).expect("manifest"),
    )
    .expect("manifest json");
    assert_eq!(manifest["schemaVersion"], 3);
    assert_eq!(manifest["kind"], "KAST_INSTALL_BUNDLE");
    assert_eq!(manifest["profile"], "ubuntu-debian-headless");
    assert_eq!(manifest["version"], "v9.8.7");
    assert_eq!(manifest["platform"], "ubuntu-debian-headless-x86_64");
    assert_eq!(manifest["entrypoint"], "install.sh");
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
    assert_eq!(manifest["artifacts"][2]["role"], "plugin");
    assert_eq!(manifest["artifacts"][3]["role"], "skill");
    assert_eq!(manifest["artifacts"][4]["role"], "guidance");
}
