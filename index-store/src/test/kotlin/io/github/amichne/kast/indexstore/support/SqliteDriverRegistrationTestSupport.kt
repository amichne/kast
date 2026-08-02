package io.github.amichne.kast.indexstore

import java.sql.Driver
import java.sql.DriverManager
import java.util.Collections

internal fun <T> withSqliteDriversDeregistered(block: () -> T): T {
    val sqliteDriverClass = Class.forName("org.sqlite.JDBC")
    val sqliteDrivers = Collections.list(DriverManager.getDrivers())
        .filter { driver -> sqliteDriverClass.isInstance(driver) }
        .ifEmpty {
            listOf(sqliteDriverClass.getDeclaredConstructor().newInstance() as Driver)
        }

    sqliteDrivers.forEach { driver ->
        runCatching { DriverManager.deregisterDriver(driver) }
    }

    return try {
        block()
    } finally {
        sqliteDrivers.forEach { driver ->
            runCatching { DriverManager.registerDriver(driver) }
        }
    }
}
