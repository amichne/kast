package install

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"regexp"
	"strings"
)

// The publisher's names, as managed_release.py reads them.
const (
	repository = "amichne/kast"
	catalogURL = "https://raw.githubusercontent.com/" + repository + "/developer-latest/builds.json"
	// GitHub's API limits unauthenticated reads per address, so this is the latest stable release's own mapping.
	stableURL     = "https://github.com/" + repository + "/releases/latest/download/release-mapping.json"
	releases      = "https://github.com/" + repository + "/releases/download"
	recordSchema  = 3
	maximumRecord = 64 << 10
	maximumAsset  = int64(2) << 30
	maximumBuilds = 5
)

var (
	digestForm   = regexp.MustCompile(`^[0-9a-f]{64}$`)
	revisionForm = regexp.MustCompile(`^[0-9a-f]{40}$`)
	commitForm   = regexp.MustCompile(`^([0-9a-f]{12}|[0-9a-f]{40})$`)
	versionForm  = regexp.MustCompile(`^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$`)
	buildForm    = regexp.MustCompile(`^0\.0\.[1-9][0-9]{0,8}$`)
)

// platformNames are the targets of every published product, and targets is the order its record lists them in.
var (
	platformNames = map[string]string{"linux-x86_64": "Linux x86-64", "macos-aarch64": "macOS ARM64"}
	targets       = []string{"linux-x86_64", "macos-aarch64"}
)

type channel string

const (
	preview channel = "preview"
	stable  channel = "stable"
)

// selector names one build: the head of a channel, one stable version, or one main revision.
type selector interface{ selects() }

type (
	channelHead struct{ channel channel }
	oneVersion  struct{ version string }
	oneCommit   struct{ prefix string }
)

func (channelHead) selects() {}
func (oneVersion) selects()  {}
func (oneCommit) selects()   {}

// Asset is one published file and the digest that admits its bytes.
type Asset struct {
	Name   string `json:"name"`
	SHA256 string `json:"sha256"`
	Bytes  int64  `json:"bytes"`
}

// role is what a file is in a build, as a reader sees it.
type role string

const (
	product              role = "Kast"
	analysisRuntime      role = "Analysis runtime"
	qualificationReceipt role = "Qualification"
	runtimeBase          role = "IntelliJ base"
)

// part is one file of a build.
type part struct {
	Asset
	role role
}

// release is one admitted build for one target. Its record matched the digest that selected it.
type release struct {
	// version is what a reader selects: the stable version, or the preview build. recordVersion is the build's own.
	tag, version, revision, target string
	recordVersion, tree            string
	channel                        channel
	parts                          []part
	// facts is the record's entry for this target.
	facts *targetDocument
	// record is the exact admitted text, and sha its published digest.
	record []byte
	sha    string
}

func (r *release) bytes() (total int64) {
	for _, part := range r.parts {
		total += part.Bytes
	}
	return total
}

// missing is the size of the files that were not reused.
func (r *release) missing(reused map[string]bool) (total int64) {
	for _, part := range r.parts {
		if !reused[part.Name] {
			total += part.Bytes
		}
	}
	return total
}

type reference struct {
	tag, public, revision, sha string
	channel                    channel
}

type catalogDocument struct {
	SchemaVersion int            `json:"schemaVersion"`
	Builds        []catalogBuild `json:"builds"`
}

type catalogBuild struct {
	MainRevision string `json:"mainRevision"`
	ReleaseTag   string `json:"releaseTag"`
	// The record's own digest admits it, so the catalog's copy stays opaque.
	Record       jsontext.Value `json:"record"`
	RecordSHA256 string         `json:"recordSha256"`
}

type mappingDocument struct {
	SchemaVersion     int    `json:"schemaVersion"`
	PublicVersion     string `json:"publicVersion"`
	BuildRecordSHA256 string `json:"buildRecordSha256"`
}

type producerDocument struct {
	RunID      int64  `json:"runId"`
	RunAttempt int64  `json:"runAttempt"`
	Workflow   string `json:"workflow"`
}

type recordDocument struct {
	SchemaVersion  int              `json:"schemaVersion"`
	Repository     string           `json:"repository"`
	SourceRevision string           `json:"sourceRevision"`
	SourceTree     string           `json:"sourceTree"`
	Version        string           `json:"version"`
	Producer       producerDocument `json:"producer"`
	Targets        []targetDocument `json:"targets"`
}

