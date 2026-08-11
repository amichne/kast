package io.github.amichne.kast.indexstore.store.codec

import io.github.amichne.kast.api.client.WorkspaceIdentity
import io.github.amichne.kast.indexstore.store.SourceIndexPageReadObserver
import io.github.amichne.kast.indexstore.store.AttachedSqliteDatabase
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStoreState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger

class StringInterningCodecTest {
    @TempDir
    lateinit var workspaceRoot: Path

    @Test
    fun `incremental inserts do not reload the full table`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { delegate ->
            delegate.createStatement().use { statement ->
                statement.execute("CREATE TABLE fq_names (fq_id INTEGER PRIMARY KEY, fq_name TEXT NOT NULL UNIQUE)")
                statement.execute("INSERT INTO fq_names(fq_name) VALUES ('alpha')")
            }
            val fullLoads = AtomicInteger(0)
            val connection = interceptedConnection(delegate) { query ->
                if (query == "SELECT fq_id, fq_name FROM fq_names") fullLoads.incrementAndGet()
            }
            val codec = StringInterningCodec(StringInterningDomain.FQ_NAME)

            codec.loadAll(connection)
            codec.batchEnsure(connection, setOf("beta"))
            codec.batchEnsure(connection, setOf("gamma"))

            assertEquals(1, fullLoads.get(), "The table should be fully hydrated only once")
            assertNotNull(codec.idFor("alpha"))
            assertNotNull(codec.idFor("beta"))
            assertNotNull(codec.idFor("gamma"))
        }
    }

    @Test
    fun `failed reload remains retryable after rollback`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { delegate ->
            delegate.createStatement().use { statement ->
                statement.execute("CREATE TABLE fq_names (fq_id INTEGER PRIMARY KEY, fq_name TEXT NOT NULL UNIQUE)")
                statement.execute("INSERT INTO fq_names(fq_name) VALUES ('alpha')")
            }
            val codec = StringInterningCodec(StringInterningDomain.FQ_NAME)
            codec.loadAll(delegate)

            delegate.autoCommit = false
            codec.batchEnsure(delegate, setOf("beta"))
            delegate.rollback()

            val failingConnection = interceptedConnection(delegate) { query ->
                if (query == "SELECT fq_id, fq_name FROM fq_names") {
                    throw SQLException("simulated reload failure")
                }
            }
            assertThrows(SQLException::class.java) {
                codec.reloadAll(failingConnection)
            }

            codec.loadAll(delegate)
            assertNotNull(codec.idFor("alpha"))
            assertNull(codec.idFor("beta"))
        }
    }

    @Test
    fun `failed state reload invalidates the data version marker`() {
        val state = SqliteSourceIndexStoreState(
            workspaceIdentity = WorkspaceIdentity.fromWorkspaceRoot(workspaceRoot.toAbsolutePath().normalize()),
            pageReadObserver = SourceIndexPageReadObserver {},
        )
        state.use {
            val connection = state.connection()
            val stalePath = workspaceRoot.resolve("stale/File.kt").toString()
            connection.autoCommit = false
            state.pathCodec.encodeOrCreate(connection, stalePath)
            connection.rollback()
            connection.autoCommit = true

            val failingConnection = interceptedConnection(connection) { query ->
                if (query == "PRAGMA main.data_version") {
                    throw SQLException("simulated data-version failure")
                }
            }
            assertThrows(SQLException::class.java) {
                state.reloadInterningTables(failingConnection)
            }

            state.loadInterningTables(connection)
            assertNull(state.pathCodec.encodeIfInterned(stalePath))
        }
    }

    @Test
    fun `repository aliases reject non-positive source identities as finite data`() {
        Class.forName("org.sqlite.JDBC")
        val base = workspaceRoot.resolve("repository-base.db")
        DriverManager.getConnection("jdbc:sqlite:$base").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE fq_names (fq_id INTEGER PRIMARY KEY, fq_name TEXT NOT NULL UNIQUE)")
                statement.execute("INSERT INTO fq_names(fq_id, fq_name) VALUES (-1, 'invalid')")
            }
        }
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE fq_names (fq_id INTEGER PRIMARY KEY, fq_name TEXT NOT NULL UNIQUE)")
                statement.execute("ATTACH DATABASE '${base.toAbsolutePath()}' AS repository_base")
            }
            val resolution = StringInterningCodec(StringInterningDomain.FQ_NAME).loadReadOnlyAliases(
                connection,
                AttachedSqliteDatabase.REPOSITORY_BASE,
            )

            assertTrue(resolution is ReadOnlyInterningAliasResolution.Rejected)
        }
    }

    @Test
    fun `large workspace and repository vocabularies retain one bidirectional cache`() {
        Class.forName("org.sqlite.JDBC")
        val base = workspaceRoot.resolve("repository-base.db")
        val workspaceCount = 25_000
        val repositoryCount = 25_000
        DriverManager.getConnection("jdbc:sqlite:$base").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE fq_names (fq_id INTEGER PRIMARY KEY, fq_name TEXT NOT NULL UNIQUE)")
            }
            insertNames(connection, "repository", repositoryCount)
        }
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE fq_names (fq_id INTEGER PRIMARY KEY, fq_name TEXT NOT NULL UNIQUE)")
                statement.execute("ATTACH DATABASE '${base.toAbsolutePath()}' AS repository_base")
            }
            insertNames(connection, "workspace", workspaceCount)
            val codec = StringInterningCodec(StringInterningDomain.FQ_NAME)

            codec.loadAll(connection)
            assertEquals(
                ReadOnlyInterningAliasResolution.Loaded,
                codec.loadReadOnlyAliases(connection, AttachedSqliteDatabase.REPOSITORY_BASE),
            )

            val retainedMappings = codec.javaClass.declaredFields
                .filter { field -> ConcurrentMap::class.java.isAssignableFrom(field.type) }
                .sumOf { field ->
                    field.isAccessible = true
                    (field.get(codec) as ConcurrentMap<*, *>).size
                }
            assertEquals(
                2 * (workspaceCount + repositoryCount),
                retainedMappings,
                "Each interned value should occupy only the forward and reverse cache",
            )
        }
    }

    private fun insertNames(connection: Connection, prefix: String, count: Int) {
        val ownsTransaction = connection.autoCommit
        if (ownsTransaction) connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT INTO fq_names(fq_name) VALUES (?)").use { statement ->
                repeat(count) { index ->
                    statement.setString(1, "$prefix.example.type$index")
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            if (ownsTransaction) connection.commit()
        } finally {
            if (ownsTransaction) connection.autoCommit = true
        }
    }

    private fun interceptedConnection(
        delegate: Connection,
        onQuery: (String) -> Unit,
    ): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            if (method.name == "createStatement" && arguments.isNullOrEmpty()) {
                interceptedStatement(method.invoke(delegate) as Statement, onQuery)
            } else {
                method.invoke(delegate, *(arguments ?: emptyArray()))
            }
        } as Connection

    private fun interceptedStatement(
        delegate: Statement,
        onQuery: (String) -> Unit,
    ): Statement =
        Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
        ) { _, method, arguments ->
            if (method.name == "executeQuery") {
                (arguments?.singleOrNull() as? String)?.let(onQuery)
            }
            method.invoke(delegate, *(arguments ?: emptyArray()))
        } as Statement
}
