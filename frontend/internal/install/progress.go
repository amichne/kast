package install

import (
	"fmt"
	"io"
	"strings"
	"time"
)

const (
	redrawInterval = 100 * time.Millisecond
	barWidth       = 24
)

// progress shows one download total: a redrawn line on a terminal, one line at each end otherwise.
type progress struct {
	log      io.Writer
	terminal bool
	silent   bool
	now      func() time.Time

	total, done, reused int64
	began, drawn        time.Time
}

func (p *progress) start(total, reused int64) {
	p.total, p.done, p.reused, p.began = total, reused, reused, p.now()
	if p.silent {
		return
	}
	if !p.terminal {
		fmt.Fprintf(p.log, "Downloading %s\n", megabytes(total-reused))
		return
	}
	p.draw()
}

func (p *progress) add(count int64) {
	p.done += count
	if p.terminal && !p.silent && p.now().Sub(p.drawn) >= redrawInterval {
		p.draw()
	}
}

func (p *progress) finish() {
	switch {
	case p.silent:
	case p.terminal:
		p.draw()
		fmt.Fprintln(p.log)
	default:
		fmt.Fprintf(p.log, "Downloaded %s of %s\n", megabytes(p.done), megabytes(p.total))
	}
}

func (p *progress) draw() {
	p.drawn = p.now()
	filled := 0
	if p.total > 0 {
		filled = int(min(p.done, p.total) * barWidth / p.total)
	}
	speed := ""
	if elapsed := p.drawn.Sub(p.began).Seconds(); elapsed > 0 {
		speed = fmt.Sprintf("   %.0f MB/s", float64(max(p.done-p.reused, 0))/elapsed/1e6)
	}
	fmt.Fprintf(p.log, "\r\x1b[K  Downloading  %s%s  %.0f / %s%s", strings.Repeat("█", filled),
		strings.Repeat("░", barWidth-filled), float64(p.done)/1e6, megabytes(p.total), speed)
}

func megabytes(count int64) string {
	if count < 10_000_000 {
		return fmt.Sprintf("%.1f MB", float64(count)/1e6)
	}
	return fmt.Sprintf("%.0f MB", float64(count)/1e6)
}
