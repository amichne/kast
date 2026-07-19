#[cfg(test)]
mod tests {
    use super::*;

    fn executable_formula() -> (tempfile::TempDir, PathBuf, PathBuf) {
        let temp = tempfile::tempdir().expect("tempdir");
        let version = cli::version();
        let formula_prefix = temp.path().join(format!("Cellar/kast/{version}"));
        let binary = formula_prefix.join("bin/kast");
        fs::create_dir_all(binary.parent().expect("binary parent")).expect("formula bin");
        fs::write(&binary, "#!/usr/bin/env sh\n").expect("binary");
        crate::manifest::make_executable(&binary).expect("executable binary");
        (temp, formula_prefix, binary)
    }

    #[test]
    fn macos_homebrew_cli_receipt_round_trips_without_plugin_authority() {
        let (temp, formula_prefix, binary) = executable_formula();
        let receipt = MacosHomebrewInstallReceipt::new(
            binary,
            formula_prefix,
            cli::version().to_string(),
        );
        let receipt_path = macos_homebrew_receipt_path(temp.path());

        write_macos_homebrew_receipt_at(&receipt_path, &receipt).expect("write receipt");
        let loaded = read_macos_homebrew_receipt_at(&receipt_path).expect("read receipt");

        assert_eq!(loaded, receipt);
        let raw = fs::read_to_string(receipt_path).expect("receipt text");
        assert!(!raw.contains("plugin"), "{raw}");
        assert_eq!(loaded.schema_version, 3);
        assert_eq!(loaded.cli.release_revision, cli::release_revision());
    }

    #[test]
    fn macos_homebrew_cli_receipt_rejects_unknown_plugin_fields() {
        let (temp, formula_prefix, binary) = executable_formula();
        let receipt_path = macos_homebrew_receipt_path(temp.path());
        let mut document = serde_json::to_value(MacosHomebrewInstallReceipt::new(
            binary,
            formula_prefix,
            cli::version().to_string(),
        ))
        .expect("receipt value");
        document["plugin"] = serde_json::json!({"version": cli::version()});
        fs::create_dir_all(receipt_path.parent().expect("receipt parent")).expect("receipt dir");
        fs::write(&receipt_path, serde_json::to_vec(&document).expect("receipt json"))
            .expect("receipt");

        let error = read_macos_homebrew_receipt_at(&receipt_path).expect_err("unknown field");

        assert_eq!(error.code, "MACOS_HOMEBREW_RECEIPT_INVALID");
        assert!(error.message.contains("repair --for machine --apply"));
    }

    #[test]
    fn macos_homebrew_cli_receipt_rejects_formula_version_drift() {
        let temp = tempfile::tempdir().expect("tempdir");
        let formula_prefix = temp.path().join("Cellar/kast/other-version");
        let binary = formula_prefix.join("bin/kast");
        fs::create_dir_all(binary.parent().expect("binary parent")).expect("formula bin");
        fs::write(&binary, "#!/usr/bin/env sh\n").expect("binary");
        crate::manifest::make_executable(&binary).expect("executable binary");
        let receipt_path = macos_homebrew_receipt_path(temp.path());
        let receipt = MacosHomebrewInstallReceipt::new(
            binary,
            formula_prefix,
            cli::version().to_string(),
        );
        write_macos_homebrew_receipt_at(&receipt_path, &receipt).expect("write receipt");

        let error = read_macos_homebrew_receipt_at(&receipt_path).expect_err("version drift");

        assert_eq!(error.code, "MACOS_HOMEBREW_RECEIPT_INVALID");
        assert!(error.message.contains("Cellar/kast version root"));
    }

    #[test]
    fn repair_classifies_an_exact_stale_schema_3_receipt_for_homebrew_upgrade() {
        let temp = tempfile::tempdir().expect("tempdir");
        let stale_version = "0.12.9";
        let formula_prefix = temp.path().join(format!("Cellar/kast/{stale_version}"));
        let binary = formula_prefix.join("bin/kast");
        let receipt_path = macos_homebrew_receipt_path(temp.path());
        let receipt = MacosHomebrewInstallReceipt::new(
            binary,
            formula_prefix,
            stale_version.to_string(),
        );
        write_macos_homebrew_receipt_at(&receipt_path, &receipt).expect("write stale receipt");

        let state = classify_existing_macos_homebrew_receipt_for_repair(&receipt_path)
            .expect("classify stale receipt");

        assert!(matches!(
            state,
            ExistingMacosHomebrewReceiptForRepair::StaleSchema3
        ));
    }

