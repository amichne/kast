package record

import (
	"encoding/json"
	"path"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/amichne/kast/frontend/internal/lifecycle/codec"
)

// Byte limits for each record. A longer document is rejected before it is parsed.
const (
	MaximumRecordBytes          = 65_536
	MaximumPayloadManifestBytes = 16 << 20
	MaximumRuntimeManifestBytes = 16 << 20
	maximumPayloadEntries       = 100_000
	maximumEntryBytes           = int64(8) << 30
)

// CurrentTarget is the only admitted target of R/current: exactly "versions/<16 hex>".
type CurrentTarget struct{ version VersionID }

// ParseCurrentTarget admits the link target string of R/current or R/current.next.
func ParseCurrentTarget(text string) (CurrentTarget, bool) {
	rest, ok := strings.CutPrefix(text, "versions/")
	if !ok {
		return CurrentTarget{}, false
	}
	version, ok := ParseVersionID(rest)
	return CurrentTarget{version}, ok
}

// PointTo is the target that names a version directory.
func PointTo(version VersionID) CurrentTarget { return CurrentTarget{version} }

// Version is the version directory the target names.
func (c CurrentTarget) Version() VersionID { return c.version }

// String is the exact link target string.
func (c CurrentTarget) String() string { return "versions/" + c.version.String() }

// VersionPath is the R-relative path of a version directory.
func VersionPath(version VersionID) RelativePath {
	return MustRelativePath("versions/" + version.String())
}

// Paths of the records inside a version directory, relative to it.
var (
	PayloadManifestPath  = MustRelativePath("share/kast/installation.json")
	ProductSelectionPath = MustRelativePath("share/kast/product-selection.json")
	BuildRecordPath      = MustRelativePath("share/kast/build-record.json")
	LayoutMarkerPath     = MustRelativePath("share/kast/layout-v2")
)

// LayoutMarker is the exact content of share/kast/layout-v2.
const LayoutMarker = "2\n"

// FileMode is the closed set of payload file modes.
type FileMode uint8

const (
	ModeReadOnly FileMode = iota + 1
	ModeExecutable
)

var modeNames = map[FileMode]string{ModeReadOnly: "0444", ModeExecutable: "0555"}

// ParseFileMode admits "0444" or "0555".
func ParseFileMode(text string) (FileMode, bool) { return parseEnum(modeNames, text) }

func (m FileMode) String() string { return modeNames[m] }

// MarshalText writes the mode.
func (m FileMode) MarshalText() ([]byte, error) { return marshalEnum(modeNames, m) }

// LinkTarget is a relative symbolic link target: not empty, not absolute, clean, without NUL, at most 512 bytes. It
// may climb with "..", and a payload link admits it only when it resolves inside the version directory.
type LinkTarget struct{ text string }

// ParseLinkTarget admits a clean relative link target.
func ParseLinkTarget(text string) (LinkTarget, bool) {
	if text == "" || len(text) > 512 || !utf8.ValidString(text) || strings.ContainsRune(text, 0) ||
		strings.HasPrefix(text, "/") || path.Clean(text) != text || text == "." {
		return LinkTarget{}, false
	}
	return LinkTarget{text}, true
}

func (l LinkTarget) String() string { return l.text }

// MarshalText writes the target.
func (l LinkTarget) MarshalText() ([]byte, error) { return []byte(l.text), nil }

// PayloadFile is one regular file of a version directory.
type PayloadFile struct {
	Path   RelativePath `json:"path"`
	SHA256 Digest       `json:"sha256"`
	Bytes  int64        `json:"bytes"`
	Mode   FileMode     `json:"mode"`
}

// PayloadLink is one symbolic link of a version directory.
type PayloadLink struct {
	Path   RelativePath `json:"path"`
	Target LinkTarget   `json:"target"`
}

// PayloadManifest is V/share/kast/installation.json: every regular file except itself, and every link.
type PayloadManifest struct {
	version VersionID
	files   []PayloadFile
	links   []PayloadLink
}

