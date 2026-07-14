#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compare_filters_are_single_select_and_filter_semantic_rows() {
        let rows = vec![
            sample_compare_row(
                Some("app.PublicThing"),
                "PublicThing",
                "CLASS",
                "PUBLIC",
                ":app",
                "main",
                ["CALL"],
            ),
            sample_compare_row(
                Some("lib.PrivateHelper"),
                "PrivateHelper",
                "FUNCTION",
                "PRIVATE",
                ":lib",
                "test",
                ["TYPE_REF"],
            ),
        ];
        let filters = CompareFilters {
            kind: Some("FUNCTION".to_string()),
            visibility: Some("PRIVATE".to_string()),
            source_set: Some("test".to_string()),
            module: Some(":lib".to_string()),
            relation: Some("TYPE_REF".to_string()),
        };

        let filtered = apply_compare_filters(&rows, &filters);

        assert_eq!(filtered.len(), 1);
        assert_eq!(filtered[0].fq_name.as_deref(), Some("lib.PrivateHelper"));
    }

    #[test]
    fn compare_diff_buckets_separate_lexical_noise_semantic_only_and_filtered_rows() {
        let lexical = vec![
            sample_compare_row(
                Some("lib.Foo"),
                "Foo",
                "CLASS",
                "PUBLIC",
                ":lib",
                "main",
                ["CALL"],
            ),
            sample_lexical_only_row("FooNotes"),
        ];
        let semantic = vec![
            sample_compare_row(
                Some("lib.Foo"),
                "Foo",
                "CLASS",
                "PUBLIC",
                ":lib",
                "main",
                ["CALL"],
            ),
            sample_compare_row(
                Some("lib.FooWidget"),
                "FooWidget",
                "CLASS",
                "PUBLIC",
                ":lib",
                "main",
                ["CALL"],
            ),
        ];
        let filtered = vec![semantic[0].clone()];

        let buckets = build_compare_diff_buckets(&lexical, &semantic, &filtered);

        assert_eq!(buckets.common_count, 1);
        assert_eq!(buckets.lexical_only[0].label, "FooNotes");
        assert_eq!(
            buckets.semantic_only[0].fq_name.as_deref(),
            Some("lib.FooWidget")
        );
        assert!(
            buckets.filtered_out.is_empty(),
            "semantic-only rows should not also be counted as filtered-out rows"
        );
    }

    #[test]
    fn compare_diff_buckets_keep_common_filtered_rows_separate() {
        let lexical = vec![sample_compare_row(
            Some("lib.Foo"),
            "Foo",
            "CLASS",
            "PUBLIC",
            ":lib",
            "main",
            ["CALL"],
        )];
        let semantic = vec![lexical[0].clone()];
        let filtered = Vec::new();

        let buckets = build_compare_diff_buckets(&lexical, &semantic, &filtered);

        assert_eq!(buckets.common_count, 1);
        assert!(buckets.lexical_only.is_empty());
        assert!(buckets.semantic_only.is_empty());
        assert_eq!(buckets.filtered_out[0].fq_name.as_deref(), Some("lib.Foo"));
    }

    #[test]
    fn compare_selection_prefers_the_active_lexical_pane() {
        let lexical = vec![sample_lexical_only_row("FooNotes")];
        let semantic = vec![sample_compare_row(
            Some("lib.Foo"),
            "Foo",
            "CLASS",
            "PUBLIC",
            ":lib",
            "main",
            ["CALL"],
        )];

        let selected = selected_compare_row(None, &lexical, &semantic, 0, 0, ComparePane::Lexical)
            .expect("selected row");

        assert_eq!(selected.0, ComparePane::Lexical);
        assert_eq!(selected.2.label, "FooNotes");
    }

    #[test]
    fn compare_module_sort_renders_tree_shaped_group_paths() {
        let mut rows = vec![
            sample_compare_row(
                Some("lib.Zed"),
                "Zed",
                "FUNCTION",
                "INTERNAL",
                ":lib",
                "test",
                ["TYPE_REF"],
            ),
            sample_compare_row(
                Some("app.Alpha"),
                "Alpha",
                "CLASS",
                "PUBLIC",
                ":app",
                "main",
                ["CALL"],
            ),
        ];

        sort_compare_rows(&mut rows, CompareSort::Module);

        assert_eq!(rows[0].fq_name.as_deref(), Some("app.Alpha"));
        assert_eq!(
            rows[0].group_path,
            vec![
                ":app".to_string(),
                "main".to_string(),
                "Alpha.kt".to_string()
            ]
        );
        assert_eq!(rows[1].depth, 3);
    }

    #[test]
    fn compare_view_mode_toggle_switches_between_full_and_difference() {
        assert_eq!(CompareViewMode::Full.toggle(), CompareViewMode::Difference);
        assert_eq!(CompareViewMode::Difference.toggle(), CompareViewMode::Full);
    }

    #[test]
    fn public_demo_enters_the_selected_story_on_enter() {
        let mut app = PublicDemoApp::new(sample_public_demo_snapshot());

        let outcome = app.on_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        assert_eq!(outcome, PublicDemoOutcome::Continue);
        assert_eq!(app.screen, PublicDemoScreen::Story);
        assert_eq!(
            app.selected_candidate().expect("selected candidate").fq_name,
            "lib.Foo"
        );
        assert_eq!(
            app.selected_chapter().expect("selected chapter").chapter,
            DemoChapter::SemanticDifference,
            "the story should open on the first available evidence chapter"
        );
    }

    #[test]
    fn public_demo_candidate_screen_renders_repo_specific_stories() {
        let app = PublicDemoApp::new(sample_public_demo_snapshot());
        let backend = ratatui::backend::TestBackend::new(100, 28);
        let mut terminal = Terminal::new(backend).expect("test terminal");

        terminal
            .draw(|frame| render_public_demo(frame, &app))
            .expect("render public demo");

        let rendered = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>();
        assert!(
            rendered.contains("Choose a story from your codebase")
                && rendered.contains("Trace the impact of Foo")
                && rendered.contains("3 indexed evidence points")
                && rendered.contains("INDEX READY")
                && rendered.contains("READ ONLY"),
            "candidate screen should explain the real story choices: {rendered}"
        );
    }

    #[test]
    fn public_demo_semantic_signal_theme_assigns_color_by_meaning() {
        let theme = PublicDemoTheme::semantic_signal();

        assert_eq!(theme.compiler, Color::Cyan);
        assert_eq!(theme.index, Color::Magenta);
        assert_eq!(theme.success, Color::Green);
        assert_eq!(theme.plan, Color::Yellow);
    }

    #[test]
    fn public_demo_monochrome_theme_honors_no_color_surfaces() {
        let theme = PublicDemoTheme::monochrome();

        assert_eq!(theme.compiler, Color::Reset);
        assert_eq!(theme.index, Color::Reset);
        assert_eq!(theme.success, Color::Reset);
        assert_eq!(theme.plan, Color::Reset);
    }

    #[test]
    fn public_demo_remains_legible_at_standard_terminal_size() {
        let app = PublicDemoApp::new(sample_public_demo_snapshot());
        let backend = ratatui::backend::TestBackend::new(80, 24);
        let mut terminal = Terminal::new(backend).expect("test terminal");

        terminal
            .draw(|frame| render_public_demo_with_theme(frame, &app, PublicDemoTheme::monochrome()))
            .expect("render compact public demo");

        let rendered = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>();
        assert!(rendered.contains("Kast Semantic Story") && rendered.contains("INDEX READY"));
    }

    #[test]
    fn public_demo_uses_tui_only_for_interactive_human_output() {
        assert!(should_run_public_demo_tui(OutputFormat::Human, true, true));
        assert!(!should_run_public_demo_tui(
            OutputFormat::Human,
            false,
            true
        ));
        assert!(!should_run_public_demo_tui(
            OutputFormat::Toon,
            true,
            true
        ));
    }

    #[test]
    fn public_demo_full_story_renders_compiler_identity_evidence() {
        let mut snapshot = sample_public_demo_snapshot();
        snapshot.availability = PublicDemoAvailability::Full;
        snapshot.backend = Some(DemoBackendSummary {
            name: "idea".to_string(),
            version: "test".to_string(),
            reference_index_ready: true,
        });
        snapshot.chapters = full_chapters();
        snapshot.selected_story = Some(DemoSelectedStory {
            fq_name: "lib.Foo".to_string(),
            indexed_reference_count: 3,
            compiler_identity: Some(DemoCompilerIdentity {
                fq_name: "lib.Foo".to_string(),
                kind: "CLASS".to_string(),
                file_path: "/workspace/lib/Foo.kt".to_string(),
                line: 3,
                preview: "class Foo".to_string(),
            }),
            compiler_reference_count: Some(2),
            diagnostics: Some(DemoDiagnosticsSummary {
                clean: true,
                error_count: 0,
                warning_count: 0,
                info_count: 0,
            }),
        });
        let mut app = PublicDemoApp::new(snapshot);
        app.on_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
        let backend = ratatui::backend::TestBackend::new(100, 28);
        let mut terminal = Terminal::new(backend).expect("test terminal");

        terminal
            .draw(|frame| render_public_demo(frame, &app))
            .expect("render full story");

        let rendered = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>();
        assert!(
            rendered.contains("Compiler resolved lib.Foo")
                && rendered.contains("CLASS")
                && rendered.contains("class Foo"),
            "identity chapter should show live compiler evidence: {rendered}"
        );
    }

    #[test]
    fn public_demo_full_story_loads_compiler_evidence_after_selection() {
        let mut snapshot = sample_public_demo_snapshot();
        snapshot.availability = PublicDemoAvailability::Full;
        snapshot.chapters = full_chapters();
        let expected_candidate = snapshot.candidates[0].clone();
        let mut app = PublicDemoApp::new(snapshot);

        let outcome = app.on_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        assert_eq!(outcome, PublicDemoOutcome::Load(expected_candidate));
        assert_eq!(app.screen, PublicDemoScreen::Story);
        assert!(app.loading, "the story should render a non-blocking loading state");
    }

    #[test]
    fn public_demo_safety_chapter_builds_a_read_only_rename_preview() {
        let mut snapshot = sample_public_demo_snapshot();
        snapshot.availability = PublicDemoAvailability::Full;
        snapshot.chapters = full_chapters();
        let mut app = PublicDemoApp::new(snapshot);
        app.on_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
        app.selected_chapter = app
            .snapshot
            .chapters
            .iter()
            .position(|chapter| chapter.chapter == DemoChapter::Safety)
            .expect("safety chapter");

        app.on_key(KeyEvent::new(KeyCode::Char('r'), KeyModifiers::NONE));
        for character in "BetterFoo".chars() {
            app.on_key(KeyEvent::new(KeyCode::Char(character), KeyModifiers::NONE));
        }
        app.on_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        let preview = app.rename_preview.as_ref().expect("rename preview");
        assert_eq!(preview.new_name, "BetterFoo");
        assert!(preview.command.contains("--new-name BetterFoo"));
        assert!(!preview.command.contains("--apply"));
        assert_eq!(preview.request_type, "RENAME_BY_SYMBOL_REQUEST");
    }

    #[test]
    fn public_demo_ambiguity_story_hands_off_to_semantic_compare() {
        let mut snapshot = sample_public_demo_snapshot();
        snapshot.candidates.push(DemoCandidate {
            kind: DemoCandidateKind::SemanticAmbiguity,
            fq_name: "lib.FooWidget".to_string(),
            symbol_kind: Some("CLASS".to_string()),
            declaration_offset: Some(1),
            title: "Separate text matches from FooWidget".to_string(),
            evidence_count: 2,
            file: Some("/workspace/lib/FooWidget.kt".to_string()),
            module: Some(":lib".to_string()),
        });
        let expected = snapshot.candidates[1].clone();
        let mut app = PublicDemoApp::new(snapshot);
        app.on_key(KeyEvent::new(KeyCode::Down, KeyModifiers::NONE));
        app.on_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));

        let outcome = app.on_key(KeyEvent::new(KeyCode::Char('e'), KeyModifiers::NONE));

        assert_eq!(outcome, PublicDemoOutcome::Explore(expected));
    }

    #[test]
    fn public_demo_backend_only_story_omits_index_dependent_chapters() {
        let chapters = backend_only_chapters();

        assert!(
            chapters
                .iter()
                .any(|chapter| chapter.chapter == DemoChapter::Identity && chapter.available)
        );
        assert!(
            chapters
                .iter()
                .any(|chapter| chapter.chapter == DemoChapter::Impact && !chapter.available)
        );
    }

    #[test]
    fn public_demo_candidate_ranking_excludes_symbols_already_used_by_another_story() {
        let selected = BTreeSet::from(["lib.Foo".to_string()]);
        let candidate = best_ranked_candidate_excluding(
            vec![
                (sample_symbol_hit("lib.Foo"), 20),
                (sample_symbol_hit("app.Bar"), 10),
            ],
            &selected,
        )
        .expect("next distinct candidate");

        assert_eq!(candidate.0.fq_name, "app.Bar");
    }

    fn sample_compare_row<const N: usize>(
        fq_name: Option<&str>,
        label: &str,
        kind: &str,
        visibility: &str,
        module_path: &str,
        source_set: &str,
        relation_kinds: [&str; N],
    ) -> CompareRow {
        let path = format!(
            "/workspace/{}/{}.kt",
            module_path.trim_start_matches(':'),
            label
        );
        let mut row = CompareRow {
            id: fq_name
                .map(|name| format!("symbol:{name}"))
                .unwrap_or_else(|| format!("lexical:{label}")),
            label: label.to_string(),
            fq_name: fq_name.map(str::to_string),
            kind: Some(kind.to_string()),
            visibility: Some(visibility.to_string()),
            path: Some(path),
            module_path: Some(module_path.to_string()),
            source_set: Some(source_set.to_string()),
            relation_kinds: relation_kinds
                .iter()
                .map(|value| value.to_string())
                .collect(),
            incoming_references: 1,
            outgoing_references: 2,
            group_path: Vec::new(),
            depth: 0,
            badge: CompareBadge::Common,
        };
        assign_compare_module_path(&mut row);
        row
    }

    fn sample_symbol_hit(fq_name: &str) -> SymbolHit {
        SymbolHit {
            fq_name: fq_name.to_string(),
            simple_name: simple_symbol_name(fq_name).to_string(),
            kind: Some("CLASS".to_string()),
            path: Some(format!("/workspace/{}.kt", simple_symbol_name(fq_name))),
            declaration_offset: Some(1),
            module_path: Some(":app".to_string()),
            incoming_references: 0,
            outgoing_references: 0,
        }
    }

    fn sample_lexical_only_row(label: &str) -> CompareRow {
        let mut row = CompareRow {
            id: format!("lexical:/workspace/lib/{label}.md:{label}"),
            label: label.to_string(),
            fq_name: None,
            kind: None,
            visibility: None,
            path: Some(format!("/workspace/lib/{label}.md")),
            module_path: Some(":lib".to_string()),
            source_set: Some("main".to_string()),
            relation_kinds: Vec::new(),
            incoming_references: 0,
            outgoing_references: 0,
            group_path: Vec::new(),
            depth: 0,
            badge: CompareBadge::LexicalOnly,
        };
        assign_compare_module_path(&mut row);
        row
    }

    fn sample_public_demo_snapshot() -> PublicDemoSnapshot {
        PublicDemoSnapshot {
            response_type: "KAST_DEMO",
            ok: true,
            availability: PublicDemoAvailability::IndexOnly,
            workspace_root: "/workspace".to_string(),
            mutates: false,
            backend: None,
            candidates: vec![DemoCandidate {
                kind: DemoCandidateKind::ImpactHub,
                fq_name: "lib.Foo".to_string(),
                symbol_kind: Some("CLASS".to_string()),
                declaration_offset: Some(1),
                title: "Trace the impact of Foo".to_string(),
                evidence_count: 3,
                file: Some("/workspace/lib/Foo.kt".to_string()),
                module: Some(":lib".to_string()),
            }],
            selected_story: None,
            chapters: index_only_chapters(),
            warnings: Vec::new(),
            help: vec!["kast agent impact --symbol lib.Foo --declaration-file /workspace/lib/Foo.kt --declaration-start-offset 1 --kind class --workspace-root <repo>".to_string()],
            schema_version: SCHEMA_VERSION,
        }
    }
}
