#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn install_skill_omits_marker_and_skips_matching_version() {
        let temp = tempfile::tempdir().unwrap();
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().to_path_buf()),
            name: Some("kast".to_string()),
            source_dir: None,
            force: false,
            no_auto_exclude_git: false,
            dry_run: false,
        };
        let first = install_skill(args.clone()).unwrap();
        assert!(!first.skipped);
        assert!(temp.path().join("kast/SKILL.md").is_file());
        assert!(!temp.path().join("kast/.kast-version").exists());
        let second = install_skill(args).unwrap();
        assert!(second.skipped);
    }

    #[test]
    fn install_instructions_omits_marker_and_skips_matching_version() {
        let temp = tempfile::tempdir().unwrap();
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().to_path_buf()),
            name: Some("kast".to_string()),
            source_dir: None,
            force: false,
            no_auto_exclude_git: false,
            dry_run: false,
        };
        let first = install_instructions(args.clone()).unwrap();
        assert!(!first.skipped);
        assert!(temp.path().join("kast/README.md").is_file());
        assert!(temp.path().join("kast/cli.md").is_file());
        assert!(temp.path().join("kast/tools.md").is_file());
        assert!(temp.path().join("kast/rpc.md").is_file());
        assert!(temp.path().join("kast/lsp.md").is_file());
        assert!(!temp.path().join("kast/.kast-version").exists());
        let second = install_instructions(args).unwrap();
        assert!(second.skipped);
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
        let prefix = Path::new("/opt/homebrew/Cellar/kast/0.7.16");
        let cli = Path::new("/opt/homebrew/Cellar/kast/0.7.16/bin/kast");

        assert!(path_is_below_homebrew_formula(cli, prefix));
        assert!(!path_is_below_homebrew_formula(
            Path::new("/Users/example/kast/target/release/kast"),
            prefix
        ));
    }
}