// NewPayloadManifest admits a manifest: files and links each strictly sorted by path, paths unique across both, no
// entry beneath another, no entry for the manifest itself, sizes within bounds, and every link resolving inside V.
func NewPayloadManifest(version VersionID, files []PayloadFile, links []PayloadLink) (PayloadManifest, error) {
	if len(files)+len(links) > maximumPayloadEntries {
		return PayloadManifest{}, codec.Reject(codec.InvalidValue, "files")
	}
	seen := map[string]bool{}
	for i, file := range files {
		member := codec.Element("files", i)
		if file.Path == PayloadManifestPath || file.Bytes < 0 || file.Bytes >= maximumEntryBytes ||
			modeNames[file.Mode] == "" || file.Path.text == "" ||
			(i > 0 && files[i-1].Path.text >= file.Path.text) {
			return PayloadManifest{}, codec.Reject(codec.InvalidValue, member)
		}
		seen[file.Path.text] = true
	}
	for i, link := range links {
		member := codec.Element("links", i)
		resolved := path.Join(path.Dir(link.Path.text), link.Target.text)
		if link.Path.text == "" || link.Target.text == "" || seen[link.Path.text] ||
			resolved == "." || resolved == ".." || strings.HasPrefix(resolved, "../") ||
			(i > 0 && links[i-1].Path.text >= link.Path.text) {
			return PayloadManifest{}, codec.Reject(codec.InvalidValue, member)
		}
		seen[link.Path.text] = true
	}
	for entry := range seen {
		for parent := path.Dir(entry); parent != "."; parent = path.Dir(parent) {
			if seen[parent] {
				return PayloadManifest{}, codec.Reject(codec.InvalidValue, "files")
			}
		}
	}
	return PayloadManifest{version, slices.Clone(files), slices.Clone(links)}, nil
}

// Version is the version directory the manifest describes.
func (m PayloadManifest) Version() VersionID { return m.version }

// Files are the regular files, sorted by path.
func (m PayloadManifest) Files() []PayloadFile { return slices.Clone(m.files) }

// Links are the symbolic links, sorted by path.
func (m PayloadManifest) Links() []PayloadLink { return slices.Clone(m.links) }

// File returns the entry for a path.
func (m PayloadManifest) File(relative RelativePath) (PayloadFile, bool) {
	index, found := slices.BinarySearchFunc(m.files, relative.text, func(file PayloadFile, target string) int {
		return strings.Compare(file.Path.text, target)
	})
	if !found {
		return PayloadFile{}, false
	}
	return m.files[index], true
}

type payloadManifestWire struct {
	SchemaVersion int           `json:"schemaVersion"`
	Version       VersionID     `json:"version"`
	Files         []PayloadFile `json:"files"`
	Links         []PayloadLink `json:"links"`
}

// Encode writes the canonical bytes.
func (m PayloadManifest) Encode() []byte {
	return mustEncode(payloadManifestWire{1, m.version, nonNil(m.files), nonNil(m.links)})
}

// DecodePayloadManifest strictly decodes installation.json.
func DecodePayloadManifest(raw []byte) (PayloadManifest, error) {
	object, err := codec.Document(raw, MaximumPayloadManifestBytes)
	if err != nil {
		return PayloadManifest{}, err
	}
	if _, err := object.SchemaVersion(1); err != nil {
		return PayloadManifest{}, err
	}
	version, err := codec.Text(object, "version", ParseVersionID)
	if err != nil {
		return PayloadManifest{}, err
	}
	fileValues, err := object.Array("files")
	if err != nil {
		return PayloadManifest{}, err
	}
	files := make([]PayloadFile, 0, len(fileValues))
	for i, value := range fileValues {
		file, err := decodePayloadFile(value, codec.Element("files", i))
		if err != nil {
			return PayloadManifest{}, err
		}
		files = append(files, file)
	}
	linkValues, err := object.Array("links")
	if err != nil {
		return PayloadManifest{}, err
	}
	links := make([]PayloadLink, 0, len(linkValues))
	for i, value := range linkValues {
		member := codec.Element("links", i)
		entry, err := codec.ObjectValue(value, member)
		if err != nil {
			return PayloadManifest{}, err
		}
		linkPath, err := codec.Text(entry, "path", ParseRelativePath)
		if err != nil {
			return PayloadManifest{}, err
		}
		target, err := codec.Text(entry, "target", ParseLinkTarget)
		if err != nil {
			return PayloadManifest{}, err
		}
		if err := entry.Closed(); err != nil {
			return PayloadManifest{}, err
		}
		links = append(links, PayloadLink{linkPath, target})
	}
	if err := object.Closed(); err != nil {
		return PayloadManifest{}, err
	}
	return NewPayloadManifest(version, files, links)
}

func decodePayloadFile(raw json.RawMessage, member string) (PayloadFile, error) {
	entry, err := codec.ObjectValue(raw, member)
	if err != nil {
		return PayloadFile{}, err
	}
	filePath, err := codec.Text(entry, "path", ParseRelativePath)
	if err != nil {
		return PayloadFile{}, err
	}
	digest, err := codec.Text(entry, "sha256", ParseDigest)
	if err != nil {
		return PayloadFile{}, err
	}
	size, err := entry.Int("bytes")
	if err != nil {
		return PayloadFile{}, err
	}
	mode, err := codec.Text(entry, "mode", ParseFileMode)
	if err != nil {
		return PayloadFile{}, err
	}
	return PayloadFile{filePath, digest, size, mode}, entry.Closed()
}

