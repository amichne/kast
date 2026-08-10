#[cfg(test)]
mod agent_file_path_tests {
    use super::*;

    #[test]
    fn relative_kotlin_file_resolves_against_explicit_workspace() {
        let fixture = PathFixture::with_file("src/with spaces/App.kt");

        let actual = fixture
            .normalizer()
            .normalize("src/with spaces/App.kt")
            .expect("canonical target");

        assert_eq!(actual.rpc_path, fixture.canonical_file());
    }

    #[test]
    fn absolute_kotlin_file_remains_compatible() {
        let fixture = PathFixture::with_file("src/App.kt");

        let actual = fixture
            .normalizer()
            .normalize(fixture.file.to_str().expect("UTF-8 file"))
            .expect("canonical target");

        assert_eq!(actual.rpc_path, fixture.canonical_file());
    }

    #[test]
    fn kotlin_script_is_supported() {
        let fixture = PathFixture::with_file("build-logic/settings.gradle.kts");

        let actual = fixture
            .normalizer()
            .normalize("build-logic/settings.gradle.kts")
            .expect("canonical script");

        assert_eq!(actual.rpc_path, fixture.canonical_file());
    }

    #[test]
    fn missing_kotlin_leaf_uses_canonical_existing_parent() {
        let fixture = PathFixture::with_workspace();
        let parent = fixture.workspace.join("src/generated");
        std::fs::create_dir_all(&parent).expect("source parent");
        let expected = parent
            .canonicalize()
            .expect("canonical parent")
            .join("Deleted.kt");

        let actual = fixture
            .normalizer()
            .normalize("src/generated/Deleted.kt")
            .expect("missing Kotlin target");

        assert_eq!(actual.rpc_path, expected.to_str().expect("UTF-8 expected"));
    }

    #[test]
    fn relative_path_requires_explicit_workspace_root() {
        let fixture = PathFixture::with_file("src/App.kt");
        let runtime = AgentRuntimeArgs {
            workspace_root: None,
        };
        let normalizer = AgentFilePathNormalizer::from_runtime(&runtime)
            .expect("current-directory normalizer");

        let error = normalizer
            .normalize("src/App.kt")
            .expect_err("relative path without declared workspace must fail");

        assert_eq!(error.code, "AGENT_RELATIVE_FILE_REQUIRES_WORKSPACE");
        drop(fixture);
    }

    #[test]
    fn relative_parent_escape_fails_closed() {
        let fixture = PathFixture::with_workspace();

        let error = fixture
            .normalizer()
            .normalize("../Outside.kt")
            .expect_err("lexical escape must fail");

        assert_eq!(error.code, "AGENT_FILE_OUTSIDE_WORKSPACE");
    }

    #[test]
    fn absolute_outside_file_fails_closed() {
        let fixture = PathFixture::with_workspace();
        let outside = fixture.temp.path().join("Outside.kt");
        std::fs::write(&outside, "class Outside\n").expect("outside source");

        let error = fixture
            .normalizer()
            .normalize(outside.to_str().expect("UTF-8 outside path"))
            .expect_err("outside target must fail");

        assert_eq!(error.code, "AGENT_FILE_OUTSIDE_WORKSPACE");
    }

    #[test]
    fn unsupported_extension_fails_closed() {
        let fixture = PathFixture::with_file("src/App.java");

        let error = fixture
            .normalizer()
            .normalize("src/App.java")
            .expect_err("Java target must fail");

        assert_eq!(error.code, "AGENT_FILE_KIND_UNSUPPORTED");
    }

    #[test]
    fn hard_output_directories_fail_closed() {
        for path in [
            "build/generated/App.kt",
            "plugin/build/distributions/Plugin.kt",
            ".gradle/cache/App.kt",
            "out/production/App.kt",
            ".idea/App.kt",
        ] {
            let fixture = PathFixture::with_file(path);

            let error = fixture
                .normalizer()
                .normalize(path)
                .expect_err("hard output target must fail");

            assert_eq!(error.code, "AGENT_FILE_HARD_EXCLUDED", "{path}");
        }
    }

    #[test]
    fn directory_with_kotlin_extension_fails_closed() {
        let fixture = PathFixture::with_workspace();
        std::fs::create_dir_all(fixture.workspace.join("src/Directory.kt"))
            .expect("Kotlin-named directory");

        let error = fixture
            .normalizer()
            .normalize("src/Directory.kt")
            .expect_err("directory target must fail");

        assert_eq!(error.code, "AGENT_FILE_KIND_UNSUPPORTED");
    }

    #[cfg(unix)]
    #[test]
    fn in_workspace_symlink_resolves_to_real_kotlin_file() {
        let fixture = PathFixture::with_file("src/Real.kt");
        let alias = fixture.workspace.join("src/Alias.kt");
        std::os::unix::fs::symlink(&fixture.file, &alias).expect("safe symlink");

        let actual = fixture
            .normalizer()
            .normalize("src/Alias.kt")
            .expect("safe symlink target");

        assert_eq!(actual.rpc_path, fixture.canonical_file());
    }

    #[cfg(unix)]
    #[test]
    fn escaping_symlink_fails_closed() {
        let fixture = PathFixture::with_workspace();
        let outside = fixture.temp.path().join("Outside.kt");
        std::fs::write(&outside, "class Outside\n").expect("outside source");
        let alias = fixture.workspace.join("Alias.kt");
        std::os::unix::fs::symlink(&outside, &alias).expect("escaping symlink");

        let error = fixture
            .normalizer()
            .normalize("Alias.kt")
            .expect_err("symlink escape must fail");

        assert_eq!(error.code, "AGENT_FILE_SYMLINK_UNSAFE");
    }

    #[cfg(unix)]
    #[test]
    fn broken_symlink_fails_closed() {
        let fixture = PathFixture::with_workspace();
        let alias = fixture.workspace.join("Broken.kt");
        std::os::unix::fs::symlink(fixture.workspace.join("Missing.kt"), &alias)
            .expect("broken symlink");

        let error = fixture
            .normalizer()
            .normalize("Broken.kt")
            .expect_err("broken symlink must fail");

        assert_eq!(error.code, "AGENT_FILE_SYMLINK_UNSAFE");
    }

    struct PathFixture {
        temp: tempfile::TempDir,
        workspace: PathBuf,
        file: PathBuf,
    }

    impl PathFixture {
        fn with_workspace() -> Self {
            let temp = tempfile::tempdir().expect("tempdir");
            let workspace = temp.path().join("workspace");
            std::fs::create_dir_all(&workspace).expect("workspace");
            Self {
                temp,
                workspace,
                file: PathBuf::new(),
            }
        }

        fn with_file(relative_path: &str) -> Self {
            let mut fixture = Self::with_workspace();
            fixture.file = fixture.workspace.join(relative_path);
            std::fs::create_dir_all(fixture.file.parent().expect("source parent"))
                .expect("source directory");
            std::fs::write(&fixture.file, "class Fixture\n").expect("source");
            fixture
        }

        fn normalizer(&self) -> AgentFilePathNormalizer {
            AgentFilePathNormalizer::from_runtime(&AgentRuntimeArgs {
                workspace_root: Some(self.workspace.clone()),
            })
            .expect("normalizer")
        }

        fn canonical_file(&self) -> String {
            self.file
                .canonicalize()
                .expect("canonical file")
                .to_str()
                .expect("UTF-8 canonical file")
                .to_string()
        }
    }
}
