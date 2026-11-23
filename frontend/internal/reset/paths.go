package reset

import (
	"bytes"
	"crypto/sha256"
	"debug/buildinfo"
	"encoding/hex"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"syscall"
)

// layout names the paths Kast creates beneath one admitted home. InstallationLayout.kt owns the same derivation for
// the JVM; each name below cites the source that writes it.
type layout struct {
	home, share, bin, agents, temporary string
	// root is the management root; workspaces and bases are its sibling stores (InstallationLayout.Share).
	root, workspaces, bases string
	installation            string
	uid                     int
}

// inventory is what exists now: exact deletion targets in deletion order, entries a reset keeps, and entries it skips
// with the reason. The public command is the last target, so an interrupted reset can be run again.
type inventory struct {
	targets, kept, skipped []string
	// owned are the directories whose executables belong to this installation.
	owned []string
	// payloads are the directories that can hold client configuration, and profiles the Hosts' private homes.
	payloads, profiles []string
}

const uuid = `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`

// Python's tempfile names end in eight of these characters.
const temporarySuffix = `[a-z0-9_]{8}`

var (
	// managed_product_installation.py (.kast-install-, .kast-prepare-), immutable_store.py (.kast-runtime-,
	// .kast-base-), installation-lifecycle.py (.kast-uninstall-stage-), and install/fetch.go (.kast-downloads).
	siblingName = regexp.MustCompile(`^\.kast-(install-` + uuid + `|prepare-` + temporarySuffix + `|(runtime|base)-` +
		temporarySuffix + `|uninstall-stage-[0-9a-f]{32}|downloads)$`)
	// managed_installation.py replace_file, beside the public command.
	stagedCommandName = regexp.MustCompile(`^\.kast-managed-` + uuid + `-command$`)
	// ServiceLoginAgent.kt: the published login agent and its temporary file.
	agentName = regexp.MustCompile(`^(io\.github\.amichne\.kast\.broker\.[0-9a-f]{32}\.login|\.kast-service-[0-9]+)\.plist$`)
)

const (
	// The main package of the public command, as the Go linker records it.
	commandPackage    = "github.com/amichne/kast/frontend/cmd/kast"
	maximumAgentBytes = 1 << 20
)

// admitLayout rejects a home that could widen a path, and any root that is not a directory of this user.
func admitLayout(home, temporary string, uid int) (*layout, error) {
	if home == "" || !filepath.IsAbs(home) {
		return nil, reject(HomeRejected, "HOME is not an absolute path: %q", home)
	}
	home = filepath.Clean(home)
	if home == string(filepath.Separator) {
		return nil, reject(HomeRejected, "HOME is the filesystem root")
	}
	if problem := rejectedDirectory(home, uid); problem != "" {
		return nil, reject(HomeRejected, "%s", problem)
	}
	share := filepath.Join(home, ".local", "share")
	paths := &layout{
		home: home, share: share, bin: filepath.Join(home, ".local", "bin"),
		agents: filepath.Join(home, "Library", "LaunchAgents"), temporary: temporary,
		root:         filepath.Join(share, "kast"),
		workspaces:   filepath.Join(share, "kast-workspaces"),
		bases:        filepath.Join(share, "kast-runtime-bases"),
		installation: filepath.Join(share, "kast", "installation"),
		uid:          uid,
	}
	for _, directory := range append([]string{filepath.Join(home, ".local"), share}, paths.roots()...) {
		if exists(directory) {
			if problem := rejectedDirectory(directory, uid); problem != "" {
				return nil, reject(RootRejected, "%s", problem)
			}
		}
	}
	return paths, nil
}

func (l *layout) roots() []string { return []string{l.root, l.workspaces, l.bases} }

// inventory reads directory entries only. A missing, damaged or unreadable record cannot change it.
func (l *layout) inventory() (*inventory, error) {
	found := &inventory{owned: l.roots()}
	found.payloads = []string{l.installation, filepath.Join(l.root, "recovery", "replacement", "payload")}
	if user := filepath.Join(l.home, ".kast"); exists(user) {
		found.kept = append(found.kept, user)
	}
	if err := l.addProfiles(found); err != nil {
		return nil, err
	}
	if err := l.addAgents(found); err != nil {
		return nil, err
	}
	l.addTemporary(found)
	for _, root := range l.roots() {
		if exists(root) {
			found.targets = append(found.targets, root)
		}
	}
	if err := l.addSiblings(found); err != nil {
		return nil, err
	}
	return found, l.addCommand(found)
}

