fn render_public_demo(frame: &mut Frame<'_>, app: &PublicDemoApp) {
    let theme = PublicDemoTheme::detect();
    render_public_demo_with_theme(frame, app, theme);
}

fn render_public_demo_with_theme(
    frame: &mut Frame<'_>,
    app: &PublicDemoApp,
    theme: PublicDemoTheme,
) {
    let root = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(5),
            Constraint::Min(12),
            Constraint::Length(3),
        ])
        .split(frame.area());
    render_public_demo_header(frame, root[0], app, theme);
    match app.screen {
        PublicDemoScreen::Candidates => render_public_candidates(frame, root[1], app, theme),
        PublicDemoScreen::Story => render_public_story(frame, root[1], app, theme),
    }
    render_public_demo_footer(frame, root[2], app, theme);
}

fn render_public_demo_header(
    frame: &mut Frame<'_>,
    area: Rect,
    app: &PublicDemoApp,
    theme: PublicDemoTheme,
) {
    let (availability, availability_color) = match app.snapshot.availability {
        PublicDemoAvailability::Full => (" FULL EVIDENCE ", theme.success),
        PublicDemoAvailability::IndexOnly => (" INDEX READY ", theme.index),
        PublicDemoAvailability::BackendOnly => (" COMPILER READY ", theme.compiler),
    };
    let lines = vec![
        Line::from(vec![
            Span::styled(
                " Kast Semantic Story ",
                Style::default()
                    .fg(theme.accent)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::raw("  "),
            Span::styled(availability, theme.badge(availability_color)),
            Span::raw("  "),
            Span::styled(" READ ONLY ", theme.badge(theme.success)),
        ]),
        Line::from(vec![
            Span::styled(" repo  ", Style::default().fg(theme.muted)),
            Span::styled(
                compact_path(&app.snapshot.workspace_root),
                Style::default().fg(theme.text),
            ),
        ]),
        Line::from(Span::styled(
            " Live semantic evidence from this repository. No files will be changed.",
            Style::default().fg(theme.muted),
        )),
    ];
    frame.render_widget(
        Paragraph::new(lines).block(
            Block::default()
                .borders(Borders::ALL)
                .border_type(BorderType::Rounded)
                .border_style(Style::default().fg(theme.muted)),
        ),
        area,
    );
}

fn render_public_candidates(
    frame: &mut Frame<'_>,
    area: Rect,
    app: &PublicDemoApp,
    theme: PublicDemoTheme,
) {
    let items = app
        .snapshot
        .candidates
        .iter()
        .map(|candidate| {
            ListItem::new(vec![
                Line::from(vec![
                    Span::styled(
                        format!("{:<20}", demo_candidate_kind_label(candidate.kind)),
                        Style::default().fg(theme.index),
                    ),
                    Span::styled(
                        candidate.title.clone(),
                        Style::default()
                            .fg(theme.text)
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
    render_public_list(
        frame,
        area,
        "Choose a story from your codebase".to_string(),
        items,
        app.selected_candidate,
        theme,
    );
}

fn render_public_story(
    frame: &mut Frame<'_>,
    area: Rect,
    app: &PublicDemoApp,
    theme: PublicDemoTheme,
) {
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
            let (marker, color) = if chapter.available {
                ("●", theme.success)
            } else {
                ("○", theme.muted)
            };
            ListItem::new(Line::from(vec![
                Span::styled(format!("{marker} "), Style::default().fg(color)),
                Span::styled(
                    demo_chapter_label(chapter.chapter),
                    Style::default().fg(theme.text),
                ),
            ]))
        })
        .collect();
    render_public_list(
        frame,
        sections[0],
        "Story chapters".to_string(),
        chapter_items,
        app.selected_chapter,
        theme,
    );

    let lines = public_story_lines(app, theme);
    frame.render_widget(
        Paragraph::new(lines)
            .block(
                Block::default()
                    .title(" Evidence ")
                    .borders(Borders::ALL)
                    .border_type(BorderType::Rounded)
                    .border_style(Style::default().fg(theme.accent)),
            )
            .wrap(Wrap { trim: false }),
        sections[1],
    );
}

fn public_story_lines(app: &PublicDemoApp, theme: PublicDemoTheme) -> Vec<Line<'static>> {
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
                .fg(theme.accent)
                .add_modifier(Modifier::BOLD),
        )),
        Line::from(candidate.fq_name.clone()),
        Line::from(""),
    ];
    if app.loading {
        lines.push(Line::from(Span::styled(
            "Loading compiler evidence…",
            Style::default().fg(theme.compiler),
        )));
        lines.push(Line::from("You can keep navigating or press q to quit."));
        return lines;
    }
    if let Some(message) = &app.evidence_error {
        lines.push(Line::from(Span::styled(
            format!("Compiler evidence unavailable: {message}"),
            Style::default().fg(theme.danger),
        )));
        lines.push(Line::from("Index-backed chapters remain available."));
        return lines;
    }
    if chapter.chapter == DemoChapter::Safety {
        if app.input_mode == PublicDemoInputMode::Rename {
            lines.push(Line::from("Hypothetical Kotlin name:"));
            lines.push(Line::from(Span::styled(
                format!("> {}", app.rename_input),
                Style::default().fg(theme.plan),
            )));
            if let Some(message) = &app.rename_error {
                lines.push(Line::from(Span::styled(
                    message.clone(),
                    Style::default().fg(theme.danger),
                )));
            }
            lines.push(Line::from("Enter preview • Esc cancel • no files are written"));
            return lines;
        }
        if let Some(preview) = &app.rename_preview {
            lines.push(Line::from(Span::styled(
                "Plan only — apply is unavailable in the demo",
                Style::default().fg(theme.plan),
            )));
            lines.push(Line::from(format!("Request: {}", preview.request_type)));
            lines.push(Line::from(format!("New name: {}", preview.new_name)));
            lines.push(Line::from(""));
            lines.push(Line::from(preview.command.clone()));
            return lines;
        }
    }
    if !chapter.available {
        lines.push(Line::from(Span::styled(
            format!("Unavailable: {}", chapter.basis),
            Style::default().fg(theme.muted),
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
        theme,
    ));
    lines
}

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
                            format!(
                                "kast agent symbol --query {} --references --workspace-root <repo>",
                                candidate.fq_name
                            ),
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
