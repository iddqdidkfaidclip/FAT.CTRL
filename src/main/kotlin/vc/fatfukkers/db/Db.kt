package vc.fatfukkers.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object Db {
    private var dataSource: HikariDataSource? = null

    fun init(sqlitePath: String) {
        if (dataSource != null) return

        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$sqlitePath"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 2
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_SERIALIZABLE"
            validate()
        }

        dataSource = HikariDataSource(config)
        Database.connect(dataSource!!)

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                WeightEntries,
                Activities,
                DailyAssignments,
            )
        }
    }
}

