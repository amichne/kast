fn render_public_demo(frame: &mut Frame<'_>, app: &PublicDemoApp) {
    let root = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(5),
            Constraint::Min(12),
            Constraint::Length(3),
        ])
        .split(frame.area());
    render_public_demo_header(frame, root[0], app);
    match app.screen {
        PublicDemoScreen::Candidates => render_public_candidates(frame, root[1], app),
        PublicDemoScreen::Story => render_public_story(frame, root[1], app),
    }
    render_public_demo_footer(frame, root[2], app);
}

fn render_public_demo_header(frame: &mut Frame<'_>, area: Rect, app: &PublicDemoApp) {
    let availability = match app.snapshot.availability {
        PublicDemoAvailability::Full => "compiler + index evidence ready",
        PublicDemoAvailability::IndexOnly => "index evidence ready",
    };
    let lines = vec![
        Line::from(vec![
            Span::styled(
                "Kast Semantic Story",
                Style::default()
                    .fg(Color::Cyan)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("  "),
            Span::styled(availability, Style::default().fg(Color::Green)),
        ]),
        Line::from(compact_path(&app.snapshot.workspace_root)),
        Line::from("Live evidence from this repository. No files will be changed."),
    ];
    frame.render_widget(
        Paragraph::new(lines).block(
            Block::default()
                .borders(Borders::ALL)
                .border_style(Style::default().fg(Color::DarkGray)),
        ),
        area,
    );
}

fn render_public_candidates(frame: &mut Frame<'_>, area: Rect, app: &PublicDemoApp) {
    let items = app
        .snapshot
        .candidates
        .iter()
        .map(|candidate| {
            ListItem::new(vec![
                Line::from(vec![
                    Span::styled(
                        format!("{:<20}", demo_candidate_kind_label(candidate.kind)),
                        Style::default().fg(Color::Magenta),
                    ),
                    Span::styled(
                        candidate.title.clone(),
                        Style::default()
                            .fg(Color::White)
                            .add_modifier(Modifier::BOLD),
                    ),
                ]),
                Line::from(format!(
                    "  {}  •  {} indexed evidence points  •  {}",
                    candidate.fq_name,
                    candidate.evidence_count,
                    candidate.module.as_deref().unwrap_or("workspace")
                )),
            ])
        })
        .collect();
    render_list(
        frame,
        area,
        "Choose a story from your codebase".to_string(),
        items,
        app.selected_candidate,
        true,
    );
}

fn render_public_story(frame: &mut Frame<'_>, area: Rect, app: &PublicDemoApp) {
    let sections = if area.width < 90 {
        Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Percentage(40), Constraint::Percentage(60)])
            .split(area)
    } else {
        Layout::default()
            .direction(Direction::Horizontal)
            .constraints([Constraint::Percentage(32), Constraint::Percentage(68)])
            .split(area)
    };
    let chapter_items = app
        .snapshot
        .chapters
        .iter()
        .map(|chapter| {
            let marker = if chapter.available { "✓" } else { "—" };
            ListItem::new(format!(
                "{marker} {}",
                demo_chapter_label(chapter.chapter)
            ))
        })
        .collect();
    render_list(
        frame,
        sections[0],
        "Story chapters".to_string(),
        chapter_items,
        app.selected_chapter,
        true,
    );

    let lines = public_story_lines(app);
    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .title("Evidence")
                    .borders(Borders::ALL)
                    .border_style(Style::default().fg(Color::Cyan)),
            )
            .wrap(Wrap { trim: false }),
        sections[1],
    );
}

fn public_story_lines(app: &PublicDemoApp) -> Vec<Line<'static>> {
    let Some(candidate) = app.selected_candidate() else {
        return vec![Line::from("No story candidate is available.")];
    };
    let Some(chapter) = app.selected_chapter() else {
        return vec![Line::from("No story chapter is available.")];
    };
    let mut lines = vec![
        Line::from(Span::styled(
            candidate.title.clone(),
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(candidate.fq_name.clone()),
        Line::from(""),
    ];
    if !chapter.available {
        lines.push(Line::from(Span::styled(
            format!("Unavailable: {}", chapter.basis),
            Style::default().fg(Color::DarkGray),
        )));
        lines.push(Line::from(
            "Kast omits unsupported evidence instead of substituting a guess.",
        ));
        return lines;
    }
    lines.extend(public_available_chapter_lines(
        candidate,
        chapter.chapter,
        app.snapshot.selected_story.as_ref(),
    ));
    lines
}

fn public_available_chapter_lines(
    candidate: &DemoCandidate,
    chapter: DemoChapter,
    selected_story: Option<&DemoSelectedStory>,
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
                            Style::default().fg(Color::Green),
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
                            format!(
                                "kast agent symbol --query {} --references --workspace-root <repo>",
                                candidate.fq_name
                            ),
                            Style::default().fg(Color::Green),
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
        DemoChapter::Relationships => format!(
            "kast agent symbol --query {} --references --workspace-root <repo>",
            candidate.fq_name
        ),
        DemoChapter::Impact => format!(
            "kast agent impact --symbol {} --workspace-root <repo>",
            candidate.fq_name
        ),
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
        Line::from(Span::styled(command, Style::default().fg(Color::Green))),
    ]
}

fn render_public_demo_footer(frame: &mut Frame<'_>, area: Rect, app: &PublicDemoApp) {
    let text = match app.screen {
        PublicDemoScreen::Candidates => {
            "↑/↓ choose • Enter begin story • q quit • read-only"
        }
        PublicDemoScreen::Story => {
            "←/→ chapter • e explore live symbol graph • Esc stories • q quit • read-only"
        }
    };
    frame.render_widget(
        Paragraph::new(text)
            .block(Block::default().borders(Borders::TOP))
            .wrap(Wrap { trim: true }),
        area,
    );
}

fn demo_candidate_kind_label(kind: DemoCandidateKind) -> &'static str {
    match kind {
        DemoCandidateKind::ImpactHub => "High-impact symbol",
        DemoCandidateKind::CallChainHub => "Call-chain hub",
        DemoCandidateKind::SemanticAmbiguity => "Semantic ambiguity",
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
