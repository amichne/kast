package install

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"syscall"
	"testing"
	"testing/iotest"
	"time"
)

const (
	version  = "0.0.123"
	tag      = "developer-v0.0.123"
	revision = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
)

// site answers exactly the scripted requests. An unexpected request fails the test.
type site struct {
	t     *testing.T
	pages map[string][]byte
	// faults are consumed in order by requests for one URL, before its page is served.
	faults map[string][]fault
	calls  []string
}

// fault replaces one response: a status, a redirect, a transport error, or a body that ends early or fails.
type fault struct {
	status    int
	location  string
	err       error
	cut       int
	broken    error
	noRange   bool
	wrongSpan bool
}

func (s *site) RoundTrip(request *http.Request) (*http.Response, error) {
	url, span := request.URL.String(), request.Header.Get("Range")
	s.calls = append(s.calls, strings.TrimSpace(filepath.Base(url)+" "+span))
	page, ok := s.pages[url]
	var planned fault
	if queue := s.faults[url]; len(queue) > 0 {
		planned, s.faults[url] = queue[0], queue[1:]
	}
	switch {
	case planned.err != nil:
		return nil, planned.err
	case planned.location != "":
		return response(request, http.StatusFound, http.Header{"Location": {planned.location}}, nil), nil
	case planned.status != 0:
		return response(request, planned.status, nil, nil), nil
	case !ok:
		s.t.Errorf("unexpected request %s", url)
		return response(request, http.StatusNotFound, nil, nil), nil
	}
	status, header, offset := http.StatusOK, http.Header{}, 0
	if span != "" && !planned.noRange {
		if _, err := fmt.Sscanf(span, "bytes=%d-", &offset); err != nil || offset >= len(page) {
			s.t.Errorf("unexpected range %q for %s", span, url)
		}
		status = http.StatusPartialContent
		start := offset
		if planned.wrongSpan {
			start++
		}
		header.Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, len(page)-1, len(page)))
	}
	body := page[offset:]
	if planned.cut > 0 {
		body = body[:planned.cut]
	}
	answer := response(request, status, header, body)
	if planned.broken != nil {
		answer.Body = io.NopCloser(io.MultiReader(bytes.NewReader(body), iotest.ErrReader(planned.broken)))
	}
	return answer, nil
}

func response(request *http.Request, status int, header http.Header, body []byte) *http.Response {
	if header == nil {
		header = http.Header{}
	}
	return &http.Response{StatusCode: status, Header: header, Body: io.NopCloser(bytes.NewReader(body)), Request: request}
}

type fixture struct {
	*site
	home   string
	pauses []time.Duration
	out    bytes.Buffer
	log    bytes.Buffer
	assets map[string][]byte
	record map[string]any
	// terminal gives the view a terminal that writer replaces when it is set.
	terminal bool
	writer   io.Writer
}

func digest(raw []byte) string {
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:])
}

func newFixture(t *testing.T) *fixture {
	home, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	f := &fixture{site: &site{t: t, pages: map[string][]byte{}, faults: map[string][]fault{}}, home: home}
	// The Linux files have other bytes, and no page: a Mac that requested one would fail the test.
	linux, _ := targetDocumentFor("linux-x86_64", "linux ")
	macos, assets := targetDocumentFor("macos-aarch64", "")
	f.assets = assets
	for name, raw := range f.assets {
		f.pages[releases+"/"+tag+"/"+name] = raw
	}
	f.record = map[string]any{
		"schemaVersion": 3, "repository": repository, "sourceRevision": revision, "sourceTree": revision,
		"version": version, "producer": map[string]any{"runId": 1, "runAttempt": 1, "workflow": ".github/workflows/ci.yml"},
		"targets": []any{linux, macos},
	}
	f.publish()
	return f
}