    #[test]
    fn repair_rejects_ambiguous_stale_schema_2_receipt_state() {
        let temp = tempfile::tempdir().expect("tempdir");
        let stale_version = "0.12.9";
        let formula_prefix = temp.path().join(format!("Cellar/kast/{stale_version}"));
        let receipt_path = macos_homebrew_receipt_path(temp.path());
        let mut document = serde_json::to_value(MacosHomebrewInstallReceipt::new(
            formula_prefix.join("../outside/kast"),
            formula_prefix,
            stale_version.to_string(),
        ))
        .expect("receipt value");
        document["plugin"] = serde_json::json!({"version": stale_version});
        fs::create_dir_all(receipt_path.parent().expect("receipt parent")).expect("receipt dir");
        fs::write(
            &receipt_path,
            serde_json::to_vec(&document).expect("receipt json"),
        )
        .expect("receipt");

        let error = classify_existing_macos_homebrew_receipt_for_repair(&receipt_path)
            .expect_err("ambiguous stale receipt must be preserved");

        assert_eq!(error.code, "MACOS_HOMEBREW_RECEIPT_INVALID");
        assert!(error.message.contains("preserved unchanged"));
    }

    #[cfg(unix)]
    #[test]
    fn legacy_cleanup_selects_only_exact_owned_absolute_cask_links() {
        let temp = tempfile::tempdir().expect("tempdir");
        let formula_prefix = temp.path().join(format!("Cellar/kast/{}/", cli::version()));
        fs::create_dir_all(&formula_prefix).expect("formula");
        let homebrew_root = fs::canonicalize(temp.path()).expect("canonical Homebrew root");
        let profile = temp.path().join("JetBrains/IntelliJIdea2026.1/plugins");
        fs::create_dir_all(&profile).expect("profile");
        let owned_target = homebrew_root.join("Caskroom/kast-plugin/0.12.9/backend-idea");
        std::os::unix::fs::symlink(&owned_target, profile.join("kast")).expect("owned link");
        let unrecognized_profile = temp
            .path()
            .join("JetBrains/IntelliJIdeaBackup2026.1/plugins");
        fs::create_dir_all(&unrecognized_profile).expect("unrecognized profile");
        std::os::unix::fs::symlink(&owned_target, unrecognized_profile.join("kast"))
            .expect("unrecognized profile link");
        let relative_profile = temp.path().join("JetBrains/IdeaIC2026.1/plugins");
        fs::create_dir_all(&relative_profile).expect("relative profile");
        std::os::unix::fs::symlink(
            "../../Caskroom/kast-plugin/0.12.9/backend-idea",
            relative_profile.join("kast"),
        )
        .expect("relative link");
        let regular_profile = temp.path().join("JetBrains/AndroidStudio2026.1/plugins");
        fs::create_dir_all(&regular_profile).expect("regular profile");
        fs::write(regular_profile.join("kast"), "preserve").expect("regular plugin file");
        let outside_profile = temp.path().join("JetBrains/PyCharm2026.1/plugins");
        fs::create_dir_all(&outside_profile).expect("outside profile");
        std::os::unix::fs::symlink(
            homebrew_root.join("outside/backend-idea"),
            outside_profile.join("kast"),
        )
        .expect("outside link");

        let owned = owned_legacy_idea_plugin_links_for_release(
            &temp.path().join("JetBrains"),
            &formula_prefix,
            LEGACY_IDEA_PLUGIN_CLEANUP_RELEASE,
        )
        .expect("owned links");

        assert_eq!(owned.len(), 1);
        assert_eq!(owned[0].target, owned_target);
        assert!(unrecognized_profile.join("kast").is_symlink());
        assert!(relative_profile.join("kast").is_symlink());
        assert!(regular_profile.join("kast").is_file());
        assert!(outside_profile.join("kast").is_symlink());
        assert!(exact_legacy_cask_target(&homebrew_root, &owned[0].target));
        assert!(!exact_legacy_cask_target(
            &homebrew_root,
            &homebrew_root.join("Caskroom/kast-plugin/../../user/backend-idea")
        ));
        assert!(
            owned_legacy_idea_plugin_links_for_release(
                &temp.path().join("JetBrains"),
                &formula_prefix,
                "0.13.1",
            )
            .expect("later release cleanup")
            .is_empty()
        );
    }

