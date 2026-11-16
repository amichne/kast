"""The byte oracle spells the Java DataInputStream contract independently."""

from composition_index import (
    IndexFailure,
    IndexRejected,
    PluginClasspath,
    PluginIndex,
    encode_plugin_index,
    parse_plugin_index,
)


def test_version_two_native_layout_matches_literal_byte_oracle() -> None:
    core = b"<idea-plugin><id>com.intellij</id></idea-plugin>"
    plugin = b"<idea-plugin><id>p</id></idea-plugin>"
    index = PluginIndex(core, (PluginClasspath("p", plugin, ("lib/p.jar",)),))
    expected = (
        b"\x02\x01\x00\x00\x00\x30"
        + core
        + b"\x00\x01\x00\x01\x00\x01p\x00\x00\x00\x25"
        + plugin
        + b"\x00\x09lib/p.jar"
    )
    assert encode_plugin_index(index) == expected
    assert parse_plugin_index(expected) == index


def test_unknown_native_version_rejects_without_interpreting_its_body() -> None:
    assert parse_plugin_index(b"\x03deliberately not version two") == IndexRejected(
        IndexFailure.UNSUPPORTED_VERSION, 0
    )


def test_truncation_rejects_every_incomplete_prefix() -> None:
    raw = encode_plugin_index(
        PluginIndex(b"<idea-plugin/>", (PluginClasspath("p", b"<idea-plugin/>", ("lib/p.jar",)),))
    )
    for end in range(len(raw)):
        result = parse_plugin_index(raw[:end])
        assert isinstance(result, IndexRejected), end
        assert result.reason == IndexFailure.TRUNCATED, end


def test_trailing_bytes_cannot_hide_a_second_index() -> None:
    raw = encode_plugin_index(PluginIndex(b"<idea-plugin/>", ()))
    assert parse_plugin_index(raw + raw) == IndexRejected(IndexFailure.TRAILING_BYTES, len(raw))
