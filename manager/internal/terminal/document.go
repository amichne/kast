// Package terminal presents results and progress to a person at a terminal. It decides nothing: the coordinator and the
// JVM own every document, and a pipe receives their exact bytes.
package terminal

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"strconv"
	"strings"
)

// Is reports whether a stream is a terminal that can show redrawn lines.
func Is(stream any) bool {
	file, ok := stream.(*os.File)
	if !ok || os.Getenv("TERM") == "dumb" {
		return false
	}
	info, err := file.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

// Document renders one JSON object or array as an indented outline. It keeps every field and its order. Any other text
// is returned unchanged.
func Document(text string) string {
	decoder := json.NewDecoder(strings.NewReader(text))
	decoder.UseNumber()
	root, err := decode(decoder)
	if err != nil || root.kind == scalar {
		return text
	}
	if _, err := decoder.Token(); !errors.Is(err, io.EOF) {
		return text
	}
	var out outline
	out.node(root, 0)
	return strings.TrimRight(out.text.String(), "\n")
}

type kind int

const (
	scalar kind = iota
	object
	array
)

// node is one JSON value. Members keep the document's order, which a Go map would lose.
type node struct {
	kind  kind
	value string
	// text marks a string scalar, the only kind that a terminal could take as control sequences.
	text  bool
	names []string
	items []node
}

func decode(decoder *json.Decoder) (node, error) {
	token, err := decoder.Token()
	if err != nil {
		return node{}, err
	}
	switch token := token.(type) {
	case json.Delim:
		found := node{kind: object}
		if token == '[' {
			found.kind = array
		}
		for decoder.More() {
			if found.kind == object {
				name, err := decoder.Token()
				if err != nil {
					return node{}, err
				}
				found.names = append(found.names, name.(string))
			}
			item, err := decode(decoder)
			if err != nil {
				return node{}, err
			}
			found.items = append(found.items, item)
		}
		_, err := decoder.Token()
		return found, err
	case string:
		return node{value: token, text: true}, nil
	case json.Number:
		return node{value: token.String()}, nil
	case bool:
		return node{value: map[bool]string{true: "yes", false: "no"}[token]}, nil
	default:
		return node{value: "none"}, nil
	}
}

type outline struct{ text strings.Builder }

func (o *outline) line(depth int, text string) {
	o.text.WriteString(strings.Repeat("  ", depth) + text + "\n")
}

func (o *outline) node(n node, depth int) {
	for index, item := range n.items {
		lead := "-"
		if n.kind == object {
			lead = label(n.names[index]) + ":"
		}
		switch {
		case item.kind == scalar:
			o.scalar(lead, item, depth)
		case len(item.items) == 0:
			o.line(depth, lead+" none")
		case n.kind == array && item.kind == object:
			o.entry(item, depth)
		default:
			o.line(depth, lead)
			o.node(item, depth+1)
		}
	}
}

// entry renders one object of a list with its first field on the list mark's line.
func (o *outline) entry(item node, depth int) {
	var nested outline
	nested.node(item, depth+1)
	text := nested.text.String()
	indent := strings.Repeat("  ", depth)
	o.text.WriteString(indent + "- " + strings.TrimPrefix(text, indent+"  "))
}

func (o *outline) scalar(lead string, item node, depth int) {
	if !item.text {
		o.line(depth, lead+" "+item.value)
		return
	}
	if !strings.Contains(strings.TrimRight(item.value, "\n"), "\n") {
		o.line(depth, strings.TrimRight(lead+" "+printable(strings.TrimRight(item.value, "\n")), " "))
		return
	}
	o.line(depth, lead)
	for line := range strings.Lines(item.value) {
		o.line(depth+1, printable(strings.TrimRight(line, "\r\n")))
	}
}

// printable replaces control characters, so source text in a result cannot drive the terminal.
func printable(text string) string {
	return strings.Map(func(r rune) rune {
		if r == '\t' || strconv.IsPrint(r) {
			return r
		}
		return '�'
	}, text)
}

// label turns a field name into words: "startOffset" and "start_offset" read as "start offset".
func label(name string) string {
	if strings.ContainsAny(name, " ./:-") {
		return printable(name)
	}
	var words strings.Builder
	lower := false
	for _, r := range name {
		switch {
		case r == '_':
			words.WriteByte(' ')
			lower = false
			continue
		case r >= 'A' && r <= 'Z' && lower:
			words.WriteByte(' ')
			words.WriteRune(r + ('a' - 'A'))
			lower = false
			continue
		}
		words.WriteRune(r)
		lower = r >= 'a' && r <= 'z'
	}
	return printable(words.String())
}
