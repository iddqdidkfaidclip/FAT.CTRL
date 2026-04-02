package vc.fatfukkers.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import vc.fatfukkers.db.Users
import vc.fatfukkers.db.WeightEntries
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeightServiceTest {

    private val service = WeightService(ZoneId.of("Europe/Moscow"))
    private val userId = 100500L
    private val today = LocalDate.of(2026, 3, 31)
    private val yesterday = today.minusDays(1)

    companion object {
        init {
            // maximumPoolSize=1 гарантирует одно физическое соединение →
            // все транзакции видят одну и ту же in-memory базу
            val ds = HikariDataSource(HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite::memory:"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 1
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_SERIALIZABLE"
            })
            Database.connect(ds)
            transaction { SchemaUtils.create(Users, WeightEntries) }
        }
    }

    @BeforeTest
    fun cleanup() {
        transaction { WeightEntries.deleteWhere { WeightEntries.telegramUserId eq userId } }
    }

    // ─── tryAddWeight ────────────────────────────────────────────────────────

    @Test
    fun `новая запись сохраняется и возвращает ok`() {
        val result = service.tryAddWeight(userId, today, 98.5)

        assertTrue(result.ok)
        assertTrue(result.message.contains("Схоронил"))
        assertTrue(result.message.contains("98.50"))
    }

    @Test
    fun `повторная запись за тот же день перезаписывает вес`() {
        service.tryAddWeight(userId, today, 98.5)
        val result = service.tryAddWeight(userId, today, 97.0)

        assertTrue(result.ok)
        assertTrue(result.message.contains("Обновил"), "Ожидали 'Обновил', получили: ${result.message}")
        assertTrue(result.message.contains("98.50 → 97.00"))

        // В БД должна остаться одна запись с новым весом
        val last = service.getLastWeight(userId)
        assertEquals(BigDecimal("97.00"), last)
    }

    @Test
    fun `разные дни сохраняются как отдельные записи`() {
        service.tryAddWeight(userId, yesterday, 99.0)
        val result = service.tryAddWeight(userId, today, 98.0)

        assertTrue(result.ok)
        assertTrue(result.message.contains("Схоронил"))

        val last = service.getLastWeight(userId)
        assertEquals(BigDecimal("98.00"), last)
    }

    // ─── cancelLastWeight ────────────────────────────────────────────────────

    @Test
    fun `отмена когда нет записей возвращает ошибку`() {
        val result = service.cancelLastWeight(userId)

        assertFalse(result.ok)
        assertTrue(result.message.contains("Нет записей"))
    }

    @Test
    fun `отмена удаляет сегодняшнюю запись`() {
        service.tryAddWeight(userId, today, 98.5)

        val result = service.cancelLastWeight(userId)

        assertTrue(result.ok)
        assertTrue(result.message.contains("98.50"))
        assertTrue(result.message.contains(today.toString()))

        // Запись должна исчезнуть
        val last = service.getLastWeight(userId)
        assertEquals(null, last)
    }

    @Test
    fun `отмена без сегодняшней записи удаляет вчерашнюю`() {
        // Сегодня не вносили, но есть вчерашняя
        service.tryAddWeight(userId, yesterday, 99.5)

        val result = service.cancelLastWeight(userId)

        assertTrue(result.ok, "Должен вернуть ok=true")
        assertTrue(result.message.contains("99.50"), "Сообщение должно содержать удалённый вес")
        assertTrue(result.message.contains(yesterday.toString()), "Сообщение должно содержать дату вчерашней записи")

        val last = service.getLastWeight(userId)
        assertEquals(null, last, "После удаления записей не должно остаться")
    }

    @Test
    fun `отмена при нескольких записях удаляет только последнюю`() {
        service.tryAddWeight(userId, yesterday, 99.5)
        service.tryAddWeight(userId, today, 98.5)

        val result = service.cancelLastWeight(userId)

        assertTrue(result.ok)
        assertTrue(result.message.contains(today.toString()), "Должна удалиться сегодняшняя, а не вчерашняя")

        // Вчерашняя должна остаться
        val last = service.getLastWeight(userId)
        assertEquals(BigDecimal("99.50"), last)
    }

    // ─── getProgressMessage ──────────────────────────────────────────────────

    @Test
    fun `прогресс без записей возвращает сообщение об отсутствии данных`() {
        val msg = service.getProgressMessage(userId)
        assertTrue(msg.contains("Нет данных"))
    }

    @Test
    fun `прогресс с одной записью показывает начальный вес`() {
        service.tryAddWeight(userId, today, 100.0)
        val msg = service.getProgressMessage(userId)
        assertTrue(msg.contains("100.0"))
    }

    @Test
    fun `прогресс показывает изменение с начала`() {
        service.tryAddWeight(userId, yesterday, 100.0)
        service.tryAddWeight(userId, today, 98.0)

        val msg = service.getProgressMessage(userId)

        assertTrue(msg.contains("100.0"), "Должен быть начальный вес")
        assertTrue(msg.contains("98.0"), "Должен быть текущий вес")
        assertTrue(msg.contains("-2"), "Должно быть изменение -2 кг")
    }
}
