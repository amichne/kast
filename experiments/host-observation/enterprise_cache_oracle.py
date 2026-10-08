"""Authored enterprise cache oracle; never derives expectations from query results."""
from dataclasses import dataclass
from pathlib import Path

from static_callback_oracle import FixtureIntegrityError, SourceSpan, Utf16Range


FIXTURE = Path(__file__).with_name('enterprise-cache-fixture')
CACHE = 'src/main/kotlin/example/cache/CacheManager.kt'
CLIENT = 'src/main/kotlin/example/client/Client.kt'
INFERRED = 'src/main/kotlin/example/client/InferredClient.kt'
TEST = 'src/test/kotlin/example/cache/TestCacheManager.kt'
UNRELATED = 'src/main/kotlin/example/unrelated/CacheManager.kt'


def span(file, text, fragment, inside=None):
    """Locate unique authored text, with Kotlin/IDE UTF-16 indexing."""
    base = 0
    selected = text
    if inside is not None:
        if text.count(inside) != 1:
            raise FixtureIntegrityError('ambiguous enclosing oracle text')
        base = text.index(inside)
        selected = inside
    if selected.count(fragment) != 1:
        raise FixtureIntegrityError('missing or ambiguous oracle text: ' + fragment)
    start = base + selected.index(fragment)
    end = start + len(fragment)
    return SourceSpan(file, Utf16Range(len(text[:start].encode('utf-16-le')) // 2,
                                      len(text[:end].encode('utf-16-le')) // 2), fragment)


@dataclass(frozen=True)
class CacheOracle:
    generic: SourceSpan
    method_name: SourceSpan
    implementation_body: SourceSpan
    interface_call: SourceSpan
    production_adapter_call: SourceSpan
    test_adapter_call: SourceSpan
    adapter_import: SourceSpan
    inferred_interface_call: SourceSpan
    implementations: tuple[str, ...]
    production_implementations: tuple[str, ...]


def materialize(documents):
    cache, client, test = (documents[file] for file in (CACHE, CLIENT, TEST))
    method = 'fun <T : Any> execute(load: () -> T, useCached: (T) -> Boolean): T'
    interface = 'interface CacheManager {\n    ' + method + '\n}'
    redis = 'class RedisCacheManager : CacheManager {\n    override ' + method + ' =\n        load()\n}'
    return CacheOracle(
        span(CACHE, cache, 'T : Any', interface),
        span(CACHE, cache, 'execute', interface),
        span(CACHE, cache, 'load()', redis),
        span(CACHE, cache, 'execute', 'manager.execute(load, useCached)'),
        span(CLIENT, client, 'executeWithCache', 'executeWithCache(manager, load = { "value" })'),
        span(TEST, test, 'executeWithCache', 'executeWithCache(TestCacheManager(), load = { "test" })'),
        span(CLIENT, client, 'executeWithCache', 'import example.cache.executeWithCache'),
        span(INFERRED, documents[INFERRED], 'execute', 'cacheManagerFactory().execute(load = { "inferred" }, useCached = { true })'),
        ('example.cache.RedisCacheManager', 'example.cache.NoOpCacheManager', 'example.cache.TestCacheManager'),
        ('example.cache.RedisCacheManager', 'example.cache.NoOpCacheManager'),
    )


def load_oracle(root=FIXTURE):
    return materialize({file: (root / file).read_text() for file in (CACHE, CLIENT, INFERRED, TEST, UNRELATED)})


def occurrence_identity(occurrence):
    location = occurrence['occurrence']
    return location['file'], location['range']['startInclusive'], location['range']['endExclusive']


def expected_site(site, root):
    return str((root / site.file).resolve(strict=True)), site.range.startInclusive, site.range.endExclusive


def require_adapter_occurrences(items, oracle, root):
    """Imports, production calls and test calls must remain distinct, with no duplicates."""
    from collections import Counter
    expected = Counter((expected_site(oracle.adapter_import, root), expected_site(oracle.production_adapter_call, root),
                        expected_site(oracle.test_adapter_call, root)))
    actual = Counter(occurrence_identity(item['occurrence']) for item in items)
    if actual != expected:
        raise FixtureIntegrityError('adapter occurrence inventory differs from the authored oracle')
    imported = next(item['occurrence'] for item in items
                    if occurrence_identity(item['occurrence']) == expected_site(oracle.adapter_import, root))
    if imported['ownership']['type'] != 'file-scoped' or imported['context'] != 'IMPORT':
        raise FixtureIntegrityError('import must remain file-scoped import evidence')
    for item in items:
        occurrence = item['occurrence']
        if occurrence_identity(occurrence) != expected_site(oracle.adapter_import, root):
            if occurrence['ownership']['type'] != 'declaration-owned' or occurrence['context'] != 'CODE':
                raise FixtureIntegrityError('adapter invocation must retain declaration-owned call evidence')
