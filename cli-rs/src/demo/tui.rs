fn run_demo_tui(mut app: DemoApp) -> Result<i32> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    let result = run_demo_event_loop(&mut terminal, &mut app);
    disable_raw_mode().ok();
    execute!(terminal.backend_mut(), LeaveAlternateScreen).ok();
    terminal.show_cursor().ok();
    result
}

fn run_compare_tui(mut app: CompareApp) -> Result<i32> {
    enable_raw_mode()?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen)?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    let result = run_compare_event_loop(&mut terminal, &mut app);
    disable_raw_mode().ok();
    execute!(terminal.backend_mut(), LeaveAlternateScreen).ok();
    terminal.show_cursor().ok();
    result
}

fn run_demo_event_loop(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    app: &mut DemoApp,
) -> Result<i32> {
    loop {
        terminal.draw(|frame| render_demo(frame, app))?;
        if app.should_quit {
            break Ok(0);
        }
        if event::poll(Duration::from_millis(120))?
            && let Event::Key(key) = event::read()?
        {
            app.on_key(key)?;
        }
    }
}

fn run_compare_event_loop(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    app: &mut CompareApp,
) -> Result<i32> {
    loop {
        terminal.draw(|frame| render_compare_demo(frame, app))?;
        if app.should_quit {
            break Ok(0);
        }
        if event::poll(Duration::from_millis(120))?
            && let Event::Key(key) = event::read()?
        {
            app.on_key(key)?;
        }
    }
}
