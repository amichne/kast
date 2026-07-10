fn run_public_demo_tui(mut db: DemoDatabase, snapshot: PublicDemoSnapshot) -> Result<i32> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    let mut app = PublicDemoApp::new(snapshot);
    let outcome = run_public_demo_event_loop(&mut terminal, &mut app);
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
    }
}

fn run_public_demo_event_loop(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    app: &mut PublicDemoApp,
) -> Result<PublicDemoOutcome> {
    loop {
        terminal.draw(|frame| render_public_demo(frame, app))?;
        if event::poll(Duration::from_millis(120))?
            && let Event::Key(key) = event::read()?
        {
            let outcome = app.on_key(key);
            if outcome != PublicDemoOutcome::Continue {
                break Ok(outcome);
            }
        }
    }
}
