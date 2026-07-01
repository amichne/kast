pub fn print_json(value: &impl Serialize) -> Result<()> {
    io::stdout().write_all(render_agent_output(value, AgentOutputFormat::Json)?.as_bytes())?;
    Ok(())
}

pub(crate) fn render_agent_output(
    value: &impl Serialize,
    format: AgentOutputFormat,
) -> Result<String> {
    match format {
        AgentOutputFormat::Json => {
            let mut rendered = serde_json::to_string_pretty(value)?;
            rendered.push('\n');
            Ok(rendered)
        }
        AgentOutputFormat::Toon => {
            let value = serde_json::to_value(value)?;
            let mut rendered = toon_format::encode_default(&value)
                .map_err(|error| CliError::new("TOON_ENCODE_ERROR", error.to_string()))?;
            rendered.push('\n');
            Ok(rendered)
        }
    }
}

pub fn print_error(error: &CliError, output: OutputFormat) -> Result<()> {
    if output == OutputFormat::Json {
        serde_json::to_writer_pretty(io::stderr(), &error.to_response())?;
        eprintln!();
        return Ok(());
    }

    let mut document = MarkdownDocument::default();
    mdln!(document, "# Kast error");
    mdln!(document);
    mdln!(document, "- Code: {}", error.code);
    mdln!(document, "- Message: {}", error.message);
    if !error.details.is_empty() {
        mdln!(document);
        mdln!(document, "## Details");
        for (key, value) in &error.details {
            mdln!(document, "- {key}: `{value}`");
        }
    }
    mdln!(document);
    mdln!(
        document,
        "Use `kast --output json ...` for the machine-readable error payload."
    );
    print_markdown_stderr(&document.into_string())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum RenderStyle {
    Plain,
    Ansi,
}

#[derive(Default)]
struct MarkdownDocument {
    text: String,
}

impl MarkdownDocument {
    fn line(&mut self, args: fmt::Arguments<'_>) {
        self.text
            .write_fmt(args)
            .expect("writing to a String cannot fail");
        self.text.push('\n');
    }

    fn blank(&mut self) {
        self.text.push('\n');
    }

    fn into_string(self) -> String {
        self.text
    }
}

pub(crate) fn print_markdown(markdown: &str) -> Result<()> {
    write_rendered_markdown(io::stdout().lock(), markdown, stdout_render_style())
}

fn print_markdown_stderr(markdown: &str) -> Result<()> {
    write_rendered_markdown(io::stderr().lock(), markdown, stderr_render_style())
}

fn write_rendered_markdown(
    mut writer: impl IoWrite,
    markdown: &str,
    style: RenderStyle,
) -> Result<()> {
    writer.write_all(render_markdown(markdown, style).as_bytes())?;
    Ok(())
}

fn stdout_render_style() -> RenderStyle {
    terminal_render_style(io::stdout().is_terminal())
}

fn stderr_render_style() -> RenderStyle {
    terminal_render_style(io::stderr().is_terminal())
}

fn terminal_render_style(is_terminal: bool) -> RenderStyle {
    let color_disabled = std::env::var_os("NO_COLOR").is_some()
        || std::env::var("TERM").is_ok_and(|terminal| terminal.eq_ignore_ascii_case("dumb"));
    if is_terminal && !color_disabled {
        RenderStyle::Ansi
    } else {
        RenderStyle::Plain
    }
}

fn render_markdown(markdown: &str, style: RenderStyle) -> String {
    match style {
        RenderStyle::Plain => render_plain_markdown(markdown),
        RenderStyle::Ansi => Renderer::new()
            .with_style(GlamourStyle::Dark)
            .render(markdown),
    }
}

fn render_plain_markdown(markdown: &str) -> String {
    let mut rendered = String::new();
    for line in markdown.lines() {
        if let Some(heading) = line.strip_prefix("# ") {
            push_heading(&mut rendered, heading, '=');
        } else if let Some(heading) = line.strip_prefix("## ") {
            push_heading(&mut rendered, heading, '-');
        } else if let Some(item) = line.strip_prefix("- ") {
            rendered.push_str("- ");
            rendered.push_str(&render_inline_plain(item));
            rendered.push('\n');
        } else {
            rendered.push_str(&render_inline_plain(line));
            rendered.push('\n');
        }
    }
    if markdown.is_empty() {
        rendered.push('\n');
    }
    rendered
}

fn push_heading(rendered: &mut String, heading: &str, underline: char) {
    rendered.push_str(heading);
    rendered.push('\n');
    rendered.push_str(&underline.to_string().repeat(heading.chars().count().max(1)));
    rendered.push('\n');
}

fn render_inline_plain(line: &str) -> String {
    let mut rendered = String::new();
    for segment in line.split('`') {
        rendered.push_str(segment);
    }
    rendered
}

#[cfg(test)]
fn render_markdown_for_test(markdown: &str, style: RenderStyle) -> String {
    render_markdown(markdown, style)
}
