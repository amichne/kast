package install

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/v2"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"time"
)

// The Control payload is one archive inside the published bundle. managed_release.py extract_control bounds it.
const (
	maximumControlEntries = 16_384
	maximumControlBytes   = int64(1) << 30
	maximumEnvironment    = 256 << 10
	maximumOverlay        = 64 << 10
	maximumCatalog        = 4 << 20
	archiveTimeout        = 2 * time.Minute
	managementPath        = "share/kast/libexec/kast-management"
	catalogPath           = "share/kast/frontend.json"
	defaultEnvironment    = "# Kast runtime configuration. Values are literal; shell syntax is not evaluated.\n"
)

var (
	payloadRoots  = []string{"bin", "lib", "share"}
	workspaceName = regexp.MustCompile(`^[0-9a-f]{64}$`)
	// These host-interface adapters select the installation's configuration, then run their raw launcher.
	completeLaunchers = []string{"kast", "kast-query", "kast-mcp", "kast-codex"}
)

// productReference is ProductReference in managed_product.py: product-selection.json and the management receipt's
// product.
type productReference struct {
	ReleaseTag            string  `json:"releaseTag"`
	PublicVersion         *string `json:"publicVersion"`
	Version               string  `json:"version"`
	SourceRevision        string  `json:"sourceRevision"`
	SourceTree            string  `json:"sourceTree"`
	BuildRecordSHA256     string  `json:"buildRecordSha256"`
	Target                string  `json:"target"`
	BundleSHA256          string  `json:"bundleSha256"`
	QualificationSHA256   string  `json:"qualificationSha256"`
	RuntimeRoot           string  `json:"runtimeRoot"`
	RuntimeArchiveSHA256  string  `json:"runtimeArchiveSha256"`
	RuntimeManifestSHA256 string  `json:"runtimeManifestSha256"`
	Policy                string  `json:"policy"`
	SchemaVersion         int     `json:"schemaVersion"`
}

// installationManifest is InstallationManifest in managed_product_installation.py, which the JVM admits.
type installationManifest struct {
	InstallationRoot  string        `json:"installationRoot"`
	StateRoot         string        `json:"stateRoot"`
	Configuration     string        `json:"configuration"`
	WorkspaceRegistry string        `json:"workspaceRegistry"`
	CodexHome         string        `json:"codexHome"`
	PayloadFiles      []payloadFile `json:"payloadFiles"`
	ControlSHA256     string        `json:"controlSha256"`
	PayloadIdentity   string        `json:"payloadIdentity"`
	SemanticVersion   string        `json:"semanticVersion"`
	SchemaVersion     int           `json:"schemaVersion"`
	ExternalAnchors   []string      `json:"externalAnchors"`
}

type payloadFile struct {
	Path   string      `json:"path"`
	SHA256 string      `json:"sha256"`
	Mode   fs.FileMode `json:"mode"`
}

// controlSource is what one Control payload is staged from.
type controlSource struct {
	bundle, version, target string
	// management is the qualified digest of the management executable.
	management string
	record     []byte
	reference  productReference
	// prior is the installation whose client configuration the payload keeps, or empty.
	prior, home string
}

