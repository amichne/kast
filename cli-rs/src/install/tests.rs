#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn macos_homebrew_receipt_round_trips_from_application_support() {
        let temp = tempfile::tempdir().expect("tempdir");
        let version = cli::version().to_string();
        let formula_prefix = temp.path().join(format!("Cellar/kast/{version}"));
        let binary = formula_prefix.join("bin/kast");
        fs::create_dir_all(binary.parent().expect("binary parent")).expect("formula bin");
        fs::write(&binary, "#!/usr/bin/env sh\n").expect("binary");
        crate::manifest::make_executable(&binary).expect("executable binary");
        let receipt = MacosHomebrewInstallReceipt::new(
            binary,
            formula_prefix,
            version.clone(),
            "amichne/kast/kast-plugin".to_string(),
            version,
        );
        let receipt_path = macos_homebrew_receipt_path(temp.path());

        write_macos_homebrew_receipt_at(&receipt_path, &receipt).expect("write receipt");
        let loaded = read_macos_homebrew_receipt_at(&receipt_path).expect("read receipt");

        assert_eq!(loaded, receipt);
        assert_eq!(
            receipt_path,
            temp.path()
                .join("Library/Application Support/Kast/homebrew-install.json")
        );
    }

    #[test]
    fn macos_homebrew_receipt_rejects_a_stale_version() {
        let temp = tempfile::tempdir().expect("tempdir");
        let formula_prefix = temp.path().join("Cellar/kast/0.0.0");
        let binary = formula_prefix.join("bin/kast");
        fs::create_dir_all(binary.parent().expect("binary parent")).expect("formula bin");
        fs::write(&binary, "#!/usr/bin/env sh\n").expect("binary");
        crate::manifest::make_executable(&binary).expect("executable binary");
        let receipt = MacosHomebrewInstallReceipt::new(
            binary,
            formula_prefix,
            "0.0.0".to_string(),
            "amichne/kast/kast-plugin".to_string(),
            "0.0.0".to_string(),
        );
        let receipt_path = macos_homebrew_receipt_path(temp.path());

        write_macos_homebrew_receipt_at(&receipt_path, &receipt).expect("write receipt");
        let error = read_macos_homebrew_receipt_at(&receipt_path).expect_err("stale receipt");

        assert_eq!(error.code, "MACOS_HOMEBREW_RECEIPT_VERSION_MISMATCH");
        assert!(error.message.contains(cli::version()), "{}", error.message);
    }

    #[test]
    fn macos_homebrew_receipt_rejects_a_missing_binary() {
        let temp = tempfile::tempdir().expect("tempdir");
        let version = cli::version().to_string();
        let formula_prefix = temp.path().join(format!("Cellar/kast/{version}"));
        fs::create_dir_all(&formula_prefix).expect("formula prefix");
        let receipt = MacosHomebrewInstallReceipt::new(
            formula_prefix.join("bin/kast"),
            formula_prefix,
            version.clone(),
            "amichne/kast/kast-plugin".to_string(),
            version,
        );
        let receipt_path = macos_homebrew_receipt_path(temp.path());

        write_macos_homebrew_receipt_at(&receipt_path, &receipt).expect("write receipt");
        let error = read_macos_homebrew_receipt_at(&receipt_path).expect_err("missing binary");

        assert_eq!(error.code, "MACOS_HOMEBREW_RECEIPT_BINARY_MISSING");
    }

    #[cfg(unix)]
    #[test]
    fn macos_homebrew_receipt_rejects_a_binary_symlink_escape() {
        let temp = tempfile::tempdir().expect("tempdir");
        let version = cli::version().to_string();
        let formula_prefix = temp.path().join(format!("Cellar/kast/{version}"));
        let binary = formula_prefix.join("bin/kast");
        let outside_binary = temp.path().join("outside/kast");
        fs::create_dir_all(binary.parent().expect("formula bin")).expect("formula bin");
        fs::create_dir_all(outside_binary.parent().expect("outside bin")).expect("outside bin");
        fs::write(&outside_binary, "#!/usr/bin/env sh\n").expect("outside binary");
        crate::manifest::make_executable(&outside_binary).expect("executable outside binary");
        std::os::unix::fs::symlink(&outside_binary, &binary).expect("formula symlink");
        let receipt = MacosHomebrewInstallReceipt::new(
            binary,
            formula_prefix,
            version.clone(),
            "amichne/kast/kast-plugin".to_string(),
            version,
        );
        let receipt_path = macos_homebrew_receipt_path(temp.path());

        write_macos_homebrew_receipt_at(&receipt_path, &receipt).expect("write receipt");
        let error = read_macos_homebrew_receipt_at(&receipt_path).expect_err("escaping receipt");

        assert_eq!(error.code, "MACOS_HOMEBREW_RECEIPT_INVALID");
    }

    #[test]
    fn install_skill_omits_marker_and_skips_matching_version() {
        let temp = tempfile::tempdir().unwrap();
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().to_path_buf()),
            name: Some("kast".to_string()),
            source_dir: None,
            force: false,
            no_auto_exclude_git: false,
        };
        let first = install_skill(args.clone()).unwrap();
        assert!(!first.skipped);
        assert!(temp.path().join("kast/SKILL.md").is_file());
        assert!(!temp.path().join("kast/AGENTS.md").exists());
        assert!(!temp.path().join("kast/references").exists());
        assert!(!temp.path().join("kast/scripts").exists());
        assert!(!temp.path().join("kast/fixtures").exists());
        assert!(!temp.path().join("kast/.kast-version").exists());
        let second = install_skill(args).unwrap();
        assert!(second.skipped);
    }

    #[test]
    fn install_skill_replaces_retired_heavy_outputs_when_managed() {
        let temp = tempfile::tempdir().unwrap();
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().to_path_buf()),
            name: Some("kast".to_string()),
            source_dir: None,
            force: false,
            no_auto_exclude_git: false,
        };
        let first = install_skill(args.clone()).unwrap();
        assert!(!first.skipped);

        let target = temp.path().join("kast");
        fs::create_dir_all(target.join("references")).unwrap();
        fs::create_dir_all(target.join("scripts")).unwrap();
        fs::create_dir_all(target.join("fixtures")).unwrap();
        fs::write(target.join("AGENTS.md"), "old source guide\n").unwrap();
        fs::write(target.join("references/commands.json"), "{}\n").unwrap();
        fs::write(target.join("scripts/verify-kast-state.py"), "#!/usr/bin/env python3\n")
            .unwrap();

        let second = install_skill(args.clone()).unwrap();
        assert!(!second.skipped);
        assert!(target.join("SKILL.md").is_file());
        assert!(!target.join("AGENTS.md").exists());
        assert!(!target.join("references").exists());
        assert!(!target.join("scripts").exists());
        assert!(!target.join("fixtures").exists());

        let third = install_skill(args).unwrap();
        assert!(third.skipped);
    }

    #[test]
    fn install_skill_source_override_requires_entrypoint() {
        let temp = tempfile::tempdir().unwrap();
        let source = temp.path().join("source");
        fs::create_dir_all(&source).unwrap();
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().join("target")),
            name: Some("kast".to_string()),
            source_dir: Some(source),
            force: false,
            no_auto_exclude_git: false,
        };

        let error = install_skill(args).unwrap_err();

        assert_eq!(error.code, "RESOURCE_SOURCE_INCOMPLETE");
        assert!(error.message.contains("SKILL.md"));
    }

    #[test]
    fn generated_resource_cache_files_are_not_packaged() {
        assert!(is_generated_resource_cache_file(Path::new(
            "scripts/__pycache__/verify-kast-state.cpython-314.pyc"
        )));
        assert!(is_generated_resource_cache_file(Path::new(
            "scripts/__pycache__/helper.pyo"
        )));
        assert!(is_generated_resource_cache_file(Path::new(".DS_Store")));
        assert!(!is_generated_resource_cache_file(Path::new(
            "scripts/verify-kast-state.py"
        )));
        assert!(!is_generated_resource_cache_file(Path::new(
            "references/commands.json"
        )));
    }

    #[test]
    fn jetbrains_plugin_dirs_match_cask_profile_filter() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path();
        for dir in [
            "AndroidStudio2025.2",
            "AndroidStudio2026.2",
            "GoLand2024.2",
            "PyCharmCE2024.1",
            "AndroidStudio2025.2-backup/2025-07-27-00-54",
            "Toolbox",
            "AndroidStudio2025.2/plugins/python-ce/helpers/typeshed/stubs/flake8/flake8",
        ] {
            fs::create_dir_all(root.join(dir)).unwrap();
        }

        let dirs = jetbrains_plugin_dirs(root).unwrap();
        let relative: Vec<_> = dirs
            .iter()
            .map(|path| path.strip_prefix(root).unwrap().display().to_string())
            .collect();

        assert_eq!(
            relative,
            vec![
                "AndroidStudio2026.2/plugins",
                "AndroidStudio2025.2/plugins",
                "GoLand2024.2/plugins",
                "PyCharmCE2024.1/plugins",
            ]
        );
    }

    #[test]
    fn latest_jetbrains_ide_app_name_prefers_newest_intellij_then_android_studio() {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path();
        for dir in [
            "AndroidStudio2026.2",
            "IntelliJIdea2025.1",
            "IntelliJIdea2026.1",
            "GoLand2026.3",
        ] {
            fs::create_dir_all(root.join(dir)).unwrap();
        }

        assert_eq!(
            latest_jetbrains_ide_app_name_under(root)
                .unwrap()
                .as_deref(),
            Some("IntelliJ IDEA")
        );

        fs::remove_dir_all(root.join("IntelliJIdea2025.1")).unwrap();
        fs::remove_dir_all(root.join("IntelliJIdea2026.1")).unwrap();

        assert_eq!(
            latest_jetbrains_ide_app_name_under(root)
                .unwrap()
                .as_deref(),
            Some("Android Studio")
        );
    }

    #[test]
    fn running_jetbrains_product_detection_includes_named_app_variants() {
        let processes = r#"
/Applications/IntelliJ IDEA EAP.app/Contents/MacOS/idea /workspace
/Applications/Android Studio Preview.app/Contents/MacOS/studio /workspace
"#;

        assert_eq!(
            running_jetbrains_products(processes),
            BTreeSet::from([
                RunningJetBrainsProduct::IntelliJIdea,
                RunningJetBrainsProduct::AndroidStudio,
            ])
        );
    }

    #[test]
    fn parses_homebrew_formula_tap() {
        let json = r#"{"formulae":[{"name":"kast","tap":"amichne/kast"}],"casks":[]}"#;
        assert_eq!(
            parse_homebrew_formula_tap(json).as_deref(),
            Some("amichne/kast")
        );
    }

    #[test]
    fn parses_homebrew_cask_metadata_version() {
        let json = r#"{"formulae":[],"casks":[{"token":"kast-plugin","version":"9.8.7"}]}"#;

        let metadata = parse_homebrew_cask_metadata(json).unwrap();

        assert_eq!(metadata.plugin_version, "9.8.7");
    }

    #[test]
    fn cask_name_uses_last_token_segment() {
        assert_eq!(cask_name("amichne/kast/kast-plugin"), "kast-plugin");
        assert_eq!(cask_name("kast-plugin"), "kast-plugin");
    }

    #[test]
    fn homebrew_formula_path_check_accepts_cellar_binary() {
        let temp = tempfile::tempdir().expect("tempdir");
        let prefix = temp.path().join("Cellar/kast/0.7.16");
        let cli = prefix.join("bin/kast");
        let outside = temp.path().join("outside/kast");
        fs::create_dir_all(cli.parent().expect("formula bin")).expect("formula bin");
        fs::create_dir_all(outside.parent().expect("outside bin")).expect("outside bin");
        fs::write(&cli, "#!/usr/bin/env sh\n").expect("formula binary");
        fs::write(&outside, "#!/usr/bin/env sh\n").expect("outside binary");

        assert!(path_is_below_homebrew_formula(&cli, &prefix));
        assert!(!path_is_below_homebrew_formula(&outside, &prefix));
    }

    #[cfg(unix)]
    #[test]
    fn homebrew_formula_path_check_rejects_a_symlink_that_escapes_the_formula() {
        let temp = tempfile::tempdir().expect("tempdir");
        let formula_prefix = temp.path().join("Cellar/kast/1.2.3");
        let formula_binary = formula_prefix.join("bin/kast");
        let outside_binary = temp.path().join("outside/kast");
        fs::create_dir_all(formula_binary.parent().expect("formula bin")).expect("formula bin");
        fs::create_dir_all(outside_binary.parent().expect("outside bin")).expect("outside bin");
        fs::write(&outside_binary, "#!/usr/bin/env sh\n").expect("outside binary");
        std::os::unix::fs::symlink(&outside_binary, &formula_binary).expect("formula symlink");

        assert!(!path_is_below_homebrew_formula(
            &formula_binary,
            &formula_prefix
        ));
    }

    #[cfg(unix)]
    #[test]
    fn homebrew_plugin_link_classifier_rejects_a_parent_directory_escape() {
        let temp = tempfile::tempdir().expect("tempdir");
        let plugin_link = temp.path().join("profile/plugins/kast");
        fs::create_dir_all(plugin_link.parent().expect("plugin parent")).expect("plugin parent");
        let expected = PathBuf::from(
            "/opt/homebrew/Caskroom/kast-plugin/1.2.3/backend-idea",
        );
        let escaping = PathBuf::from(
            "/opt/homebrew/Caskroom/kast-plugin/../../user-owned/backend-idea",
        );
        std::os::unix::fs::symlink(&escaping, &plugin_link).expect("plugin link");

        assert!(matches!(
            classify_homebrew_plugin_profile_path(&expected, &plugin_link),
            HomebrewPluginProfilePath::Unmanaged { .. }
        ));
    }

    #[test]
    fn homebrew_cli_verification_rejects_development_binary() {
        let context = HomebrewContext {
            brew_prefix: PathBuf::from("/opt/homebrew"),
            formula_prefix: PathBuf::from("/opt/homebrew/opt/kast"),
            cli_path: PathBuf::from("/opt/homebrew/opt/kast/bin/kast"),
            running_cli_path: PathBuf::from("/Users/example/.local/bin/kast-dev"),
        };

        let error = verify_homebrew_cli(&context).unwrap_err();

        assert_eq!(error.code, "HOMEBREW_INSTALL_REQUIRED");
        assert_eq!(
            error.details.get("cliPath").map(String::as_str),
            Some("/Users/example/.local/bin/kast-dev")
        );
    }

    #[cfg(unix)]
    #[test]
    fn homebrew_cli_verification_accepts_bin_symlink_to_formula_binary() {
        let temp = tempfile::tempdir().expect("tempdir");
        let cellar_prefix = temp.path().join("Cellar/kast/1.2.3");
        let formula_binary = cellar_prefix.join("bin/kast");
        let opt_prefix = temp.path().join("opt/kast");
        let invoked_binary = temp.path().join("bin/kast");
        fs::create_dir_all(formula_binary.parent().expect("formula bin")).expect("formula bin");
        fs::create_dir_all(opt_prefix.parent().expect("opt parent")).expect("opt parent");
        fs::create_dir_all(invoked_binary.parent().expect("bin parent")).expect("bin parent");
        fs::write(&formula_binary, "#!/usr/bin/env sh\n").expect("formula binary");
        std::os::unix::fs::symlink(&cellar_prefix, &opt_prefix).expect("opt symlink");
        std::os::unix::fs::symlink(&formula_binary, &invoked_binary).expect("bin symlink");
        let context = HomebrewContext {
            brew_prefix: temp.path().to_path_buf(),
            cli_path: opt_prefix.join("bin/kast"),
            formula_prefix: opt_prefix,
            running_cli_path: invoked_binary,
        };

        assert!(verify_homebrew_cli(&context).is_ok());
    }
}
