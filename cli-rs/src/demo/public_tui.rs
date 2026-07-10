fn run_public_demo_tui(
    mut db: DemoDatabase,
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
        PublicDemoOutcome::Explore(fq_name) => {
            let request = db.request.clone();
            let snapshot = db.snapshot(Some(&fq_name), "", Vec::new())?;
            run_demo_tui(DemoApp::from_snapshot(db, request, snapshot))
        }
        PublicDemoOutcome::Continue | PublicDemoOutcome::Quit => Ok(0),
        PublicDemoOutcome::Load(_) => unreachable!("load outcomes stay inside the event loop"),
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
