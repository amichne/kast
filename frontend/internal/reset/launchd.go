package reset

import (
	"regexp"
	"strings"
)

// launchctlUnknown is launchctl's status for a service that is not loaded.
const launchctlUnknown = 113

var assignment = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*=`)

// parseLabels reads the labels with Kast's prefix from `launchctl list`.
func parseLabels(listing string) []string {
	var labels []string
	for line := range strings.Lines(listing) {
		fields := strings.Split(strings.TrimRight(line, "\n"), "\t")
		if len(fields) == 3 && strings.HasPrefix(fields[2], labelPrefix) {
			labels = append(labels, fields[2])
		}
	}
	return labels
}

// parseProgram reads the program from `launchctl print`. Kast's jobs run `/usr/bin/env -i NAME=value... PROGRAM ...`
// (BrokerLaunchdServiceDocument.kt), so the first argument that assigns nothing is the program. Any other shape proves
// no program.
func parseProgram(definition string) string {
	_, block, found := strings.Cut(definition, "\n\targuments = {\n")
	if !found {
		return ""
	}
	var arguments []string
	for line := range strings.Lines(block) {
		argument, indented := strings.CutPrefix(strings.TrimRight(line, "\n"), "\t\t")
		if !indented {
			break
		}
		arguments = append(arguments, argument)
	}
	if len(arguments) < 3 || arguments[0] != "/usr/bin/env" || arguments[1] != "-i" {
		return ""
	}
	for _, argument := range arguments[2:] {
		if !assignment.MatchString(argument) {
			return argument
		}
	}
	return ""
}
