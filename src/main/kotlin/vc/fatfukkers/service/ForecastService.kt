package vc.fatfukkers.service

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class ForecastResult(
    val forecastPoints: List<Pair<LocalDate, Double>>, // точки пунктирной линии
    val goalDate: LocalDate?,                          // дата достижения цели (null если > maxDays)
    val message: String
)

object ForecastService {

    private val MONTH_GEN_RU = DateTimeFormatter.ofPattern("MMM yyyy", Locale("ru", "RU"))

    /**
     * Вычисляет линейный тренд и строит прогноз.
     * @param dataPoints  отсортированные по дате пары (дата, вес)
     * @param goal        целевой вес (или null если не задан)
     * @param today       текущая дата
     * @param maxDaysAhead если до цели больше этого — обрезаем прогноз до этой границы
     */
    fun compute(
        dataPoints: List<Pair<LocalDate, Double>>,
        goal: Double?,
        today: LocalDate,
        maxDaysAhead: Long = 365L
    ): ForecastResult? {
        if (dataPoints.size < 2) return null

        // Линейная регрессия: yi = slope * xi + intercept
        // xi = дни от первой точки (чтобы избежать потери точности с большими epoch)
        val origin = dataPoints.first().first.toEpochDay()
        val xs = dataPoints.map { (it.first.toEpochDay() - origin).toDouble() }
        val ys = dataPoints.map { it.second }

        val n = xs.size.toDouble()
        val sumX  = xs.sum()
        val sumY  = ys.sum()
        val sumXY = xs.zip(ys).sumOf { (x, y) -> x * y }
        val sumX2 = xs.sumOf { x -> x * x }
        val denom = n * sumX2 - sumX * sumX
        if (abs(denom) < 1e-9) return null

        val slope     = (n * sumXY - sumX * sumY) / denom   // кг/день
        val intercept = (sumY - slope * sumX) / n

        fun predictAt(epochDay: Long): Double = slope * (epochDay - origin) + intercept

        val lastEpochDay  = dataPoints.last().first.toEpochDay()
        val todayEpochDay = today.toEpochDay()

        // Дата достижения цели по тренду
        var goalDate: LocalDate? = null
        var daysToGoal: Long? = null

        if (goal != null && abs(slope) > 1e-6) {
            // goal = slope*(d - origin) + intercept  =>  d = (goal - intercept)/slope + origin
            val goalEpochDay = ((goal - intercept) / slope + origin).toLong()
            val fromToday = goalEpochDay - todayEpochDay
            // Принимаем только будущие даты в пределах maxDaysAhead
            if (fromToday in 1..maxDaysAhead) {
                goalDate   = LocalDate.ofEpochDay(goalEpochDay)
                daysToGoal = fromToday
            }
        }

        // Конец прогнозной линии: либо дата цели, либо today + maxDaysAhead
        val forecastEndDay = goalDate?.toEpochDay() ?: (todayEpochDay + maxDaysAhead)

        // Равномерно семплируем ~20 точек по прогнозному отрезку
        val forecastPoints = mutableListOf<Pair<LocalDate, Double>>()
        val span = (forecastEndDay - lastEpochDay).coerceAtLeast(1L)
        val step = maxOf(1L, span / 20)
        var d = lastEpochDay
        while (d <= forecastEndDay) {
            forecastPoints += LocalDate.ofEpochDay(d) to predictAt(d)
            d += step
        }
        // Гарантируем последнюю точку
        if (forecastPoints.last().first.toEpochDay() != forecastEndDay) {
            forecastPoints += LocalDate.ofEpochDay(forecastEndDay) to predictAt(forecastEndDay)
        }

        val message = buildMessage(
            slope, goal, goalDate, daysToGoal,
            maxDaysAhead, dataPoints.last().second
        )

        return ForecastResult(forecastPoints, goalDate, message)
    }

    private fun buildMessage(
        slope: Double,
        goal: Double?,
        goalDate: LocalDate?,
        daysToGoal: Long?,
        maxDaysAhead: Long,
        currentWeight: Double
    ): String {
        if (goal == null) return ""

        val isWeightLoss = goal < currentWeight
        val trendFlat    = abs(slope) <= 0.001
        val trendCorrect = if (isWeightLoss) slope < -0.001 else slope > 0.001

        return when {
            goalDate != null && daysToGoal != null -> {
                val timeStr  = formatDuration(daysToGoal)
                val monthStr = goalDate.format(MONTH_GEN_RU)
                "\uD83D\uDCC8 Такими темпами цели достигнешь через ~$timeStr (к $monthStr)"
            }
            trendFlat ->
                "\uD83D\uDE10 Вес стоит на месте. Пора что-то менять!"
            !trendCorrect -> {
                val dir = if (slope > 0) "растёт" else "падает"
                "\u26A0\uFE0F Вес $dir — в неправильную сторону! Поднажми!"
            }
            else -> {
                // Тренд верный, но цель дальше maxDaysAhead
                val kgPerMonth = abs(slope) * 30.0
                val remaining  = abs(currentWeight - goal)
                val estMonths  = (remaining / kgPerMonth).toLong().coerceAtLeast(1)
                "\uD83D\uDC22 Медленновато... До цели ещё ~${formatDuration(estMonths * 30)}. Поднажми!"
            }
        }
    }

    private fun formatDuration(days: Long): String {
        val months  = days / 30
        val remDays = days % 30
        return when {
            months >= 12 -> {
                val years     = months / 12
                val remMonths = months % 12
                if (remMonths > 0) "$years ${pluralYears(years)} $remMonths ${pluralMonths(remMonths)}"
                else "$years ${pluralYears(years)}"
            }
            months > 0   -> "$months ${pluralMonths(months)}" + if (remDays > 10) " и $remDays дн." else ""
            else         -> "$days дн."
        }
    }

    private fun pluralYears(n: Long): String = when {
        n % 100L in 11L..14L -> "лет"
        n % 10L == 1L        -> "год"
        n % 10L in 2L..4L   -> "года"
        else                 -> "лет"
    }

    private fun pluralMonths(n: Long): String = when {
        n % 100L in 11L..14L -> "месяцев"
        n % 10L == 1L        -> "месяц"
        n % 10L in 2L..4L   -> "месяца"
        else                 -> "месяцев"
    }
}