// targetDocumentFor is one target of a build record, with the files that it names.
func targetDocumentFor(target, mark string) (map[string]any, map[string][]byte) {
	base := bytes.Repeat([]byte(mark+"base"), 4096)
	bundle, runtime := bytes.Repeat([]byte(mark+"bundle"), 512), bytes.Repeat([]byte(mark+"runtime"), 256)
	receipt := []byte(mark + "receipt")
	asset := func(name string, raw []byte) map[string]any {
		return map[string]any{"name": name, "sha256": digest(raw), "bytes": len(raw)}
	}
	other := digest([]byte("other"))
	document := map[string]any{
		"target": target,
		"inputs": map[string]any{"javaReleaseSha256": other, "dependencyLocksSha256": other,
			"compositionRecipeSha256": other, "donorSha256": other},
		"bundle":  asset("kast-composed-setup-v"+version+"-"+target+".tar.gz", bundle),
		"runtime": asset("kast-runtime-v"+version+"-"+target+".zip", runtime),
		"qualification": map[string]any{
			"receipt":      asset("qualification-"+target+".json", receipt),
			"producer":     map[string]any{"runId": 1, "runAttempt": 1, "workflow": ".github/workflows/composed-linux.yml"},
			"bundleSha256": digest(bundle), "runtimeSha256": digest(runtime), "controlSha256": other,
			"kind": "NATIVE", "outcome": "COMPLETE",
			"composition": map[string]any{"baseSha256": digest(base), "nativeRuntimeSha256": other,
				"nativeBundleSha256": other, "runtimeSha256": digest(runtime)},
		},
		"runtimeBase": asset("kast-runtime-base-"+target+"-"+digest(base)+".zip", base),
	}
	return document, map[string][]byte{
		"kast-composed-setup-v" + version + "-" + target + ".tar.gz": bundle,
		"kast-runtime-v" + version + "-" + target + ".zip":           runtime,
		"qualification-" + target + ".json":                          receipt,
		"kast-runtime-base-" + target + "-" + digest(base) + ".zip":  base,
	}
}

// publish encodes the record and the catalog that names its digest.
func (f *fixture) publish() {
	f.t.Helper()
	raw := f.encode(f.record)
	f.pages[releases+"/"+tag+"/build-record.json"] = raw
	f.pages[catalogURL] = f.encode(map[string]any{"schemaVersion": 2, "builds": []any{
		f.entry(revision, tag, digest(raw)),
	}})
}

func (f *fixture) entry(revision, tag, sha string) map[string]any {
	return map[string]any{"mainRevision": revision, "releaseTag": tag, "record": map[string]any{}, "recordSha256": sha}
}

func (f *fixture) encode(value any) []byte {
	raw, err := json.Marshal(value)
	if err != nil {
		f.t.Fatal(err)
	}
	return raw
}

// target is this fixture's Mac target, the second one that a record lists.
func (f *fixture) target() map[string]any { return f.record["targets"].([]any)[1].(map[string]any) }

func (f *fixture) run(arguments ...string) error {
	return f.runContext(context.Background(), arguments...)
}

func (f *fixture) runContext(ctx context.Context, arguments ...string) error {
	f.out.Reset()
	f.log.Reset()
	return f.install().Run(ctx, arguments)
}

func (f *fixture) install() *Install {
	install := &Install{Home: f.home, Platform: "macos-aarch64", Transport: f.site, Stall: time.Minute,
		Pause: func(wait time.Duration) { f.pauses = append(f.pauses, wait) }, Now: func() time.Time { return time.Unix(0, 0) },
		Terminal: f.terminal, Environment: []string{"TERM=xterm-256color"}, Out: &f.out, Log: &f.log}
	if f.writer != nil {
		install.Log = f.writer
	}
	return install
}

func (f *fixture) requireFailure(want Failure, err error) *Rejection {
	f.t.Helper()
	var rejection *Rejection
	if !errors.As(err, &rejection) || rejection.Failure != want {
		f.t.Fatalf("got %v, want %s", err, want)
	}
	if rejection.Next == "" {
		f.t.Errorf("%s names no next step", want)
	}
	return rejection
}

