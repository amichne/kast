package envelope

import (
	"bytes"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
	"github.com/amichne/kast/frontend/internal/lifecycle/record"
)

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func digest(t *testing.T, c string) record.Digest {
	t.Helper()
	value, ok := record.ParseDigest(strings.Repeat(c, 64))
	if !ok {
		t.Fatal(c)
	}
	return value
}

func mutate(t *testing.T, raw []byte, old, replacement string) []byte {
	t.Helper()
	if !bytes.Contains(raw, []byte(old)) {
		t.Fatalf("fixture lacks %q", old)
	}
	return bytes.Replace(raw, []byte(old), []byte(replacement), 1)
}

func wantCause(t *testing.T, name string, err error, want codec.Cause) {
	t.Helper()
	if cause, ok := codec.CauseOf(err); !ok || cause != want {
		t.Errorf("%s: got %v, want %v", name, err, want)
	}
}

func TestEnvelopeDecodesNamedMembers(t *testing.T) {
	envelope, err := DecodeSelectionEnvelope(fixture(t, "lifecycle-envelope.json"))
	if err != nil {
		t.Fatal(err)
	}
	if envelope.ReleaseTag().String() != "v1.2.3" || envelope.Layout() != SupportedLayout ||
		envelope.RecordSHA256() != digest(t, "b") || len(envelope.Handoff()) != 1 {
		t.Fatalf("decoded %+v", envelope)
	}
	control, ok := envelope.Control(record.TargetLinuxX8664)
	if !ok || control.SHA256() != digest(t, "c") || control.Bytes() != 9000000 {
		t.Fatalf("linux control %+v", control)
	}
}

func TestEnvelopeToleratesUnknownMembersAtEveryLevel(t *testing.T) {
	envelope, err := DecodeSelectionEnvelope(fixture(t, "lifecycle-envelope-newer.json"))
	if err != nil {
		t.Fatalf("a newer envelope broke this binary: %v", err)
	}
	if envelope.Layout() != 3 || !slices.Equal(envelope.Handoff(), []int64{2, 1}) {
		t.Fatalf("decoded %+v", envelope)
	}
	if _, ok := envelope.Control(record.TargetMacOSAArch64); ok {
		t.Fatal("a missing target appeared")
	}
	if _, ok := envelope.Control(record.TargetLinuxX8664); !ok {
		t.Fatal("a known target with unknown members was dropped")
	}
}

func TestEnvelopeStillRejectsWhatToleranceDoesNotCover(t *testing.T) {
	raw := fixture(t, "lifecycle-envelope.json")
	decode := func(b []byte) error { _, err := DecodeSelectionEnvelope(b); return err }
	cases := []struct {
		name, old, new string
		want           codec.Cause
	}{
		{"duplicate member", `"layout":2`, `"layout":2,"layout":2`, codec.DuplicateMember},
		{"newer major", `"schemaVersion":1`, `"schemaVersion":2`, codec.SchemaUnsupported},
		{"missing layout", `"layout":2,`, ``, codec.MissingMember},
		{"null handoff", `"handoff":[1]`, `"handoff":null`, codec.NullMember},
		{"duplicate handoff", `"handoff":[1]`, `"handoff":[1,1]`, codec.InvalidValue},
		{"zero layout", `"layout":2`, `"layout":0`, codec.InvalidValue},
		{"invalid digest", `"recordSha256":"bbbb`, `"recordSha256":"BBBB`, codec.InvalidValue},
		{"known target without digest", `{"sha256":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc","bytes":9000000}`,
			`{"bytes":9000000}`, codec.MissingMember},
		{"zero control size", `"bytes":9000000`, `"bytes":0`, codec.InvalidValue},
		{"invalid tag", `"releaseTag":"v1.2.3"`, `"releaseTag":"latest"`, codec.InvalidValue},
	}
	for _, c := range cases {
		wantCause(t, c.name, decode(mutate(t, raw, c.old, c.new)), c.want)
	}
	wantCause(t, "trailing data", decode(append(bytes.TrimRight(bytes.Clone(raw), "\n"), '1')), codec.TrailingData)
}

func handoffValue(t *testing.T) Handoff {
	t.Helper()
	public, _ := record.ParseSemVer("1.2.3")
	identity, ok := record.NewIdentity(record.StableTag(public), record.PolicyTrackStable, &public)
	revision, ok2 := record.ParseRevision(strings.Repeat("1", 40))
	if !ok || !ok2 {
		t.Fatal("literal identity rejected")
	}
	handoff, ok := NewHandoff(1, OperationUpgrade, NewSelection(identity, revision), digest(t, "b"), digest(t, "c"),
		OutputJSON)
	if !ok {
		t.Fatal("handoff rejected")
	}
	return handoff
}

func TestHandoffEncodesCanonicallyAndDecodesStrictly(t *testing.T) {
	raw := fixture(t, "handoff.json")
	handoff := handoffValue(t)
	if !bytes.Equal(handoff.Encode(), raw) {
		t.Fatalf("encoding differs:\n%s\n%s", handoff.Encode(), raw)
	}
	decoded, err := DecodeHandoff(raw)
	if err != nil || decoded != handoff {
		t.Fatalf("decoded %+v, %v", decoded, err)
	}
	decode := func(b []byte) error { _, err := DecodeHandoff(b); return err }
	cases := []struct {
		name, old, new string
		want           codec.Cause
	}{
		{"unknown member", `"output":"JSON"`, `"output":"JSON","extra":1`, codec.UnknownMember},
		{"unknown selection member", `"sourceRevision"`, `"mainRevision":null,"sourceRevision"`, codec.UnknownMember},
		{"unsupported schema", `"schemaVersion":1`, `"schemaVersion":2`, codec.SchemaUnsupported},
		{"unknown operation", `"operation":"UPGRADE"`, `"operation":"UNINSTALL"`, codec.InvalidValue},
		{"unknown output", `"output":"JSON"`, `"output":"YAML"`, codec.InvalidValue},
		{"absent public version", `"publicVersion":"1.2.3",`, ``, codec.MissingMember},
		{"null public version on stable tag", `"publicVersion":"1.2.3"`, `"publicVersion":null`, codec.InvalidValue},
		{"duplicate member", `"output":"JSON"`, `"output":"JSON","output":"TEXT"`, codec.DuplicateMember},
		{"null selection", `"selection":{"releaseTag":"v1.2.3","policy":"TRACK_STABLE","publicVersion":"1.2.3","sourceRevision":"1111111111111111111111111111111111111111"}`,
			`"selection":null`, codec.NullMember},
	}
	for _, c := range cases {
		wantCause(t, c.name, decode(mutate(t, raw, c.old, c.new)), c.want)
	}
	if _, ok := NewHandoff(2, OperationInstall, handoff.Selection(), digest(t, "b"), digest(t, "c"), OutputText); ok {
		t.Fatal("a handoff at an unwritten schema was built")
	}
}
