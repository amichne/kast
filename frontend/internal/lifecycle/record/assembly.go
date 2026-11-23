package record

import (
	"path"
	"strings"
	"unicode/utf8"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
)

// HostHome is the absolute Host home of a runtime entry: clean, absolute, without NUL, at most 4096 bytes. It is the
// one absolute path a record holds, because the Kotlin reader compares it with its own location.
type HostHome struct{ text string }

// ParseHostHome admits a clean absolute path.
func ParseHostHome(text string) (HostHome, bool) {
	if len(text) < 2 || len(text) > 4096 || !utf8.ValidString(text) || strings.ContainsRune(text, 0) ||
		!strings.HasPrefix(text, "/") || path.Clean(text) != text {
		return HostHome{}, false
	}
	return HostHome{text}, true
}

func (h HostHome) String() string { return h.text }

// MarshalText writes the path.
func (h HostHome) MarshalText() ([]byte, error) { return admittedText(h.text, h.text != "") }

// PlatformBuild is an IntelliJ platform build of the admitted lines: 262 or 263, then dot-separated decimals. The rule
// mirrors the Kotlin reader, ManagedHeadlessInstallation.readRecord.
type PlatformBuild struct{ text string }

// ParsePlatformBuild admits "262.N[.N...]" or "263.N[.N...]".
func ParsePlatformBuild(text string) (PlatformBuild, bool) {
	rest, ok := strings.CutPrefix(text, "262.")
	if !ok {
		rest, ok = strings.CutPrefix(text, "263.")
	}
	if !ok || len(text) > 64 {
		return PlatformBuild{}, false
	}
	for _, part := range strings.Split(rest, ".") {
		if part == "" || strings.Trim(part, "0123456789") != "" {
			return PlatformBuild{}, false
		}
	}
	return PlatformBuild{text}, true
}

func (p PlatformBuild) String() string { return p.text }

// MarshalText writes the build.
func (p PlatformBuild) MarshalText() ([]byte, error) { return admittedText(p.text, p.text != "") }

// AssemblyRecord is <runtime entry>/host[/Kast.app/Contents]/kast-headless.json. The member names follow the record
// that the existing writer emits and the Kotlin reader decodes: "installation" is the Host home and
// "kotlinPluginBuild" is the platform build followed by "-IJ".
type AssemblyRecord struct {
	home         HostHome
	distribution Digest
	bootstrap    Digest
	platform     PlatformBuild
}

// NewAssemblyRecord builds a record; the plugin build is derived from the platform build.
func NewAssemblyRecord(home HostHome, distribution, bootstrap Digest, platform PlatformBuild) AssemblyRecord {
	return AssemblyRecord{home, distribution, bootstrap, platform}
}

// Home is the entry's absolute Host home.
func (a AssemblyRecord) Home() HostHome { return a.home }

// Distribution is the distribution digest.
func (a AssemblyRecord) Distribution() Digest { return a.distribution }

// Bootstrap is the bootstrap digest.
func (a AssemblyRecord) Bootstrap() Digest { return a.bootstrap }

// Platform is the platform build.
func (a AssemblyRecord) Platform() PlatformBuild { return a.platform }

type assemblyRecordWire struct {
	SchemaVersion      int           `json:"schemaVersion"`
	Installation       HostHome      `json:"installation"`
	DistributionSHA256 Digest        `json:"distributionSha256"`
	BootstrapSHA256    Digest        `json:"bootstrapSha256"`
	PlatformBuild      PlatformBuild `json:"platformBuild"`
	KotlinPluginBuild  string        `json:"kotlinPluginBuild"`
}

// Encode writes the canonical bytes.
func (a AssemblyRecord) Encode() []byte {
	return mustEncode(assemblyRecordWire{1, a.home, a.distribution, a.bootstrap, a.platform, a.platform.text + "-IJ"})
}

// DecodeAssemblyRecord strictly decodes kast-headless.json.
func DecodeAssemblyRecord(raw []byte) (AssemblyRecord, error) {
	object, err := codec.Document(raw, MaximumRecordBytes)
	if err != nil {
		return AssemblyRecord{}, err
	}
	if _, err := object.SchemaVersion(1); err != nil {
		return AssemblyRecord{}, err
	}
	home, err := codec.Text(object, "installation", ParseHostHome)
	if err != nil {
		return AssemblyRecord{}, err
	}
	distribution, err := codec.Text(object, "distributionSha256", ParseDigest)
	if err != nil {
		return AssemblyRecord{}, err
	}
	bootstrap, err := codec.Text(object, "bootstrapSha256", ParseDigest)
	if err != nil {
		return AssemblyRecord{}, err
	}
	platform, err := codec.Text(object, "platformBuild", ParsePlatformBuild)
	if err != nil {
		return AssemblyRecord{}, err
	}
	plugin, err := object.String("kotlinPluginBuild")
	if err != nil {
		return AssemblyRecord{}, err
	}
	if plugin != platform.text+"-IJ" {
		return AssemblyRecord{}, codec.Reject(codec.InvalidValue, "kotlinPluginBuild")
	}
	return AssemblyRecord{home, distribution, bootstrap, platform}, object.Closed()
}