func (f *fixture) downloads() map[string][]byte {
	found := map[string][]byte{}
	names, _ := os.ReadDir(filepath.Join(f.home, ".local/share/.kast-downloads"))
	for _, name := range names {
		if name.Name() == lockName {
			continue
		}
		raw, err := os.ReadFile(filepath.Join(f.home, ".local/share/.kast-downloads", name.Name()))
		if err != nil {
			f.t.Fatal(err)
		}
		found[name.Name()] = raw
	}
	return found
}

func (f *fixture) requireComplete() {
	f.t.Helper()
	found := f.downloads()
	if len(found) != len(f.assets) {
		f.t.Fatalf("downloads %d, want %d", len(found), len(f.assets))
	}
	for name, raw := range f.assets {
		if !bytes.Equal(found[digest(raw)+"-"+name], raw) {
			f.t.Errorf("%s differs", name)
		}
	}
}

func (f *fixture) baseURL() string {
	return releases + "/" + tag + "/" + f.target()["runtimeBase"].(map[string]any)["name"].(string)
}

func (f *fixture) baseBytes() []byte { return f.pages[f.baseURL()] }

func (f *fixture) partPath() string {
	raw := f.baseBytes()
	return filepath.Join(f.home, ".local/share/.kast-downloads", digest(raw)+"-"+filepath.Base(f.baseURL())+partSuffix)
}

func (f *fixture) writePart(raw []byte) {
	f.t.Helper()
	if err := os.MkdirAll(filepath.Dir(f.partPath()), 0o700); err != nil {
		f.t.Fatal(err)
	}
	if err := os.WriteFile(f.partPath(), raw, 0o600); err != nil {
		f.t.Fatal(err)
	}
}

func TestADryRunPlansTheLatestPreviewWithoutDownloadsOrFiles(t *testing.T) {
	f := newFixture(t)
	if err := f.run("--dry-run"); err != nil {
		t.Fatal(err)
	}
	if want := []string{"builds.json", "build-record.json"}; strings.Join(f.calls, ",") != strings.Join(want, ",") {
		t.Errorf("calls %q", f.calls)
	}
	if entries, _ := os.ReadDir(f.home); len(entries) != 0 {
		t.Errorf("a dry run wrote %v", entries)
	}
	for _, want := range []string{"Kast 0.0.123 (preview) for macOS ARM64", "in 4 files", "Nothing was downloaded or installed."} {
		if !strings.Contains(f.out.String(), want) {
			t.Errorf("missing %q in %q", want, f.out.String())
		}
	}
}

func TestADownloadVerifiesEveryFileAndASecondRunReusesThem(t *testing.T) {
	f := newFixture(t)
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	f.requireComplete()
	f.calls = nil
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	if want := "builds.json,build-record.json"; strings.Join(f.calls, ",") != want {
		t.Errorf("a second run requested %q", f.calls)
	}
	f.requireComplete()
}

func TestAnInterruptedDownloadResumesFromItsBytes(t *testing.T) {
	f := newFixture(t)
	f.writePart(f.baseBytes()[:1000])
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	f.requireComplete()
	if !strings.Contains(strings.Join(f.calls, ","), "bytes=1000-") {
		t.Errorf("no resume in %q", f.calls)
	}
}

func TestAServerThatIgnoresOrMisplacesTheRangeRestartsTheFile(t *testing.T) {
	for name, planned := range map[string]fault{"ignored": {noRange: true}, "misplaced": {wrongSpan: true}} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			f.writePart([]byte("wrong prefix"))
			f.faults[f.baseURL()] = []fault{planned}
			if err := f.run("--download-only"); err != nil {
				t.Fatal(err)
			}
			f.requireComplete()
		})
	}
}

