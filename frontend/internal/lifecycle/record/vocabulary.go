// Package record defines the lifecycle records of the Go installation layout and their strict decoders and canonical
// encoders: the current pointer, the payload manifest, the product selection at schemaVersion 2, the build record,
// the runtime receipt and manifest binding, the assembly record, and the retirement document. Every decoder reads
// schemaVersion first and rejects unknown members, duplicate members, trailing data and unknown enum values. The
// package performs no effects.
package record

import (
	"crypto/sha256"
	"encoding/hex"
	"path"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
)

func isLowerHex(text string, length int) bool {
	if len(text) != length {
		return false
	}
	for i := 0; i < len(text); i++ {
		c := text[i]
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

// Digest is a SHA-256 digest. It encodes as 64 lowercase hexadecimal characters.
type Digest [32]byte

// ParseDigest admits exactly 64 lowercase hexadecimal characters.
func ParseDigest(text string) (Digest, bool) {
	var digest Digest
	if !isLowerHex(text, 64) {
		return digest, false
	}
	_, _ = hex.Decode(digest[:], []byte(text))
	return digest, true
}

// DigestOf is the SHA-256 digest of bytes.
func DigestOf(raw []byte) Digest { return sha256.Sum256(raw) }

func (d Digest) String() string { return hex.EncodeToString(d[:]) }

// MarshalText writes the lowercase hexadecimal form.
func (d Digest) MarshalText() ([]byte, error) { return []byte(d.String()), nil }

// VersionID names one version directory. It encodes as 16 lowercase hexadecimal characters.
type VersionID [8]byte

// ParseVersionID admits exactly 16 lowercase hexadecimal characters.
func ParseVersionID(text string) (VersionID, bool) {
	var id VersionID
	if !isLowerHex(text, 16) {
		return id, false
	}
	_, _ = hex.Decode(id[:], []byte(text))
	return id, true
}

func (v VersionID) String() string { return hex.EncodeToString(v[:]) }

// MarshalText writes the lowercase hexadecimal form.
func (v VersionID) MarshalText() ([]byte, error) { return []byte(v.String()), nil }

// Revision is a full Git commit identifier: 40 lowercase hexadecimal characters, never all zeros.
type Revision struct{ text string }

// ParseRevision admits a full, nonzero commit identifier.
func ParseRevision(text string) (Revision, bool) {
	if !isLowerHex(text, 40) || text == strings.Repeat("0", 40) {
		return Revision{}, false
	}
	return Revision{text}, true
}

func (r Revision) String() string { return r.text }

// MarshalText writes the identifier.
func (r Revision) MarshalText() ([]byte, error) { return admittedText(r.text, r.text != "") }

// admittedText writes an admitted value and refuses the zero value, so an unadmitted one never reaches a record.
func admittedText(text string, admitted bool) ([]byte, error) {
	if !admitted {
		return nil, codec.Reject(codec.InvalidValue, "")
	}
	return []byte(text), nil
}

// Target is the closed set of release targets.
type Target uint8

const (
	TargetMacOSAArch64 Target = iota + 1
	TargetLinuxX8664
)

var targetNames = map[Target]string{TargetMacOSAArch64: "macos-aarch64", TargetLinuxX8664: "linux-x86_64"}

// ParseTarget admits a release target name.
func ParseTarget(text string) (Target, bool) { return parseEnum(targetNames, text) }

func (t Target) String() string { return targetNames[t] }

// MarshalText writes the target name.
func (t Target) MarshalText() ([]byte, error) { return marshalEnum(targetNames, t) }

// Policy is the closed set of selection policies.
type Policy uint8

const (
	PolicyTrackStable Policy = iota + 1
	PolicyTrackMain
	PolicyPinnedBuild
	PolicyLocal
)

var policyNames = map[Policy]string{
	PolicyTrackStable: "TRACK_STABLE", PolicyTrackMain: "TRACK_MAIN", PolicyPinnedBuild: "PINNED_BUILD",
	PolicyLocal: "LOCAL",
}

// ParsePolicy admits a policy name.
func ParsePolicy(text string) (Policy, bool) { return parseEnum(policyNames, text) }

func (p Policy) String() string { return policyNames[p] }

// MarshalText writes the policy name.
func (p Policy) MarshalText() ([]byte, error) { return marshalEnum(policyNames, p) }

// SemVer is a stable public version: three decimal components without leading zeros.
type SemVer struct{ major, minor, patch uint32 }

// ParseSemVer admits MAJOR.MINOR.PATCH with no leading zeros, at most 64 bytes.
func ParseSemVer(text string) (SemVer, bool) {
	parts := strings.Split(text, ".")
	if len(text) > 64 || len(parts) != 3 {
		return SemVer{}, false
	}
	var values [3]uint32
	for i, part := range parts {
		value, ok := decimal(part)
		if !ok {
			return SemVer{}, false
		}
		values[i] = value
	}
	return SemVer{values[0], values[1], values[2]}, true
}

func decimal(text string) (uint32, bool) {
	if text == "" || (len(text) > 1 && text[0] == '0') {
		return 0, false
	}
	for i := 0; i < len(text); i++ {
		if text[i] < '0' || text[i] > '9' {
			return 0, false
		}
	}
	value, err := strconv.ParseUint(text, 10, 32)
	return uint32(value), err == nil
}

func (s SemVer) String() string {
	return strconv.FormatUint(uint64(s.major), 10) + "." + strconv.FormatUint(uint64(s.minor), 10) + "." +
		strconv.FormatUint(uint64(s.patch), 10)
}

// MarshalText writes the version.
func (s SemVer) MarshalText() ([]byte, error) { return []byte(s.String()), nil }

// DeveloperVersion is a preview build version, 0.0.N with N positive.
type DeveloperVersion struct{ build uint32 }

// ParseDeveloperVersion admits 0.0.N with N positive and without leading zeros.
func ParseDeveloperVersion(text string) (DeveloperVersion, bool) {
	rest, ok := strings.CutPrefix(text, "0.0.")
	if !ok || len(text) > 64 {
		return DeveloperVersion{}, false
	}
	value, ok := decimal(rest)
	if !ok || value == 0 {
		return DeveloperVersion{}, false
	}
	return DeveloperVersion{value}, true
}

func (d DeveloperVersion) String() string { return "0.0." + strconv.FormatUint(uint64(d.build), 10) }

// Build is the build number N, which orders previews.
func (d DeveloperVersion) Build() uint32 { return d.build }

// ReleaseTag is "developer-v<0.0.N>" for a preview or "v<semver>" for a stable release.
type ReleaseTag struct {
	stable    bool
	public    SemVer
	developer DeveloperVersion
}

// ParseReleaseTag admits a preview or stable release tag.
func ParseReleaseTag(text string) (ReleaseTag, bool) {
	if rest, ok := strings.CutPrefix(text, "developer-v"); ok {
		version, ok := ParseDeveloperVersion(rest)
		return ReleaseTag{developer: version}, ok
	}
	if rest, ok := strings.CutPrefix(text, "v"); ok {
		version, ok := ParseSemVer(rest)
		return ReleaseTag{stable: true, public: version}, ok
	}
	return ReleaseTag{}, false
}

// StableTag is the tag of a stable release.
func StableTag(version SemVer) ReleaseTag { return ReleaseTag{stable: true, public: version} }

// DeveloperTag is the tag of a preview release.
func DeveloperTag(version DeveloperVersion) ReleaseTag { return ReleaseTag{developer: version} }

// Stable returns the public version of a stable tag.
func (t ReleaseTag) Stable() (SemVer, bool) { return t.public, t.stable }

// PublicVersionOf is the explicit public version a stable tag implies, nil for a preview tag.
func (t ReleaseTag) PublicVersionOf() *SemVer {
	if !t.stable {
		return nil
	}
	version := t.public
	return &version
}

// Developer returns the build version of a preview tag.
func (t ReleaseTag) Developer() (DeveloperVersion, bool) { return t.developer, !t.stable }

func (t ReleaseTag) String() string {
	if t.stable {
		return "v" + t.public.String()
	}
	return "developer-v" + t.developer.String()
}

// MarshalText writes the tag.
func (t ReleaseTag) MarshalText() ([]byte, error) {
	return admittedText(t.String(), t.stable || t.developer.build != 0)
}

// Identity is the selection identity shared by the handoff and the product selection: the release tag, the policy
// and the public version. A stable tag requires the public version it names; a preview tag requires none. Tracking
// stable needs a stable tag; tracking main and a local build need a preview tag.
type Identity struct {
	tag    ReleaseTag
	policy Policy
}

// NewIdentity admits a tag, a policy and an explicit public version that agree.
func NewIdentity(tag ReleaseTag, policy Policy, public *SemVer) (Identity, bool) {
	stable, isStable := tag.Stable()
	switch {
	case isStable != (public != nil):
		return Identity{}, false
	case isStable && stable != *public:
		return Identity{}, false
	case policy == PolicyTrackStable && !isStable:
		return Identity{}, false
	case (policy == PolicyTrackMain || policy == PolicyLocal) && isStable:
		return Identity{}, false
	case policyNames[policy] == "":
		return Identity{}, false
	}
	return Identity{tag: tag, policy: policy}, true
}

// Tag is the release tag.
func (i Identity) Tag() ReleaseTag { return i.tag }

// Policy is the selection policy.
func (i Identity) Policy() Policy { return i.policy }

// PublicVersion is the explicit public version, nil for a preview.
func (i Identity) PublicVersion() *SemVer {
	if version, ok := i.tag.Stable(); ok {
		return &version
	}
	return nil
}

// RelativePath is a slash-separated path that stays beneath its root: not empty, not absolute, already clean, with
// no "." or ".." element, no NUL, valid UTF-8, and at most 512 bytes.
type RelativePath struct{ text string }

// ParseRelativePath admits a clean local path.
func ParseRelativePath(text string) (RelativePath, bool) {
	if text == "" || len(text) > 512 || !utf8.ValidString(text) || strings.ContainsRune(text, 0) ||
		strings.HasPrefix(text, "/") || path.Clean(text) != text {
		return RelativePath{}, false
	}
	for _, element := range strings.Split(text, "/") {
		if element == "." || element == ".." {
			return RelativePath{}, false
		}
	}
	return RelativePath{text}, true
}

// MustRelativePath admits a path the code itself spells; a rejection is a defect.
func MustRelativePath(text string) RelativePath {
	relative, ok := ParseRelativePath(text)
	if !ok {
		panic("record: invalid literal relative path " + strconv.Quote(text))
	}
	return relative
}

func (p RelativePath) String() string { return p.text }

// Join appends a clean relative path.
func (p RelativePath) Join(next RelativePath) RelativePath {
	return RelativePath{p.text + "/" + next.text}
}

// Contains reports whether other is p or lies beneath it.
func (p RelativePath) Contains(other RelativePath) bool {
	return other.text == p.text || strings.HasPrefix(other.text, p.text+"/")
}

// MarshalText writes the path.
func (p RelativePath) MarshalText() ([]byte, error) { return admittedText(p.text, p.text != "") }

// RootKind is the closed set of roots a lifecycle path is relative to. HOME and HANDOFF extend the specification's
// list: HOME admits the home path checks, and HANDOFF names files of the private handoff directory.
type RootKind uint8

const (
	RootR RootKind = iota + 1
	RootRuntimeStore
	RootBaseStore
	RootPublicCommand
	RootHomeShare
	RootTmpAlias
	RootHome
	RootHandoff
)

var rootNames = map[RootKind]string{
	RootR: "R", RootRuntimeStore: "RUNTIME_STORE", RootBaseStore: "BASE_STORE", RootPublicCommand: "PUBLIC_COMMAND",
	RootHomeShare: "HOME_SHARE", RootTmpAlias: "TMP_ALIAS", RootHome: "HOME", RootHandoff: "HANDOFF",
}

// ParseRootKind admits a root name.
func ParseRootKind(text string) (RootKind, bool) { return parseEnum(rootNames, text) }

func (r RootKind) String() string { return rootNames[r] }

// MarshalText writes the root name.
func (r RootKind) MarshalText() ([]byte, error) { return marshalEnum(rootNames, r) }

// Location is a path beneath a named root. It never carries an absolute path.
type Location struct {
	Root RootKind     `json:"root"`
	Path RelativePath `json:"path"`
}

// At builds a location from a path the code itself spells.
func At(root RootKind, relative string) Location { return Location{root, MustRelativePath(relative)} }

func (l Location) String() string { return l.Root.String() + ":" + l.Path.String() }

// AssetName is a release asset file name: 1 to 128 bytes of letters, digits, '.', '_' and '-', not starting with '.'.
type AssetName struct{ text string }

// ParseAssetName admits a release asset name.
func ParseAssetName(text string) (AssetName, bool) {
	if text == "" || len(text) > 128 || text[0] == '.' {
		return AssetName{}, false
	}
	for i := 0; i < len(text); i++ {
		c := text[i]
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-') {
			return AssetName{}, false
		}
	}
	return AssetName{text}, true
}

// MustAssetName admits an asset name the code itself spells.
func MustAssetName(text string) AssetName {
	name, ok := ParseAssetName(text)
	if !ok {
		panic("record: invalid literal asset name " + strconv.Quote(text))
	}
	return name
}

func (a AssetName) String() string { return a.text }

// MarshalText writes the name.
func (a AssetName) MarshalText() ([]byte, error) { return admittedText(a.text, a.text != "") }

// ControlAsset is the fixed name of a target's standalone Control executable asset.
func ControlAsset(target Target) AssetName { return MustAssetName("kast-control-" + target.String()) }

// BuildRecordAsset is the build record's asset name.
var BuildRecordAsset = MustAssetName("build-record.json")

// ServiceKind is the closed set of supervised services Kast labels.
type ServiceKind uint8

const (
	ServiceSemantic ServiceKind = iota + 1
	ServiceBroker
)

// ServiceLabelPrefix is the shared prefix of every Kast supervisor label.
const ServiceLabelPrefix = "io.github.amichne.kast."

// ServiceLabel is a Kast supervisor label: the prefix, "semantic." or "broker.", and 32 lowercase hexadecimal
// characters.
type ServiceLabel struct {
	kind   ServiceKind
	suffix string
}

// ParseServiceLabel admits a semantic or broker label.
func ParseServiceLabel(text string) (ServiceLabel, bool) {
	rest, ok := strings.CutPrefix(text, ServiceLabelPrefix)
	if !ok {
		return ServiceLabel{}, false
	}
	if hexed, ok := strings.CutPrefix(rest, "semantic."); ok && isLowerHex(hexed, 32) {
		return ServiceLabel{ServiceSemantic, hexed}, true
	}
	if hexed, ok := strings.CutPrefix(rest, "broker."); ok && isLowerHex(hexed, 32) {
		return ServiceLabel{ServiceBroker, hexed}, true
	}
	return ServiceLabel{}, false
}

// Kind is the service the label names.
func (s ServiceLabel) Kind() ServiceKind { return s.kind }

func (s ServiceLabel) String() string {
	if s.kind == ServiceSemantic {
		return ServiceLabelPrefix + "semantic." + s.suffix
	}
	return ServiceLabelPrefix + "broker." + s.suffix
}

// MarshalText writes the label.
func (s ServiceLabel) MarshalText() ([]byte, error) { return admittedText(s.String(), s.kind != 0) }

func parseEnum[E comparable](names map[E]string, text string) (E, bool) {
	for value, name := range names {
		if name == text {
			return value, true
		}
	}
	var zero E
	return zero, false
}

func marshalEnum[E comparable](names map[E]string, value E) ([]byte, error) {
	name, ok := names[value]
	if !ok {
		return nil, codec.Reject(codec.InvalidValue, "")
	}
	return []byte(name), nil
}
