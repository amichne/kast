package install

import (
	"context"
	"errors"
	"io"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/charmbracelet/x/ansi"
)

const (
	hideCursor = "\x1b[?25l"
	showCursor = "\x1b[?25h"
)

// terminalQuery matches sequences that a terminal answers on standard input, which this command never reads.
var terminalQuery = regexp.MustCompile(`\x1b\[\?[0-9]*\$p|\x1b\[\?u|\x1b\[[>=]?[0-9]*c|\x1b\[6n|\x1b\][0-9]+;\?`)

func parts(sizes ...int64) []part {
	roles := []role{product, analysisRuntime, qualificationReceipt, runtimeBase}
	var found []part
	for index, size := range sizes {
		found = append(found, part{Asset{Bytes: size}, roles[index]})
	}
	return found
}

// clock is a controlled time source for one progress value.
type clock struct{ at time.Time }

func (c *clock) now() time.Time { return c.at }

func lines(text string) []string {
	var found []string
	for line := range strings.Lines(ansi.Strip(text)) {
		found = append(found, strings.TrimRight(line, " \n"))
	}
	return found
}

func requireLines(t *testing.T, want []string, text string) {
	t.Helper()
	if got := lines(text); strings.Join(got, "\n") != strings.Join(want, "\n") {
		t.Errorf("got\n%s\nwant\n%s", strings.Join(got, "\n"), strings.Join(want, "\n"))
	}
}

func TestAFrameShowsEachFilesStageAndTheTotal(t *testing.T) {
	c := &clock{time.Unix(0, 0)}
	p := newProgress(parts(100_000_000, 200_000_000, 1_000_000, 400_000_000), c.now)
	p.file(0).enter(checking)
	p.file(0).hold(100_000_000)
	p.file(0).enter(complete)
	p.begin()
	p.file(1).enter(receiving)
	p.file(1).hold(50_000_000)
	c.at = c.at.Add(10 * time.Second)
	p.file(1).add(100_000_000)
	frame := p.snapshot().frame(80, "*")
	if !strings.Contains(frame, "\x1b[") {
		t.Error("the frame has no style")
	}
	requireLines(t, []string{
		"  ✓ Kast               100 MB  already here",
		"  * Analysis runtime   150 / 200 MB",
		"  · Qualification      1.0 MB",
		"  · IntelliJ base      400 MB",
		"",
		// Bytes that were already here are progress but not speed: 100 MB in 10 s, 451 MB left.
		"  ██████████░░░░░░░░░░░░░░░░░░  250 / 701 MB   10 MB/s   46s left",
	}, frame)
}

func TestAFrameNamesCheckingAndVerification(t *testing.T) {
	c := &clock{time.Unix(0, 0)}
	p := newProgress(parts(20_000_000, 30_000_000), c.now)
	p.file(0).enter(checking)
	requireLines(t, []string{
		"  * Kast               20 MB  checking",
		"  · Analysis runtime   30 MB",
	}, p.snapshot().frame(80, "*"))
	p.begin()
	p.file(0).enter(receiving)
	p.file(0).add(20_000_000)
	p.file(0).enter(verifying)
	if frame := lines(p.snapshot().frame(80, "*")); frame[0] != "  * Kast               20 MB  verifying" {
		t.Errorf("frame %q", frame)
	}
}

func TestAnEndedFrameKeepsTheFilesWithoutTheBar(t *testing.T) {
	c := &clock{time.Unix(0, 0)}
	p := newProgress(parts(20_000_000, 30_000_000, 1_000_000), c.now)
	p.begin()
	p.file(0).enter(receiving)
	p.file(0).add(20_000_000)
	p.file(0).enter(complete)
	p.file(1).enter(receiving)
	p.file(1).add(10_000_000)
	p.end()
	requireLines(t, []string{
		"  ✓ Kast               20 MB",
		"  ✗ Analysis runtime   10 / 30 MB",
		"  · Qualification      1.0 MB",
	}, p.snapshot().frame(80, "*"))
}

func TestARestartRemovesTheFilesBytesFromTheTotalAndTheSpeed(t *testing.T) {
	c := &clock{time.Unix(0, 0)}
	p := newProgress(parts(40_000_000), c.now)
	p.begin()
	p.file(0).enter(receiving)
	p.file(0).hold(30_000_000)
	p.file(0).restart()
	c.at = c.at.Add(2 * time.Second)
	p.file(0).add(20_000_000)
	if frame := lines(p.snapshot().frame(80, "*")); frame[len(frame)-1] !=
		"  ██████████████░░░░░░░░░░░░░░  20 / 40 MB   10 MB/s   2s left" {
		t.Errorf("frame %q", frame)
	}
}

