package install

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"slices"
	"strings"
	"syscall"
)

// A build's runtime is composed from two published files. The runtime archive holds the build's own files and a
// manifest that lists every file of the runtime with its digest; the base archive holds the IntelliJ files that many
// builds share. Every composed byte is checked against that manifest, whose archive the build record admits.
const (
	runtimeManifestName    = "manifest.json"
	runtimeMarkerName      = "runtime-base.json"
	baseManifestName       = "base-manifest.json"
	maximumRuntimeManifest = 16 << 20
	maximumRuntimeEntries  = 100_000
	maximumRuntimeBytes    = int64(8) << 30
	maximumLinkBytes       = 4096
	maximumQualification   = 1 << 20
)

// runtimeEntry is RuntimeFile or RuntimeLink in runtime_archive.py. A file has a size, digest and executable flag; a
// link has only its target.
type runtimeEntry struct {
	Type       string `json:"type"`
	Path       string `json:"path"`
	Bytes      *int64 `json:"bytes"`
	SHA256     string `json:"sha256"`
	Executable *bool  `json:"executable"`
	Target     string `json:"target"`
}

func (e *runtimeEntry) link() bool { return e.Type == "SYMLINK" }

// runtimeManifest is RuntimeManifest in runtime_archive.py.
type runtimeManifest struct {
	SchemaVersion      int            `json:"schemaVersion"`
	Kind               string         `json:"kind"`
	Version            string         `json:"version"`
	Platform           string         `json:"platform"`
	PlatformBuild      string         `json:"platformBuild"`
	DistributionSHA256 string         `json:"distributionSha256"`
	BootstrapSHA256    string         `json:"bootstrapSha256"`
	Files              []runtimeEntry `json:"files"`
}

// baseReference is Composition in runtime_base.py: the runtime archive's name for the base it was split from.
type baseReference struct {
	SchemaVersion int    `json:"schemaVersion"`
	Kind          string `json:"kind"`
	Target        string `json:"target"`
	Base          Asset  `json:"base"`
}

// The runtime store's records. Each is the Python dataclass of the same name, encoded as managed_installation.encoded
// encodes it, because the JVM and the reset read them.
type (
	runtimeReceipt struct {
		ArchiveSHA256  string `json:"archiveSha256"`
		ManifestSHA256 string `json:"manifestSha256"`
		SchemaVersion  int    `json:"schemaVersion"`
	}
	assemblyRecord struct {
		SchemaVersion      int    `json:"schemaVersion"`
		Installation       string `json:"installation"`
		DistributionSHA256 string `json:"distributionSha256"`
		BootstrapSHA256    string `json:"bootstrapSha256"`
		PlatformBuild      string `json:"platformBuild"`
		KotlinPluginBuild  string `json:"kotlinPluginBuild"`
	}
	provenanceRecord struct {
		Kind           string `json:"kind"`
		Target         string `json:"target"`
		ArchiveSHA256  string `json:"archiveSha256"`
		RecordSHA256   string `json:"recordSha256"`
		ManifestSHA256 string `json:"manifestSha256"`
		SchemaVersion  int    `json:"schemaVersion"`
	}
)

// hostPrefix is where the IDE's files start inside a runtime of the target.
func hostPrefix(target string) string {
	if strings.HasPrefix(target, "linux-") {
		return "host"
	}
	return "host/Kast.app/Contents"
}

// runtimeSource is one admitted runtime: its archives, the facts that name them, and its manifest.
type runtimeSource struct {
	runtime, base, qualification string
	facts                        *targetDocument
	record                       []byte
	recordSHA, target            string
	manifest                     runtimeManifest
	manifestRaw                  []byte
	manifestSHA                  string
}

// runtimeRoot is where the store keeps the runtime that the archive digest names.
func runtimeRoot(share, archiveSHA string) string {
	return filepath.Join(share, "kast-workspaces", "runtimes", archiveSHA)
}

// readRuntimeManifest admits the manifest of the runtime archive without composing anything.
func readRuntimeManifest(source *runtimeSource, version string) error {
	archive, err := zip.OpenReader(source.runtime)
	if err != nil {
		return invalidRuntime("the runtime archive is not a ZIP file: %v", err)
	}
	defer archive.Close()
	files, err := members(&archive.Reader, "runtime")
	if err != nil {
		return err
	}
	raw, err := member(files, runtimeManifestName, maximumRuntimeManifest)
	if err != nil {
		return err
	}
	var manifest runtimeManifest
	if err := strict(raw, &manifest); err != nil {
		return invalidRuntime("the runtime manifest is invalid: %v", err)
	}
	if manifest.SchemaVersion != 1 || manifest.Kind != "KAST_RUNTIME_V1" || manifest.Version != version ||
		manifest.Platform != source.target || !digestForm.MatchString(manifest.DistributionSHA256) ||
		!digestForm.MatchString(manifest.BootstrapSHA256) || manifest.PlatformBuild == "" {
		return invalidRuntime("the runtime manifest names another build")
	}
	if err := admitEntries(manifest, source.target); err != nil {
		return err
	}
	source.manifest, source.manifestRaw, source.manifestSHA = manifest, raw, sha256Hex(raw)
	return nil
}