type targetDocument struct {
	Target        string                `json:"target"`
	Inputs        inputsDocument        `json:"inputs"`
	Bundle        Asset                 `json:"bundle"`
	Runtime       Asset                 `json:"runtime"`
	Qualification qualificationDocument `json:"qualification"`
	RuntimeBase   Asset                 `json:"runtimeBase"`
}

type inputsDocument struct {
	JavaRelease       string `json:"javaReleaseSha256"`
	DependencyLocks   string `json:"dependencyLocksSha256"`
	CompositionRecipe string `json:"compositionRecipeSha256"`
	Donor             string `json:"donorSha256"`
}

type qualificationDocument struct {
	Receipt     Asset               `json:"receipt"`
	Producer    producerDocument    `json:"producer"`
	Bundle      string              `json:"bundleSha256"`
	Runtime     string              `json:"runtimeSha256"`
	Control     string              `json:"controlSha256"`
	Kind        string              `json:"kind"`
	Outcome     string              `json:"outcome"`
	Composition compositionDocument `json:"composition"`
}

type compositionDocument struct {
	Base          string `json:"baseSha256"`
	NativeRuntime string `json:"nativeRuntimeSha256"`
	NativeBundle  string `json:"nativeBundleSha256"`
	Runtime       string `json:"runtimeSha256"`
}

// strict admits one object with exactly the declared members. Duplicate and differently cased names are errors.
func strict(raw []byte, document any) error {
	return json.Unmarshal(raw, document, json.RejectUnknownMembers(true))
}

// resolve finds the one build a selector names and admits its record for this target.
func (i *Install) resolve(ctx context.Context, selected selector) (*release, error) {
	found, err := i.refer(ctx, selected)
	if err != nil {
		return nil, err
	}
	raw, err := i.read(ctx, releases+"/"+found.tag+"/build-record.json")
	if err != nil {
		return nil, err
	}
	if sum := sha256.Sum256(raw); hex.EncodeToString(sum[:]) != found.sha {
		return nil, reject(RecordInvalid, "the build record of %s does not match its published digest", found.tag)
	}
	return admit(raw, found, i.Platform)
}

func (i *Install) refer(ctx context.Context, selected selector) (reference, error) {
	switch selected := selected.(type) {
	case oneVersion:
		return i.mapped(ctx, selected.version)
	case channelHead:
		if selected.channel == preview {
			return i.catalogued(ctx, "")
		}
		raw, err := i.read(ctx, stableURL)
		if err != nil {
			return reference{}, err
		}
		var latest mappingDocument
		if strict(raw, &latest) != nil || latest.SchemaVersion != 1 || !versionForm.MatchString(latest.PublicVersion) {
			return reference{}, reject(RecordInvalid, "the latest stable release mapping is invalid")
		}
		return i.mapped(ctx, latest.PublicVersion)
	case oneCommit:
		return i.catalogued(ctx, selected.prefix)
	default:
		return reference{}, errors.New("unreachable selector")
	}
}

// mapped follows a stable release to the immutable build that it republishes.
func (i *Install) mapped(ctx context.Context, version string) (reference, error) {
	tag := "v" + version
	raw, err := i.read(ctx, releases+"/"+tag+"/release-mapping.json")
	if err != nil {
		return reference{}, err
	}
	var document mappingDocument
	if strict(raw, &document) != nil || document.SchemaVersion != 1 || document.PublicVersion != version ||
		!digestForm.MatchString(document.BuildRecordSHA256) {
		return reference{}, reject(RecordInvalid, "the release mapping of %s is invalid", tag)
	}
	return reference{tag: tag, public: version, sha: document.BuildRecordSHA256, channel: stable}, nil
}

// catalogued selects the newest preview build, or the one build whose main revision has the prefix.
func (i *Install) catalogued(ctx context.Context, prefix string) (reference, error) {
	raw, err := i.read(ctx, catalogURL)
	if err != nil {
		return reference{}, err
	}
	var document catalogDocument
	if strict(raw, &document) != nil || document.SchemaVersion != 2 || document.Builds == nil ||
		len(document.Builds) > maximumBuilds {
		return reference{}, reject(RecordInvalid, "the preview catalog is invalid")
	}
	seen := map[string]bool{}
	var matches []reference
	for _, build := range document.Builds {
		number, tagged := strings.CutPrefix(build.ReleaseTag, "developer-v")
		if !tagged || !buildForm.MatchString(number) || !revisionForm.MatchString(build.MainRevision) ||
			!digestForm.MatchString(build.RecordSHA256) || seen[build.MainRevision] {
			return reference{}, reject(RecordInvalid, "the preview catalog is invalid")
		}
		seen[build.MainRevision] = true
		if strings.HasPrefix(build.MainRevision, prefix) {
			matches = append(matches, reference{tag: build.ReleaseTag, public: number, revision: build.MainRevision,
				sha: build.RecordSHA256, channel: preview})
		}
	}
	switch {
	case len(matches) == 0 && prefix == "":
		return reference{}, reject(BuildNotFound, "the preview catalog is empty")
	case len(matches) == 0:
		return reference{}, reject(BuildNotFound, "no published build has revision %s", prefix)
	case len(matches) > 1 && prefix != "":
		return reference{}, reject(CommitAmbiguous, "%d published builds start with %s", len(matches), prefix)
	}
	return matches[0], nil
}

