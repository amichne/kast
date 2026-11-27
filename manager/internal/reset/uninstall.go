package reset

import (
	"fmt"
	"io"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
)

const uninstallUsage = "Usage: kast uninstall\n\n" +
	"Stops what Kast runs and removes this user's installation and workspace state.\n" +
	"Keeps ~/.kast, and copies client configuration and workspace change evidence to a backup directory.\n" +
	"To remove a damaged installation, use `kast reset`."

// The entries that Kast writes directly beneath each root: InstallationLayout.kt, ContainerStore.kt, ContainerFiles.kt,
// and immutable_store.py.
var (
	rootEntries = []string{"activation.lock", "installation", "management.json", "management.lock", "recovery"}
	storeEntry  = regexp.MustCompile(`^(container\.lock|containers|locks|runtimes|shared)$`)
	baseEntry   = regexp.MustCompile(`^([0-9a-f]{64}|\.kast-base-[a-z0-9_]{8})$`)
)

// desktopEntry is the file that the Finder writes in a directory it showed.
const desktopEntry = ".DS_Store"

// NativeUninstall removes the current user's installation beneath home. Settle stops every workspace Host through the
// installation, which refuses while a Host works or holds an unsettled change.
func NativeUninstall(home string, arguments []string, out io.Writer, settle func() error) error {
	reset, err := native(home, out)
	if err != nil {
		return err
	}
	return reset.Uninstall(arguments, settle)
}

// Uninstall removes an undamaged installation. It differs from a reset in what it refuses: work in progress, an
// unfinished upgrade, and entries that Kast did not write.
func (r *Reset) Uninstall(arguments []string, settle func() error) error {
	switch {
	case len(arguments) == 1 && (arguments[0] == "--help" || arguments[0] == "-h"):
		r.print(uninstallUsage)
		return nil
	case len(arguments) > 0:
		return reject(UsageRejected, "%s", uninstallUsage)
	}
	paths, err := admitLayout(r.Home, r.Temporary, r.UID)
	if err != nil {
		return err
	}
	if r.remove == nil {
		r.remove = paths.removeTree
	}
	found, err := paths.inventory()
	if err != nil {
		return err
	}
	if len(found.targets) == 0 {
		r.print(notInstalled)
		return nil
	}
	if err := paths.requireRemovable(found); err != nil {
		return err
	}
	running, err := r.observe(paths, found)
	if err != nil {
		return err
	}
	if len(running.ancestors) > 0 {
		return reject(StopRejected, "this command runs inside Kast: %s. Run it from another terminal.",
			strings.Join(running.ancestors, ", "))
	}
	workspaces, err := entries(filepath.Join(paths.workspaces, "containers"), found)
	if err != nil {
		return err
	}
	if len(workspaces) > 0 {
		r.print("Stopping workspace Hosts")
		if err := settle(); err != nil {
			return reject(StopRejected, "%v", err)
		}
	}
	r.print("Removing Kast")
	if err := r.perform(paths, found); err != nil {
		return err
	}
	r.print("Kast is removed.")
	if len(found.kept) > 0 {
		r.print("Kept: " + strings.Join(found.kept, ", "))
	}
	return nil
}

// requireRemovable names what an uninstall must not delete.
func (l *layout) requireRemovable(found *inventory) error {
	for _, pending := range []string{filepath.Join(l.root, "recovery", "replacement"), filepath.Join(l.root, "shutdown.json")} {
		if exists(pending) {
			return reject(RootRejected, "an earlier upgrade did not finish: %s. Run `kast upgrade` to finish it.", pending)
		}
	}
	var foreign []string
	for _, root := range []struct {
		path string
		owns func(string) bool
	}{
		{l.root, func(name string) bool { return slices.Contains(rootEntries, name) }},
		{l.workspaces, storeEntry.MatchString},
		{l.bases, baseEntry.MatchString},
	} {
		names, err := entries(root.path, found)
		if err != nil {
			return err
		}
		for _, name := range names {
			if name != desktopEntry && !root.owns(name) {
				foreign = append(foreign, filepath.Join(root.path, name))
			}
		}
	}
	if len(foreign) > 0 {
		return reject(RootRejected, "Kast did not write these, so nothing was changed; move them, then run `kast uninstall` "+
			"again:\n  %s", strings.Join(foreign, "\n  "))
	}
	return nil
}

// Refused is a settlement failure with the Hosts' own reason.
type Refused struct{ Reason string }

func (r *Refused) Error() string {
	return fmt.Sprintf("the workspace Hosts did not stop: %s. Wait for their work to end, then run `kast uninstall` again. "+
		"`kast reset` ends them without waiting.", r.Reason)
}
