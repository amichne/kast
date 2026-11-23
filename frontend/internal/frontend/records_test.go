package frontend

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// The shared fixtures in testdata/installed-records are the installer's exact bytes. test-managed-build-record.py
// decodes them with the installer's strict decoder and requires its writer to re-encode them byte for byte, so drift
// on either side fails.

func sharedRecord(t *testing.T, name string) []byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", "installed-records", name))
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func digestOf(t *testing.T, text string) Digest {
	t.Helper()
	var digest Digest
	if decoded, err := hex.DecodeString(text); err != nil || len(decoded) != len(digest) {
		t.Fatalf("not a digest: %q", text)
	} else {
		copy(digest[:], decoded)
	}
	return digest
}

const (
	fixtureArchive  = "4444444444444444444444444444444444444444444444444444444444444444"
	fixtureManifest = "25a1d0d396f67b0db78bdc3f3954b893ea47db79ee2d25a73003c8ade95f19f7"
)

// stableSelection is product-selection-stable.json as explicit values.
func stableSelection(t *testing.T) productSelection {
	publicVersion := "1.2.3"
	return productSelection{
		ReleaseTag:            "v1.2.3",
		PublicVersion:         &publicVersion,
		Version:               "0.0.123",
		SourceRevision:        Revision(strings.Repeat("a", 40)),
		SourceTree:            Revision(strings.Repeat("b", 40)),
		BuildRecordSHA256:     digestOf(t, strings.Repeat("1", 64)),
		Target:                TargetMacOSAArch64,
		BundleSHA256:          digestOf(t, strings.Repeat("2", 64)),
		QualificationSHA256:   digestOf(t, strings.Repeat("3", 64)),
		RuntimeRoot:           "/home/kast/.local/share/kast-workspaces/runtimes/" + fixtureArchive,
		RuntimeArchiveSHA256:  digestOf(t, fixtureArchive),
		RuntimeManifestSHA256: digestOf(t, fixtureManifest),
		Policy:                PolicyTrackStable,
		SchemaVersion:         1,
	}
}

// previewSelection is product-selection-preview.json: an explicit null public version.
func previewSelection(t *testing.T) productSelection {
	selection := stableSelection(t)
	selection.ReleaseTag = "developer-v0.0.123"
	selection.PublicVersion = nil
	selection.Target = TargetLinuxX8664
	selection.Policy = PolicyTrackMain
	return selection
}

// encodeRecord encodes a record as the installer does: no whitespace, no HTML escaping, one trailing newline.
func encodeRecord(t *testing.T, record any) []byte {
	t.Helper()
	var encoded bytes.Buffer
	encoder := json.NewEncoder(&encoded)
	encoder.SetEscapeHTML(false)
	if err := encoder.Encode(record); err != nil {
		t.Fatal(err)
	}
	return encoded.Bytes()
}

func TestTheInstallersSelectionsDecodeToExplicitValuesAndEncodeToItsExactBytes(t *testing.T) {
	for name, want := range map[string]productSelection{
		"product-selection-stable.json":  stableSelection(t),
		"product-selection-preview.json": previewSelection(t),
	} {
		raw := sharedRecord(t, name)
		got, failure := decodeProductSelection(raw)
		if failure != nil || !reflect.DeepEqual(got, want) {
			t.Fatalf("%s: decoded %+v %v, want %+v", name, got, failure, want)
		}
		if encoded := encodeRecord(t, want); !bytes.Equal(encoded, raw) {
			t.Fatalf("%s: encoded\n%s\nwant the installer's\n%s", name, encoded, raw)
		}
	}
}

func TestTheInstallersRuntimeReceiptDecodesToExplicitValuesAndEncodesToItsExactBytes(t *testing.T) {
	raw := sharedRecord(t, "runtime.json")
	want := runtimeReceipt{digestOf(t, fixtureArchive), digestOf(t, fixtureManifest), 1}
	got, failure := decodeRuntimeReceipt(raw)
	if failure != nil || got != want {
		t.Fatalf("decoded %+v %v, want %+v", got, failure, want)
	}
	if encoded := encodeRecord(t, want); !bytes.Equal(encoded, raw) {
		t.Fatalf("encoded\n%s\nwant the installer's\n%s", encoded, raw)
	}
}