// admit decodes a record whose digest already matched, and checks every fact a download relies on.
func admit(raw []byte, found reference, platform string) (*release, error) {
	var schema struct {
		Version int `json:"schemaVersion"`
	}
	if json.Unmarshal(raw, &schema) != nil {
		return nil, reject(RecordInvalid, "the build record of %s is not one JSON object", found.tag)
	}
	if schema.Version != recordSchema {
		return nil, reject(ReleaseUnsupported, "%s has build record version %d; this installer needs version %d", found.tag,
			schema.Version, recordSchema)
	}
	var record recordDocument
	if err := strict(raw, &record); err != nil {
		return nil, reject(RecordInvalid, "the build record of %s is invalid: %v", found.tag, err)
	}
	invalid := func(check string) error {
		return reject(RecordInvalid, "the build record of %s is invalid: %s", found.tag, check)
	}
	switch {
	case record.Repository != repository:
		return nil, invalid("repository")
	case !revisionForm.MatchString(record.SourceRevision) || !revisionForm.MatchString(record.SourceTree):
		return nil, invalid("source")
	case found.revision != "" && found.revision != record.SourceRevision:
		return nil, invalid("revision")
	case !buildForm.MatchString(record.Version) || (found.channel == preview && found.public != record.Version):
		return nil, invalid("version")
	case len(record.Targets) != len(targets):
		return nil, invalid("targets")
	}
	// One invalid target makes the record invalid for every platform.
	var parts []part
	var facts *targetDocument
	for index, target := range record.Targets {
		if target.Target != targets[index] {
			return nil, invalid("targets")
		}
		files, check := target.admit(record.Version)
		if check != "" {
			return nil, invalid(check)
		}
		if target.Target == platform {
			parts, facts = files, &record.Targets[index]
		}
	}
	return &release{tag: found.tag, version: found.public, revision: record.SourceRevision, target: platform,
		recordVersion: record.Version, tree: record.SourceTree, channel: found.channel, parts: parts, facts: facts,
		record: raw, sha: found.sha}, nil
}

// admit returns the target's files, or the name of the check that failed.
func (t *targetDocument) admit(version string) ([]part, string) {
	evidence, composition := t.Qualification, t.Qualification.Composition
	for _, value := range []string{t.Inputs.JavaRelease, t.Inputs.DependencyLocks, t.Inputs.CompositionRecipe,
		t.Inputs.Donor, evidence.Control, composition.NativeRuntime, composition.NativeBundle} {
		if !digestForm.MatchString(value) {
			return nil, "digests"
		}
	}
	for _, expected := range []struct {
		name  string
		asset Asset
	}{
		{"kast-composed-setup-v" + version + "-" + t.Target + ".tar.gz", t.Bundle},
		{"kast-runtime-v" + version + "-" + t.Target + ".zip", t.Runtime},
		{"qualification-" + t.Target + ".json", evidence.Receipt},
		{"kast-runtime-base-" + t.Target + "-" + t.RuntimeBase.SHA256 + ".zip", t.RuntimeBase},
	} {
		if expected.asset.Name != expected.name || !digestForm.MatchString(expected.asset.SHA256) ||
			expected.asset.Bytes <= 0 || expected.asset.Bytes >= maximumAsset {
			return nil, "files"
		}
	}
	if evidence.Kind != "NATIVE" || evidence.Outcome != "COMPLETE" || evidence.Bundle != t.Bundle.SHA256 ||
		evidence.Runtime != t.Runtime.SHA256 || composition.Base != t.RuntimeBase.SHA256 ||
		composition.Runtime != t.Runtime.SHA256 {
		return nil, "qualification"
	}
	return []part{{t.Bundle, product}, {t.Runtime, analysisRuntime}, {evidence.Receipt, qualificationReceipt},
		{t.RuntimeBase, runtimeBase}}, ""
}