// addProfiles finds the private homes of workspace Hosts, current and retired (ContainerStore.kt).
func (l *layout) addProfiles(found *inventory) error {
	containers := filepath.Join(l.workspaces, "containers")
	names, err := entries(containers, found)
	if err != nil {
		return err
	}
	for _, name := range names {
		container := filepath.Join(containers, name)
		found.profiles = append(found.profiles, filepath.Join(container, "state", "home"))
		generations := filepath.Join(container, "generations")
		retired, err := entries(generations, found)
		if err != nil {
			return err
		}
		for _, generation := range retired {
			found.profiles = append(found.profiles, filepath.Join(generations, generation, "state", "home"))
		}
	}
	return nil
}

// addAgents selects login agents by name and by a program beneath this installation.
func (l *layout) addAgents(found *inventory) error {
	names, err := entries(l.agents, found)
	if err != nil {
		return err
	}
	for _, name := range names {
		if !agentName.MatchString(name) {
			continue
		}
		path := filepath.Join(l.agents, name)
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() || info.Size() > maximumAgentBytes {
			found.skip(path, "not a login agent file")
			continue
		}
		raw, err := os.ReadFile(path)
		if err != nil || !bytes.Contains(raw, []byte("<string>"+escapeXML(l.installation)+"/")) {
			found.skip(path, "starts no program of this installation")
			continue
		}
		l.addOwned(found, path, info)
	}
	return nil
}

// addTemporary derives Kast's exact names in the shared temporary directory: the coordinator socket alias
// (InstalledEndpointAlias.kt), the Codex listener directory (InstalledUpstreamDirectory.kt), and each Host's endpoint
// directory (HostedEndpointPaths.kt).
func (l *layout) addTemporary(found *inventory) {
	run := filepath.Join(l.installation, "state", "run")
	alias := filepath.Join(l.temporary, "kast-uds-"+digest(run)[:32])
	if info, err := os.Lstat(alias); err == nil {
		if target, err := os.Readlink(alias); err == nil && target == run {
			l.addOwned(found, alias, info)
		} else {
			found.skip(alias, "does not link to this installation")
		}
	}
	directories := []string{"kast-codex-" + digest(run)[:32]}
	for _, profile := range found.profiles {
		directories = append(directories, "kast-"+digest(profile)[:32])
	}
	for _, name := range directories {
		path := filepath.Join(l.temporary, name)
		if info, err := os.Lstat(path); err == nil && info.IsDir() {
			l.addOwned(found, path, info)
		} else if err == nil {
			found.skip(path, "not a directory")
		}
	}
}

// addSiblings selects interrupted installer and uninstaller work beside the management root. The two lock files there
// hold no data and outlive every installation, so they stay.
func (l *layout) addSiblings(found *inventory) error {
	names, err := entries(l.share, found)
	if err != nil {
		return err
	}
	identity := digest(l.root)
	retiring := regexp.MustCompile(`^\.kast-retiring-` + identity[:32] + `-` + uuid + `$`)
	for _, name := range names {
		record := name == ".kast-uninstall-"+identity+".json" || name == ".kast-reset-"+identity[:32]+".json"
		if strings.HasPrefix(name, backupPrefix) {
			found.kept = append(found.kept, filepath.Join(l.share, name))
		}
		if !record && !siblingName.MatchString(name) && !retiring.MatchString(name) {
			continue
		}
		path := filepath.Join(l.share, name)
		info, err := os.Lstat(path)
		if err != nil || (!info.IsDir() && !info.Mode().IsRegular()) {
			found.skip(path, "neither a directory nor a file")
			continue
		}
		if l.addOwned(found, path, info) && info.IsDir() {
			found.owned = append(found.owned, path)
			found.payloads = append(found.payloads, path, filepath.Join(path, "payload", "installation"))
		}
	}
	return nil
}

// addCommand selects the public command last. The installer writes a copy of the Control executable, and refuses to
// install over any other file.
func (l *layout) addCommand(found *inventory) error {
	names, err := entries(l.bin, found)
	if err != nil {
		return err
	}
	// os.ReadDir sorts by name, so the staged copies precede the command.
	for _, name := range names {
		path := filepath.Join(l.bin, name)
		info, err := os.Lstat(path)
		switch {
		case err != nil || (name != "kast" && !stagedCommandName.MatchString(name)):
		case info.Mode()&fs.ModeSymlink != 0:
			target, err := os.Readlink(path)
			if err == nil && !filepath.IsAbs(target) {
				target = filepath.Join(l.bin, target)
			}
			if err == nil && beneath(filepath.Clean(target), l.root) {
				l.addOwned(found, path, info)
			} else {
				found.skip(path, "links outside this installation")
			}
		case info.Mode().IsRegular() && l.isCommand(path):
			l.addOwned(found, path, info)
		default:
			found.skip(path, "not Kast's command; the installer refuses to replace it")
		}
	}
	return nil
}

// isCommand recognizes the copy by the installed payload's bytes or by the main package the Go linker recorded.
func (l *layout) isCommand(path string) bool {
	if build, err := buildinfo.ReadFile(path); err == nil {
		return build.Path == commandPackage
	}
	payload, err := os.ReadFile(filepath.Join(l.installation, "share", "kast", "libexec", "kast-management"))
	if err != nil || len(payload) == 0 {
		return false
	}
	command, err := os.ReadFile(path)
	return err == nil && bytes.Equal(command, payload)
}