// member replaces one member's value in a record's text, keeping every other byte.
func member(t *testing.T, raw []byte, key, value string) []byte {
	t.Helper()
	var members map[string]json.RawMessage
	if json.Unmarshal(raw, &members) != nil {
		t.Fatalf("fixture is not an object")
	}
	old := `"` + key + `":` + string(members[key])
	if !bytes.Contains(raw, []byte(old)) {
		t.Fatalf("fixture has no member %s", key)
	}
	return bytes.Replace(raw, []byte(old), []byte(`"`+key+`":`+value), 1)
}

// withoutMember drops one member and keeps every other member's exact value.
func withoutMember(t *testing.T, raw []byte, key string) []byte {
	t.Helper()
	var members map[string]json.RawMessage
	if json.Unmarshal(raw, &members) != nil {
		t.Fatalf("fixture is not an object")
	}
	delete(members, key)
	encoded, err := json.Marshal(members)
	if err != nil {
		t.Fatal(err)
	}
	return encoded
}

func TestEverySelectionRejectionIsItsTypedCause(t *testing.T) {
	stable := sharedRecord(t, "product-selection-stable.json")
	invalid := func(cause DecodeCause, field RecordField) RuntimeFailure {
		return *recordInvalid(RecordProductSelection, cause, field)
	}
	for _, c := range []struct {
		name string
		raw  []byte
		want RuntimeFailure
	}{
		{"oversized", bytes.Repeat([]byte(" "), maximumRecordBytes+1), invalid(DecodeTooLarge, "")},
		{"empty", nil, invalid(DecodeSyntax, "")},
		{"truncated", stable[:40], invalid(DecodeSyntax, "")},
		{"array", []byte("[]\n"), invalid(DecodeNotObject, "")},
		{"string", []byte(`"selection"`), invalid(DecodeNotObject, "")},
		{"duplicate key", append([]byte(`{"policy":"TrackMain",`), stable[1:]...), invalid(DecodeDuplicateKey, "")},
		{"second document", append(bytes.Clone(stable), "{}"...), invalid(DecodeTrailingData, "")},
		{"trailing text", append(bytes.Clone(stable), "x"...), invalid(DecodeTrailingData, "")},
		{"unknown field", append([]byte(`{"baseSha256":null,`), stable[1:]...), invalid(DecodeUnknownField, "")},
		{"schema text", member(t, stable, "schemaVersion", `"1"`), invalid(DecodeFieldValue, "schemaVersion")},
		{"schema null", member(t, stable, "schemaVersion", "null"), invalid(DecodeFieldValue, "schemaVersion")},
		{"schema missing", withoutMember(t, stable, "schemaVersion"), invalid(DecodeMissingField, "schemaVersion")},
		{"schema newer", member(t, stable, "schemaVersion", "2"),
			*recordSchemaUnsupported(RecordProductSelection, 2)},
		// A newer writer's fields are reported as its schema, never as unknown fields.
		{"newer schema with new fields", append([]byte(`{"baseSha256":null,`), member(t, stable, "schemaVersion", "2")[1:]...),
			*recordSchemaUnsupported(RecordProductSelection, 2)},
		{"unknown target", member(t, stable, "target", `"windows-x86_64"`), invalid(DecodeFieldValue, "target")},
		{"unknown policy", member(t, stable, "policy", `"TRACK_STABLE"`), invalid(DecodeFieldValue, "policy")},
		{"uppercase digest", member(t, stable, "buildRecordSha256", `"`+strings.Repeat("A", 64)+`"`),
			invalid(DecodeFieldValue, "buildRecordSha256")},
		{"short digest", member(t, stable, "runtimeManifestSha256", `"abc"`),
			invalid(DecodeFieldValue, "runtimeManifestSha256")},
		{"numeric digest", member(t, stable, "runtimeArchiveSha256", "4"), invalid(DecodeFieldValue, "runtimeArchiveSha256")},
		{"zero revision", member(t, stable, "sourceRevision", `"`+strings.Repeat("0", 40)+`"`),
			invalid(DecodeFieldValue, "sourceRevision")},
		{"long revision", member(t, stable, "sourceTree", `"`+strings.Repeat("b", 64)+`"`),
			invalid(DecodeFieldValue, "sourceTree")},
		{"numeric tag", member(t, stable, "releaseTag", "1"), invalid(DecodeFieldValue, "releaseTag")},
		{"empty tag", member(t, stable, "releaseTag", `""`), invalid(DecodeFieldValue, "releaseTag")},
		{"empty public version", member(t, stable, "publicVersion", `""`), invalid(DecodeFieldValue, "publicVersion")},
		{"overlong version", member(t, stable, "version", `"`+strings.Repeat("9", maximumTextBytes+1)+`"`),
			invalid(DecodeFieldValue, "version")},
		{"relative runtime root", member(t, stable, "runtimeRoot", `"kast-workspaces/runtimes/x"`),
			invalid(DecodeFieldValue, "runtimeRoot")},
	} {
		got, failure := decodeProductSelection(c.raw)
		if failure == nil || *failure != c.want || !reflect.DeepEqual(got, productSelection{}) {
			t.Fatalf("%s: got %+v %v, want %v", c.name, got, failure, &c.want)
		}
	}
}