func TestTransientFailuresRetryAndKeepReceivedBytes(t *testing.T) {
	f := newFixture(t)
	f.faults[f.baseURL()] = []fault{{status: 503}, {cut: 700}, {err: errors.New("reset")},
		{cut: 200, broken: errors.New("broken")}}
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	f.requireComplete()
	if len(f.pauses) != 4 {
		t.Errorf("pauses %v", f.pauses)
	}
	for _, want := range []string{"bytes=700-", "bytes=900-"} {
		if !strings.Contains(strings.Join(f.calls, ","), want) {
			t.Errorf("received bytes were not kept: %q", f.calls)
		}
	}
}

func TestARestartDropsTheBytesItReplaces(t *testing.T) {
	f := newFixture(t)
	f.writePart([]byte("wrong prefix"))
	f.faults[f.baseURL()] = []fault{{noRange: true, cut: 5}}
	for range attempts {
		f.faults[f.baseURL()] = append(f.faults[f.baseURL()], fault{err: errors.New("offline")})
	}
	f.requireFailure(SourceUnavailable, f.run("--download-only"))
	if raw, err := os.ReadFile(f.partPath()); err != nil || !bytes.Equal(raw, f.baseBytes()[:5]) {
		t.Errorf("part %q %v", raw, err)
	}
}

func TestOnlyHTTPSRedirectsAreFollowed(t *testing.T) {
	f := newFixture(t)
	moved := "https://assets.example/base"
	f.pages[moved] = f.baseBytes()
	f.faults[f.baseURL()] = []fault{{location: moved}}
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	f = newFixture(t)
	f.faults[catalogURL] = []fault{{location: "http://plain.example/builds.json"}}
	f.requireFailure(SourceUnavailable, f.run("--dry-run"))
	if len(f.calls) != 1 || len(f.pauses) != 0 {
		t.Errorf("calls %q pauses %v", f.calls, f.pauses)
	}
}

func TestACatalogRevisionMustBeTheRecordsRevision(t *testing.T) {
	f := newFixture(t)
	f.pages[catalogURL] = f.encode(map[string]any{"schemaVersion": 2, "builds": []any{
		f.entry(strings.Repeat("b", 40), tag, digest(f.pages[releases+"/"+tag+"/build-record.json"]))}})
	f.requireFailure(RecordInvalid, f.run("--dry-run"))
}

func TestEachPlatformSelectsItsOwnTargetOfOneRecord(t *testing.T) {
	f := newFixture(t)
	raw := f.pages[releases+"/"+tag+"/build-record.json"]
	for platform := range platformNames {
		found, err := admit(raw, reference{tag: tag, public: version, revision: revision, channel: preview}, platform)
		if err != nil {
			t.Fatal(err)
		}
		if found.target != platform || len(found.parts) != 4 {
			t.Errorf("%s selected %s with %d files", platform, found.target, len(found.parts))
		}
		for _, file := range found.parts {
			if !strings.Contains(file.Name, platform) {
				t.Errorf("%s selected %s", platform, file.Name)
			}
		}
	}
}

func TestARecordNeedsBothTargetsInOrderAndNeitherMayBeInvalid(t *testing.T) {
	linux := func(f *fixture) map[string]any { return f.record["targets"].([]any)[0].(map[string]any) }
	for name, change := range map[string]func(*fixture){
		"one target": func(f *fixture) { f.record["targets"] = f.record["targets"].([]any)[1:] },
		"reversed":   func(f *fixture) { slices.Reverse(f.record["targets"].([]any)) },
		"repeated":   func(f *fixture) { f.record["targets"].([]any)[0] = f.target() },
		"unknown":    func(f *fixture) { linux(f)["target"] = "linux-aarch64" },
		"a third target": func(f *fixture) {
			f.record["targets"] = append(f.record["targets"].([]any), f.target())
		},
		// The Mac does not use this target, and still rejects the record.
		"invalid other target": func(f *fixture) { linux(f)["qualification"].(map[string]any)["outcome"] = "PARTIAL" },
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			change(f)
			f.publish()
			f.requireFailure(RecordInvalid, f.run("--dry-run"))
		})
	}
}

