#[derive(Debug, Clone, Copy)]
struct PublicDemoTheme {
    accent: Color,
    compiler: Color,
    index: Color,
    success: Color,
    plan: Color,
    danger: Color,
    muted: Color,
    text: Color,
    color_enabled: bool,
}

impl PublicDemoTheme {
    fn detect() -> Self {
        if std::env::var_os("NO_COLOR").is_some_and(|value| !value.is_empty()) {
            Self::monochrome()
        } else {
            Self::semantic_signal()
        }
    }

    fn semantic_signal() -> Self {
        Self {
            accent: Color::Cyan,
            compiler: Color::Cyan,
            index: Color::Magenta,
            success: Color::Green,
            plan: Color::Yellow,
            danger: Color::Red,
            muted: Color::DarkGray,
            text: Color::White,
            color_enabled: true,
        }
    }

    fn monochrome() -> Self {
        Self {
            accent: Color::Reset,
            compiler: Color::Reset,
            index: Color::Reset,
            success: Color::Reset,
            plan: Color::Reset,
            danger: Color::Reset,
            muted: Color::Reset,
            text: Color::Reset,
            color_enabled: false,
        }
    }

    fn badge(self, color: Color) -> Style {
        if self.color_enabled {
            Style::default()
                .fg(Color::Black)
                .bg(color)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().add_modifier(Modifier::BOLD)
        }
    }

    fn keycap(self) -> Style {
        self.badge(self.accent)
    }

    fn selection(self) -> Style {
        if self.color_enabled {
            Style::default()
                .fg(Color::Black)
                .bg(self.accent)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default().add_modifier(Modifier::REVERSED | Modifier::BOLD)
        }
    }
}