func TestEveryAbsentOrNullSelectionFieldIsRejectedByNameExceptTheNullablePublicVersion(t *testing.T) {
	stable := sharedRecord(t, "product-selection-stable.json")
	for _, field := range declaredFields(reflect.TypeFor[productSelection]()) {
		if _, failure := decodeProductSelection(withoutMember(t, stable, field)); failure == nil ||
			*failure != *recordInvalid(RecordProductSelection, DecodeMissingField, RecordField(field)) {
			t.Fatalf("absent %s: %v", field, failure)
		}
		_, failure := decodeProductSelection(member(t, stable, field, "null"))
		switch {
		case field == "publicVersion" && failure != nil:
			t.Fatalf("an explicit null public version was rejected: %v", failure)
		case field != "publicVersion" && field != "schemaVersion" &&
			(failure == nil || *failure != *recordInvalid(RecordProductSelection, DecodeFieldValue, RecordField(field))):
			t.Fatalf("null %s: %v", field, failure)
		}
	}
}

func TestEveryRuntimeReceiptRejectionIsItsTypedCause(t *testing.T) {
	receipt := sharedRecord(t, "runtime.json")
	for _, c := range []struct {
		name string
		raw  []byte
		want RuntimeFailure
	}{
		{"unknown field", append([]byte(`{"runtimeRoot":"/r",`), receipt[1:]...),
			*recordInvalid(RecordRuntimeReceipt, DecodeUnknownField, "")},
		{"archive missing", withoutMember(t, receipt, "archiveSha256"),
			*recordInvalid(RecordRuntimeReceipt, DecodeMissingField, "archiveSha256")},
		{"manifest null", member(t, receipt, "manifestSha256", "null"),
			*recordInvalid(RecordRuntimeReceipt, DecodeFieldValue, "manifestSha256")},
		{"manifest prefixed", member(t, receipt, "manifestSha256", `"sha256:`+fixtureManifest+`"`),
			*recordInvalid(RecordRuntimeReceipt, DecodeFieldValue, "manifestSha256")},
		{"schema newer", member(t, receipt, "schemaVersion", "2"), *recordSchemaUnsupported(RecordRuntimeReceipt, 2)},
	} {
		got, failure := decodeRuntimeReceipt(c.raw)
		if failure == nil || *failure != c.want || got != (runtimeReceipt{}) {
			t.Fatalf("%s: got %+v %v, want %v", c.name, got, failure, &c.want)
		}
	}
}

func TestEnumerationsEncodeOnlyTheirNamedValues(t *testing.T) {
	if _, err := json.Marshal(Target(0)); err == nil {
		t.Fatal("an unnamed target encoded")
	}
	if _, err := json.Marshal(Policy(9)); err == nil {
		t.Fatal("an unnamed policy encoded")
	}
	for name, want := range map[string]Target{"macos-aarch64": TargetMacOSAArch64, "linux-x86_64": TargetLinuxX8664} {
		if got, ok := parseTarget(name); !ok || got != want || got.String() != name {
			t.Fatalf("target %s: %v %v", name, got, ok)
		}
	}
	for _, name := range []string{"", "macos-x86_64", "linux-aarch64", "-"} {
		if _, ok := parseTarget(name); ok {
			t.Fatalf("an unsupported platform %q was admitted", name)
		}
	}
}
