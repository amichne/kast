package record

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
)

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatal(err)
	}
	return raw
}

func digest(t *testing.T, c string) Digest {
	t.Helper()
	value, ok := ParseDigest(strings.Repeat(c, 64))
	if !ok {
		t.Fatalf("digest %q", c)
	}
	return value
}

func version(t *testing.T, text string) VersionID {
	t.Helper()
	value, ok := ParseVersionID(text)
	if !ok {
		t.Fatalf("version %q", text)
	}
	return value
}

func revision(t *testing.T, c string) Revision {
	t.Helper()
	value, ok := ParseRevision(strings.Repeat(c, 40))
	if !ok {
		t.Fatalf("revision %q", c)
	}
	return value
}

// expectCause asserts a rejection and its exact cause.
func expectCause(t *testing.T, name string, err error, want codec.Cause) {
	t.Helper()
	cause, ok := codec.CauseOf(err)
	if !ok || cause != want {
		t.Errorf("%s: got %v, want %v", name, err, want)
	}
}

func replace(t *testing.T, raw []byte, old, replacement string) []byte {
	t.Helper()
	if !bytes.Contains(raw, []byte(old)) {
		t.Fatalf("fixture lacks %q", old)
	}
	return bytes.Replace(raw, []byte(old), []byte(replacement), 1)
}

// rejection is one mutated document and the cause its decoder must report.
type rejection struct {
	name string
	old  string
	new  string
	want codec.Cause
}

func checkRejections(t *testing.T, raw []byte, decode func([]byte) error, cases []rejection) {
	t.Helper()
	for _, c := range cases {
		expectCause(t, c.name, decode(replace(t, raw, c.old, c.new)), c.want)
	}
}

// commonRejections apply to every strict record: an unknown member, a duplicate member, trailing data, a document
// that is not an object, invalid UTF-8, and an unsupported schema version.
func commonRejections(t *testing.T, raw []byte, schema string, decode func([]byte) error) {
	t.Helper()
	trimmed := bytes.TrimRight(raw, "\n")
	open := `{"schemaVersion":` + schema
	cases := map[string]struct {
		document []byte
		want     codec.Cause
	}{
		"unknown member":   {replace(t, raw, open, open+`,"extra":1`), codec.UnknownMember},
		"case variant":     {replace(t, raw, open, open+`,"SchemaVersion":`+schema), codec.UnknownMember},
		"duplicate member": {replace(t, raw, open, open+`,"schemaVersion":`+schema), codec.DuplicateMember},
		"trailing data":    {append(bytes.Clone(trimmed), []byte(` {}`)...), codec.TrailingData},
		"second document":  {append(bytes.Clone(raw), raw...), codec.TrailingData},
		"array":            {[]byte(`[` + string(trimmed) + `]`), codec.NotObject},
		"invalid utf-8":    {append(bytes.Clone(trimmed[:len(trimmed)-1]), []byte(",\"x\":\"\xff\"}")...), codec.Malformed},
		"truncated":        {trimmed[:len(trimmed)-1], codec.Malformed},
		"newer schema":     {replace(t, raw, open, `{"schemaVersion":99`), codec.SchemaUnsupported},
		"string schema":    {replace(t, raw, open, `{"schemaVersion":"`+schema+`"`), codec.InvalidValue},
		"fraction schema":  {replace(t, raw, open, `{"schemaVersion":`+schema+`.0`), codec.InvalidValue},
		"missing schema":   {replace(t, raw, open+",", `{`), codec.MissingMember},
		"null schema":      {replace(t, raw, open, `{"schemaVersion":null`), codec.NullMember},
	}
	for name, c := range cases {
		expectCause(t, name, decode(c.document), c.want)
	}
}

