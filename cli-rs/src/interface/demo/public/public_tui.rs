fn run_public_demo_tui(
    db: Option<DemoDatabase>,
    snapshot: PublicDemoSnapshot,
    connection: Option<DemoBackendConnection>,
) -> Result<i32> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    let mut app = PublicDemoApp::new(snapshot);
    let worker = connection.map(DemoEvidenceWorker::spawn);
    let outcome = run_public_demo_event_loop(&mut terminal, &mut app, worker.as_ref());
    disable_raw_mode().ok();
    execute!(terminal.backend_mut(), LeaveAlternateScreen).ok();
    terminal.show_cursor().ok();

    match outcome? {
        PublicDemoOutcome::Explore(candidate) => db
            .map(|db| run_public_demo_explorer(db, candidate))
            .unwrap_or(Ok(0)),
        PublicDemoOutcome::Continue | PublicDemoOutcome::Quit => Ok(0),
        PublicDemoOutcome::Load(_) => unreachable!("load outcomes stay inside the event loop"),
    }
}

fn run_public_demo_explorer(mut db: DemoDatabase, candidate: DemoCandidate) -> Result<i32> {
    let request = db.request.clone();
    match candidate.kind {
        DemoCandidateKind::SemanticAmbiguity => {
            let query = simple_symbol_name(&candidate.fq_name).to_string();
            let snapshot = db.compare_snapshot(CompareSnapshotRequest {
                query: &query,
                filters: &CompareFilters::default(),
                sort: CompareSort::Module,
                view_mode: CompareViewMode::Full,
                requested_symbol: Some(&candidate.fq_name),
                selected_lexical: 0,
                selected_semantic: 0,
                active_pane: ComparePane::Semantic,
            })?;
            run_compare_tui(CompareApp::from_snapshot(db, request, snapshot))
        }
        DemoCandidateKind::ImpactHub
        | DemoCandidateKind::CallChainHub
        | DemoCandidateKind::SelectedSymbol => {
            let snapshot = db.snapshot(Some(&candidate.fq_name), "", Vec::new())?;
            run_demo_tui(DemoApp::from_snapshot(db, request, snapshot))
        }
    }
}

fn run_public_demo_event_loop(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    app: &mut PublicDemoApp,
    worker: Option<&DemoEvidenceWorker>,
) -> Result<PublicDemoOutcome> {
    loop {
        if let Some(result) = worker.and_then(DemoEvidenceWorker::try_receive) {
            app.accept_story_evidence(result);
        }
        terminal.draw(|frame| render_public_demo(frame, app))?;
        if event::poll(Duration::from_millis(120))?
            && let Event::Key(key) = event::read()?
        {
            let outcome = app.on_key(key);
            match outcome {
                PublicDemoOutcome::Load(candidate) => {
                    if let Some(worker) = worker {
                        if let Err(message) = worker.request(candidate) {
                            app.accept_story_evidence(Err(message));
                        }
                    } else {
                        app.accept_story_evidence(Err(
                            "No ready compiler backend is available.".to_string(),
                        ));
                    }
                }
                PublicDemoOutcome::Continue => {}
                PublicDemoOutcome::Quit | PublicDemoOutcome::Explore(_) => break Ok(outcome),
            }
        }
    }
}