func TestAPersistentFailureStopsAfterBoundedAttemptsAndKeepsThePart(t *testing.T) {
	f := newFixture(t)
	f.writePart(f.baseBytes()[:10])
	for range attempts {
		f.faults[f.baseURL()] = append(f.faults[f.baseURL()], fault{err: errors.New("offline")})
	}
	f.requireFailure(SourceUnavailable, f.run("--download-only"))
	if len(f.faults[f.baseURL()]) != 0 {
		t.Errorf("unused faults: %d", len(f.faults[f.baseURL()]))
	}
	if raw, err := os.ReadFile(f.partPath()); err != nil || len(raw) != 10 {
		t.Errorf("part %d %v", len(raw), err)
	}
}

func TestAMissingFileIsNotRetried(t *testing.T) {
	f := newFixture(t)
	f.faults[f.baseURL()] = []fault{{status: 404}}
	f.requireFailure(BuildNotFound, f.run("--download-only"))
	f.faults[releases+"/v9.9.9/release-mapping.json"] = []fault{{status: 404}}
	f.requireFailure(BuildNotFound, f.run("--dry-run", "--version", "9.9.9"))
	f.faults[catalogURL] = []fault{{status: 403}}
	f.requireFailure(SourceUnavailable, f.run("--dry-run"))
	if len(f.pauses) != 0 {
		t.Errorf("retried %v", f.pauses)
	}
}

func TestWrongBytesAreRejectedAndRemoved(t *testing.T) {
	f := newFixture(t)
	raw := bytes.Clone(f.baseBytes())
	raw[5]++
	f.pages[f.baseURL()] = raw
	f.requireFailure(DownloadInvalid, f.run("--download-only"))
	for name := range f.downloads() {
		if strings.Contains(name, "runtime-base") {
			t.Errorf("kept %s", name)
		}
	}
}

func TestABodyLongerThanItsRecordIsRejected(t *testing.T) {
	f := newFixture(t)
	f.pages[f.baseURL()] = append(bytes.Clone(f.baseBytes()), 'x')
	f.requireFailure(DownloadInvalid, f.run("--download-only"))
}

