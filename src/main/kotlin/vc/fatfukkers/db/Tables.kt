package vc.fatfukkers.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object Users : Table("users") {
    val telegramId = long("telegram_id").uniqueIndex()
    val username = varchar("username", 64).nullable()
    val firstName = varchar("first_name", 128).nullable()
    val lastName = varchar("last_name", 128).nullable()
    val goalWeightKg = decimal("goal_weight_kg", precision = 6, scale = 2).nullable()
    val createdAtEpochMs = long("created_at_epoch_ms")

    override val primaryKey = PrimaryKey(telegramId, name = "pk_users")
}

object WeightEntries : LongIdTable("weight_entries") {
    val telegramUserId = long("telegram_user_id").index()
    val dateIso = varchar("date_iso", 10) // yyyy-MM-dd
    val weightKg = decimal("weight_kg", precision = 6, scale = 2)
    val createdAtEpochMs = long("created_at_epoch_ms")

    init {
        uniqueIndex("ux_weight_user_date", telegramUserId, dateIso)
    }
}

object Activities : LongIdTable("activities") {
    val name = varchar("name", 128).uniqueIndex()
    val minReps = integer("min_reps").default(10)
    val maxReps = integer("max_reps").default(50)
    val isActive = bool("is_active").default(true)
}

object DailyAssignments : LongIdTable("daily_assignments") {
    val telegramUserId = long("telegram_user_id").index()
    val dateIso = varchar("date_iso", 10) // yyyy-MM-dd
    val activityId = reference(
        name = "activity_id",
        foreign = Activities,
        onDelete = ReferenceOption.RESTRICT,
        onUpdate = ReferenceOption.CASCADE
    )
    val reps = integer("reps")
    val createdAtEpochMs = long("created_at_epoch_ms")

    init {
        uniqueIndex("ux_assignment_user_date", telegramUserId, dateIso)
    }
}

