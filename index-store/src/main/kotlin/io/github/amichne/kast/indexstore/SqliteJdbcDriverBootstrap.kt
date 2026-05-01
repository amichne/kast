package io.github.amichne.kast.indexstore

import java.sql.Driver
import java.sql.DriverManager
import java.util.Collections

internal object SqliteJdbcDriverBootstrap {
    @Volatile
    private var bootstrapped = false

    fun ensureRegistered() {
        if (bootstrapped && hasSqliteDriver()) return

        synchronized(this) {
            if (bootstrapped && hasSqliteDriver()) return

            val driverClass = Class.forName("org.sqlite.JDBC", true, SqliteJdbcDriverBootstrap::class.java.classLoader)
            if (!hasSqliteDriver()) {
                val driver = driverClass.getDeclaredConstructor().newInstance() as Driver
                DriverManager.registerDriver(driver)
            }
            bootstrapped = true
        }
    }

    private fun hasSqliteDriver(): Boolean = Collections.list(DriverManager.getDrivers())
        .any { driver ->
            runCatching { driver.acceptsURL("jdbc:sqlite::memory:") }.getOrDefault(false)
        }
}
