#[cfg(test)]
mod source_snapshot_tests {
    use super::SourceSnapshot;
    use std::fs;
    use std::path::Path;
    use std::process::Command;

    #[test]
    fn capture_changes_content_digest_for_dirty_bytes_at_the_same_commit() {
        let repository = initialized_repository();
        let first = SourceSnapshot::capture(repository.path()).expect("initial snapshot");

        fs::write(repository.path().join("src.txt"), "changed\n").expect("changed source");
        let second = SourceSnapshot::capture(repository.path()).expect("changed snapshot");

        assert_eq!(first.git_commit, second.git_commit);
        assert_ne!(first.source_tree_sha256, second.source_tree_sha256);
    }

    #[test]
    fn capture_changes_content_digest_for_untracked_non_ignored_source() {
        let repository = initialized_repository();
        let first = SourceSnapshot::capture(repository.path()).expect("initial snapshot");

        fs::write(repository.path().join("new-source.txt"), "new\n").expect("untracked source");
        let second = SourceSnapshot::capture(repository.path()).expect("changed snapshot");

        assert_ne!(first.source_tree_sha256, second.source_tree_sha256);
    }

    #[test]
    fn capture_encodes_a_tracked_deletion_without_requiring_the_deleted_path() {
        let repository = initialized_repository();
        let before = SourceSnapshot::capture(repository.path()).expect("initial snapshot");

        fs::remove_file(repository.path().join("src.txt")).expect("delete tracked source");
        let deleted = SourceSnapshot::capture(repository.path()).expect("deleted snapshot");
        let repeated =
            SourceSnapshot::capture(repository.path()).expect("repeated deleted snapshot");

        assert_eq!(
            deleted, repeated,
            "stable tracked deletion must be reproducible"
        );
        assert_ne!(before.source_tree_sha256, deleted.source_tree_sha256);
    }

    #[test]
    fn write_atomic_persists_a_strict_round_trippable_snapshot() {
        let repository = initialized_repository();
        let snapshot = SourceSnapshot::capture(repository.path()).expect("snapshot");
        let output = repository.path().join("state/source-snapshot.json");

        snapshot.write_atomic(&output).expect("write snapshot");
        let loaded: SourceSnapshot =
            serde_json::from_slice(&fs::read(&output).expect("snapshot bytes"))
                .expect("strict snapshot JSON");

        assert_eq!(loaded, snapshot);
    }

    #[test]
    fn source_digest_frames_file_content_across_entry_boundaries() {
        let repository = initialized_repository();
        let mut colliding_old_encoding = b"left".to_vec();
        colliding_old_encoding.extend_from_slice(&1_u64.to_be_bytes());
        colliding_old_encoding.extend_from_slice(b"b");
        colliding_old_encoding.extend_from_slice(b"file\0");
        colliding_old_encoding.push(0);
        colliding_old_encoding.extend_from_slice(b"right");
        fs::write(repository.path().join("a"), colliding_old_encoding).expect("first shape");
        run_git(repository.path(), &["add", "a"]);
        run_git(
            repository.path(),
            &["commit", "--quiet", "-m", "collision fixture"],
        );
        let one_file = SourceSnapshot::capture(repository.path()).expect("one-file snapshot");

        fs::write(repository.path().join("a"), b"left").expect("shortened first file");
        fs::write(repository.path().join("b"), b"right").expect("second file");
        let two_files = SourceSnapshot::capture(repository.path()).expect("two-file snapshot");

        assert_ne!(
            one_file.source_tree_sha256, two_files.source_tree_sha256,
            "file boundaries must be part of the source digest"
        );
    }

    fn initialized_repository() -> tempfile::TempDir {
        let repository = tempfile::tempdir().expect("repository");
        run_git(repository.path(), &["init", "--quiet"]);
        run_git(
            repository.path(),
            &["config", "user.email", "test@example.com"],
        );
        run_git(repository.path(), &["config", "user.name", "Kast Test"]);
        fs::write(repository.path().join("src.txt"), "initial\n").expect("initial source");
        run_git(repository.path(), &["add", "src.txt"]);
        run_git(repository.path(), &["commit", "--quiet", "-m", "initial"]);
        repository
    }

    fn run_git(root: &Path, args: &[&str]) {
        let output = Command::new("git")
            .arg("-C")
            .arg(root)
            .args(args)
            .output()
            .expect("git command");
        assert!(
            output.status.success(),
            "git {:?} failed: {}",
            args,
            String::from_utf8_lossy(&output.stderr)
        );
    }
}

#[cfg(test)]
mod component_digest_tests {
    use super::tree_sha256;
    use std::fs;

    #[test]
    fn component_digest_frames_file_content_across_entry_boundaries() {
        let component = tempfile::tempdir().expect("component");
        let mut colliding_old_encoding = b"left".to_vec();
        colliding_old_encoding.extend_from_slice(&1_u64.to_be_bytes());
        colliding_old_encoding.extend_from_slice(b"b");
        colliding_old_encoding.extend_from_slice(b"file\0");
        colliding_old_encoding.push(0);
        colliding_old_encoding.extend_from_slice(b"right");
        fs::write(component.path().join("a"), colliding_old_encoding).expect("first shape");
        let one_file = tree_sha256(component.path()).expect("one-file digest");

        fs::write(component.path().join("a"), b"left").expect("shortened first file");
        fs::write(component.path().join("b"), b"right").expect("second file");
        let two_files = tree_sha256(component.path()).expect("two-file digest");

        assert_ne!(
            one_file, two_files,
            "file boundaries must be part of the component digest"
        );
    }
}

#[cfg(test)]
mod provenance_tests {
    use super::{Sha256Digest, validate_cli_producer_source_identity};

    #[test]
    fn cli_attestation_rejects_an_unbound_producer() {
        let expected = Sha256Digest::try_from("a".repeat(64)).expect("digest");

        let error =
            validate_cli_producer_source_identity(None, &expected).expect_err("unbound producer");

        assert_eq!(error.code, "LOCAL_CLI_SOURCE_ATTESTATION_MISSING");
    }

    #[test]
    fn cli_attestation_rejects_bytes_built_for_another_source_snapshot() {
        let expected = Sha256Digest::try_from("a".repeat(64)).expect("digest");

        let error = validate_cli_producer_source_identity(Some(&"b".repeat(64)), &expected)
            .expect_err("stale producer");

        assert_eq!(error.code, "LOCAL_CLI_SOURCE_MISMATCH");
    }
}

#[cfg(test)]
mod refresh_tests {
    use super::{
        LocalArtifactKind, LocalArtifactProvenance, LocalDevelopmentActivateRequest,
        LocalDevelopmentAuthority, LocalDevelopmentPrepareRequest, LocalDevelopmentRefreshRequest,
        LocalDevelopmentRemoveRequest, LocalDevelopmentRollbackRequest, LocalRefreshPhase,
        LocalRemovalPhase, SourceSnapshot, activate_local_development_generation,
        prepare_local_development_generation, refresh_local_development,
        refresh_local_development_with_observer, remove_local_development,
        remove_local_development_with_observer, render_local_guidance, render_local_skill,
        rollback_local_development, validate_backend_distribution,
        validate_prepared_layout,
        validate_rendered_command_lockstep, validate_rendered_command_path,
        with_local_authority_lock, with_local_runtime_start_lock_after_validation,
    };
    use std::fs;
    use std::io::Write;
    use std::path::Path;
    use std::process::Command;
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;

    #[test]
    fn rendered_local_commands_shell_quote_entrypoints() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let source_skill = fixture.path().join("SKILL.md");
        fs::write(
            &source_skill,
            "---\nname: kast\ndescription: fixture\n---\nAcquire `kast agent lease acquire --workspace-root \"$PWD\" --backend <idea|headless>`. Run `kast agent verify --workspace-root \"$PWD\" --backend <name> --lease-id <id>`.\n",
        )
        .expect("source skill");
        let entrypoint = fixture
            .path()
            .join("agent's local authority/bin/kast");
        let quoted_entrypoint = format!(
            "'{}'",
            entrypoint.display().to_string().replace('\'', "'\"'\"'")
        );
        let snapshot = SourceSnapshot::capture(repository.path()).expect("source snapshot");

        let rendered_skill =
            render_local_skill(&source_skill, &entrypoint).expect("rendered skill");
        let rendered_guidance =
            render_local_guidance(&source_skill, &entrypoint, &snapshot);