func TestPayloadManifestDecodesAndEncodesCanonically(t *testing.T) {
	raw := fixture(t, "installation.json")
	manifest, err := DecodePayloadManifest(raw)
	if err != nil {
		t.Fatal(err)
	}
	if manifest.Version() != version(t, "0123456789abcdef") || len(manifest.Files()) != 4 ||
		len(manifest.Links()) != 1 {
		t.Fatalf("decoded %+v", manifest)
	}
	built, err := NewPayloadManifest(version(t, "0123456789abcdef"), []PayloadFile{
		{MustRelativePath("bin/kast"), digest(t, "a"), 10, ModeExecutable},
		{MustRelativePath("share/kast/build-record.json"), digest(t, "b"), 20, ModeReadOnly},
		{MustRelativePath("share/kast/layout-v2"), digest(t, "c"), 2, ModeReadOnly},
		{MustRelativePath("share/kast/product-selection.json"), digest(t, "d"), 30, ModeReadOnly},
	}, []PayloadLink{{MustRelativePath("share/kast/libexec/kast-management"), LinkTarget{"../../../bin/kast"}}})
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(built.Encode(), raw) {
		t.Fatalf("encoding differs:\n%s\n%s", built.Encode(), raw)
	}
	empty, err := NewPayloadManifest(version(t, "0123456789abcdef"), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if want := `{"schemaVersion":1,"version":"0123456789abcdef","files":[],"links":[]}` + "\n"; string(empty.Encode()) != want {
		t.Fatalf("empty manifest encodes %s", empty.Encode())
	}
}

func TestPayloadManifestRejections(t *testing.T) {
	raw := fixture(t, "installation.json")
	decode := func(b []byte) error { _, err := DecodePayloadManifest(b); return err }
	commonRejections(t, raw, "1", decode)
	checkRejections(t, raw, decode, []rejection{
		{"unsorted files", `"path":"bin/kast"`, `"path":"zzz"`, codec.InvalidValue},
		{"self entry", `"path":"share/kast/layout-v2"`, `"path":"share/kast/installation.json"`, codec.InvalidValue},
		{"absolute path", `"path":"bin/kast"`, `"path":"/bin/kast"`, codec.InvalidValue},
		{"climbing path", `"path":"bin/kast"`, `"path":"../bin/kast"`, codec.InvalidValue},
		{"unclean path", `"path":"bin/kast"`, `"path":"bin//kast"`, codec.InvalidValue},
		{"dot element", `"path":"bin/kast"`, `"path":"./bin"`, codec.InvalidValue},
		{"unknown mode", `"mode":"0555"`, `"mode":"0755"`, codec.InvalidValue},
		{"negative size", `"bytes":10`, `"bytes":-1`, codec.InvalidValue},
		{"escaping link", `"target":"../../../bin/kast"`, `"target":"../../../../etc"`, codec.InvalidValue},
		{"absolute link", `"target":"../../../bin/kast"`, `"target":"/bin/kast"`, codec.InvalidValue},
		{"link over file", `"path":"share/kast/libexec/kast-management"`, `"path":"bin/kast"`, codec.InvalidValue},
		{"entry beneath file", `"path":"share/kast/libexec/kast-management"`, `"path":"bin/kast/x"`, codec.InvalidValue},
		{"uppercase digest", `"sha256":"aaaa`, `"sha256":"AAAA`, codec.InvalidValue},
		{"version not hex", `"version":"0123456789abcdef"`, `"version":"0123456789abcdeg"`, codec.InvalidValue},
		{"unknown file member", `"mode":"0555"`, `"mode":"0555","owner":0`, codec.UnknownMember},
		{"null links", `"links":[{"path":"share/kast/libexec/kast-management","target":"../../../bin/kast"}]`,
			`"links":null`, codec.NullMember},
		{"absent links", `,"links":[{"path":"share/kast/libexec/kast-management","target":"../../../bin/kast"}]`, ``,
			codec.MissingMember},
	})
}

func TestProductSelectionDecodesAndEncodesCanonically(t *testing.T) {
	stableRaw := fixture(t, "product-selection-stable.json")
	public, _ := ParseSemVer("1.2.3")
	identity, ok := NewIdentity(StableTag(public), PolicyTrackStable, &public)
	if !ok {
		t.Fatal("stable identity")
	}
	base := digest(t, "9")
	stable := NewProductSelection(version(t, "0123456789abcdef"), TargetLinuxX8664, digest(t, "b"), identity,
		revision(t, "1"), digest(t, "e"), digest(t, "f"), &base)
	if !bytes.Equal(stable.Encode(), stableRaw) {
		t.Fatalf("stable encoding differs:\n%s\n%s", stable.Encode(), stableRaw)
	}
	decoded, err := DecodeProductSelection(stableRaw)
	if err != nil || decoded != stable && !bytes.Equal(decoded.Encode(), stableRaw) {
		t.Fatalf("decoded %+v, %v", decoded, err)
	}
	if bound, ok := decoded.Base(); !ok || bound != base {
		t.Fatal("base binding lost")
	}

	previewRaw := fixture(t, "product-selection-preview.json")
	build, _ := ParseDeveloperVersion("0.0.42")
	previewIdentity, ok := NewIdentity(DeveloperTag(build), PolicyTrackMain, nil)
	if !ok {
		t.Fatal("preview identity")
	}
	preview := NewProductSelection(version(t, "fedcba9876543210"), TargetMacOSAArch64, digest(t, "b"),
		previewIdentity, revision(t, "1"), digest(t, "e"), digest(t, "f"), nil)
	if !bytes.Equal(preview.Encode(), previewRaw) {
		t.Fatalf("preview encoding differs:\n%s\n%s", preview.Encode(), previewRaw)
	}
	decodedPreview, err := DecodeProductSelection(previewRaw)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := decodedPreview.Base(); ok || decodedPreview.Identity().PublicVersion() != nil {
		t.Fatal("explicit nulls became values")
	}
}

func TestProductSelectionRejections(t *testing.T) {
	stable := fixture(t, "product-selection-stable.json")
	preview := fixture(t, "product-selection-preview.json")
	decode := func(b []byte) error { _, err := DecodeProductSelection(b); return err }
	commonRejections(t, stable, "2", decode)
	expectCause(t, "legacy version 1", decode(replace(t, stable, `"schemaVersion":2`, `"schemaVersion":1`)),
		codec.SchemaUnsupported)
	checkRejections(t, stable, decode, []rejection{
		{"absent public version", `"publicVersion":"1.2.3",`, ``, codec.MissingMember},
		{"absent base", `,"baseSha256":"9999999999999999999999999999999999999999999999999999999999999999"`, ``,
			codec.MissingMember},
		{"stable without public version", `"publicVersion":"1.2.3"`, `"publicVersion":null`, codec.InvalidValue},
		{"public version differs", `"publicVersion":"1.2.3"`, `"publicVersion":"1.2.4"`, codec.InvalidValue},
		{"stable tag tracking main", `"policy":"TRACK_STABLE"`, `"policy":"TRACK_MAIN"`, codec.InvalidValue},
		{"unknown policy", `"policy":"TRACK_STABLE"`, `"policy":"TrackStable"`, codec.InvalidValue},
		{"unknown target", `"target":"linux-x86_64"`, `"target":"linux-aarch64"`, codec.InvalidValue},
		{"zero revision", `"sourceRevision":"1111111111111111111111111111111111111111"`,
			`"sourceRevision":"0000000000000000000000000000000000000000"`, codec.InvalidValue},
		{"short revision", `"sourceRevision":"1111111111111111111111111111111111111111"`,
			`"sourceRevision":"111111111111"`, codec.InvalidValue},
		{"leading zero version", `"releaseTag":"v1.2.3"`, `"releaseTag":"v01.2.3"`, codec.InvalidValue},
		{"legacy runtime root", `"baseSha256"`, `"runtimeRoot":"/x","baseSha256"`, codec.UnknownMember},
		{"base not a digest", `"baseSha256":"9999`, `"baseSha256":"zzzz`, codec.InvalidValue},
		{"null target", `"target":"linux-x86_64"`, `"target":null`, codec.NullMember},
	})
	checkRejections(t, preview, decode, []rejection{
		{"preview with public version", `"publicVersion":null`, `"publicVersion":"0.0.42"`, codec.InvalidValue},
		{"preview tracking stable", `"policy":"TRACK_MAIN"`, `"policy":"TRACK_STABLE"`, codec.InvalidValue},
		{"preview zero build", `"releaseTag":"developer-v0.0.42"`, `"releaseTag":"developer-v0.0.0"`,
			codec.InvalidValue},
	})
	for _, policy := range []string{"PINNED_BUILD", "LOCAL"} {
		if err := decode(replace(t, preview, `"policy":"TRACK_MAIN"`, `"policy":"`+policy+`"`)); err != nil {
			t.Errorf("preview with %s: %v", policy, err)
		}
	}
	expectCause(t, "stable local", decode(replace(t, stable, `"policy":"TRACK_STABLE"`, `"policy":"LOCAL"`)),
		codec.InvalidValue)
}

func TestRuntimeReceiptDecodesBothMemberOrdersAndEncodesCanonically(t *testing.T) {
	raw := fixture(t, "runtime.json")
	receipt := NewRuntimeReceipt(digest(t, "e"), digest(t, "f"))
	if !bytes.Equal(receipt.Encode(), raw) {
		t.Fatalf("encoding differs:\n%s\n%s", receipt.Encode(), raw)
	}
	for _, name := range []string{"runtime.json", "runtime-python.json"} {
		decoded, err := DecodeRuntimeReceipt(fixture(t, name))
		if err != nil || decoded != receipt {
			t.Fatalf("%s: %+v, %v", name, decoded, err)
		}
	}
	decode := func(b []byte) error { _, err := DecodeRuntimeReceipt(b); return err }
	commonRejections(t, raw, "1", decode)
	checkRejections(t, raw, decode, []rejection{
		{"missing manifest", `,"manifestSha256":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"`, ``,
			codec.MissingMember},
		{"null archive", `"archiveSha256":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"`,
			`"archiveSha256":null`, codec.NullMember},
	})
}

func TestRuntimeManifestBindingNeedsEveryDigest(t *testing.T) {
	manifestBytes := []byte(`{"kind":"KAST_RUNTIME_V1"}`)
	manifest, err := BindRuntimeManifest(manifestBytes)
	if err != nil {
		t.Fatal(err)
	}
	entry := digest(t, "e")
	identity, _ := NewIdentity(DeveloperTag(DeveloperVersion{42}), PolicyTrackMain, nil)
	selection := NewProductSelection(version(t, "0123456789abcdef"), TargetLinuxX8664, digest(t, "b"), identity,
		revision(t, "1"), entry, manifest.Digest(), nil)
	receipt := NewRuntimeReceipt(entry, manifest.Digest())
	if !receipt.Binds(entry, manifest, selection) {
		t.Fatal("matching receipt does not bind")
	}
	other, _ := BindRuntimeManifest([]byte(`{}`))
	if receipt.Binds(entry, other, selection) || receipt.Binds(digest(t, "a"), manifest, selection) ||
		NewRuntimeReceipt(entry, digest(t, "f")).Binds(entry, manifest, selection) {
		t.Fatal("a mismatched digest binds")
	}
	if _, err := BindRuntimeManifest(make([]byte, MaximumRuntimeManifestBytes+1)); err == nil {
		t.Fatal("oversize manifest bound")
	}
}

func TestAssemblyRecordFollowsTheKotlinReader(t *testing.T) {
	raw := fixture(t, "kast-headless.json")
	home, ok := ParseHostHome("/home/user/.local/share/kast-workspaces/runtimes/" + strings.Repeat("e", 64) + "/host")
	platform, ok2 := ParsePlatformBuild("263.1234.5")
	if !ok || !ok2 {
		t.Fatal("literal values rejected")
	}
	built := NewAssemblyRecord(home, digest(t, "a"), digest(t, "c"), platform)
	if !bytes.Equal(built.Encode(), raw) {
		t.Fatalf("encoding differs:\n%s\n%s", built.Encode(), raw)
	}
	decoded, err := DecodeAssemblyRecord(raw)
	if err != nil || decoded != built {
		t.Fatalf("decoded %+v, %v", decoded, err)
	}
	decode := func(b []byte) error { _, err := DecodeAssemblyRecord(b); return err }
	commonRejections(t, raw, "1", decode)
	checkRejections(t, raw, decode, []rejection{
		{"plugin build differs", `"kotlinPluginBuild":"263.1234.5-IJ"`, `"kotlinPluginBuild":"263.1234.5"`,
			codec.InvalidValue},
		{"unsupported platform", `"platformBuild":"263.1234.5"`, `"platformBuild":"251.1"`, codec.InvalidValue},
		{"relative home", `"installation":"/home`, `"installation":"home`, codec.InvalidValue},
		{"specification member name", `"installation"`, `"home"`, codec.MissingMember},
	})
}

func TestBuildRecordDecodesStrictly(t *testing.T) {
	raw := fixture(t, "build-record.json")
	record, err := DecodeBuildRecord(raw)
	if err != nil {
		t.Fatal(err)
	}
	linux, ok := record.Target(TargetLinuxX8664)
	if !ok || linux.ControlSHA256() != digest(t, "c") || linux.Runtime().SHA256() != digest(t, "e") {
		t.Fatalf("linux target %+v", linux)
	}
	if base, ok := linux.Base(); !ok || base.SHA256() != digest(t, "9") {
		t.Fatal("base asset lost")
	}
	if record.Digest() != DigestOf(raw) || !bytes.Equal(record.Bytes(), raw) || record.Version().String() != "0.0.42" {
		t.Fatal("record identity lost")
	}
	decode := func(b []byte) error { _, err := DecodeBuildRecord(b); return err }
	checkRejections(t, raw, decode, []rejection{
		{"unknown member", `"repository"`, `"extra":1,"repository"`, codec.UnknownMember},
		{"duplicate member", `"repository"`, `"version":"0.0.42","repository"`, codec.DuplicateMember},
		{"newer schema", `"schemaVersion": 3`, `"schemaVersion": 6`, codec.SchemaUnsupported},
		{"other repository", `"amichne/kast"`, `"someone/kast"`, codec.InvalidValue},
		{"stable version", `"version": "0.0.42"`, `"version": "1.0.0"`, codec.InvalidValue},
		{"foreign workflow", `".github/workflows/ci.yml"`, `".github/workflows/x.yml"`, codec.InvalidValue},
		{"local producer in CI schema", `{"runId": 5, "runAttempt": 2, "workflow": ".github/workflows/ci.yml"}`,
			`{"kind": "LOCAL"}`, codec.InvalidValue},
		{"bundle name", `kast-composed-setup-v0.0.42-linux-x86_64.tar.gz`, `kast-composed-setup.tar.gz`,
			codec.InvalidValue},
		{"bundle digest disagrees", `"bundleSha256": "aaaa`, `"bundleSha256": "abaa`, codec.InvalidValue},
		{"outcome", `"outcome": "COMPLETE"`, `"outcome": "PARTIAL"`, codec.InvalidValue},
		{"absent base", `"runtimeBase": {"name": "kast-runtime-base-linux`, `"other": {"name": "kast-runtime-base-linux`,
			codec.MissingMember},
		{"composition without base", `"runtimeBase": {"name": "kast-runtime-base-linux-x86_64-` +
			strings.Repeat("9", 64) + `.zip", "sha256": "` + strings.Repeat("9", 64) + `", "bytes": 4000}`,
			`"runtimeBase": null`, codec.InvalidValue},
		{"composition base disagrees", `"baseSha256": "9999`, `"baseSha256": "9899`, codec.InvalidValue},
		{"zero asset size", `"bytes": 1000}`, `"bytes": 0}`, codec.InvalidValue},
		{"target order", `"target": "linux-x86_64"`, `"target": "macos-aarch64"`, codec.InvalidValue},
	})
}

func TestRetirementDocumentDecodesStrictly(t *testing.T) {
	retired, err := DecodeRetirementDocument(fixture(t, "retirement-retired.json"))
	if err != nil {
		t.Fatal(err)
	}
	facts, ok := retired.(Retired)
	if !ok || facts.Semantic().Kind() != ServiceSemantic || len(facts.Brokers()) != 2 || len(facts.Hosts()) != 3 {
		t.Fatalf("retired %+v", retired)
	}
	rejectedRaw := fixture(t, "retirement-rejected.json")
	rejected, err := DecodeRetirementDocument(rejectedRaw)
	if err != nil || rejected.(RetirementRejected).Reason() != ReasonActiveHost {
		t.Fatalf("rejected %+v, %v", rejected, err)
	}
	decode := func(b []byte) error { _, err := DecodeRetirementDocument(b); return err }
	commonRejections(t, rejectedRaw, "1", decode)
	raw := fixture(t, "retirement-retired.json")
	checkRejections(t, raw, decode, []rejection{
		{"unknown outcome", `"outcome":"RETIRED"`, `"outcome":"DONE"`, codec.InvalidValue},
		{"reason beside facts", `"outcome":"RETIRED"`, `"outcome":"RETIRED","reason":"ACTIVE_HOST"`,
			codec.UnknownMember},
		{"broker as semantic", `"semanticLabel":"io.github.amichne.kast.semantic.`,
			`"semanticLabel":"io.github.amichne.kast.broker.`, codec.InvalidValue},
		{"unsorted brokers", `broker.00000000000000000000000000000001`, `broker.00000000000000000000000000000003`,
			codec.InvalidValue},
		{"duplicate host", `{"pid":41,"startTime":9}`, `{"pid":41,"startTime":7}`, codec.InvalidValue},
		{"zero pid", `{"pid":41,"startTime":7}`, `{"pid":0,"startTime":7}`, codec.InvalidValue},
		{"missing hosts", `,"hosts":[{"pid":41,"startTime":7},{"pid":41,"startTime":9},{"pid":77,"startTime":3}]`, ``,
			codec.MissingMember},
	})
	checkRejections(t, rejectedRaw, decode, []rejection{
		{"Go-side reason", `"ACTIVE_HOST"`, `"DEADLINE_EXCEEDED"`, codec.InvalidValue},
		{"unknown reason", `"ACTIVE_HOST"`, `"HOST_BUSY"`, codec.InvalidValue},
		{"facts beside reason", `"reason":"ACTIVE_HOST"`, `"reason":"ACTIVE_HOST","hosts":[]`, codec.UnknownMember},
	})
}

func TestCurrentTargetHasOneShape(t *testing.T) {
	target, ok := ParseCurrentTarget("versions/0123456789abcdef")
	if !ok || target.String() != "versions/0123456789abcdef" || PointTo(target.Version()) != target {
		t.Fatal("canonical target rejected")
	}
	for _, text := range []string{"", "versions/0123456789ABCDEF", "./versions/0123456789abcdef",
		"/x/versions/0123456789abcdef", "versions/0123456789abcdef/", "versions/0123456789abcde",
		"version/0123456789abcdef", "versions/../versions/0123456789abcdef"} {
		if _, ok := ParseCurrentTarget(text); ok {
			t.Errorf("admitted %q", text)
		}
	}
}

func TestVocabularyRejectsNonCanonicalSpellings(t *testing.T) {
	for _, text := range []string{"1.2", "1.2.3.4", "01.2.3", "1.2.-3", "v1.2.3", "1.2.3-rc1", ""} {
		if _, ok := ParseSemVer(text); ok {
			t.Errorf("semver %q admitted", text)
		}
	}
	for _, text := range []string{"developer-v1.0.0", "developer-v0.0.01", "V1.0.0", "1.0.0", "developer-0.0.1"} {
		if _, ok := ParseReleaseTag(text); ok {
			t.Errorf("tag %q admitted", text)
		}
	}
	for _, text := range []string{"io.github.amichne.kast.semantic.0123", "io.github.amichne.kast.other." +
		strings.Repeat("a", 32), "com.example.semantic." + strings.Repeat("a", 32)} {
		if _, ok := ParseServiceLabel(text); ok {
			t.Errorf("label %q admitted", text)
		}
	}
	for _, text := range []string{"", ".hidden", "a/b", strings.Repeat("a", 129)} {
		if _, ok := ParseAssetName(text); ok {
			t.Errorf("asset %q admitted", text)
		}
	}
}
