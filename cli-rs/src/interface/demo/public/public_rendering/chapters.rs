fn public_available_chapter_lines(
    candidate: &DemoCandidate,
    chapter: DemoChapter,
    selected_story: Option<&DemoSelectedStory>,
    theme: PublicDemoTheme,
) -> Vec<Line<'static>> {
    if let Some(story) = selected_story.filter(|story| story.fq_name == candidate.fq_name) {
        match chapter {
            DemoChapter::Identity => {
                if let Some(identity) = &story.compiler_identity {
                    return vec![
                        Line::from(format!("Compiler resolved {}", identity.fq_name)),
                        Line::from(format!("Kind: {}", identity.kind)),
                        Line::from(format!("{}:{}", identity.file_path, identity.line)),
                        Line::from(""),
                        Line::from(Span::styled(
                            identity.preview.clone(),
                            Style::default().fg(theme.success),
                        )),
                    ];
                }
            }
            DemoChapter::Relationships => {
                if let Some(reference_count) = story.compiler_reference_count {
                    return vec![
                        Line::from(format!(
                            "Compiler confirmed {reference_count} reference locations."
                        )),
                        Line::from(format!(
                            "The source index records {} graph evidence points.",
                            story.indexed_reference_count
                        )),
                        Line::from(""),
                        Line::from(Span::styled(
                            demo_relationship_command(candidate, "references"),
                            Style::default().fg(theme.compiler),
                        )),
                    ];
                }
            }
            DemoChapter::Safety => {
                if let Some(diagnostics) = &story.diagnostics {
                    return vec![
                        Line::from(format!(
                            "Compiler baseline: {}",
                            if diagnostics.clean { "clean" } else { "diagnostics present" }
                        )),
                        Line::from(format!(
                            "{} errors • {} warnings • {} info",
                            diagnostics.error_count,
                            diagnostics.warning_count,
                            diagnostics.info_count
                        )),
                        Line::from(""),
                        Line::from("Rename remains plan-first; this demo never exposes --apply."),
                    ];
                }
            }
            DemoChapter::SemanticDifference | DemoChapter::Impact | DemoChapter::Recap => {}
        }
    }
    let command = match chapter {
        DemoChapter::Identity => format!(
            "kast agent symbol --query {} --workspace-root <repo>",
            candidate.fq_name
        ),
        DemoChapter::SemanticDifference => {
            "Press e to compare lexical candidates with indexed Kotlin identities.".to_string()
        }
        DemoChapter::Relationships => demo_relationship_command(candidate, "references"),
        DemoChapter::Impact => demo_relationship_command(candidate, "impact"),
        DemoChapter::Safety => format!(
            "kast agent rename --symbol {} --new-name <name> --workspace-root <repo>",
            candidate.fq_name
        ),
        DemoChapter::Recap => {
            "Every demonstrated operation is available through typed `kast agent` commands."
                .to_string()
        }
    };
    vec![
        Line::from(format!(
            "{} indexed evidence points support this story.",
            candidate.evidence_count
        )),
        Line::from(format!(
            "File: {}",
            candidate.file.as_deref().unwrap_or("not indexed")
        )),
        Line::from(format!(
            "Module: {}",
            candidate.module.as_deref().unwrap_or("workspace")
        )),
        Line::from(""),
        Line::from(Span::styled(command, Style::default().fg(theme.index))),
    ]
}

fn render_public_demo_footer(
    frame: &mut Frame<'_>,
    area: Rect,
    app: &PublicDemoApp,
    theme: PublicDemoTheme,
) {
    let commands: Vec<(&str, &str)> = match app.screen {
        PublicDemoScreen::Candidates => vec![("↑/↓", "choose"), ("Enter", "begin"), ("q", "quit")],
        PublicDemoScreen::Story => {
            if app.input_mode == PublicDemoInputMode::Rename {
                vec![("type", "name"), ("Enter", "preview"), ("Esc", "cancel")]
            } else if app.snapshot.availability == PublicDemoAvailability::BackendOnly {
                vec![("←/→", "chapter"), ("r", "rename"), ("Esc", "stories"), ("q", "quit")]
            } else {
                vec![("←/→", "chapter"), ("r", "rename"), ("e", "graph"), ("Esc", "stories"), ("q", "quit")]
            }
        }
    };
    let mut spans = Vec::new();
    for (index, (key, label)) in commands.into_iter().enumerate() {
        if index > 0 {
            spans.push(Span::styled("  ", Style::default().fg(theme.muted)));
        }
        spans.push(Span::styled(format!(" {key} "), theme.keycap()));
        spans.push(Span::styled(format!(" {label}"), Style::default().fg(theme.text)));
    }
    spans.push(Span::raw("  "));
    spans.push(Span::styled(" READ ONLY ", theme.badge(theme.success)));
    frame.render_widget(
        Paragraph::new(Line::from(spans))
            .block(
                Block::default()
                    .borders(Borders::TOP)
                    .border_style(Style::default().fg(theme.muted)),
            )
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn render_public_list(
    frame: &mut Frame<'_>,
    area: Rect,
    title: String,
    items: Vec<ListItem<'_>>,
    selected: usize,
    theme: PublicDemoTheme,
) {
    let mut state = ListState::default();
    if !items.is_empty() {
        state.select(Some(selected.min(items.len().saturating_sub(1))));
    }
    let list = List::new(items)
        .block(
            Block::default()
                .title(format!(" {title} "))
                .borders(Borders::ALL)
                .border_type(BorderType::Rounded)
                .border_style(Style::default().fg(theme.accent)),
        )
        .highlight_style(theme.selection())
        .highlight_symbol("▌ ");
    frame.render_stateful_widget(list, area, &mut state);
}

fn demo_candidate_kind_label(kind: DemoCandidateKind) -> &'static str {
    match kind {
        DemoCandidateKind::ImpactHub => "High-impact symbol",
        DemoCandidateKind::CallChainHub => "Call-chain hub",
        DemoCandidateKind::SemanticAmbiguity => "Semantic ambiguity",
        DemoCandidateKind::SelectedSymbol => "Selected symbol",
    }
}

fn demo_chapter_label(chapter: DemoChapter) -> &'static str {
    match chapter {
        DemoChapter::Identity => "Identity",
        DemoChapter::SemanticDifference => "Why semantics",
        DemoChapter::Relationships => "Relationships",
        DemoChapter::Impact => "Impact",
        DemoChapter::Safety => "Safety",
        DemoChapter::Recap => "Recap",
    }
}