// ProductSelection is V/share/kast/product-selection.json at schemaVersion 2: why this V exists and which store
// entries it binds.
type ProductSelection struct {
	version         VersionID
	target          Target
	recordSHA256    Digest
	identity        Identity
	sourceRevision  Revision
	runtimeArchive  Digest
	runtimeManifest Digest
	base            *Digest
}

// NewProductSelection builds a selection from admitted parts.
func NewProductSelection(version VersionID, target Target, recordSHA256 Digest, identity Identity,
	sourceRevision Revision, runtimeArchive, runtimeManifest Digest, base *Digest) ProductSelection {
	var bound *Digest
	if base != nil {
		copied := *base
		bound = &copied
	}
	return ProductSelection{version, target, recordSHA256, identity, sourceRevision, runtimeArchive,
		runtimeManifest, bound}
}

// Version is the version directory the selection belongs to.
func (p ProductSelection) Version() VersionID { return p.version }

// Target is the release target.
func (p ProductSelection) Target() Target { return p.target }

// RecordSHA256 is the digest of the build record bytes.
func (p ProductSelection) RecordSHA256() Digest { return p.recordSHA256 }

// Identity is the release tag, policy and public version.
func (p ProductSelection) Identity() Identity { return p.identity }

// SourceRevision is the build's source revision.
func (p ProductSelection) SourceRevision() Revision { return p.sourceRevision }

// RuntimeArchive names the bound runtime entry S/kast-workspaces/runtimes/<sha>.
func (p ProductSelection) RuntimeArchive() Digest { return p.runtimeArchive }

// RuntimeManifest is the digest of the bound runtime entry's manifest.json.
func (p ProductSelection) RuntimeManifest() Digest { return p.runtimeManifest }

// Base names the bound base entry, if the build has one.
func (p ProductSelection) Base() (Digest, bool) {
	if p.base == nil {
		return Digest{}, false
	}
	return *p.base, true
}

type productSelectionWire struct {
	SchemaVersion         int        `json:"schemaVersion"`
	Version               VersionID  `json:"version"`
	Target                Target     `json:"target"`
	RecordSHA256          Digest     `json:"recordSha256"`
	ReleaseTag            ReleaseTag `json:"releaseTag"`
	Policy                Policy     `json:"policy"`
	PublicVersion         *SemVer    `json:"publicVersion"`
	SourceRevision        Revision   `json:"sourceRevision"`
	RuntimeArchiveSHA256  Digest     `json:"runtimeArchiveSha256"`
	RuntimeManifestSHA256 Digest     `json:"runtimeManifestSha256"`
	BaseSHA256            *Digest    `json:"baseSha256"`
}

// Encode writes the canonical bytes at schemaVersion 2.
func (p ProductSelection) Encode() []byte {
	return mustEncode(productSelectionWire{2, p.version, p.target, p.recordSHA256, p.identity.tag,
		p.identity.policy, p.identity.PublicVersion(), p.sourceRevision, p.runtimeArchive, p.runtimeManifest, p.base})
}

// DecodeProductSelection strictly decodes product-selection.json. It decodes schemaVersion 2 only: version 1 is the
// legacy shape, and any other version is from another binary.
func DecodeProductSelection(raw []byte) (ProductSelection, error) {
	object, err := codec.Document(raw, MaximumRecordBytes)
	if err != nil {
		return ProductSelection{}, err
	}
	if _, err := object.SchemaVersion(2); err != nil {
		return ProductSelection{}, err
	}
	version, err := codec.Text(object, "version", ParseVersionID)
	if err != nil {
		return ProductSelection{}, err
	}
	target, err := codec.Text(object, "target", ParseTarget)
	if err != nil {
		return ProductSelection{}, err
	}
	recordSHA, err := codec.Text(object, "recordSha256", ParseDigest)
	if err != nil {
		return ProductSelection{}, err
	}
	identity, err := DecodeIdentity(object)
	if err != nil {
		return ProductSelection{}, err
	}
	revision, err := codec.Text(object, "sourceRevision", ParseRevision)
	if err != nil {
		return ProductSelection{}, err
	}
	runtimeArchive, err := codec.Text(object, "runtimeArchiveSha256", ParseDigest)
	if err != nil {
		return ProductSelection{}, err
	}
	runtimeManifest, err := codec.Text(object, "runtimeManifestSha256", ParseDigest)
	if err != nil {
		return ProductSelection{}, err
	}
	base, err := codec.NullableText(object, "baseSha256", ParseDigest)
	if err != nil {
		return ProductSelection{}, err
	}
	if err := object.Closed(); err != nil {
		return ProductSelection{}, err
	}
	return NewProductSelection(version, target, recordSHA, identity, revision, runtimeArchive, runtimeManifest,
		base), nil
}