func (l *layout) addOwned(found *inventory, path string, info fs.FileInfo) bool {
	if owner(info) != l.uid {
		found.skip(path, "owned by another user")
		return false
	}
	found.targets = append(found.targets, path)
	return true
}

func (found *inventory) skip(path, reason string) {
	found.skipped = append(found.skipped, path+": "+reason)
}

// removeTree deletes one target and returns what it left. It deletes links themselves, and never descends into a
// link, another user's directory, or another filesystem.
func (l *layout) removeTree(path string) []string {
	parent, err := os.OpenRoot(filepath.Dir(path))
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return []string{path + ": " + err.Error()}
	}
	defer parent.Close()
	info, err := parent.Stat(".")
	if err != nil {
		return []string{path + ": " + err.Error()}
	}
	return l.removeEntry(parent, path, device(info))
}

func (l *layout) removeEntry(parent *os.Root, path string, filesystem uint64) []string {
	name := filepath.Base(path)
	info, err := parent.Lstat(name)
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return nil
	case err != nil:
		return []string{path + ": " + err.Error()}
	case owner(info) != l.uid:
		return []string{path + ": owned by another user"}
	case device(info) != filesystem:
		return []string{path + ": on another filesystem"}
	case info.IsDir():
		if left := l.removeChildren(parent, path, info, filesystem); len(left) > 0 {
			return left
		}
	}
	if err := parent.Remove(name); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return []string{path + ": " + err.Error()}
	}
	return nil
}

func (l *layout) removeChildren(parent *os.Root, path string, info fs.FileInfo, filesystem uint64) []string {
	name := filepath.Base(path)
	if info.Mode().Perm()&0o700 != 0o700 {
		// The installer makes immutable directories read-only.
		if err := parent.Chmod(name, 0o700); err != nil {
			return []string{path + ": " + err.Error()}
		}
	}
	directory, err := parent.OpenRoot(name)
	if err != nil {
		return []string{path + ": " + err.Error()}
	}
	defer directory.Close()
	if opened, err := directory.Stat("."); err != nil || !os.SameFile(opened, info) {
		return []string{path + ": changed during deletion"}
	}
	children, err := fs.ReadDir(directory.FS(), ".")
	if err != nil {
		return []string{path + ": " + err.Error()}
	}
	var left []string
	for _, child := range children {
		left = append(left, l.removeEntry(directory, filepath.Join(path, child.Name()), filesystem)...)
	}
	return left
}

// entries lists a directory that is reached without links. An absent one has no entries; any other is skipped.
func entries(directory string, found *inventory) ([]string, error) {
	if !exists(directory) {
		return nil, nil
	}
	if resolved, err := filepath.EvalSymlinks(directory); err != nil || resolved != directory {
		found.skip(directory, "reached through a link")
		return nil, nil
	}
	if info, err := os.Lstat(directory); err != nil || !info.IsDir() {
		return nil, nil
	}
	listed, err := os.ReadDir(directory)
	if err != nil {
		return nil, reject(RootRejected, "%v", err)
	}
	names := make([]string, len(listed))
	for i, entry := range listed {
		names[i] = entry.Name()
	}
	return names, nil
}

// rejectedDirectory reports why a path is not a real directory of this user, or nothing.
func rejectedDirectory(path string, uid int) string {
	info, err := os.Lstat(path)
	if err != nil {
		return err.Error()
	}
	resolved, err := filepath.EvalSymlinks(path)
	switch {
	case err != nil:
		return err.Error()
	case resolved != path:
		return path + " is reached through a link"
	case !info.IsDir():
		return path + " is not a directory"
	case owner(info) != uid:
		return path + " is owned by another user"
	default:
		return ""
	}
}

// beneath reports whether path lies inside root. macOS can report a path in another case than the user's HOME.
func beneath(path, root string) bool {
	if len(path) <= len(root) || path[len(root)] != filepath.Separator {
		return false
	}
	if runtime.GOOS == "darwin" {
		return strings.EqualFold(path[:len(root)], root)
	}
	return path[:len(root)] == root
}

// exists treats an unreadable path as present, so a reset never reports absence it did not observe.
func exists(path string) bool {
	_, err := os.Lstat(path)
	return !errors.Is(err, fs.ErrNotExist) && !errors.Is(err, syscall.ENOTDIR)
}

func digest(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}

func escapeXML(value string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;").Replace(value)
}

func owner(info fs.FileInfo) int { return int(info.Sys().(*syscall.Stat_t).Uid) }

func device(info fs.FileInfo) uint64 { return uint64(info.Sys().(*syscall.Stat_t).Dev) }