        assert!(
            rendered_skill.contains(&format!("`{quoted_entrypoint} agent verify")),
            "rendered skill command must shell-quote the entrypoint: {rendered_skill}",
        );
        assert!(
            !rendered_skill.contains("<idea|headless>")
                && !rendered_skill.contains("--backend <name>")
                && rendered_skill.contains("--backend=headless"),
            "local authority must specialize generic backend templates: {rendered_skill}",
        );
        assert!(
            rendered_guidance.contains(&format!("`{quoted_entrypoint} agent lease acquire")),
            "rendered guidance command must shell-quote the entrypoint: {rendered_guidance}",
        );
        validate_rendered_command_lockstep(&rendered_skill, &entrypoint)
            .expect("quoted skill commands remain in lockstep");
        validate_rendered_command_lockstep(&rendered_guidance, &entrypoint)
            .expect("quoted guidance commands remain in lockstep");
    }

    #[test]
    fn refresh_activates_one_complete_generation_without_touching_release_state() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let release_sentinel = fixture.path().join("release/kast");
        write_file(&release_sentinel, b"release-binary\n");
        let release_before = fs::read(&release_sentinel).expect("release sentinel");

        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        let snapshot = SourceSnapshot::read_strict(&request.expected_source_snapshot)
            .expect("source snapshot");
        let result = refresh_local_development(request).expect("refresh");

        assert_eq!(
            result.receipt.authority,
            LocalDevelopmentAuthority::LocalDevelopment
        );
        assert_eq!(result.receipt.source, snapshot);
        assert!(prefix.join("current/bin/kast").is_file());
        assert!(
            prefix
                .join("current/lib/backends/headless/current/runtime-libs/classpath.txt")
                .is_file()
        );
        assert!(prefix.join("current/lib/skills/kast/SKILL.md").is_file());
        assert!(prefix.join("current/guidance/AGENTS.local.md").is_file());
        assert!(repository.path().join("AGENTS.local.md").is_file());
        assert_eq!(
            result.receipt.components.guidance.effective_target,
            fs::canonicalize(repository.path())
                .expect("canonical repository")
                .join("AGENTS.local.md")
        );
        assert!(prefix.join("current/config/config.toml").is_file());
        let local_config = fs::read_to_string(prefix.join("current/config/config.toml"))
            .expect("local authority config");
        assert!(local_config.contains("defaultBackend = \"headless\""));
        assert!(
            local_config.contains("[projectOpen]\nprofileAutoInit = false"),
            "local headless authority must not invoke release-owned project-open bootstrap",
        );
        assert!(prefix.join("current/authority.json").is_file());
        assert!(prefix.join("bin/kast").is_file());
        assert_eq!(
            fs::read_dir(prefix.join("bin"))
                .expect("stable command directory")
                .count(),
            1,
            "local authority must expose one command name",
        );
        let entrypoint =
            fs::read_to_string(prefix.join("bin/kast")).expect("local development entrypoint");
        assert!(
            entrypoint.contains("export KAST_DATA_HOME=\"$state/data\""),
            "local entrypoint must isolate Kotlin workspace data by generation"
        );
        let canonical_prefix = fs::canonicalize(&prefix).expect("canonical local prefix");
        let quoted_entrypoint = format!(
            "'{}'",
            canonical_prefix
                .join("bin/kast")
                .display()
                .to_string()
                .replace('\'', "'\"'\"'")
        );
        let acquire_command = format!(
            "{} agent lease acquire --workspace-root \"$PWD\" --backend=headless",
            quoted_entrypoint,
        );
        let verify_command = format!(
            "{} agent verify --workspace-root \"$PWD\" --backend=headless --lease-id <id>",
            quoted_entrypoint,
        );
        for installed_resource in [
            prefix.join("current/lib/skills/kast/SKILL.md"),
            prefix.join("current/guidance/AGENTS.local.md"),
        ] {
            let content = fs::read_to_string(&installed_resource).expect("installed resource");
            let acquire_offset = content.find(&acquire_command).unwrap_or_else(|| {
                panic!("{} must teach local lease acquisition", installed_resource.display())
            });
            let verify_offset = content.find(&verify_command).unwrap_or_else(|| {
                panic!(
                    "{} must teach local verification",
                    installed_resource.display()
                )
            });
            assert!(
                acquire_offset < verify_offset,
                "{} must acquire the exact runtime before verification",
                installed_resource.display(),
            );
        }
        let installed_skill = fs::read_to_string(prefix.join("current/lib/skills/kast/SKILL.md"))
            .expect("installed skill");
        assert!(
            installed_skill.contains("Do not apply ordinary install repair"),
            "local skill must route repair back through the source refresh task"
        );
        assert!(
            !installed_skill.contains("Add `--apply`"),
            "local skill must not advertise cross-authority apply repair"
        );
        assert_eq!(
            fs::read(&release_sentinel).expect("preserved release sentinel"),
            release_before
        );
    }

    #[test]
    fn refresh_is_idempotent_for_an_unchanged_source_snapshot() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );

        let first = refresh_local_development(request.clone()).expect("first refresh");
        let current_before = fs::read_link(prefix.join("current")).expect("current link");
        let second = refresh_local_development(request).expect("second refresh");
        let current_after = fs::read_link(prefix.join("current")).expect("current link");
        let generation_count = fs::read_dir(prefix.join("generations"))
            .expect("generations")
            .count();

        assert!(!first.skipped);
        assert!(second.skipped);
        assert_eq!(current_after, current_before);
        assert_eq!(generation_count, 1);
    }

    #[test]
    fn rebuilt_artifacts_from_the_same_source_activate_as_a_new_generation() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let first = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        ))
        .expect("first refresh");
        write_file(
            &fixture.path().join("build/kast"),
            b"#!/bin/sh\necho rebuilt\n",
        );
        make_executable(&fixture.path().join("build/kast"));

        let second = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "rebuilt",
        ))
        .expect("rebuilt refresh");

        assert_ne!(first.receipt.generation_id, second.receipt.generation_id);
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("current generation"),
            Path::new("generations").join(second.receipt.generation_id.as_str()),
        );
        assert_eq!(
            fs::read_link(prefix.join("previous")).expect("previous generation"),
            Path::new("generations").join(first.receipt.generation_id.as_str()),
        );
        assert_eq!(
            fs::read_dir(prefix.join("generations"))
                .expect("generations")
                .count(),
            2,
        );
    }

    #[test]
    fn one_prepared_generation_activates_idempotently_without_rebuilding_inputs() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let prepared_generations = fixture.path().join("prepared-generations");
        let skill_source = repository
            .path()
            .join("cli-rs/resources/kast-skill/SKILL.md");
        write_file(
            &skill_source,
            b"---\nname: kast\ndescription: fixture\n---\nRun `kast agent verify --workspace-root \"$PWD\"`.\n",
        );
        let raw = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "prepared",
        );
        let prepared_result = prepare_local_development_generation(
            LocalDevelopmentPrepareRequest {
                source_root: raw.source_root.clone(),
                expected_source_snapshot: raw.expected_source_snapshot.clone(),
                cli_binary: raw.cli_binary.clone(),
                cli_provenance: raw.cli_provenance.clone(),
                backend_directory: raw.backend_directory.clone(),
                backend_provenance: raw.backend_provenance.clone(),
                skill_source,
                output_directory: prepared_generations,
            },
        )
        .expect("prepare generation");
        assert!(!prepared_result.skipped);
        let prepared = prepared_result.directory.clone();
        assert!(prepared.join("generation.json").is_file());
        assert!(prepared.join("source-snapshot.json").is_file());
        assert!(prepared.join("bin/kast").is_file());
        assert!(prepared.join("backend-headless").is_dir());
        assert!(prepared.join("provenance/cli.json").is_file());
        assert!(prepared.join("provenance/backend.json").is_file());
        assert!(prepared.join("provenance/backend-components.json").is_file());
        assert!(prepared.join("inputs/kast-skill/SKILL.md").is_file());
        assert!(prepared.join("inputs/guidance.json").is_file());
        assert!(prepared.join("inputs/config.toml").is_file());
        let relocated = fixture.path().join("relocated-generation");
        fs::rename(&prepared, &relocated).expect("relocate prepared generation");

        let activation = LocalDevelopmentActivateRequest {
            source_root: repository.path().to_path_buf(),
            workspace_root: repository.path().to_path_buf(),
            prefix: prefix.clone(),
            prepared_generation: relocated,
        };
        let first = activate_local_development_generation(activation.clone())
            .expect("first activation");
        let second =
            activate_local_development_generation(activation).expect("second activation");

        assert!(!first.skipped);
        assert!(second.skipped);
        assert_eq!(first.receipt.generation_id, prepared_result.ledger.generation_id);
        assert_eq!(second.receipt.generation_id, prepared_result.ledger.generation_id);
    }

    #[test]
    fn prepared_rebuilds_from_same_source_select_artifact_bound_directories() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("unused-local-authority");
        let prepared_generations = fixture.path().join("prepared-generations");
        let first_inputs = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first-prepared",
        );
        let first = prepare_local_development_generation(LocalDevelopmentPrepareRequest {
            source_root: first_inputs.source_root,
            expected_source_snapshot: first_inputs.expected_source_snapshot,
            cli_binary: first_inputs.cli_binary,
            cli_provenance: first_inputs.cli_provenance,
            backend_directory: first_inputs.backend_directory,
            backend_provenance: first_inputs.backend_provenance,
            skill_source: first_inputs.skill_source,
            output_directory: prepared_generations.clone(),
        })
        .expect("first prepared generation");

        write_file(
            &fixture.path().join("build/kast"),
            b"#!/bin/sh\necho rebuilt\n",
        );
        make_executable(&fixture.path().join("build/kast"));
        let rebuilt_inputs = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "rebuilt-prepared",
        );
        let rebuilt = prepare_local_development_generation(LocalDevelopmentPrepareRequest {
            source_root: rebuilt_inputs.source_root,
            expected_source_snapshot: rebuilt_inputs.expected_source_snapshot,
            cli_binary: rebuilt_inputs.cli_binary,
            cli_provenance: rebuilt_inputs.cli_provenance,
            backend_directory: rebuilt_inputs.backend_directory,
            backend_provenance: rebuilt_inputs.backend_provenance,
            skill_source: rebuilt_inputs.skill_source,
            output_directory: prepared_generations.clone(),
        })
        .expect("rebuilt prepared generation");

        assert_ne!(first.ledger.generation_id, rebuilt.ledger.generation_id);
        assert_ne!(first.directory, rebuilt.directory);
        let prepared_generations =
            fs::canonicalize(prepared_generations).expect("canonical prepared generations");
        assert_eq!(first.directory.parent(), Some(prepared_generations.as_path()));
        assert_eq!(rebuilt.directory.parent(), Some(prepared_generations.as_path()));
        assert_eq!(
            fs::read_dir(prepared_generations)
                .expect("prepared generations")
                .count(),
            2,
        );
    }

    #[test]
    fn prepared_generation_tampering_fails_before_activation() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let prepared_generations = fixture.path().join("prepared-generations");
        let skill_source = repository
            .path()
            .join("cli-rs/resources/kast-skill/SKILL.md");
        write_file(
            &skill_source,
            b"---\nname: kast\ndescription: fixture\n---\nRun `kast agent verify --workspace-root \"$PWD\"`.\n",
        );
        let raw = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "tampered-prepared",
        );
        let prepared = prepare_local_development_generation(LocalDevelopmentPrepareRequest {
            source_root: raw.source_root,
            expected_source_snapshot: raw.expected_source_snapshot,
            cli_binary: raw.cli_binary,
            cli_provenance: raw.cli_provenance,
            backend_directory: raw.backend_directory,
            backend_provenance: raw.backend_provenance,
            skill_source,
            output_directory: prepared_generations,
        })
        .expect("prepare generation")
        .directory;
        let ledger_path = prepared.join("generation.json");
        let ledger_bytes = fs::read(&ledger_path).expect("prepared ledger");
        let mut ledger_json: serde_json::Value =
            serde_json::from_slice(&ledger_bytes).expect("prepared ledger JSON");
        ledger_json
            .as_object_mut()
            .expect("prepared ledger object")
            .insert("unknownField".to_string(), serde_json::Value::Bool(true));
        fs::write(
            &ledger_path,
            serde_json::to_vec_pretty(&ledger_json).expect("tampered ledger JSON"),
        )
        .expect("tamper prepared ledger");
        let unknown_error =
            activate_local_development_generation(LocalDevelopmentActivateRequest {
                source_root: repository.path().to_path_buf(),
                workspace_root: repository.path().to_path_buf(),
                prefix: prefix.clone(),
                prepared_generation: prepared.clone(),
            })
            .expect_err("unknown ledger field");
        assert_eq!(unknown_error.code, "LOCAL_PREPARED_GENERATION_INVALID");
        assert!(unknown_error.message.contains("unknown field"));
        fs::write(&ledger_path, ledger_bytes).expect("restore prepared ledger");
        fs::write(
            prepared.join("inputs/kast-skill/SKILL.md"),
            b"tampered\n",
        )
        .expect("tamper prepared skill");

        let error = activate_local_development_generation(LocalDevelopmentActivateRequest {
            source_root: repository.path().to_path_buf(),
            workspace_root: repository.path().to_path_buf(),
            prefix,
            prepared_generation: prepared,
        })
        .expect_err("tampered generation");

        assert_eq!(error.code, "LOCAL_PREPARED_COMPONENT_CHECKSUM_MISMATCH");
    }

    #[test]
    fn prepared_generation_layout_rejects_unexpected_empty_directories() {
        let fixture = tempfile::tempdir().expect("fixture");
        for path in [
            "generation.json",
            "source-snapshot.json",
            "bin/kast",
            "provenance/cli.json",
            "provenance/backend.json",
            "provenance/backend-components.json",
            "inputs/kast-skill/SKILL.md",
            "inputs/guidance.json",
            "inputs/config.toml",
        ] {
            write_file(&fixture.path().join(path), b"fixture\n");
        }
        fs::create_dir_all(fixture.path().join("backend-headless/runtime-libs"))
            .expect("backend tree");
        fs::create_dir_all(fixture.path().join("unexpected/empty"))
            .expect("unexpected empty directory");

        let error = validate_prepared_layout(fixture.path()).expect_err("unexpected directory");

        assert_eq!(error.code, "LOCAL_PREPARED_GENERATION_LAYOUT_INVALID");
        assert!(error.message.contains("unexpected directory"));
    }

    #[test]
    fn idempotent_refresh_rejects_a_tampered_generation_manifest() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        let first = refresh_local_development(request.clone()).expect("first refresh");
        fs::write(&first.receipt.install_manifest, b"{}\n").expect("tampered manifest");

        let error = refresh_local_development(request).expect_err("tampered manifest");

        assert_eq!(error.code, "LOCAL_COMPONENT_CHECKSUM_MISMATCH");
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("preserved current"),
            Path::new("generations").join(first.receipt.generation_id.as_str()),
        );
    }

    #[cfg(unix)]
    #[test]
    fn idempotent_refresh_rejects_a_stable_launcher_that_bypasses_current_wrapper() {
        use std::os::unix::fs::symlink;

        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        let first = refresh_local_development(request.clone()).expect("first refresh");
        fs::remove_file(prefix.join("bin/kast")).expect("remove stable launcher");
        symlink("../current/bin/kast", prefix.join("bin/kast")).expect("bypassing launcher");

        let error = refresh_local_development(request).expect_err("bypassing launcher");

        assert_eq!(error.code, "LOCAL_COMPONENT_TARGET_MISMATCH");
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("preserved current"),
            Path::new("generations").join(first.receipt.generation_id.as_str()),
        );
    }

    #[test]
    fn refresh_rejects_a_preexisting_unowned_prefix_without_deleting_its_content() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("shared-prefix");
        write_file(&prefix.join("keep.txt"), b"user-owned\n");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "unowned-prefix",
        );

        let error = refresh_local_development(request).expect_err("unowned prefix");

        assert_eq!(error.code, "LOCAL_PREFIX_CONFLICT");
        assert_eq!(
            fs::read(prefix.join("keep.txt")).expect("preserved sentinel"),
            b"user-owned\n"
        );
        assert!(fs::symlink_metadata(prefix.join("current")).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn refresh_rejects_a_symlink_alias_for_an_existing_prefix() {
        use std::os::unix::fs::symlink;

        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let first = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        ))
        .expect("first refresh");
        let alias = fixture.path().join("local-authority-alias");
        symlink(&prefix, &alias).expect("prefix alias");

        let error = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &alias,
            "alias",
        ))
        .expect_err("symlink-selected prefix");

        assert_eq!(error.code, "LOCAL_PREFIX_UNSAFE");
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("preserved current"),
            Path::new("generations").join(first.receipt.generation_id.as_str()),
        );
    }

    #[test]
    fn refresh_rejects_source_guidance_that_teaches_a_nonexistent_staged_command() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        fs::write(
            repository
                .path()
                .join("cli-rs/resources/kast-skill/SKILL.md"),
            "---\nname: kast\ndescription: invalid fixture\n---\nRun `kast agent imaginary`.\n",
        )
        .expect("invalid skill");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "invalid-command",
        );

        let error = refresh_local_development(request).expect_err("stale taught command");

        assert_eq!(error.code, "LOCAL_COMMAND_LOCKSTEP_INVALID");
        assert!(
            !prefix.join("current").exists(),
            "command lockstep rejection must happen before activation",
        );
    }

    #[test]
    fn refresh_rejects_source_guidance_that_teaches_a_stale_command_flag() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        fs::write(
            repository
                .path()
                .join("cli-rs/resources/kast-skill/SKILL.md"),
            "---\nname: kast\ndescription: invalid fixture\n---\nRun `kast agent verify --workspace-root \"$PWD\" --removed-flag`.\n",
        )
        .expect("invalid skill");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "invalid-flag",
        );

        let error = refresh_local_development(request).expect_err("stale taught flag");

        assert_eq!(error.code, "LOCAL_COMMAND_LOCKSTEP_INVALID");
        assert!(
            !prefix.join("current").exists(),
            "flag lockstep rejection must happen before activation",
        );
    }

    #[test]
    fn command_lockstep_accepts_a_complete_templated_rename_invocation() {
        validate_rendered_command_path(
            "/tmp/kast agent rename --symbol <fq-name> --new-name <name> --workspace-root \"$PWD\"",
            " agent rename --symbol <fq-name> --new-name <name> --workspace-root \"$PWD\"",
        )
        .expect("valid templated invocation");
    }

    #[test]
    fn command_lockstep_accepts_a_bare_command_path_reference() {
        validate_rendered_command_path("/tmp/kast agent rename", " agent rename")
            .expect("valid bare command path");
    }

    #[test]
    fn command_lockstep_rejects_an_invocation_missing_a_required_argument() {
        let error = validate_rendered_command_path(
            "/tmp/kast agent symbol --workspace-root \"$PWD\"",
            " agent symbol --workspace-root \"$PWD\"",
        )
        .expect_err("missing required query");

        assert_eq!(error.code, "LOCAL_COMMAND_LOCKSTEP_INVALID");
    }

    #[test]
    fn command_lockstep_checks_positive_invocations_on_a_mixed_negative_guidance_line() {
        let entrypoint = Path::new("/tmp/kast");
        let error = validate_rendered_command_lockstep(
            "Do not teach `'/tmp/kast' agent tools`; instead run `'/tmp/kast' agent imaginary --bad`.",
            entrypoint,
        )
        .expect_err("positive stale invocation must not inherit the negative exemption");

        assert_eq!(error.code, "LOCAL_COMMAND_LOCKSTEP_INVALID");
    }

    #[test]
    fn command_lockstep_accepts_only_the_closed_negative_command_references() {
        validate_rendered_command_lockstep(
            "Do not teach `'/tmp/kast' agent tools`, `'/tmp/kast' agent call`, `'/tmp/kast' agent workflow`, or `'/tmp/kast' rpc`.",
            Path::new("/tmp/kast"),
        )
        .expect("closed negative references");
    }

    #[test]
    fn backend_classpath_entries_cannot_escape_the_attested_tree() {
        let fixture = tempfile::tempdir().expect("fixture");
        let backend = fixture.path().join("backend-headless");
        write_backend_fixture(&backend);
        write_file(&backend.join("outside.jar"), b"foreign\n");
        fs::write(
            backend.join("runtime-libs/classpath.txt"),
            "../outside.jar\n",
        )
        .expect("escaping classpath");

        let error = validate_backend_distribution(&backend).expect_err("escaping classpath");

        assert_eq!(error.code, "LOCAL_BACKEND_CLASSPATH_INVALID");
    }

    #[test]
    fn rebuilt_artifacts_preserve_an_inactive_generation_from_the_same_source() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let first = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first-a",
        ))
        .expect("first A refresh");

        fs::write(
            repository.path().join("settings.gradle.kts"),
            "rootProject.name = \"second\"\n",
        )
        .expect("B source");
        refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "second-b",
        ))
        .expect("B refresh");

        fs::write(
            repository.path().join("settings.gradle.kts"),
            "rootProject.name = \"fixture\"\n",
        )
        .expect("restore A source");
        fs::write(fixture.path().join("build/kast"), b"rebuilt A bytes\n")
            .expect("different rebuilt CLI");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "rebuilt-a",
        );

        let rebuilt = refresh_local_development(request).expect("rebuilt A refresh");

        assert_ne!(first.receipt.generation_id, rebuilt.receipt.generation_id);
        assert_eq!(
            fs::read(
                prefix
                    .join("generations")
                    .join(first.receipt.generation_id.as_str())
                    .join("bin/kast"),
            )
            .expect("preserved first CLI"),
            b"#!/bin/sh\nexit 0\n",
        );
    }

    #[test]
    fn refresh_rejects_cli_bytes_changed_after_artifact_provenance() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        fs::write(&request.cli_binary, b"#!/bin/sh\nexit 17\n").expect("changed CLI bytes");

        let error = refresh_local_development(request).expect_err("mixed CLI bytes");

        assert_eq!(error.code, "LOCAL_ARTIFACT_CHECKSUM_MISMATCH");
        assert!(
            !prefix.exists(),
            "artifact rejection must happen before staging"
        );
    }

    #[test]
    fn refresh_rejects_backend_provenance_from_another_source_snapshot() {
        let repository = initialized_repository();
        let other_repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        let mut provenance = super::read_local_artifact_provenance(&request.backend_provenance)
            .expect("backend provenance");
        provenance.source =
            SourceSnapshot::capture(other_repository.path()).expect("other source snapshot");
        super::write_json_atomic(&request.backend_provenance, &provenance)
            .expect("mixed provenance");

        let error = refresh_local_development(request).expect_err("mixed backend source");

        assert_eq!(error.code, "LOCAL_ARTIFACT_SOURCE_MISMATCH");
        assert!(
            !prefix.exists(),
            "artifact rejection must happen before staging"
        );
    }

    #[test]
    fn refresh_rejects_a_backend_relabelled_without_producer_emitted_source_identity() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        fs::write(
            request
                .backend_directory
                .join("idea-home/plugins/kast-headless/lib/backend-headless-test-plugin.jar"),
            b"relabelled plugin bytes",
        )
        .expect("remove producer identity from plugin bytes");
        let source = SourceSnapshot::read_strict(&request.expected_source_snapshot)
            .expect("source snapshot");
        write_artifact_provenance(
            &request.backend_provenance,
            LocalArtifactKind::HeadlessBackend,
            &source,
            &request.backend_directory,
        );

        let error = refresh_local_development(request).expect_err("relabeled backend");

        assert_eq!(error.code, "LOCAL_BACKEND_SOURCE_ATTESTATION_INVALID");
        assert!(
            !prefix.exists(),
            "producer rejection must happen before staging"
        );
    }

    #[test]
    fn refresh_rejects_a_relabelled_stale_backend_sibling_jar() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        fs::write(
            request
                .backend_directory
                .join("idea-home/plugins/kast-headless/lib/analysis-api-test.jar"),
            b"stale analysis api bytes\n",
        )
        .expect("replace producer-owned sibling");
        let source = SourceSnapshot::read_strict(&request.expected_source_snapshot)
            .expect("source snapshot");
        write_artifact_provenance(
            &request.backend_provenance,
            LocalArtifactKind::HeadlessBackend,
            &source,
            &request.backend_directory,
        );

        let error = refresh_local_development(request).expect_err("relabelled stale sibling");

        assert_eq!(error.code, "LOCAL_BACKEND_COMPONENT_CHECKSUM_MISMATCH");
        assert!(
            !prefix.exists(),
            "producer rejection must happen before staging"
        );
    }

    #[cfg(unix)]
    #[test]
    fn refresh_rejects_a_generation_symlinked_from_another_prefix() {
        use std::os::unix::fs::symlink;

        let repository = initialized_repository();
        let other_workspace = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let first_prefix = fixture.path().join("first-authority");
        let first = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &first_prefix,
            "first",
        ))
        .expect("first refresh");
        let second_prefix = fixture.path().join("second-authority");
        fs::create_dir_all(second_prefix.join("generations")).expect("second generations");
        symlink(
            first_prefix
                .join("generations")
                .join(first.receipt.generation_id.as_str()),
            second_prefix
                .join("generations")
                .join(first.receipt.generation_id.as_str()),
        )
        .expect("foreign generation symlink");
        let request = refresh_request(
            repository.path(),
            other_workspace.path(),
            fixture.path(),
            &second_prefix,
            "second",
        );

        let error = refresh_local_development(request).expect_err("foreign generation");

        assert_eq!(error.code, "LOCAL_AUTHORITY_RECEIPT_INVALID");
        assert!(
            fs::symlink_metadata(second_prefix.join("current")).is_err(),
            "foreign generation must never become current"
        );
    }

    #[test]
    fn failed_refresh_preserves_the_previously_active_generation() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let first_request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        let first = refresh_local_development(first_request).expect("first refresh");
        let current_before = fs::read_link(prefix.join("current")).expect("current link");

        fs::write(
            repository.path().join("settings.gradle.kts"),
            "rootProject.name = \"changed\"\n",
        )
        .expect("changed source");
        fs::remove_file(repository.path().join("AGENTS.local.md"))
            .expect("remove owned guidance link");
        write_file(
            &repository.path().join("AGENTS.local.md"),
            b"user-owned guidance\n",
        );
        let second_request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "second",
        );

        let error = refresh_local_development(second_request).expect_err("guidance conflict");

        assert_eq!(error.code, "LOCAL_COMPONENT_TARGET_MISMATCH");
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("preserved current link"),
            current_before
        );
        let active = super::read_local_development_receipt(&prefix.join("authority.json"))
            .expect("preserved active receipt");
        assert_eq!(active.generation_id, first.receipt.generation_id);
    }

    #[test]
    fn post_activation_failure_rolls_back_current_previous_and_new_generation() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let first = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        ))
        .expect("first refresh");
        let current_before = fs::read_link(prefix.join("current")).expect("current link");
        let entrypoint_before = fs::read(prefix.join("bin/kast")).expect("entrypoint bytes");
        fs::write(
            repository.path().join("settings.gradle.kts"),
            "rootProject.name = \"changed\"\n",
        )
        .expect("changed source");
        let second_request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "second",
        );

        let error = refresh_local_development_with_observer(second_request, |phase| {
            assert_eq!(phase, LocalRefreshPhase::AfterActivation);
            fs::write(prefix.join("bin/kast"), b"incompatible wrapper\n")
                .expect("simulate changed stable entrypoint");
            Err(super::CliError::new(
                "TEST_INJECTED_FAILURE",
                "injected after current moved",
            ))
        })
        .expect_err("injected refresh failure");

        assert_eq!(error.code, "TEST_INJECTED_FAILURE");
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("restored current link"),
            current_before
        );
        assert!(
            fs::symlink_metadata(prefix.join("previous")).is_err(),
            "first-generation failure must restore the absent previous link"
        );
        assert_eq!(
            fs::read_dir(prefix.join("generations"))
                .expect("generations")
                .count(),
            1
        );
        let active = super::read_local_development_receipt(&prefix.join("authority.json"))
            .expect("restored active receipt");
        assert_eq!(active.generation_id, first.receipt.generation_id);
        assert_eq!(
            fs::read(prefix.join("bin/kast")).expect("restored entrypoint bytes"),
            entrypoint_before,
            "rollback must restore the stable entrypoint owned by the prior receipt"
        );
    }

    #[test]
    fn first_activation_retry_reconciles_receipt_owned_pre_current_state() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        let first = refresh_local_development(request.clone()).expect("first activation");
        fs::remove_file(prefix.join("current")).expect("simulate crash before current cutover");

        let recovered = refresh_local_development(request).expect("retry activation");

        assert_eq!(recovered.receipt.generation_id, first.receipt.generation_id);
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("recovered current"),
            Path::new("generations").join(first.receipt.generation_id.as_str()),
        );
        super::validate_receipt_components(&recovered.receipt)
            .expect("complete recovered generation");
    }

    #[test]
    fn first_activation_retry_preserves_unreceipted_matching_staging() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        let source = SourceSnapshot::read_strict(&request.expected_source_snapshot)
            .expect("source snapshot");
        let artifacts = super::LocalDevelopmentArtifactSet {
            cli: super::read_local_artifact_provenance(&request.cli_provenance)
                .expect("CLI provenance"),
            backend: super::read_local_artifact_provenance(&request.backend_provenance)
                .expect("backend provenance"),
        };
        let generation_id = super::LocalGenerationId::from_artifact_set(&source, &artifacts);
        let staged = prefix.join(format!(".staging-{}", generation_id.as_str()));
        write_file(&staged.join("partial"), b"interrupted staging\n");

        let error = refresh_local_development(request)
            .expect_err("unreceipted staging must block activation");

        assert_eq!(
            error.code, "LOCAL_STAGING_AUTHORITY_INVALID",
            "{error:#?}"
        );
        assert_eq!(
            fs::read(staged.join("partial")).expect("preserved foreign staging"),
            b"interrupted staging\n",
        );
        assert!(!prefix.join("current").exists());
    }

    #[cfg(unix)]
    #[test]
    fn first_activation_retry_reconciles_exact_atomic_symlink_temporaries() {
        use std::os::unix::fs::symlink;

        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let request = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        let first = refresh_local_development(request.clone()).expect("first activation");
        fs::remove_file(prefix.join("current")).expect("remove incomplete current");
        fs::rename(
            prefix.join("bin/kast"),
            prefix.join("bin/kast.next"),
        )
        .expect("interrupted launcher temporary");
        fs::rename(
            prefix.join("authority.json"),
            prefix.join("authority.next"),
        )
        .expect("interrupted authority temporary");
        symlink(
            Path::new("generations").join(first.receipt.generation_id.as_str()),
            prefix.join("current.next"),
        )
        .expect("interrupted current temporary");

        let recovered = refresh_local_development(request).expect("retry activation");

        assert_eq!(recovered.receipt.generation_id, first.receipt.generation_id);
        for temporary in [
            prefix.join("bin/kast.next"),
            prefix.join("authority.next"),
            prefix.join("current.next"),
        ] {
            assert!(
                fs::symlink_metadata(&temporary).is_err(),
                "temporary must be consumed: {}",
                temporary.display(),
            );
        }
        super::validate_receipt_components(&recovered.receipt)
            .expect("complete recovered generation");
    }

    #[test]
    fn refresh_rejects_switching_an_existing_prefix_to_another_workspace() {
        let source = initialized_repository();
        let other_workspace = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let first_request = refresh_request(
            source.path(),
            source.path(),
            fixture.path(),
            &prefix,
            "first",
        );
        refresh_local_development(first_request).expect("first refresh");
        let current_before = fs::read_link(prefix.join("current")).expect("current link");

        fs::write(
            source.path().join("settings.gradle.kts"),
            "rootProject.name = \"changed\"\n",
        )
        .expect("changed source");
        let second_request = refresh_request(
            source.path(),
            other_workspace.path(),
            fixture.path(),
            &prefix,
            "second",
        );

        let error = refresh_local_development(second_request).expect_err("workspace switch");

        assert_eq!(error.code, "LOCAL_WORKSPACE_AUTHORITY_MISMATCH");
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("preserved current link"),
            current_before
        );
        assert_eq!(
            fs::read_dir(prefix.join("generations"))
                .expect("generations")
                .count(),
            1
        );
    }

    #[test]
    fn rollback_reactivates_the_validated_previous_complete_generation() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let first = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        ))
        .expect("first refresh");
        fs::write(
            repository.path().join("settings.gradle.kts"),
            "rootProject.name = \"changed\"\n",
        )
        .expect("changed source");
        let second = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "second",
        ))
        .expect("second refresh");
        let first_manifest: serde_json::Value = serde_json::from_slice(
            &fs::read(
                prefix
                    .join("generations")
                    .join(first.receipt.generation_id.as_str())
                    .join("install.json"),
            )
            .expect("first manifest"),
        )
        .expect("first manifest JSON");
        let second_manifest: serde_json::Value = serde_json::from_slice(
            &fs::read(
                prefix
                    .join("generations")
                    .join(second.receipt.generation_id.as_str())
                    .join("install.json"),
            )
            .expect("second manifest"),
        )
        .expect("second manifest JSON");
        assert_ne!(
            first_manifest["roots"]["runtime"], second_manifest["roots"]["runtime"],
            "runtime descriptors must never cross source generations"
        );
        assert!(
            first_manifest["roots"]["runtime"]
                .as_str()
                .expect("first runtime root")
                .contains(first.receipt.generation_id.as_str())
        );
        assert!(
            second_manifest["roots"]["runtime"]
                .as_str()
                .expect("second runtime root")
                .contains(second.receipt.generation_id.as_str())
        );

        let rollback = rollback_local_development(LocalDevelopmentRollbackRequest {
            prefix: prefix.clone(),
            to_generation: first.receipt.generation_id.clone(),
        })
        .expect("rollback");

        assert_eq!(rollback.receipt.generation_id, first.receipt.generation_id);
        assert_eq!(
            rollback.replaced_generation,
            Some(second.receipt.generation_id.clone())
        );
        assert!(!rollback.skipped);
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("rolled back current"),
            Path::new("generations").join(first.receipt.generation_id.as_str())
        );
        assert_eq!(
            fs::read_link(prefix.join("previous")).expect("new rollback target"),
            Path::new("generations").join(second.receipt.generation_id.as_str())
        );
        assert_eq!(
            fs::read(repository.path().join("AGENTS.local.md")).expect("effective guidance"),
            fs::read(
                prefix
                    .join("generations")
                    .join(first.receipt.generation_id.as_str())
                    .join("guidance/AGENTS.local.md")
            )
            .expect("first guidance")
        );

        let retry = rollback_local_development(LocalDevelopmentRollbackRequest {
            prefix: prefix.clone(),
            to_generation: first.receipt.generation_id.clone(),
        })
        .expect("idempotent rollback retry");
        assert!(retry.skipped);
        assert_eq!(retry.replaced_generation, None);
        assert_eq!(retry.receipt.generation_id, first.receipt.generation_id);
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("retry current"),
            Path::new("generations").join(first.receipt.generation_id.as_str())
        );
    }

    #[cfg(unix)]
    #[test]
    fn remove_refuses_to_orphan_a_live_receipt_owned_runtime() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let refreshed = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        ))
        .expect("refresh");
        let mut backend = register_live_runtime(
            &prefix,
            &refreshed.receipt.generation_id,
            repository.path(),
            fixture.path(),
        );

        let error = remove_local_development(LocalDevelopmentRemoveRequest {
            prefix: prefix.clone(),
            workspace_root: repository.path().to_path_buf(),
        })
        .expect_err("live runtime must block removal");
        let _ = backend.kill();
        let _ = backend.wait();

        assert_eq!(error.code, "LOCAL_RUNTIME_ACTIVE");
        assert!(prefix.exists(), "blocked removal must preserve authority");
        assert_eq!(error.details.get("pid"), Some(&backend.id().to_string()),);
        assert!(
            error
                .details
                .get("stopCommand")
                .is_some_and(|command| command.contains("developer runtime stop")),
            "blocked removal must provide the receipt-owned stop command",
        );

        let removed = remove_local_development(LocalDevelopmentRemoveRequest {
            prefix: prefix.clone(),
            workspace_root: repository.path().to_path_buf(),
        })
        .expect("remove after backend exit");
        assert!(removed.removed);
        assert!(!prefix.exists());
    }

    #[cfg(unix)]
    #[test]
    fn refresh_refuses_to_orphan_a_live_runtime_before_generation_activation() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let first = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        ))
        .expect("first refresh");
        let mut backend = register_live_runtime(
            &prefix,
            &first.receipt.generation_id,
            repository.path(),
            fixture.path(),
        );
        fs::write(
            repository.path().join("settings.gradle.kts"),
            "rootProject.name = \"changed\"\n",
        )
        .expect("changed source");

        let error = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "second",
        ))
        .expect_err("live runtime must block generation-changing refresh");

        assert_eq!(error.code, "LOCAL_RUNTIME_ACTIVE");
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("preserved current generation"),
            Path::new("generations").join(first.receipt.generation_id.as_str()),
        );
        assert_eq!(
            fs::read_dir(prefix.join("generations"))
                .expect("preserved generations")
                .count(),
            1,
            "blocked refresh must not stage a hidden generation",
        );
        let _ = backend.kill();
        let _ = backend.wait();
    }

    #[cfg(unix)]
    #[test]
    fn rollback_refuses_to_orphan_a_live_current_generation_runtime() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let first = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        ))
        .expect("first refresh");
        fs::write(
            repository.path().join("settings.gradle.kts"),
            "rootProject.name = \"changed\"\n",
        )
        .expect("changed source");
        let second = refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "second",
        ))
        .expect("second refresh");
        let mut backend = register_live_runtime(
            &prefix,
            &second.receipt.generation_id,
            repository.path(),
            fixture.path(),
        );

        let error = rollback_local_development(LocalDevelopmentRollbackRequest {
            prefix: prefix.clone(),
            to_generation: first.receipt.generation_id.clone(),
        })
        .expect_err("live runtime must block generation-changing rollback");

        assert_eq!(error.code, "LOCAL_RUNTIME_ACTIVE");
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("preserved current generation"),
            Path::new("generations").join(second.receipt.generation_id.as_str()),
        );
        let _ = backend.kill();
        let _ = backend.wait();
    }

    #[cfg(unix)]
    #[test]
    fn idempotent_remove_cleans_a_dangling_owned_guidance_projection() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        ))
        .expect("refresh");
        let guidance = repository.path().join("AGENTS.local.md");
        fs::remove_dir_all(&prefix).expect("simulate missing prefix");
        assert!(fs::symlink_metadata(&guidance).is_ok());
        assert!(!guidance.exists(), "projection must now be dangling");

        let removed = remove_local_development(LocalDevelopmentRemoveRequest {
            prefix: prefix.clone(),
            workspace_root: repository.path().to_path_buf(),
        })
        .expect("idempotent removal recovery");

        assert!(!removed.removed);
        assert!(fs::symlink_metadata(guidance).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn missing_prefix_removal_and_refresh_share_the_namespace_lock() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "initial",
        ))
        .expect("initial refresh");
        fs::remove_dir_all(&prefix).expect("simulate missing prefix");
        let replacement = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "replacement",
        );

        let (cleanup_tx, cleanup_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let removal_prefix = prefix.clone();
        let removal_workspace = repository.path().to_path_buf();
        let removal = thread::spawn(move || {
            remove_local_development_with_observer(
                LocalDevelopmentRemoveRequest {
                    prefix: removal_prefix,
                    workspace_root: removal_workspace,
                },
                |phase| {
                    assert_eq!(phase, LocalRemovalPhase::BeforeMissingPrefixCleanup);
                    cleanup_tx.send(()).expect("announce missing cleanup");
                    release_rx.recv().expect("release missing cleanup barrier");
                    Ok(())
                },
            )
        });
        cleanup_rx.recv().expect("missing cleanup owns lock");

        let (refreshed_tx, refreshed_rx) = mpsc::channel();
        let refresh = thread::spawn(move || {
            refreshed_tx
                .send(refresh_local_development(replacement))
                .expect("return refresh result");
        });
        assert!(
            refreshed_rx
                .recv_timeout(Duration::from_millis(200))
                .is_err(),
            "refresh must remain blocked while missing-prefix cleanup owns the namespace lock",
        );

        release_tx.send(()).expect("finish missing cleanup");
        let removed = removal.join().expect("removal thread").expect("removal");
        assert!(!removed.removed);
        let refreshed = refreshed_rx
            .recv_timeout(Duration::from_secs(10))
            .expect("refresh result")
            .expect("replacement refresh");
        refresh.join().expect("refresh thread");

        assert!(prefix.exists(), "replacement authority must remain active");
        assert!(repository.path().join("AGENTS.local.md").is_file());
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("replacement current"),
            Path::new("generations").join(refreshed.receipt.generation_id.as_str()),
        );
    }

    #[test]
    fn runtime_registration_completes_before_a_waiting_generation_transition() {
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let registration = fixture.path().join("runtime-registered");
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let startup_prefix = prefix.clone();
        let startup_registration = registration.clone();
        let startup = thread::spawn(move || {
            with_local_runtime_start_lock_after_validation(
                &startup_prefix,
                || Ok(()),
                || {
                    started_tx.send(()).expect("announce runtime spawn");
                    release_rx.recv().expect("release registration barrier");
                    fs::write(&startup_registration, "registered\n")?;
                    Ok(())
                },
            )
        });
        started_rx
            .recv()
            .expect("runtime start owns authority lock");

        let (transition_tx, transition_rx) = mpsc::channel();
        let transition_prefix = prefix.clone();
        let transition_registration = registration.clone();
        let transition = thread::spawn(move || {
            let observed_registration = with_local_authority_lock(&transition_prefix, || {
                Ok(transition_registration.is_file())
            });
            transition_tx
                .send(observed_registration)
                .expect("return transition observation");
        });
        assert!(
            transition_rx
                .recv_timeout(Duration::from_millis(200))
                .is_err(),
            "generation transition must wait until the spawned runtime is registered",
        );

        release_tx.send(()).expect("finish runtime registration");
        startup.join().expect("startup thread").expect("startup");
        assert!(
            transition_rx
                .recv_timeout(Duration::from_secs(10))
                .expect("transition observation")
                .expect("transition lock"),
            "the transition must observe registration before it can inspect live runtimes",
        );
        transition.join().expect("transition thread");
    }

    #[test]
    fn concurrent_runtime_start_reuses_the_first_registration_instead_of_spawning_twice() {
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let registration = fixture.path().join("runtime-registered");
        let (first_started_tx, first_started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let first_prefix = prefix.clone();
        let first_registration = registration.clone();
        let first = thread::spawn(move || {
            with_local_runtime_start_lock_after_validation(
                &first_prefix,
                || Ok(()),
                || {
                    first_started_tx.send(()).expect("announce first spawn");
                    release_rx.recv().expect("release first registration");
                    fs::write(&first_registration, "registered\n")?;
                    Ok(())
                },
            )
        });
        first_started_rx
            .recv()
            .expect("first start owns authority lock");

        let (duplicate_spawn_tx, duplicate_spawn_rx) = mpsc::channel();
        let (second_result_tx, second_result_rx) = mpsc::channel();
        let second_prefix = prefix.clone();
        let second_registration = registration.clone();
        let second = thread::spawn(move || {
            let result = with_local_runtime_start_lock_after_validation(
                &second_prefix,
                || Ok(()),
                || {
                    if second_registration.is_file() {
                        Ok(false)
                    } else {
                        duplicate_spawn_tx
                            .send(())
                            .expect("announce duplicate spawn");
                        Ok(true)
                    }
                },
            );
            second_result_tx.send(result).expect("return second start");
        });
        assert!(
            second_result_rx
                .recv_timeout(Duration::from_millis(200))
                .is_err(),
            "second start must wait for the first registration",
        );

        release_tx.send(()).expect("finish first registration");
        first
            .join()
            .expect("first start thread")
            .expect("first start");
        assert!(
            !second_result_rx
                .recv_timeout(Duration::from_secs(10))
                .expect("second start result")
                .expect("second start lock"),
            "second start must choose reuse after re-inspecting under the lock",
        );
        second.join().expect("second start thread");
        assert!(
            duplicate_spawn_rx.try_recv().is_err(),
            "a concurrently registered runtime must prevent a duplicate spawn",
        );
    }

    #[test]
    fn generation_transition_completes_before_a_waiting_runtime_start_revalidates() {
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let generation = fixture.path().join("active-generation");
        fs::write(&generation, "old\n").expect("initial generation");
        let (transition_tx, transition_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let transition_prefix = prefix.clone();
        let transition_generation = generation.clone();
        let transition = thread::spawn(move || {
            with_local_authority_lock(&transition_prefix, || {
                transition_tx.send(()).expect("announce transition lock");
                release_rx.recv().expect("release transition barrier");
                fs::write(&transition_generation, "new\n")?;
                Ok(())
            })
        });
        transition_rx
            .recv()
            .expect("transition owns authority lock");

        let (spawned_tx, spawned_rx) = mpsc::channel();
        let (startup_tx, startup_rx) = mpsc::channel();
        let startup_prefix = prefix.clone();
        let startup_generation = generation.clone();
        let startup = thread::spawn(move || {
            let result = with_local_runtime_start_lock_after_validation(
                &startup_prefix,
                || {
                    if fs::read_to_string(&startup_generation)? == "old\n" {
                        Ok(())
                    } else {
                        Err(super::CliError::new(
                            "LOCAL_AUTHORITY_INACTIVE",
                            "Expected generation is no longer active.",
                        ))
                    }
                },
                || {
                    spawned_tx.send(()).expect("announce spawn");
                    Ok(())
                },
            );
            startup_tx.send(result).expect("return startup result");
        });
        assert!(
            startup_rx.recv_timeout(Duration::from_millis(200)).is_err(),
            "runtime start must wait while a generation transition owns the authority lock",
        );

        release_tx.send(()).expect("finish generation transition");
        transition
            .join()
            .expect("transition thread")
            .expect("transition");
        let error = startup_rx
            .recv_timeout(Duration::from_secs(10))
            .expect("startup result")
            .expect_err("stale runtime start must fail revalidation");
        startup.join().expect("startup thread");

        assert_eq!(error.code, "LOCAL_AUTHORITY_INACTIVE");
        assert!(
            spawned_rx.try_recv().is_err(),
            "runtime process must not spawn after the expected generation changes",
        );
    }

    #[cfg(unix)]
    #[test]
    fn linked_worktree_refresh_uses_source_owned_ignore_without_mutating_shared_git_exclude() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let linked = fixture.path().join("linked-worktree");
        let linked_text = linked.to_str().expect("UTF-8 linked worktree");
        run_git(
            repository.path(),
            &[
                "worktree",
                "add",
                "--quiet",
                "-b",
                "feature/local-test",
                linked_text,
            ],
        );
        let shared_exclude = repository.path().join(".git/info/exclude");
        let exclude_before = fs::read(&shared_exclude).unwrap_or_default();
        let prefix = linked.join(".kast/local-development");

        refresh_local_development(refresh_request(
            &linked,
            &linked,
            fixture.path(),
            &prefix,
            "linked",
        ))
        .expect("linked refresh");

        let ignored = Command::new("git")
            .arg("-C")
            .arg(&linked)
            .args(["check-ignore", "--quiet", "--", "AGENTS.local.md"])
            .status()
            .expect("git check-ignore");
        assert!(
            ignored.success(),
            "source-owned .gitignore must cover local guidance"
        );
        let local_prefix_ignored = Command::new("git")
            .arg("-C")
            .arg(&linked)
            .args(["check-ignore", "--quiet", "--", ".kast/local-development"])
            .status()
            .expect("git check-ignore local prefix");
        assert!(
            local_prefix_ignored.success(),
            "source-owned .gitignore must cover the default local prefix before first refresh",
        );
        assert_eq!(
            fs::read(&shared_exclude).unwrap_or_default(),
            exclude_before,
            "linked refresh must not mutate the shared repository-local exclude file",
        );

        remove_local_development(LocalDevelopmentRemoveRequest {
            prefix,
            workspace_root: linked,
        })
        .expect("linked removal");
        assert_eq!(
            fs::read(&shared_exclude).unwrap_or_default(),
            exclude_before
        );
    }

    #[test]
    fn remove_restores_release_authority_without_mutating_unrelated_state() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        let release_sentinel = fixture.path().join("release/kast");
        write_file(&release_sentinel, b"release-binary\n");
        let release_before = fs::read(&release_sentinel).expect("release sentinel");
        refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "first",
        ))
        .expect("refresh");
        let authority_path =
            fs::canonicalize(prefix.join("authority.json")).expect("physical authority receipt");
        let mut legacy_authority: serde_json::Value =
            serde_json::from_slice(&fs::read(&authority_path).expect("authority receipt"))
                .expect("authority JSON");
        legacy_authority["schemaVersion"] = serde_json::json!(1);
        legacy_authority
            .as_object_mut()
            .expect("authority object")
            .remove("artifacts");
        super::replace_plain_file_atomically(
            &authority_path,
            &serde_json::to_vec_pretty(&legacy_authority).expect("legacy authority JSON"),
        )
        .expect("legacy authority receipt");

        let removed = remove_local_development(LocalDevelopmentRemoveRequest {
            prefix: prefix.clone(),
            workspace_root: repository.path().to_path_buf(),
        })
        .expect("remove");

        assert!(removed.removed);
        assert!(!prefix.exists());
        assert!(!repository.path().join("AGENTS.local.md").exists());
        assert_eq!(
            fs::read(&release_sentinel).expect("preserved release sentinel"),
            release_before
        );
    }

    #[cfg(unix)]
    #[test]
    fn removal_and_refresh_share_one_lock_outside_the_renamed_prefix() {
        let repository = initialized_repository();
        let fixture = tempfile::tempdir().expect("fixture");
        let prefix = fixture.path().join("local-authority");
        refresh_local_development(refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "initial",
        ))
        .expect("initial refresh");
        let replacement = refresh_request(
            repository.path(),
            repository.path(),
            fixture.path(),
            &prefix,
            "replacement",
        );

        let (renamed_tx, renamed_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let removal_prefix = prefix.clone();
        let removal_workspace = repository.path().to_path_buf();
        let removal = thread::spawn(move || {
            remove_local_development_with_observer(
                LocalDevelopmentRemoveRequest {
                    prefix: removal_prefix,
                    workspace_root: removal_workspace,
                },
                |phase| {
                    assert_eq!(phase, LocalRemovalPhase::AfterPrefixRenamed);
                    renamed_tx.send(()).expect("announce renamed prefix");
                    release_rx.recv().expect("release removal barrier");
                    Ok(())
                },
            )
        });
        renamed_rx.recv().expect("prefix renamed");

        let (refreshed_tx, refreshed_rx) = mpsc::channel();
        let refresh = thread::spawn(move || {
            refreshed_tx
                .send(refresh_local_development(replacement))
                .expect("return refresh result");
        });
        assert!(
            refreshed_rx
                .recv_timeout(Duration::from_millis(200))
                .is_err(),
            "refresh must remain blocked while removal owns the stable namespace lock",
        );

        release_tx.send(()).expect("finish removal");
        let removed = removal.join().expect("removal thread").expect("removal");
        assert!(removed.removed);
        let refreshed = refreshed_rx
            .recv_timeout(Duration::from_secs(10))
            .expect("refresh result")
            .expect("replacement refresh");
        refresh.join().expect("refresh thread");

        assert!(prefix.exists(), "replacement authority must remain active");
        assert_eq!(
            fs::read_link(prefix.join("current")).expect("replacement current"),
            Path::new("generations").join(refreshed.receipt.generation_id.as_str()),
        );
        assert!(
            prefix
                .parent()
                .expect("prefix parent")
                .join(".local-authority.refresh.lock")
                .is_file(),
            "namespace lock must survive prefix replacement",
        );
    }

    fn initialized_repository() -> tempfile::TempDir {
        let repository = tempfile::tempdir().expect("repository");
        run_git(repository.path(), &["init", "--quiet"]);
        run_git(
            repository.path(),
            &["config", "user.email", "test@example.com"],
        );
        run_git(repository.path(), &["config", "user.name", "Kast Test"]);
        fs::write(
            repository.path().join("settings.gradle.kts"),
            "rootProject.name = \"fixture\"\n",
        )
        .expect("fixture source");
        fs::write(
            repository.path().join(".gitignore"),
            "/AGENTS.local.md\n/.kast/\n",
        )
        .expect("local guidance ignore");
        write_file(
            &repository
                .path()
                .join("cli-rs/resources/kast-skill/SKILL.md"),
            b"---\nname: kast\ndescription: fixture\n---\nUse `kast agent verify`.\n",
        );
        write_file(
            &repository
                .path()
                .join("cli-rs/resources/local-development/config.toml"),
            super::LOCAL_DEVELOPMENT_CONFIG,
        );
        run_git(repository.path(), &["add", "."]);
        run_git(repository.path(), &["commit", "--quiet", "-m", "initial"]);
        repository
    }

    fn write_backend_fixture(root: &Path) {
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

    fn refresh_request(
        source_root: &Path,
        workspace_root: &Path,
        fixture: &Path,
        prefix: &Path,
        label: &str,
    ) -> LocalDevelopmentRefreshRequest {
        let cli_binary = fixture.join("build/kast");
        if !cli_binary.exists() {
            write_file(&cli_binary, b"#!/bin/sh\nexit 0\n");
            make_executable(&cli_binary);
        }
        let backend_directory = fixture.join("build/backend-headless");
        if !backend_directory.exists() {
            write_backend_fixture(&backend_directory);
        }
        let snapshot = SourceSnapshot::capture(source_root).expect("snapshot");
        write_backend_source_snapshot_jar(&backend_directory, &snapshot);
        let snapshot_file = fixture.join(format!("source-snapshot-{label}.json"));
        snapshot
            .write_atomic(&snapshot_file)
            .expect("snapshot file");
        let cli_provenance = fixture.join(format!("cli-provenance-{label}.json"));
        write_artifact_provenance(
            &cli_provenance,
            LocalArtifactKind::Cli,
            &snapshot,
            &cli_binary,
        );
        let backend_provenance = fixture.join(format!("backend-provenance-{label}.json"));
        write_artifact_provenance(
            &backend_provenance,
            LocalArtifactKind::HeadlessBackend,
            &snapshot,
            &backend_directory,
        );
        let skill_source = source_root.join("cli-rs/resources/kast-skill/SKILL.md");
        let config_source = source_root.join("cli-rs/resources/local-development/config.toml");
        LocalDevelopmentRefreshRequest {
            source_root: source_root.to_path_buf(),
            workspace_root: workspace_root.to_path_buf(),
            prefix: prefix.to_path_buf(),
            expected_source_snapshot: snapshot_file,
            cli_binary,
            cli_provenance,
            backend_directory,
            backend_provenance,
            skill_source,
            config_source,
        }
    }

    fn write_backend_source_snapshot_jar(backend_directory: &Path, snapshot: &SourceSnapshot) {
        let plugin_jar = backend_directory
            .join("idea-home/plugins/kast-headless/lib/backend-headless-test-plugin.jar");
        let file = fs::File::create(plugin_jar).expect("plugin jar");
        let mut archive = zip::ZipWriter::new(file);
        archive
            .start_file(
                super::LOCAL_BACKEND_SOURCE_SNAPSHOT_ENTRY,
                zip::write::SimpleFileOptions::default(),
            )
            .expect("snapshot jar entry");
        archive
            .write_all(&serde_json::to_vec(snapshot).expect("snapshot JSON"))
            .expect("snapshot jar bytes");
        archive
            .start_file(
                super::LOCAL_BACKEND_COMPONENT_MANIFEST_ENTRY,
                zip::write::SimpleFileOptions::default(),
            )
            .expect("component manifest jar entry");
        archive
            .write_all(
                &serde_json::to_vec(&backend_component_manifest(backend_directory, snapshot))
                    .expect("component manifest JSON"),
            )
            .expect("component manifest jar bytes");
        archive.finish().expect("finish plugin jar");
    }

    fn backend_component_manifest(
        backend_directory: &Path,
        snapshot: &SourceSnapshot,
    ) -> serde_json::Value {
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
                "sha256": crate::manifest::sha256_file(&backend_directory.join(path))
                    .expect("component SHA-256"),
            })
        });
        serde_json::json!({
            "schemaVersion": 1,
            "sourceTreeSha256": snapshot.source_tree_sha256,
            "components": components,
        })
    }

    fn write_artifact_provenance(
        output: &Path,
        kind: LocalArtifactKind,
        source: &SourceSnapshot,
        artifact: &Path,
    ) {
        let artifact = fs::canonicalize(artifact).expect("canonical artifact");
        let provenance = LocalArtifactProvenance {
            schema_version: super::LOCAL_ARTIFACT_PROVENANCE_SCHEMA_VERSION,
            kind,
            source: source.clone(),
            sha256: super::tree_sha256(&artifact).expect("artifact digest"),
            artifact,
            implementation_version: "test-version".to_string(),
        };
        super::write_json_atomic(output, &provenance).expect("artifact provenance");
    }

    fn write_file(path: &Path, bytes: &[u8]) {
        fs::create_dir_all(path.parent().expect("file parent")).expect("create parent");
        fs::write(path, bytes).expect("write file");
    }

    #[cfg(unix)]
    fn register_live_runtime(
        prefix: &Path,
        generation_id: &super::LocalGenerationId,
        workspace_root: &Path,
        fixture: &Path,
    ) -> std::process::Child {
        let backend = Command::new("sleep")
            .arg("60")
            .spawn()
            .expect("live backend fixture");
        let descriptor_file = prefix
            .join("state")
            .join(generation_id.as_str())
            .join("cache/workspace/daemons.json");
        write_file(
            &descriptor_file,
            &serde_json::to_vec_pretty(&serde_json::json!([{
                "workspaceRoot": workspace_root,
                "backendName": "headless",
                "backendVersion": "test",
                "transport": "uds",
                "socketPath": fixture.join("backend.sock"),
                "pid": backend.id(),
                "schemaVersion": crate::SCHEMA_VERSION,
            }]))
            .expect("descriptor JSON"),
        );
        backend
    }

    #[cfg(unix)]
    fn make_executable(path: &Path) {
        use std::os::unix::fs::PermissionsExt;

        let mut permissions = fs::metadata(path).expect("metadata").permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(path, permissions).expect("executable");
    }

    #[cfg(not(unix))]
    fn make_executable(_path: &Path) {}

    fn run_git(root: &Path, args: &[&str]) {
        let output = Command::new("git")
            .arg("-C")
            .arg(root)
            .args(args)
            .output()
            .expect("git command");
        assert!(
            output.status.success(),
            "git {:?} failed: {}",
            args,
            String::from_utf8_lossy(&output.stderr)
        );
    }
}