// admitEntries checks every path, size and link of the manifest before any file is written.
func admitEntries(manifest runtimeManifest, target string) error {
	if len(manifest.Files) == 0 || len(manifest.Files) > maximumRuntimeEntries {
		return invalidRuntime("the runtime manifest lists %d files", len(manifest.Files))
	}
	names := make(map[string]bool, len(manifest.Files))
	var total int64
	bootstrap := hostPrefix(target) + "/third-party-plugins/kast-managed-headless/lib/kast-managed-headless.jar"
	booted := false
	for index := range manifest.Files {
		entry := &manifest.Files[index]
		if !safePath(entry.Path) || !(strings.HasPrefix(entry.Path, "host/") ||
			strings.HasPrefix(entry.Path, "components/plugins/")) || names[entry.Path] {
			return invalidRuntime("the runtime manifest lists the path %q", entry.Path)
		}
		names[entry.Path] = true
		switch entry.Type {
		case "FILE":
			if entry.Bytes == nil || *entry.Bytes < 0 || !digestForm.MatchString(entry.SHA256) ||
				entry.Executable == nil || entry.Target != "" {
				return invalidRuntime("the runtime manifest lists an invalid file %q", entry.Path)
			}
			total += *entry.Bytes
			booted = booted || (entry.Path == bootstrap && entry.SHA256 == manifest.BootstrapSHA256)
		case "SYMLINK":
			resolved := path.Clean(path.Join(path.Dir(entry.Path), entry.Target))
			if entry.Bytes != nil || entry.SHA256 != "" || entry.Executable != nil || !strings.HasPrefix(entry.Path, "host/") ||
				entry.Target == "" || len(entry.Target) > maximumLinkBytes || strings.ContainsAny(entry.Target, "\x00\r\n\\:") ||
				strings.HasPrefix(entry.Target, "/") || !strings.HasPrefix(resolved, "host/") {
				return invalidRuntime("the runtime manifest lists an invalid link %q", entry.Path)
			}
		default:
			return invalidRuntime("the runtime manifest lists an entry of type %q", entry.Type)
		}
	}
	if total > maximumRuntimeBytes {
		return invalidRuntime("the runtime holds %d bytes", total)
	}
	if !booted {
		return invalidRuntime("the runtime lacks its managed headless bootstrap")
	}
	// A file beneath another entry would be written through it.
	for name := range names {
		for parent := path.Dir(name); parent != "."; parent = path.Dir(parent) {
			if names[parent] {
				return invalidRuntime("the runtime manifest lists %q beneath another entry", name)
			}
		}
	}
	return nil
}

// safePath is a relative slash path with no empty, current or parent part.
func safePath(name string) bool {
	if name == "" || len(name) > 4096 || strings.ContainsAny(name, "\\\x00\r\n") || strings.HasPrefix(name, "/") {
		return false
	}
	parts := strings.Split(name, "/")
	return len(parts) <= 64 && !slices.ContainsFunc(parts, func(part string) bool {
		return part == "" || part == "." || part == ".."
	})
}

