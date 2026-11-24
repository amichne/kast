package install

import (
	"context"
	"fmt"
	"io"
	"strings"
	"time"

	bar "charm.land/bubbles/v2/progress"
	"charm.land/bubbles/v2/spinner"
	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"
)

const (
	viewInterval  = 100 * time.Millisecond
	defaultWidth  = 80
	defaultHeight = 24
	barWidth      = 28
	// minimumSample is the time after which a speed is worth showing.
	minimumSample = time.Second
)

var (
	faint  = lipgloss.NewStyle().Faint(true)
	good   = lipgloss.NewStyle().Foreground(lipgloss.Green)
	bad    = lipgloss.NewStyle().Foreground(lipgloss.Red)
	active = lipgloss.NewStyle().Foreground(lipgloss.Magenta)
)

// show draws a download until it ends or its context does. It reads no input, so it sends no terminal query, and a
// signal stays with the process. Its failure says nothing about the download.
func show(ctx context.Context, p *progress, log io.Writer, environment []string) error {
	_, err := tea.NewProgram(view{ctx: ctx, progress: p, width: defaultWidth}, tea.WithInput(nil), tea.WithOutput(log),
		tea.WithEnvironment(environment), tea.WithoutSignalHandler(),
		// A terminal's own size replaces this one.
		tea.WithWindowSize(defaultWidth, defaultHeight)).Run()
	return err
}

type view struct {
	ctx      context.Context
	progress *progress
	width    int
	drawn    int
}

type drawMessage struct{}

func draw() tea.Cmd { return tea.Tick(viewInterval, func(time.Time) tea.Msg { return drawMessage{} }) }

func (v view) Init() tea.Cmd { return draw() }

func (v view) Update(message tea.Msg) (tea.Model, tea.Cmd) {
	switch message := message.(type) {
	case tea.WindowSizeMsg:
		v.width = message.Width
	case drawMessage:
		v.drawn++
		if v.ctx.Err() != nil || v.progress.snapshot().ended {
			return v, tea.Quit
		}
		return v, draw()
	}
	return v, nil
}

func (v view) View() tea.View {
	seen := v.progress.snapshot()
	// A cancelled transfer has not returned yet, but this is its last frame.
	seen.ended = seen.ended || v.ctx.Err() != nil
	frames := spinner.MiniDot.Frames
	drawn := tea.NewView(seen.frame(v.width, frames[v.drawn%len(frames)]))
	drawn.DisableBracketedPasteMode = true
	return drawn
}

// frame draws each file, and the total while transfers run. No line is wider than the terminal.
func (s snapshot) frame(width int, spin string) string {
	var drawn []string
	for _, file := range s.files {
		drawn = append(drawn, file.line(spin, s.ended))
	}
	if s.begun && !s.ended {
		drawn = append(drawn, "", s.summary(width))
	}
	fit := lipgloss.NewStyle().MaxWidth(width)
	for index, line := range drawn {
		drawn[index] = fit.Render(line)
	}
	return strings.Join(drawn, "\n") + "\n"
}

func (f fileProgress) line(spin string, ended bool) string {
	mark, size, note := faint.Render("·"), megabytes(f.total), ""
	switch {
	case f.stage == complete:
		mark = good.Render("✓")
		if f.held == f.total {
			note = "already here"
		}
	case f.stage == waiting:
	case ended:
		mark, size = bad.Render("✗"), portion(f.done, f.total)
	case f.stage == receiving:
		mark, size = active.Render(spin), portion(f.done, f.total)
	case f.stage == checking:
		mark, note = active.Render(spin), "checking"
	case f.stage == verifying:
		mark, note = active.Render(spin), "verifying"
	}
	if note != "" {
		note = "  " + note
	}
	return fmt.Sprintf("  %s %-16s   %s", mark, f.role, faint.Render(size+note))
}

func portion(done, total int64) string {
	if total < 10_000_000 {
		return megabytes(done) + " / " + megabytes(total)
	}
	return fmt.Sprintf("%.0f / %s", float64(done)/1e6, megabytes(total))
}

func (s snapshot) summary(width int) string {
	done, total := s.done(), s.total()
	text := portion(done, total)
	if received := s.received(); received > 0 && s.elapsed >= minimumSample {
		speed := float64(received) / s.elapsed.Seconds()
		text += fmt.Sprintf("   %.0f MB/s   %s left", speed/1e6,
			remaining(time.Duration(float64(total-done)/speed*float64(time.Second))))
	}
	room := max(width-lipgloss.Width(text)-4, 1)
	meter := bar.New(bar.WithDefaultBlend(), bar.WithFillCharacters('█', '░'), bar.WithoutPercentage(),
		bar.WithWidth(min(room, barWidth)))
	return "  " + meter.ViewAs(float64(done)/float64(total)) + "  " + text
}

// remaining rounds up, so a running transfer never shows zero.
func remaining(left time.Duration) string {
	seconds := int64((left + time.Second - 1) / time.Second)
	switch {
	case seconds < 60:
		return fmt.Sprintf("%ds", seconds)
	case seconds < 3600:
		return fmt.Sprintf("%dm %ds", seconds/60, seconds%60)
	default:
		return fmt.Sprintf("%dh %dm", seconds/3600, seconds%3600/60)
	}
}