// DecodeIdentity reads releaseTag, policy and the explicit publicVersion, and admits their agreement.
func DecodeIdentity(object codec.Object) (Identity, error) {
	tag, err := codec.Text(object, "releaseTag", ParseReleaseTag)
	if err != nil {
		return Identity{}, err
	}
	policy, err := codec.Text(object, "policy", ParsePolicy)
	if err != nil {
		return Identity{}, err
	}
	public, err := codec.NullableText(object, "publicVersion", ParseSemVer)
	if err != nil {
		return Identity{}, err
	}
	identity, ok := NewIdentity(tag, policy, public)
	if !ok {
		return Identity{}, codec.Reject(codec.InvalidValue, object.Member("publicVersion"))
	}
	return identity, nil
}

// RuntimeReceipt is S/kast-workspaces/runtimes/<archive sha>/runtime.json.
type RuntimeReceipt struct {
	archive  Digest
	manifest Digest
}

// NewRuntimeReceipt builds a receipt.
func NewRuntimeReceipt(archive, manifest Digest) RuntimeReceipt {
	return RuntimeReceipt{archive, manifest}
}

// Archive is the runtime archive digest, which names the entry directory.
func (r RuntimeReceipt) Archive() Digest { return r.archive }

// Manifest is the digest of the entry's manifest.json.
func (r RuntimeReceipt) Manifest() Digest { return r.manifest }

type runtimeReceiptWire struct {
	SchemaVersion  int    `json:"schemaVersion"`
	ArchiveSHA256  Digest `json:"archiveSha256"`
	ManifestSHA256 Digest `json:"manifestSha256"`
}

// Encode writes the canonical bytes.
func (r RuntimeReceipt) Encode() []byte {
	return mustEncode(runtimeReceiptWire{1, r.archive, r.manifest})
}

// DecodeRuntimeReceipt strictly decodes runtime.json. Member order is not significant, so receipts written by the
// existing Python publisher decode too.
func DecodeRuntimeReceipt(raw []byte) (RuntimeReceipt, error) {
	object, err := codec.Document(raw, MaximumRecordBytes)
	if err != nil {
		return RuntimeReceipt{}, err
	}
	if _, err := object.SchemaVersion(1); err != nil {
		return RuntimeReceipt{}, err
	}
	archive, err := codec.Text(object, "archiveSha256", ParseDigest)
	if err != nil {
		return RuntimeReceipt{}, err
	}
	manifest, err := codec.Text(object, "manifestSha256", ParseDigest)
	if err != nil {
		return RuntimeReceipt{}, err
	}
	return RuntimeReceipt{archive, manifest}, object.Closed()
}

// RuntimeManifest is a runtime entry's manifest.json. It comes unchanged from the runtime archive, so the lifecycle
// core binds it by digest and never re-encodes it.
type RuntimeManifest struct{ digest Digest }

// BindRuntimeManifest admits manifest bytes within the size limit and records their digest.
func BindRuntimeManifest(raw []byte) (RuntimeManifest, error) {
	if len(raw) > MaximumRuntimeManifestBytes {
		return RuntimeManifest{}, codec.Reject(codec.TooLarge, "")
	}
	return RuntimeManifest{DigestOf(raw)}, nil
}

// Digest is the manifest's SHA-256 digest.
func (m RuntimeManifest) Digest() Digest { return m.digest }

// Binds reports whether an entry's receipt and manifest agree with each other, with the entry's directory name and
// with the product selection that binds it.
func (r RuntimeReceipt) Binds(entry Digest, manifest RuntimeManifest, selection ProductSelection) bool {
	return r.archive == entry && r.manifest == manifest.digest && selection.runtimeArchive == entry &&
		selection.runtimeManifest == manifest.digest
}

func nonNil[T any](values []T) []T {
	if values == nil {
		return []T{}
	}
	return values
}

func mustEncode(value any) []byte {
	encoded, err := codec.Encode(value)
	if err != nil {
		panic("record: an admitted record failed to encode: " + err.Error())
	}
	return encoded
}