func TestADamagedReusableFileIsDownloadedAgain(t *testing.T) {
	f := newFixture(t)
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	path := strings.TrimSuffix(f.partPath(), partSuffix)
	damaged := bytes.Clone(f.baseBytes())
	damaged[0]++
	if err := os.WriteFile(path, damaged, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	f.requireComplete()
}

func TestADownloadRemovesOnlyOtherKastDownloads(t *testing.T) {
	f := newFixture(t)
	directory := filepath.Dir(f.partPath())
	if err := os.MkdirAll(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	old, foreign := filepath.Join(directory, strings.Repeat("0", 64)+"-old.zip"), filepath.Join(directory, "notes.txt")
	for _, path := range []string{old, foreign} {
		if err := os.WriteFile(path, nil, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(old); !errors.Is(err, os.ErrNotExist) {
		t.Errorf("kept the old download: %v", err)
	}
	if _, err := os.Stat(foreign); err != nil {
		t.Error(err)
	}
}

func TestLinkedStorageIsRejectedWithoutFollowing(t *testing.T) {
	for _, link := range []string{".local", ".local/share", ".local/share/.kast-downloads"} {
		t.Run(link, func(t *testing.T) {
			f := newFixture(t)
			elsewhere := t.TempDir()
			path := filepath.Join(f.home, link)
			if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
				t.Fatal(err)
			}
			if err := os.Symlink(elsewhere, path); err != nil {
				t.Fatal(err)
			}
			f.requireFailure(StorageRejected, f.run("--download-only"))
			if entries, _ := os.ReadDir(elsewhere); len(entries) != 0 {
				t.Errorf("wrote through the link: %v", entries)
			}
		})
	}
}

func TestARelativeHomeIsRejectedBeforeAnyFile(t *testing.T) {
	for _, home := range []string{"", "relative"} {
		f := newFixture(t)
		t.Chdir(f.home)
		f.home = home
		f.requireFailure(StorageRejected, f.run("--download-only"))
		if entries, _ := os.ReadDir("."); len(entries) != 0 {
			t.Errorf("wrote %v", entries)
		}
	}
}

func TestALinkedPartIsNotFollowed(t *testing.T) {
	f := newFixture(t)
	victim := filepath.Join(t.TempDir(), "victim")
	if err := os.WriteFile(victim, []byte("keep"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(f.partPath()), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(victim, f.partPath()); err != nil {
		t.Fatal(err)
	}
	f.requireFailure(StorageRejected, f.run("--download-only"))
	if raw, _ := os.ReadFile(victim); string(raw) != "keep" {
		t.Errorf("victim %q", raw)
	}
}

func TestASecondDownloadDoesNotShareTheFirstOnesFiles(t *testing.T) {
	f := newFixture(t)
	directory := filepath.Dir(f.partPath())
	if err := os.MkdirAll(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	held, err := os.OpenFile(filepath.Join(directory, lockName), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	defer held.Close()
	if err := syscall.Flock(int(held.Fd()), syscall.LOCK_SH); err != nil {
		t.Fatal(err)
	}
	f.requireFailure(DownloadBusy, f.run("--download-only"))
}

func TestACancelledDownloadKeepsItsPartAndReportsInterruption(t *testing.T) {
	f := newFixture(t)
	f.writePart(f.baseBytes()[:10])
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	f.requireFailure(Interrupted, f.runContext(ctx, "--download-only"))
	if raw, err := os.ReadFile(f.partPath()); err != nil || len(raw) != 10 {
		t.Errorf("part %d %v", len(raw), err)
	}
}

func TestSelectorsChooseExactlyOneBuild(t *testing.T) {
	f := newFixture(t)
	first := f.entry(revision, tag, digest(f.pages[releases+"/"+tag+"/build-record.json"]))
	twin := f.entry(revision[:12]+strings.Repeat("b", 28), "developer-v0.0.122", strings.Repeat("c", 64))
	f.pages[catalogURL] = f.encode(map[string]any{"schemaVersion": 2, "builds": []any{first, twin}})
	if err := f.run("--dry-run", "--commit", revision); err != nil {
		t.Fatal(err)
	}
	f.requireFailure(CommitAmbiguous, f.run("--dry-run", "--commit", revision[:12]))
	f.requireFailure(BuildNotFound, f.run("--dry-run", "--commit", strings.Repeat("e", 40)))
	f.pages[catalogURL] = f.encode(map[string]any{"schemaVersion": 2, "builds": []any{}})
	f.requireFailure(BuildNotFound, f.run("--dry-run"))
}

func TestStableAndVersionFollowTheReleaseMapping(t *testing.T) {
	for _, arguments := range [][]string{{"--channel", "stable"}, {"--version", "1.2.3"}} {
		t.Run(arguments[0], func(t *testing.T) {
			f := newFixture(t)
			raw := f.pages[releases+"/"+tag+"/build-record.json"]
			f.pages[stableURL] = f.encode(map[string]any{"tag_name": "v1.2.3", "draft": false, "prerelease": false, "id": 7})
			f.pages[releases+"/v1.2.3/release-mapping.json"] = f.encode(map[string]any{
				"schemaVersion": 1, "publicVersion": "1.2.3", "buildRecordSha256": digest(raw)})
			f.pages[releases+"/v1.2.3/build-record.json"] = raw
			if err := f.run(append([]string{"--dry-run"}, arguments...)...); err != nil {
				t.Fatal(err)
			}
			if !strings.Contains(f.out.String(), "Kast 1.2.3 (stable) for macOS ARM64") {
				t.Errorf("output %q", f.out.String())
			}
			f.pages[releases+"/v1.2.3/release-mapping.json"] = f.encode(map[string]any{
				"schemaVersion": 1, "publicVersion": "1.2.4", "buildRecordSha256": digest(raw)})
			f.requireFailure(RecordInvalid, f.run(append([]string{"--dry-run"}, arguments...)...))
		})
	}
}

func TestAnOlderReleaseIsRejectedBeforeItsFilesAreRequested(t *testing.T) {
	for _, schema := range []int{2, 4, 5} {
		f := newFixture(t)
		f.record["schemaVersion"] = schema
		f.publish()
		f.requireFailure(ReleaseUnsupported, f.run("--download-only"))
		if len(f.calls) != 2 {
			t.Errorf("schema %d: calls %q", schema, f.calls)
		}
	}
}

func TestARecordThatDoesNotMatchItsDigestIsRejected(t *testing.T) {
	f := newFixture(t)
	f.pages[releases+"/"+tag+"/build-record.json"] = append(f.pages[releases+"/"+tag+"/build-record.json"], '\n')
	f.requireFailure(RecordInvalid, f.run("--dry-run"))
}

func TestInvalidRecordsAreRejected(t *testing.T) {
	change := func(path string, value any) func(*fixture) {
		return func(f *fixture) {
			keys, object := strings.Split(path, "."), f.target()
			if keys[0] == "record" {
				object, keys = f.record, keys[1:]
			}
			for _, key := range keys[:len(keys)-1] {
				object = object[key].(map[string]any)
			}
			object[keys[len(keys)-1]] = value
		}
	}
	for name, mutate := range map[string]func(*fixture){
		"unknown member":      change("record.extra", 1),
		"repository":          change("record.repository", "someone/kast"),
		"revision":            change("record.sourceRevision", "main"),
		"tag and version":     change("record.version", "0.0.124"),
		"no target":           change("record.targets", []any{}),
		"asset name":          change("bundle.name", "../kast.tar.gz"),
		"asset digest":        change("runtime.sha256", strings.Repeat("A", 64)),
		"empty asset":         change("bundle.bytes", 0),
		"oversized asset":     change("bundle.bytes", int64(2)<<30),
		"fractional size":     change("bundle.bytes", 1.5),
		"bundle evidence":     change("qualification.bundleSha256", strings.Repeat("0", 64)),
		"runtime evidence":    change("qualification.runtimeSha256", strings.Repeat("0", 64)),
		"incomplete":          change("qualification.outcome", "PARTIAL"),
		"base evidence":       change("qualification.composition.baseSha256", strings.Repeat("0", 64)),
		"missing base":        change("runtimeBase", nil),
		"missing composition": change("qualification.composition", nil),
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			mutate(f)
			f.publish()
			f.requireFailure(RecordInvalid, f.run("--dry-run"))
		})
	}
}

func TestInvalidCatalogsAreRejected(t *testing.T) {
	entry := func(f *fixture, key string, value any) map[string]any {
		found := f.entry(revision, tag, digest(f.pages[releases+"/"+tag+"/build-record.json"]))
		if key != "" {
			found[key] = value
		}
		return found
	}
	catalog := func(schema int, key string, value any, copies int) func(*fixture) []byte {
		return func(f *fixture) []byte {
			builds := make([]any, copies)
			for index := range builds {
				builds[index] = entry(f, key, value)
			}
			return f.encode(map[string]any{"schemaVersion": schema, "builds": builds})
		}
	}
	literal := func(text string) func(*fixture) []byte { return func(*fixture) []byte { return []byte(text) } }
	for name, page := range map[string]func(*fixture) []byte{
		"schema":         catalog(3, "", nil, 1),
		"duplicate name": literal(`{"schemaVersion":2,"schemaVersion":2,"builds":[]}`),
		"trailing data":  literal(`{"schemaVersion":2,"builds":[]} {}`),
		"case":           literal(`{"SchemaVersion":2,"builds":[]}`),
		"null builds":    literal(`{"schemaVersion":2,"builds":null}`),
		"tag":            catalog(2, "releaseTag", "v1/../x", 1),
		"revision":       catalog(2, "mainRevision", "main", 1),
		"digest":         catalog(2, "recordSha256", "x", 1),
		"unknown member": catalog(2, "extra", 1, 1),
		"repeated build": catalog(2, "", nil, 2),
		"too large": func(f *fixture) []byte {
			return append(catalog(2, "", nil, 1)(f), bytes.Repeat([]byte(" "), maximumRecord)...)
		},
	} {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			f.pages[catalogURL] = page(f)
			f.requireFailure(RecordInvalid, f.run("--dry-run"))
		})
	}
	f := newFixture(t)
	f.pages[catalogURL] = catalog(2, "", nil, 1)(f)
	if err := f.run("--dry-run"); err != nil {
		t.Errorf("the unchanged catalog is invalid: %v", err)
	}
}

func TestAnUnsupportedPlatformIsRejectedBeforeAnyRequest(t *testing.T) {
	f := newFixture(t)
	install := &Install{Home: f.home, Platform: "linux-aarch64", Transport: f.site, Out: &f.out, Log: &f.log}
	f.requireFailure(PlatformUnsupported, install.Run(context.Background(), []string{"--dry-run"}))
	if len(f.calls) != 0 {
		t.Errorf("calls %q", f.calls)
	}
}

func TestUsageIsRejectedBeforeAnyRequest(t *testing.T) {
	for _, arguments := range [][]string{
		{}, {"--json"}, {"--dry-run", "--download-only"}, {"--dry-run", "--dry-run"}, {"--dry-run", "--channel"},
		{"--dry-run", "--channel", "nightly"}, {"--dry-run", "--version", "1.2"}, {"--dry-run", "--commit", "abc"},
		{"--dry-run", "--channel", "preview", "--commit", revision}, {"--dry-run", "extra"}, {"--dry-run", "--unknown"},
	} {
		t.Run(strings.Join(arguments, " "), func(t *testing.T) {
			f := newFixture(t)
			f.requireFailure(UsageRejected, f.run(arguments...))
			if len(f.calls) != 0 {
				t.Errorf("calls %q", f.calls)
			}
		})
	}
}

func TestJSONPrintsOneDocumentAndNoProgress(t *testing.T) {
	f := newFixture(t)
	if err := f.run("--download-only", "--json"); err != nil {
		t.Fatal(err)
	}
	var document map[string]any
	if err := json.Unmarshal(f.out.Bytes(), &document); err != nil {
		t.Fatalf("%v in %q", err, f.out.String())
	}
	if document["type"] != "DOWNLOADED" || document["version"] != version || document["channel"] != "preview" ||
		document["target"] != "macos-aarch64" || document["revision"] != revision {
		t.Errorf("document %v", document)
	}
	if files := document["files"].([]any); len(files) != 4 || files[0].(map[string]any)["reused"] != false {
		t.Errorf("files %v", document["files"])
	}
	if f.log.Len() != 0 {
		t.Errorf("progress %q", f.log.String())
	}
	f.record["repository"] = "someone/kast"
	f.publish()
	f.requireFailure(RecordInvalid, f.run("--dry-run", "--json"))
	clear(document)
	if err := json.Unmarshal(f.out.Bytes(), &document); err != nil || document["type"] != "REJECTED" ||
		document["failure"] != "RECORD_INVALID" || document["next"] == "" {
		t.Errorf("document %v %v", document, err)
	}
}

func TestPlainProgressHasNoControlCharacters(t *testing.T) {
	f := newFixture(t)
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	if text := f.log.String(); strings.ContainsAny(text, "\r\x1b") || !strings.Contains(text, "Downloading") {
		t.Errorf("progress %q", text)
	}
}

func TestPlainProgressReportsOnlyReceivedFiles(t *testing.T) {
	f := newFixture(t)
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	if err := f.run("--download-only"); err != nil {
		t.Fatal(err)
	}
	if f.log.Len() != 0 || !strings.Contains(f.out.String(), "already here") {
		t.Errorf("progress %q, output %q", f.log.String(), f.out.String())
	}
}