func TestAnUnknownSpeedPromisesNoRemainingTime(t *testing.T) {
	for _, observed := range []struct {
		received int64
		elapsed  time.Duration
	}{{0, 0}, {0, time.Minute}, {1_000_000, 0}, {1_000_000, minimumSample - time.Millisecond}} {
		c := &clock{time.Unix(0, 0)}
		p := newProgress(parts(40_000_000), c.now)
		p.begin()
		p.file(0).enter(receiving)
		p.file(0).add(observed.received)
		c.at = c.at.Add(observed.elapsed)
		if frame := p.snapshot().frame(80, "*"); strings.Contains(frame, "left") || strings.Contains(frame, "MB/s") {
			t.Errorf("%+v: frame %q", observed, lines(frame))
		}
	}
}

func TestASmallFileNamesItsOwnUnit(t *testing.T) {
	for want, count := range map[string]int64{"1 KB": 1, "12 KB": 12_000, "0.1 MB": 100_000, "9.9 MB": 9_900_000,
		"10 MB": 10_000_000} {
		if got := megabytes(count); got != want {
			t.Errorf("%d: %q, want %q", count, got, want)
		}
	}
	c := &clock{time.Unix(0, 0)}
	p := newProgress(parts(12_000), c.now)
	p.file(0).enter(receiving)
	p.file(0).add(3_000)
	requireLines(t, []string{"  * Kast               3 KB / 12 KB"}, p.snapshot().frame(80, "*"))
}

// fetchBase downloads the fixture's largest file alone and returns its progress.
func (f *fixture) fetchBase() (fileProgress, error) {
	f.t.Helper()
	store, err := openStore(f.home)
	if err != nil {
		f.t.Fatal(err)
	}
	defer store.close()
	raw := f.baseBytes()
	base := part{Asset{"kast-runtime-base-macos-aarch64-" + digest(raw) + ".zip", digest(raw), int64(len(raw))}, runtimeBase}
	p := newProgress([]part{base}, time.Now)
	p.begin()
	err = f.install().download(context.Background(), store, tag, base.Asset, p.file(0))
	return p.snapshot().files[0], err
}

func TestAResumedDownloadHoldsItsEarlierBytes(t *testing.T) {
	f := newFixture(t)
	f.writePart(f.baseBytes()[:10])
	total := int64(len(f.baseBytes()))
	if got, err := f.fetchBase(); err != nil || got != (fileProgress{runtimeBase, complete, total, 10, total}) {
		t.Errorf("got %+v, %v", got, err)
	}
}

func TestARestartedDownloadHoldsNothing(t *testing.T) {
	f := newFixture(t)
	f.writePart(f.baseBytes()[:10])
	f.faults[f.baseURL()] = []fault{{noRange: true}}
	total := int64(len(f.baseBytes()))
	if got, err := f.fetchBase(); err != nil || got != (fileProgress{runtimeBase, complete, total, 0, total}) {
		t.Errorf("got %+v, %v", got, err)
	}
}

func TestWrongBytesStopInVerification(t *testing.T) {
	f := newFixture(t)
	f.writePart([]byte("not the published bytes"))
	got, err := f.fetchBase()
	f.requireFailure(DownloadInvalid, err)
	if got.stage != verifying {
		t.Errorf("got %+v", got)
	}
}

// A snapshot that shared its files with the transfer would change under a view.
func TestASnapshotDoesNotChange(t *testing.T) {
	p := newProgress(parts(40_000_000), time.Now)
	taken := p.snapshot()
	p.file(0).add(1)
	if taken.done() != 0 || p.snapshot().done() != 1 {
		t.Errorf("snapshot %+v", taken)
	}
}

func TestAFrameFitsANarrowTerminal(t *testing.T) {
	c := &clock{time.Unix(0, 0)}
	p := newProgress(parts(100_000_000, 200_000_000), c.now)
	p.begin()
	p.file(0).enter(receiving)
	c.at = c.at.Add(time.Second)
	p.file(0).add(1_000_000)
	for width := 1; width <= 120; width++ {
		for _, line := range lines(p.snapshot().frame(width, "*")) {
			if ansi.StringWidth(line) > width {
				t.Fatalf("width %d: %q", width, line)
			}
		}
	}
}