// extractControl writes the bundle's Control archive into stage and returns that archive's digest.
func extractControl(source *controlSource, stage string) (string, error) {
	file, err := os.Open(source.bundle)
	if err != nil {
		return "", reject(StorageRejected, "%v", err)
	}
	defer file.Close()
	compressed, err := gzip.NewReader(file)
	if err != nil {
		return "", invalidControl("the bundle is not a gzip file: %v", err)
	}
	bundle := tar.NewReader(compressed)
	name := "setup-kast/assets/kast-control-v" + source.version + "-" + source.target + ".tar.gz"
	found := ""
	for {
		header, err := bundle.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return "", invalidControl("the bundle cannot be read: %v", err)
		}
		if header.Name != name {
			continue
		}
		if found != "" || header.Typeflag != tar.TypeReg {
			return "", invalidControl("the bundle holds %s twice or not as a file", name)
		}
		hash := sha256.New()
		if err := extractPayload(io.TeeReader(bundle, hash), stage); err != nil {
			return "", err
		}
		// The archive's digest covers every byte, including what follows its last member.
		if _, err := io.Copy(hash, bundle); err != nil {
			return "", invalidControl("the bundle cannot be read: %v", err)
		}
		found = hex.EncodeToString(hash.Sum(nil))
	}
	if found == "" {
		return "", invalidControl("the bundle lacks %s", name)
	}
	management, err := fileDigest(filepath.Join(stage, managementPath))
	if err != nil || management != source.management {
		return "", invalidControl("the management executable is not the qualified one")
	}
	return found, nil
}

