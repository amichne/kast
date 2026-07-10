#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PublicDemoScreen {
    Candidates,
    Story,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum PublicDemoOutcome {
    Continue,
    Quit,
    Explore(String),
}

struct PublicDemoApp {
    snapshot: PublicDemoSnapshot,
    selected_candidate: usize,
    selected_chapter: usize,
    screen: PublicDemoScreen,
}

impl PublicDemoApp {
    fn new(snapshot: PublicDemoSnapshot) -> Self {
        Self {
            snapshot,
            selected_candidate: 0,
            selected_chapter: 0,
            screen: PublicDemoScreen::Candidates,
        }
    }

    fn on_key(&mut self, key: KeyEvent) -> PublicDemoOutcome {
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
            return PublicDemoOutcome::Quit;
        }
        match self.screen {
            PublicDemoScreen::Candidates => self.on_candidate_key(key),
            PublicDemoScreen::Story => self.on_story_key(key),
        }
    }

    fn on_candidate_key(&mut self, key: KeyEvent) -> PublicDemoOutcome {
        match key.code {
            KeyCode::Char('q') | KeyCode::Esc => PublicDemoOutcome::Quit,
            KeyCode::Up | KeyCode::Char('k') => {
                self.selected_candidate = move_index(
                    self.selected_candidate,
                    self.snapshot.candidates.len(),
                    -1,
                );
                PublicDemoOutcome::Continue
            }
            KeyCode::Down | KeyCode::Char('j') => {
                self.selected_candidate = move_index(
                    self.selected_candidate,
                    self.snapshot.candidates.len(),
                    1,
                );
                PublicDemoOutcome::Continue
            }
            KeyCode::Enter if self.selected_candidate().is_some() => {
                self.selected_chapter = self
                    .snapshot
                    .chapters
                    .iter()
                    .position(|chapter| chapter.available)
                    .unwrap_or_default();
                self.screen = PublicDemoScreen::Story;
                PublicDemoOutcome::Continue
            }
            _ => PublicDemoOutcome::Continue,
        }
    }

    fn on_story_key(&mut self, key: KeyEvent) -> PublicDemoOutcome {
        match key.code {
            KeyCode::Char('q') => PublicDemoOutcome::Quit,
            KeyCode::Esc => {
                self.screen = PublicDemoScreen::Candidates;
                PublicDemoOutcome::Continue
            }
            KeyCode::Left | KeyCode::Char('h') | KeyCode::Char('p') => {
                self.selected_chapter = move_index(
                    self.selected_chapter,
                    self.snapshot.chapters.len(),
                    -1,
                );
                PublicDemoOutcome::Continue
            }
            KeyCode::Right | KeyCode::Char('l') | KeyCode::Char('n') => {
                self.selected_chapter = move_index(
                    self.selected_chapter,
                    self.snapshot.chapters.len(),
                    1,
                );
                PublicDemoOutcome::Continue
            }
            KeyCode::Char('e') => self
                .selected_candidate()
                .map(|candidate| PublicDemoOutcome::Explore(candidate.fq_name.clone()))
                .unwrap_or(PublicDemoOutcome::Continue),
            _ => PublicDemoOutcome::Continue,
        }
    }

    fn selected_candidate(&self) -> Option<&DemoCandidate> {
        self.snapshot.candidates.get(self.selected_candidate)
    }

    fn selected_chapter(&self) -> Option<&DemoChapterAvailability> {
        self.snapshot.chapters.get(self.selected_chapter)
    }
}
