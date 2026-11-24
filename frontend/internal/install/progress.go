package install

import (
	"fmt"
	"slices"
	"sync"
	"time"
)

// stage is what a download does with one file now.
type stage int

const (
	waiting stage = iota
	checking
	receiving
	verifying
	complete
)

// fileProgress is one file's stage and bytes. Held bytes were here before this command; they are progress, not speed.
type fileProgress struct {
	role              role
	stage             stage
	total, held, done int64
}

// snapshot is one consistent observation of a download.
type snapshot struct {
	files []fileProgress
	// elapsed is the time since the first transfer could start.
	elapsed time.Duration
	begun   bool
	ended   bool
}

func (s snapshot) total() (bytes int64) {
	for _, file := range s.files {
		bytes += file.total
	}
	return bytes
}

func (s snapshot) done() (bytes int64) {
	for _, file := range s.files {
		bytes += file.done
	}
	return bytes
}

func (s snapshot) received() (bytes int64) {
	for _, file := range s.files {
		bytes += file.done - file.held
	}
	return bytes
}

// progress is what one download has done. Its transfers write it, and a view reads snapshots of it, so a slow terminal
// never delays a transfer.
type progress struct {
	now func() time.Time

	mutex   sync.Mutex
	current snapshot
	began   time.Time
}

func newProgress(parts []part, now func() time.Time) *progress {
	p := &progress{now: now}
	for _, part := range parts {
		p.current.files = append(p.current.files, fileProgress{role: part.role, total: part.Bytes})
	}
	return p
}

func (p *progress) begin() {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	p.current.begun, p.began = true, p.now()
}

func (p *progress) end() {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	p.current.ended = true
}

func (p *progress) snapshot() snapshot {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	taken := p.current
	taken.files = slices.Clone(taken.files)
	if taken.begun {
		taken.elapsed = p.now().Sub(p.began)
	}
	return taken
}

// meter writes one file's progress.
type meter struct {
	progress *progress
	index    int
}

func (p *progress) file(index int) meter { return meter{p, index} }

func (m meter) change(change func(*fileProgress)) {
	m.progress.mutex.Lock()
	defer m.progress.mutex.Unlock()
	change(&m.progress.current.files[m.index])
}

func (m meter) enter(next stage) { m.change(func(f *fileProgress) { f.stage = next }) }

// hold counts bytes that were here before this command.
func (m meter) hold(count int64) {
	m.change(func(f *fileProgress) { f.held, f.done = f.held+count, f.done+count })
}

func (m meter) add(count int64) { m.change(func(f *fileProgress) { f.done += count }) }

// restart drops every byte of the file.
func (m meter) restart() { m.change(func(f *fileProgress) { f.held, f.done = 0, 0 }) }

// megabytes rounds a size for a reader. A nonempty size is never shown as zero.
func megabytes(count int64) string {
	switch {
	case count < 100_000:
		return fmt.Sprintf("%d KB", (count+999)/1000)
	case count < 10_000_000:
		return fmt.Sprintf("%.1f MB", float64(count)/1e6)
	default:
		return fmt.Sprintf("%.0f MB", float64(count)/1e6)
	}
}