    #[cfg(unix)]
    #[test]
    fn legacy_cleanup_apply_backs_up_exact_links_is_idempotent_and_fails_closed() {
        let temp = tempfile::tempdir().expect("tempdir");
        let backup_root = temp.path().join("backups");
        let target = temp
            .path()
            .join("Caskroom/kast-plugin/0.12.9/backend-idea");
        let link = temp.path().join("JetBrains/IdeaIC2026.1/plugins/kast");
        fs::create_dir_all(link.parent().expect("link parent")).expect("profile");
        std::os::unix::fs::symlink(&target, &link).expect("legacy link");
        let owned = OwnedLegacySymlink {
            path: link.clone(),
            target: target.clone(),
        };
        let mut result = repair_result_for_test();

        let changed = apply_owned_legacy_idea_plugin_cleanup(
            vec![owned.clone()],
            &backup_root,
            &mut result,
            || {
                fs::remove_file(&link)?;
                fs::write(&link, "user-owned plugin state")?;
                Ok(())
            },
        )
        .expect_err("apply-time state drift must fail closed");
        assert_eq!(changed.code, "LEGACY_IDEA_PLUGIN_CLEANUP_STATE_CHANGED");
        assert_eq!(
            fs::read_to_string(&link).expect("preserved replacement"),
            "user-owned plugin state",
        );
        assert!(result.backups.is_empty());

        fs::remove_file(&link).expect("remove replacement");
        std::os::unix::fs::symlink(&target, &link).expect("restore legacy link");
        apply_owned_legacy_idea_plugin_cleanup(
            vec![owned.clone()],
            &backup_root,
            &mut result,
            || Ok(()),
        )
        .expect("apply cleanup");

        assert!(!link.exists() && !link.is_symlink());
        assert_eq!(result.backups.len(), 1);
        assert_eq!(
            fs::read_link(&result.backups[0]).expect("backup symlink evidence"),
            target,
        );
        apply_owned_legacy_idea_plugin_cleanup(
            vec![],
            &backup_root,
            &mut result,
            || panic!("an idempotent no-op must not run the IDE preflight"),
        )
        .expect("idempotent cleanup");
        assert_eq!(result.backups.len(), 1);

        std::os::unix::fs::symlink(&target, &link).expect("restored legacy link");
        let error = apply_owned_legacy_idea_plugin_cleanup(
            vec![owned],
            &backup_root,
            &mut result,
            || Err(CliError::new("JETBRAINS_IDE_OPEN", "IDE open")),
        )
        .expect_err("failed preflight must preserve state");
        assert_eq!(error.code, "JETBRAINS_IDE_OPEN");
        assert!(link.is_symlink());
        assert_eq!(result.backups.len(), 1);

        let blocked_link = temp.path().join("JetBrains/GoLand2026.1/plugins/kast");
        fs::create_dir_all(blocked_link.parent().expect("blocked link parent"))
            .expect("blocked profile");
        std::os::unix::fs::symlink(&target, &blocked_link).expect("blocked legacy link");
        let invalid_backup_root = temp.path().join("not-a-directory");
        fs::write(&invalid_backup_root, "conflict").expect("backup conflict");
        apply_owned_legacy_idea_plugin_cleanup(
            vec![OwnedLegacySymlink {
                path: blocked_link.clone(),
                target,
            }],
            &invalid_backup_root,
            &mut result,
            || Ok(()),
        )
        .expect_err("backup preparation failure must precede mutation");
        assert!(blocked_link.is_symlink());
        assert_eq!(result.backups.len(), 1);
    }

