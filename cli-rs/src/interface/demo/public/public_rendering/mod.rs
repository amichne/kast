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

include!("chapters.rs");