// composeRuntime writes the runtime into directory, which must not exist, and seals it as the store does. Root is
// where the store will keep it, which the assembly record names.
func composeRuntime(source *runtimeSource, directory, root string) error {
	runtime, err := zip.OpenReader(source.runtime)
	if err != nil {
		return invalidRuntime("the runtime archive is not a ZIP file: %v", err)
	}
	defer runtime.Close()
	own, err := members(&runtime.Reader, "runtime")
	if err != nil {
		return err
	}
	// A local build's runtime archive holds every file, and names no base.
	shared := map[string]*zip.File{}
	if source.facts.RuntimeBase == nil {
		if own[runtimeMarkerName] != nil {
			return invalidRuntime("the runtime archive names a base that its record lacks")
		}
	} else {
		base, err := zip.OpenReader(source.base)
		if err != nil {
			return invalidRuntime("the base archive is not a ZIP file: %v", err)
		}
		defer base.Close()
		if shared, err = members(&base.Reader, "base"); err != nil {
			return err
		}
		marker, err := member(own, runtimeMarkerName, 64<<10)
		if err != nil {
			return err
		}
		var reference baseReference
		if strict(marker, &reference) != nil || reference.SchemaVersion != 1 ||
			reference.Kind != "KAST_RUNTIME_COMPOSITION_V1" || reference.Target != source.target ||
			reference.Base != *source.facts.RuntimeBase {
			return invalidRuntime("the runtime archive names another base")
		}
	}
	// Each entry comes from exactly one archive, and neither archive holds anything else.
	listed := map[string]bool{}
	for _, entry := range source.manifest.Files {
		listed[entry.Path] = true
		if (own[entry.Path] != nil) == (shared[entry.Path] != nil) {
			return invalidRuntime("%q is in neither or both archives", entry.Path)
		}
	}
	for name := range own {
		if !listed[name] && name != runtimeManifestName && name != runtimeMarkerName {
			return invalidRuntime("the runtime archive holds the unlisted %q", name)
		}
	}
	for name := range shared {
		if !listed[name] && name != baseManifestName {
			return invalidRuntime("the base archive holds the unlisted %q", name)
		}
	}
	if err := os.Mkdir(directory, 0o700); err != nil {
		return reject(StorageRejected, "%v", err)
	}
	for index := range source.manifest.Files {
		entry := &source.manifest.Files[index]
		file := own[entry.Path]
		if file == nil {
			file = shared[entry.Path]
		}
		if entry.link() {
			continue
		}
		if err := writeRuntimeFile(directory, entry, file); err != nil {
			return err
		}
	}
	// Links follow the files, so no file is ever written through one.
	for index := range source.manifest.Files {
		entry := &source.manifest.Files[index]
		if !entry.link() {
			continue
		}
		file := own[entry.Path]
		if file == nil {
			file = shared[entry.Path]
		}
		target, err := readMember(file, maximumLinkBytes)
		if err != nil || string(target) != entry.Target {
			return invalidRuntime("the archived link %q does not match the manifest", entry.Path)
		}
		destination := filepath.Join(directory, filepath.FromSlash(entry.Path))
		if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
			return reject(StorageRejected, "%v", err)
		}
		if err := os.Symlink(entry.Target, destination); err != nil {
			return reject(StorageRejected, "%v", err)
		}
	}
	return source.writeRecords(directory, root)
}

func writeRuntimeFile(directory string, entry *runtimeEntry, file *zip.File) error {
	if file.UncompressedSize64 != uint64(*entry.Bytes) {
		return invalidRuntime("%q has another size than its manifest", entry.Path)
	}
	destination := filepath.Join(directory, filepath.FromSlash(entry.Path))
	if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
		return reject(StorageRejected, "%v", err)
	}
	reader, err := file.Open()
	if err != nil {
		return invalidRuntime("%q cannot be read: %v", entry.Path, err)
	}
	defer reader.Close()
	mode := os.FileMode(0o444)
	if *entry.Executable {
		mode = 0o555
	}
	hash := sha256.New()
	if err := writeSealed(destination, io.TeeReader(io.LimitReader(reader, *entry.Bytes+1), hash), mode); err != nil {
		return err
	}
	if hex.EncodeToString(hash.Sum(nil)) != entry.SHA256 {
		return invalidRuntime("%q does not match its manifest", entry.Path)
	}
	return nil
}

// writeRecords adds the files the store and the JVM read beside the composed files, then seals every directory.
func (s *runtimeSource) writeRecords(directory, root string) error {
	prefix := hostPrefix(s.target)
	assembly := assemblyRecord{1, filepath.Join(root, filepath.FromSlash(prefix)), s.manifest.DistributionSHA256,
		s.manifest.BootstrapSHA256, s.manifest.PlatformBuild, s.manifest.PlatformBuild + "-IJ"}
	qualification, err := readRegular(s.qualification, maximumQualification)
	if err != nil {
		return reject(StorageRejected, "%v", err)
	}
	archive, err := os.Open(s.runtime)
	if err != nil {
		return reject(StorageRejected, "%v", err)
	}
	defer archive.Close()
	provenance := filepath.Join(directory, "provenance")
	if err := os.Mkdir(provenance, 0o700); err != nil {
		return reject(StorageRejected, "%v", err)
	}
	for _, record := range []struct {
		name string
		data []byte
	}{
		{runtimeManifestName, s.manifestRaw},
		{"runtime.json", encodedLine(runtimeReceipt{s.facts.Runtime.SHA256, s.manifestSHA, 1})},
		{filepath.Join(filepath.FromSlash(prefix), "kast-headless.json"), encodedLine(assembly)},
		{"provenance/build-record.json", s.record},
		{"provenance/qualification.json", qualification},
		{"provenance/provenance.json", encodedLine(provenanceRecord{"RUNTIME", s.target, s.facts.Runtime.SHA256,
			s.recordSHA, s.manifestSHA, 1})},
	} {
		if err := writeSealed(filepath.Join(directory, record.name), strings.NewReader(string(record.data)),
			0o444); err != nil {
			return err
		}
	}
	// The store keeps the original archive, from which a damaged runtime can be read again.
	if err := writeSealed(filepath.Join(provenance, "archive.zip"), archive, 0o444); err != nil {
		return err
	}
	return sealDirectories(directory)
}

