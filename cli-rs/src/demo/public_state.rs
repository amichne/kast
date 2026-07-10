#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PublicDemoScreen {
    Candidates,
    Story,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PublicDemoInputMode {
    Navigate,
    Rename,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DemoRenamePreview {
    new_name: String,
    command: String,
    request_type: &'static str,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum PublicDemoOutcome {
    Continue,
    Quit,
    Explore(String),
    Load(DemoCandidate),
}

struct PublicDemoApp {
    snapshot: PublicDemoSnapshot,
    selected_candidate: usize,
    selected_chapter: usize,
    screen: PublicDemoScreen,
    loading: bool,
    evidence_error: Option<String>,
    input_mode: PublicDemoInputMode,
    rename_input: String,
    rename_preview: Option<DemoRenamePreview>,
    rename_error: Option<String>,
}

impl PublicDemoApp {
    fn new(snapshot: PublicDemoSnapshot) -> Self {
        Self {
            snapshot,
            selected_candidate: 0,
            selected_chapter: 0,
            screen: PublicDemoScreen::Candidates,
            loading: false,
            evidence_error: None,
            input_mode: PublicDemoInputMode::Navigate,
            rename_input: String::new(),
            rename_preview: None,
            rename_error: None,
        }
    }

    fn on_key(&mut self, key: KeyEvent) -> PublicDemoOutcome {
        if key.modifiers.contains(KeyModifiers::CONTROL) && key.code == KeyCode::Char('c') {
            return PublicDemoOutcome::Quit;
        }
        if self.input_mode == PublicDemoInputMode::Rename {
            return self.on_rename_key(key);
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
                let candidate = self.selected_candidate().cloned();
                self.selected_chapter = self
                    .snapshot
                    .chapters
                    .iter()
                    .position(|chapter| chapter.available)
                    .unwrap_or_default();
                self.screen = PublicDemoScreen::Story;
                if self.snapshot.availability == PublicDemoAvailability::Full
                    && candidate.as_ref().is_some_and(|candidate| {
                        self.snapshot
                            .selected_story
                            .as_ref()
                            .is_none_or(|story| story.fq_name != candidate.fq_name)
                    })
                {
                    self.loading = true;
                    self.evidence_error = None;
                    candidate
                        .map(PublicDemoOutcome::Load)
                        .unwrap_or(PublicDemoOutcome::Continue)
                } else {
                    PublicDemoOutcome::Continue
                }
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
            KeyCode::Char('r') if self.selected_chapter_is(DemoChapter::Safety) => {
                self.input_mode = PublicDemoInputMode::Rename;
                self.rename_input.clear();
                self.rename_preview = None;
                self.rename_error = None;
                PublicDemoOutcome::Continue
            }
            _ => PublicDemoOutcome::Continue,
        }
    }

    fn on_rename_key(&mut self, key: KeyEvent) -> PublicDemoOutcome {
        match key.code {
            KeyCode::Esc => {
                self.input_mode = PublicDemoInputMode::Navigate;
                self.rename_error = None;
            }
            KeyCode::Backspace => {
                self.rename_input.pop();
                self.rename_error = None;
            }
            KeyCode::Enter => {
                if let Err(message) = validate_demo_new_name(&self.rename_input) {
                    self.rename_error = Some(message);
                } else if let Some(candidate) = self.selected_candidate() {
                    let new_name = self.rename_input.clone();
                    self.rename_preview = Some(DemoRenamePreview {
                        command: format!(
                            "kast agent rename --symbol {} --new-name {new_name} --workspace-root <repo>",
                            candidate.fq_name
                        ),
                        new_name,
                        request_type: "RENAME_BY_SYMBOL_REQUEST",
                    });
                    self.input_mode = PublicDemoInputMode::Navigate;
                    self.rename_error = None;
                }
            }
            KeyCode::Char(character) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.rename_input.push(character);
                self.rename_error = None;
            }
            _ => {}
        }
        PublicDemoOutcome::Continue
    }

    fn selected_candidate(&self) -> Option<&DemoCandidate> {
        self.snapshot.candidates.get(self.selected_candidate)
    }

    fn selected_chapter(&self) -> Option<&DemoChapterAvailability> {
        self.snapshot.chapters.get(self.selected_chapter)
    }

    fn selected_chapter_is(&self, expected: DemoChapter) -> bool {
        self.selected_chapter()
            .is_some_and(|chapter| chapter.available && chapter.chapter == expected)
    }

    fn accept_story_evidence(
        &mut self,
        result: std::result::Result<DemoSelectedStory, String>,
    ) {
        self.loading = false;
        match result {
            Ok(story) => {
                self.snapshot.selected_story = Some(story);
                self.evidence_error = None;
            }
            Err(message) => self.evidence_error = Some(message),
        }
    }
}

fn validate_demo_new_name(name: &str) -> std::result::Result<(), String> {
    let mut characters = name.chars();
    let Some(first) = characters.next() else {
        return Err("Enter a hypothetical Kotlin name.".to_string());
    };
    if !first.is_ascii_alphabetic() && first != '_' {
        return Err("The name must start with an ASCII letter or underscore.".to_string());
    }
    if characters.any(|character| !character.is_ascii_alphanumeric() && character != '_') {
        return Err("The name may contain only ASCII letters, digits, or underscores.".to_string());
    }
    const KOTLIN_KEYWORDS: &[&str] = &[
        "as",
        "break",
        "class",
        "continue",
        "do",
        "else",
        "false",
        "for",
        "fun",
        "if",
        "in",
        "interface",
        "is",
        "null",
        "object",
        "package",
        "return",
        "super",
        "this",
        "throw",
        "true",
        "try",
        "typealias",
        "typeof",
        "val",
        "var",
        "when",
        "while",
    ];
    if KOTLIN_KEYWORDS.contains(&name) {
        return Err("The hypothetical name must not be a Kotlin keyword.".to_string());
    }
    Ok(())
}