func TestRemainingTimesAreShortAndRoundUp(t *testing.T) {
	for want, left := range map[string]time.Duration{"1s": time.Millisecond, "59s": 59 * time.Second,
		"1m 0s": 60 * time.Second, "2m 5s": 124*time.Second + time.Millisecond, "1h 1m": 61 * time.Minute} {
		if got := remaining(left); got != want {
			t.Errorf("%v: %q, want %q", left, got, want)
		}
	}
}

func TestATerminalDownloadShowsEveryFileAndRestoresTheCursor(t *testing.T) {
	f := newFixture(t)
	f.terminal = true
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	f.requireComplete()
	log := f.log.String()
	if strings.Count(log, hideCursor) != 1 || strings.LastIndex(log, showCursor) < strings.LastIndex(log, hideCursor) {
		t.Errorf("cursor in %q", log)
	}
	if query := terminalQuery.FindString(log); query != "" {
		t.Errorf("the view asks the terminal %q", query)
	}
	if strings.Contains(log, "\x1b[?2004h") || strings.Contains(log, "\x1b[?1049h") {
		t.Errorf("the view changes paste or screen mode: %q", log)
	}
	// The renderer writes only changed cells, so a file's label and its later mark can be apart.
	text := ansi.Strip(log)
	for _, label := range []string{"Kast", "Analysis runtime", "Qualification", "IntelliJ base"} {
		if !strings.Contains(text, label) {
			t.Errorf("missing %q in %q", label, text)
		}
	}
	if strings.Count(text, "✓") < len(f.assets) {
		t.Errorf("marks in %q", text)
	}
	// The result stays plain, so standard output is the same on a terminal and in a file.
	if out := f.out.String(); strings.Contains(out, "\x1b") || !strings.Contains(out, "Nothing was installed.") {
		t.Errorf("output %q", out)
	}
}

func TestATerminalDownloadOfHeldFilesShowsThem(t *testing.T) {
	f := newFixture(t)
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	f.terminal = true
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	if text := ansi.Strip(f.log.String()); strings.Count(text, "✓") < len(f.assets) {
		t.Errorf("log %q", text)
	}
}

func TestHeldFilesAreCompleteWithoutReceivedBytes(t *testing.T) {
	f := newFixture(t)
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	found, err := f.install().resolve(context.Background(), channelHead{preview})
	if err != nil {
		t.Fatal(err)
	}
	store, err := openStore(f.home)
	if err != nil {
		t.Fatal(err)
	}
	defer store.close()
	p := newProgress(found.parts, time.Now)
	if _, err := f.install().fill(context.Background(), store, found, p, io.Discard); err != nil {
		t.Fatal(err)
	}
	for _, file := range p.snapshot().files {
		if file != (fileProgress{file.role, complete, file.total, file.total, file.total}) {
			t.Errorf("file %+v", file)
		}
	}
}

func TestAFailedTerminalDownloadRestoresTheCursorBeforeItReturns(t *testing.T) {
	f := newFixture(t)
	f.terminal = true
	f.faults[f.baseURL()] = []fault{{status: 404}}
	f.requireFailure(BuildNotFound, f.run("--download-only"))
	if log := f.log.String(); strings.LastIndex(log, showCursor) < strings.LastIndex(log, hideCursor) ||
		strings.Count(log, "✗") != 1 || !strings.Contains(ansi.Strip(log), "IntelliJ base") {
		t.Errorf("log %q", log)
	}
}

func TestJSONOnATerminalShowsNoView(t *testing.T) {
	f := newFixture(t)
	f.terminal = true
	if err := f.run("--download-only", "--json"); err != nil {
		t.Fatal(err)
	}
	if f.log.Len() != 0 {
		t.Errorf("progress %q", f.log.String())
	}
}

// A cancellation must restore the terminal while a transfer, a retry pause, or a digest is still ending.
func TestACancelledViewEndsWithoutItsDownload(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	p := newProgress(parts(40_000_000), time.Now)
	p.begin()
	p.file(0).enter(receiving)
	ended := make(chan error, 1)
	go func() { ended <- show(ctx, p, io.Discard, nil) }()
	select {
	case err := <-ended:
		t.Fatalf("the view ended before its download: %v", err)
	case <-time.After(3 * viewInterval):
	}
	cancel()
	select {
	case err := <-ended:
		if err != nil {
			t.Error(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the view did not end")
	}
}

type brokenWriter struct{}

func (brokenWriter) Write([]byte) (int, error) { return 0, errors.New("the terminal is gone") }

func TestALostTerminalDoesNotStopADownload(t *testing.T) {
	f := newFixture(t)
	f.terminal, f.writer = true, brokenWriter{}
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	f.requireComplete()
}