    fn repair_result_for_test() -> InstallRepairResult {
        InstallRepairResult {
            applied: true,
            config_path: "test-config".to_string(),
            apply_command: "kast repair --apply".to_string(),
            actions: vec![],
            backups: vec![],
            warnings: vec![],
            schema_version: SCHEMA_VERSION,
        }
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn legacy_cleanup_fails_closed_unless_pgrep_proves_no_ide_match() {
        use std::os::unix::process::ExitStatusExt;

        let output = |exit_code: i32| Output {
            status: std::process::ExitStatus::from_raw(exit_code << 8),
            stdout: vec![],
            stderr: vec![],
        };

        let open = require_jetbrains_ides_closed_from_pgrep(Ok(output(0)))
            .expect_err("matched IDE must block cleanup");
        assert_eq!(open.code, "JETBRAINS_IDE_OPEN");
        require_jetbrains_ides_closed_from_pgrep(Ok(output(1)))
            .expect("pgrep exit 1 proves no match");
        let failed = require_jetbrains_ides_closed_from_pgrep(Ok(output(2)))
            .expect_err("pgrep failure must block cleanup");
        assert_eq!(failed.code, "JETBRAINS_IDE_STATE_UNAVAILABLE");
        let unavailable = require_jetbrains_ides_closed_from_pgrep(Err(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            "missing pgrep",
        )))
        .expect_err("missing pgrep must block cleanup");
        assert_eq!(unavailable.code, "JETBRAINS_IDE_STATE_UNAVAILABLE");
    }

    #[test]
    fn install_skill_omits_marker_and_skips_matching_version() {
        let temp = tempfile::tempdir().expect("tempdir");
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().to_path_buf()),
            name: Some("kast".to_string()),
            source_dir: None,
            force: false,
            no_auto_exclude_git: false,
        };
        let first = install_skill(args.clone()).expect("first install");
        assert!(!first.skipped);
        assert!(temp.path().join("kast/SKILL.md").is_file());
        assert!(!temp.path().join("kast/AGENTS.md").exists());
        let second = install_skill(args).expect("second install");
        assert!(second.skipped);
    }

    #[test]
    fn install_skill_replaces_retired_heavy_outputs_when_managed() {
        let temp = tempfile::tempdir().expect("tempdir");
        let args = ResourceInstallArgs {
            target_dir: Some(temp.path().to_path_buf()),
            name: Some("kast".to_string()),
            source_dir: None,
            force: false,
            no_auto_exclude_git: false,
        };
        install_skill(args.clone()).expect("first install");
        let target = temp.path().join("kast");
        fs::create_dir_all(target.join("references")).expect("legacy references");
        fs::write(target.join("AGENTS.md"), "old source guide\n").expect("legacy guide");
        let second = install_skill(args.clone()).expect("replacement");
        assert!(!second.skipped);
        assert!(!target.join("AGENTS.md").exists());
        assert!(!target.join("references").exists());
        assert!(install_skill(args).expect("stable install").skipped);
    }

    #[test]
    fn install_skill_source_override_requires_entrypoint() {
        let temp = tempfile::tempdir().expect("tempdir");
        let source = temp.path().join("source");
        fs::create_dir_all(&source).expect("source");
        let error = install_skill(ResourceInstallArgs {
            target_dir: Some(temp.path().join("target")),
            name: Some("kast".to_string()),
            source_dir: Some(source),
            force: false,
            no_auto_exclude_git: false,
        })
        .expect_err("incomplete source");
        assert_eq!(error.code, "RESOURCE_SOURCE_INCOMPLETE");
        assert!(error.message.contains("SKILL.md"));
    }

    #[test]
    fn generated_resource_cache_files_are_not_packaged() {
        assert!(is_generated_resource_cache_file(Path::new(
            "scripts/__pycache__/verify-kast-state.cpython-314.pyc"
        )));
        assert!(is_generated_resource_cache_file(Path::new(".DS_Store")));
        assert!(!is_generated_resource_cache_file(Path::new(
            "scripts/verify-kast-state.py"
        )));
    }
}