// writeSealed creates one new file with its final mode and its durable bytes.
func writeSealed(destination string, data io.Reader, mode os.FileMode) error {
	file, err := os.OpenFile(destination, os.O_WRONLY|os.O_CREATE|os.O_EXCL|syscall.O_NOFOLLOW, 0o600)
	if err != nil {
		return storageFailure(err)
	}
	_, err = io.Copy(file, data)
	if err == nil {
		err = syscall.Fsync(int(file.Fd()))
	}
	if err = errors.Join(err, file.Close()); err != nil {
		return storageFailure(err)
	}
	return storageFailure(os.Chmod(destination, mode))
}

// sealDirectories makes every directory beneath root read-only and leaves root private and removable, as
// managed_product_installation.py does.
func sealDirectories(root string) error {
	var directories []string
	err := filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err == nil && entry.IsDir() && path != root {
			directories = append(directories, path)
		}
		return err
	})
	if err != nil {
		return reject(StorageRejected, "%v", err)
	}
	slices.Reverse(directories)
	for _, directory := range directories {
		if err := os.Chmod(directory, 0o555); err != nil {
			return reject(StorageRejected, "%v", err)
		}
	}
	return storageFailure(syncDirectory(root))
}

// reusableRuntime reports whether the store already holds this exact runtime, as the JVM admits it.
func reusableRuntime(root string, source *runtimeSource) (bool, error) {
	if !present(root) {
		return false, nil
	}
	receipt, err := readRegular(filepath.Join(root, "runtime.json"), maximumRecord)
	manifest, failure := readRegular(filepath.Join(root, runtimeManifestName), maximumRuntimeManifest)
	if errors.Join(err, failure) != nil || string(receipt) != string(encodedLine(runtimeReceipt{source.facts.Runtime.SHA256,
		source.manifestSHA, 1})) || sha256Hex(manifest) != source.manifestSHA {
		return false, reject(InstallationDamaged, "the runtime store entry %s does not match its records", root)
	}
	return true, nil
}

// members indexes an archive by name. A name may appear once, and never as a directory.
func members(archive *zip.Reader, name string) (map[string]*zip.File, error) {
	if len(archive.File) > maximumRuntimeEntries+2 {
		return nil, invalidRuntime("the %s archive holds %d entries", name, len(archive.File))
	}
	members := make(map[string]*zip.File, len(archive.File))
	for _, file := range archive.File {
		if members[file.Name] != nil || strings.HasSuffix(file.Name, "/") {
			return nil, invalidRuntime("the %s archive holds %q twice or as a directory", name, file.Name)
		}
		members[file.Name] = file
	}
	return members, nil
}

func member(members map[string]*zip.File, name string, limit int64) ([]byte, error) {
	file := members[name]
	if file == nil {
		return nil, invalidRuntime("the archive lacks %s", name)
	}
	raw, err := readMember(file, limit)
	if err != nil {
		return nil, invalidRuntime("%s cannot be read: %v", name, err)
	}
	return raw, nil
}

func readMember(file *zip.File, limit int64) ([]byte, error) {
	if file.UncompressedSize64 > uint64(limit) {
		return nil, errors.New("too large")
	}
	reader, err := file.Open()
	if err != nil {
		return nil, err
	}
	defer reader.Close()
	return io.ReadAll(io.LimitReader(reader, limit))
}

func invalidRuntime(format string, values ...any) error {
	return reject(DownloadInvalid, "%s", fmt.Sprintf(format, values...))
}

// encodedLine is managed_installation.encoded: one compact JSON document and a newline.
func encodedLine(document any) []byte {
	raw, err := json.Marshal(document)
	if err != nil {
		panic(err)
	}
	return append(raw, '\n')
}

func syncDirectory(directory string) error {
	file, err := os.Open(directory)
	if err != nil {
		return err
	}
	defer file.Close()
	return syscall.Fsync(int(file.Fd()))
}

// storageFailure names a full disk, which only freeing space resolves.
func storageFailure(err error) error {
	switch {
	case err == nil:
		return nil
	case errors.Is(err, syscall.ENOSPC):
		return &Rejection{Failure: StorageRejected, Detail: "the disk is full: " + err.Error(),
			Next: "Free disk space, then run the same command again."}
	default:
		return reject(StorageRejected, "%v", err)
	}
}
