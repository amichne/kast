package selection

import (
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/envelope"
	"github.com/amichne/kast/frontend/internal/lifecycle/failure"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

func text(value string) *string { return &value }

func must[T any](value T, ok bool) T {
	if !ok {
		panic("literal rejected")
	}
	return value
}

func envelopeFor(t *testing.T, tag, revision string, layout int, handoff string) envelope.SelectionEnvelope {
	t.Helper()
	raw := `{"schemaVersion":1,"releaseTag":"` + tag + `","sourceRevision":"` + revision +
		`","recordSha256":"` + strings.Repeat("b", 64) + `","layout":` + string(rune('0'+layout)) +
		`,"handoff":[` + handoff + `],"controls":{"linux-x86_64":{"sha256":"` + strings.Repeat("c", 64) +
		`","bytes":10}}}`
	value, err := envelope.DecodeSelectionEnvelope([]byte(raw))
	if err != nil {
		t.Fatal(err)
	}
	return value
}

func code(rejected *failure.Failure) failure.Code {
	if rejected == nil {
		return 0
	}
	return rejected.Code()
}

func TestParseAdmitsExactlyOneValidSelector(t *testing.T) {
	cases := []struct {
		name  string
		flags Flags
		want  failure.Code
		got   Selector
	}{
		{"default", Flags{}, 0, StableChannel{}},
		{"stable", Flags{Channel: text("stable")}, 0, StableChannel{}},
		{"preview", Flags{Channel: text("preview")}, 0, PreviewChannel{}},
		{"version", Flags{Version: text("1.2.3")}, 0, PinnedVersion{must(record.ParseSemVer("1.2.3"))}},
		{"short commit", Flags{Commit: text("0123456789ab")}, 0, PinnedCommit{"0123456789ab"}},
		{"conflict", Flags{Channel: text("stable"), Version: text("1.2.3")}, failure.SelectorsConflict, nil},
		{"empty channel", Flags{Channel: text("")}, failure.SelectorInvalid, nil},
		{"unknown channel", Flags{Channel: text("nightly")}, failure.SelectorInvalid, nil},
		{"tagged version", Flags{Version: text("v1.2.3")}, failure.SelectorInvalid, nil},
		{"seven-character commit", Flags{Commit: text("0123456")}, failure.SelectorInvalid, nil},
		{"uppercase commit", Flags{Commit: text("0123456789AB")}, failure.SelectorInvalid, nil},
	}
	for _, c := range cases {
		selector, rejected := Parse(c.flags)
		if code(rejected) != c.want || (c.got != nil && selector != c.got) {
			t.Errorf("%s: %v, %v", c.name, selector, rejected)
		}
	}
}

func TestResolveDecidesLayoutBeforeAnythingRuns(t *testing.T) {
	stableTag := must(record.ParseReleaseTag("v1.2.3"))
	revisionA, revisionB := "a"+strings.Repeat("1", 39), "a"+strings.Repeat("2", 39)
	good := envelopeFor(t, "v1.2.3", revisionA, 2, "1")
	cases := []struct {
		name       string
		selector   Selector
		candidates Candidates
		want       failure.Code
		policy     record.Policy
	}{
		{"stable", StableChannel{}, Candidates{Tag: stableTag, Envelope: &good}, 0, record.PolicyTrackStable},
		{"pinned version", PinnedVersion{must(record.ParseSemVer("1.2.3"))}, Candidates{Tag: stableTag, Envelope: &good},
			0, record.PolicyPinnedBuild},
		{"old stable without envelope", StableChannel{}, Candidates{Tag: must(record.ParseReleaseTag("v0.52.0"))},
			failure.ReleaseLayoutUnsupported, 0},
		{"older layout", StableChannel{}, Candidates{Tag: stableTag,
			Envelope: ptr(envelopeFor(t, "v1.2.3", revisionA, 1, "1"))}, failure.ReleaseLayoutUnsupported, 0},
		{"no common handoff", StableChannel{}, Candidates{Tag: stableTag,
			Envelope: ptr(envelopeFor(t, "v1.2.3", revisionA, 2, "7"))}, failure.HandoffUnsupported, 0},
		{"envelope of another tag", StableChannel{}, Candidates{Tag: must(record.ParseReleaseTag("v1.2.4")), Envelope: &good},
			failure.CatalogInvalid, 0},
		{"preview newest", PreviewChannel{}, Candidates{Catalog: []envelope.SelectionEnvelope{
			envelopeFor(t, "developer-v0.0.9", revisionA, 2, "1"), envelopeFor(t, "developer-v0.0.8", revisionB, 2, "1")}},
			0, record.PolicyTrackMain},
		{"empty catalog", PreviewChannel{}, Candidates{}, failure.BuildUnavailable, 0},
		{"commit with no match", PinnedCommit{"a" + strings.Repeat("1", 5) + "000000"}, Candidates{}, failure.BuildUnavailable, 0},
		{"commit prefix of two", PinnedCommit{"a11111111111"}, Candidates{Catalog: []envelope.SelectionEnvelope{
			envelopeFor(t, "developer-v0.0.9", "a11111111111"+strings.Repeat("3", 28), 2, "1"),
			envelopeFor(t, "developer-v0.0.8", "a11111111111"+strings.Repeat("4", 28), 2, "1")}},
			failure.CommitAmbiguous, 0},
		{"pinned commit", PinnedCommit{revisionB[:12]}, Candidates{Catalog: []envelope.SelectionEnvelope{
			envelopeFor(t, "developer-v0.0.9", revisionA, 2, "1"), envelopeFor(t, "developer-v0.0.8", revisionB, 2, "1")}},
			0, record.PolicyPinnedBuild},
		{"stable tag in the preview catalog", PreviewChannel{}, Candidates{Catalog: []envelope.SelectionEnvelope{good}},
			failure.CatalogInvalid, 0},
	}
	for _, c := range cases {
		resolved, rejected := Resolve(c.selector, c.candidates, record.TargetLinuxX8664, []int64{1})
		if code(rejected) != c.want {
			t.Errorf("%s: %v", c.name, rejected)
			continue
		}
		if rejected == nil && (resolved.Selection.Identity().Policy() != c.policy || resolved.Handoff != 1) {
			t.Errorf("%s: resolved %+v", c.name, resolved)
		}
	}
	if _, rejected := Resolve(StableChannel{}, Candidates{Tag: stableTag, Envelope: &good}, record.TargetMacOSAArch64,
		[]int64{1}); code(rejected) != failure.BuildUnavailable {
		t.Errorf("a missing host control resolved: %v", rejected)
	}
	newest := envelopeFor(t, "v1.2.3", revisionA, 2, "1,3,2")
	if resolved, _ := Resolve(StableChannel{}, Candidates{Tag: stableTag, Envelope: &newest}, record.TargetLinuxX8664,
		[]int64{1, 2}); resolved.Handoff != 2 {
		t.Errorf("handoff %d, want the highest in common", resolved.Handoff)
	}
}

func ptr[T any](value T) *T { return &value }

func TestHostTarget(t *testing.T) {
	for _, c := range []struct {
		goos, goarch string
		want         failure.Code
	}{{"darwin", "arm64", 0}, {"linux", "amd64", 0}, {"darwin", "amd64", failure.PlatformUnsupported},
		{"linux", "arm64", failure.PlatformUnsupported}, {"windows", "amd64", failure.PlatformUnsupported}} {
		if _, rejected := HostTarget(c.goos, c.goarch); code(rejected) != c.want {
			t.Errorf("%s/%s: %v", c.goos, c.goarch, rejected)
		}
	}
}