// extractPayload admits only directories and regular files beneath bin, lib and share.
func extractPayload(archive io.Reader, stage string) error {
	compressed, err := gzip.NewReader(archive)
	if err != nil {
		return invalidControl("the Control archive is not a gzip file: %v", err)
	}
	payload := tar.NewReader(compressed)
	seen := map[string]bool{}
	var total int64
	for count := 0; ; count++ {
		header, err := payload.Next()
		if errors.Is(err, io.EOF) {
			if count == 0 {
				return invalidControl("the Control archive is empty")
			}
			return nil
		}
		if err != nil {
			return invalidControl("the Control archive cannot be read: %v", err)
		}
		name := strings.TrimSuffix(header.Name, "/")
		total += header.Size
		root, _, _ := strings.Cut(name, "/")
		if count >= maximumControlEntries || total > maximumControlBytes || header.Size < 0 || !safePath(name) ||
			strings.Count(name, "/") >= 32 || !slices.Contains(payloadRoots, root) || seen[name] ||
			(header.Typeflag != tar.TypeDir && header.Typeflag != tar.TypeReg) {
			return invalidControl("the Control archive holds the entry %q", header.Name)
		}
		seen[name] = true
		destination := filepath.Join(stage, filepath.FromSlash(name))
		if header.Typeflag == tar.TypeDir {
			if err := os.MkdirAll(destination, 0o755); err != nil {
				return storageFailure(err)
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(destination), 0o755); err != nil {
			return storageFailure(err)
		}
		if err := writeSealed(destination, io.LimitReader(payload, header.Size), header.FileInfo().Mode().Perm()); err != nil {
			return err
		}
	}
}

// stageRecords adds what the installer owns beside the archived payload: the build's records, the client
// configuration it keeps, and the launchers that select it.
func stageRecords(source *controlSource, stage string) error {
	metadata := filepath.Join(stage, "share", "kast")
	for _, record := range []struct {
		name string
		data []byte
	}{{"build-record.json", source.record}, {"product-selection.json", encodedLine(source.reference)}} {
		if err := writeSealed(filepath.Join(metadata, record.name), strings.NewReader(string(record.data)), 0o644); err != nil {
			return err
		}
	}
	if err := stageConfiguration(source.prior, stage); err != nil {
		return err
	}
	for _, executable := range completeLaunchers {
		script := "#!/bin/sh\nset -eu\n" +
			"[ ! -L \"$0\" ] || exit 1\n" +
			"script_dir=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd -P)\n" +
			"installation_root=$(CDPATH= cd -- \"$script_dir/..\" && pwd -P)\n" +
			"if [ -z \"${KAST_CONFIGURATION_FILE+x}\" ]; then\n" +
			"  export KAST_CONFIGURATION_FILE=\"$installation_root/config/environment\"\n" +
			"fi\nunset KAST_SESSION_ROOT\n" +
			"exec \"$script_dir/" + executable + "\" \"$@\"\n"
		launcher := filepath.Join(stage, "bin", executable+"-complete")
		if err := writeSealed(launcher, strings.NewReader(script), 0o755); err != nil {
			return err
		}
	}
	return nil
}

// stageConfiguration carries the prior installation's client configuration: its environment and each workspace's
// environment. Any other entry there stops the upgrade rather than being lost.
func stageConfiguration(prior, stage string) error {
	configuration := filepath.Join(stage, "config")
	if err := os.Mkdir(configuration, 0o700); err != nil {
		return storageFailure(err)
	}
	environment := []byte(defaultEnvironment)
	if prior != "" {
		kept := filepath.Join(prior, "config")
		names, err := entryNames(kept)
		if err != nil {
			return reject(InstallationDamaged, "the client configuration is unreadable: %v", err)
		}
		for _, name := range names {
			switch name {
			case "environment":
				if environment, err = readRegular(filepath.Join(kept, name), maximumEnvironment); err != nil {
					return reject(InstallationDamaged, "config/environment is unreadable: %v", err)
				}
			case "workspaces":
				if err := stageOverlays(filepath.Join(kept, name), filepath.Join(configuration, name)); err != nil {
					return err
				}
			default:
				return reject(InstallationDamaged, "config/%s is not client configuration that an upgrade keeps", name)
			}
		}
	}
	return writeSealed(filepath.Join(configuration, "environment"), strings.NewReader(string(environment)), 0o600)
}

func stageOverlays(kept, staged string) error {
	workspaces, err := entryNames(kept)
	if err != nil {
		return reject(InstallationDamaged, "config/workspaces is unreadable: %v", err)
	}
	for _, workspace := range workspaces {
		names, err := entryNames(filepath.Join(kept, workspace))
		if !workspaceName.MatchString(workspace) || err != nil || len(names) > 1 ||
			(len(names) == 1 && names[0] != "environment") {
			return reject(InstallationDamaged, "config/workspaces/%s is not a workspace environment", workspace)
		}
		// An interrupted first save or removal can leave the workspace directory empty.
		if len(names) == 0 {
			continue
		}
		overlay, err := readRegular(filepath.Join(kept, workspace, "environment"), maximumOverlay)
		if err != nil {
			return reject(InstallationDamaged, "config/workspaces/%s/environment is unreadable: %v", workspace, err)
		}
		directory := filepath.Join(staged, workspace)
		if err := os.MkdirAll(directory, 0o700); err != nil {
			return storageFailure(err)
		}
		if err := writeSealed(filepath.Join(directory, "environment"), strings.NewReader(string(overlay)), 0o600); err != nil {
			return err
		}
	}
	return nil
}

// entryNames lists a directory reached without a link. An absent directory has no entries.
func entryNames(directory string) ([]string, error) {
	info, err := os.Lstat(directory)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil || !info.IsDir() {
		return nil, errors.New("not a directory")
	}
	entries, err := os.ReadDir(directory)
	var names []string
	for _, entry := range entries {
		names = append(names, entry.Name())
	}
	return names, err
}

// controlEntry is the JVM entry of frontend.json, which names the class-data archive and how to create it.
type controlEntry struct {
	MainClass       string            `json:"mainClass"`
	ClassPath       []string          `json:"classPath"`
	JavaHomes       map[string]string `json:"javaHomes"`
	SharedArchive   string            `json:"sharedArchive"`
	ArchiveTraining []string          `json:"archiveTraining"`
}

// trainArchive creates the JVM entry's class-data archive with the installed runtime. The archive only shortens
// startup, so a failed training leaves no archive and the upgrade continues; it reports whether one was created.
func trainArchive(ctx context.Context, run runner, stage, runtime, target, temporary string) (bool, error) {
	raw, err := readRegular(filepath.Join(stage, catalogPath), maximumCatalog)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	var catalog struct {
		Control *controlEntry `json:"control"`
	}
	if err != nil || json.Unmarshal(raw, &catalog) != nil || catalog.Control == nil {
		return false, invalidControl("the Control catalog is unreadable")
	}
	entry := catalog.Control
	home := entry.JavaHomes[target]
	paths := append([]string{entry.SharedArchive, home}, entry.ClassPath...)
	if entry.MainClass == "" || home == "" || !strings.HasSuffix(entry.SharedArchive, ".jsa") || len(entry.ClassPath) == 0 ||
		slices.ContainsFunc(paths, func(path string) bool { return !safePath(path) }) {
		return false, invalidControl("the Control catalog names an invalid JVM entry")
	}
	archive := filepath.Join(stage, filepath.FromSlash(entry.SharedArchive))
	if present(archive) {
		return false, invalidControl("the Control payload already holds %s", entry.SharedArchive)
	}
	var classPath []string
	for _, path := range entry.ClassPath {
		classPath = append(classPath, filepath.Join(stage, filepath.FromSlash(path)))
	}
	command := append([]string{filepath.Join(runtime, filepath.FromSlash(home), "bin", "java"),
		"-XX:ArchiveClassesAtExit=" + archive, "-cp", strings.Join(classPath, ":"), entry.MainClass},
		entry.ArchiveTraining...)
	// Training reads no installation state; an empty HOME keeps it from reaching any.
	empty, err := os.MkdirTemp(temporary, "kast-control-archive-")
	if err != nil {
		return false, storageFailure(err)
	}
	defer os.RemoveAll(empty)
	status, err := run(ctx, command, []string{"HOME=" + empty, "PATH=/usr/bin:/bin", "LC_ALL=C"}, archiveTimeout)
	info, failure := os.Lstat(archive)
	if err != nil || status != 0 || failure != nil || !info.Mode().IsRegular() {
		os.Remove(archive)
		return false, nil
	}
	return true, storageFailure(os.Chmod(archive, 0o644))
}

// sealManifest records every payload file beneath bin, lib and share, in the order Python sorts its paths: part by
// part.
func sealManifest(source *controlSource, stage, installation, controlSHA string) error {
	var files []payloadFile
	for _, root := range payloadRoots {
		err := filepath.WalkDir(filepath.Join(stage, root), func(path string, entry os.DirEntry, err error) error {
			if err != nil || entry.IsDir() {
				return err
			}
			info, err := entry.Info()
			if err != nil || !info.Mode().IsRegular() {
				return errors.Join(err, errors.New(path+" is not a regular file"))
			}
			sum, err := fileDigest(path)
			relative, _ := filepath.Rel(stage, path)
			files = append(files, payloadFile{filepath.ToSlash(relative), "sha256:" + sum, info.Mode().Perm()})
			return err
		})
		if err != nil && !(root == "lib" && errors.Is(err, os.ErrNotExist)) {
			return storageFailure(err)
		}
	}
	slices.SortFunc(files, func(a, b payloadFile) int {
		return slices.Compare(strings.Split(a.Path, "/"), strings.Split(b.Path, "/"))
	})
	manifest := installationManifest{
		InstallationRoot: installation, StateRoot: filepath.Join(installation, "state"),
		Configuration:     filepath.Join(installation, "config", "environment"),
		WorkspaceRegistry: filepath.Join(installation, "config", "workspaces.json"),
		CodexHome:         filepath.Join(source.home, ".codex"), PayloadFiles: files, ControlSHA256: "sha256:" + controlSHA,
		PayloadIdentity: "sha256:" + source.reference.BuildRecordSHA256, SemanticVersion: source.reference.Version,
		SchemaVersion: manifestSchema, ExternalAnchors: []string{},
	}
	raw, err := json.Marshal(manifest)
	if err != nil {
		return storageFailure(err)
	}
	return writeSealed(filepath.Join(stage, "installation.json"), strings.NewReader(string(raw)), 0o600)
}

func invalidControl(format string, values ...any) error {
	return reject(DownloadInvalid, format, values...)
}
