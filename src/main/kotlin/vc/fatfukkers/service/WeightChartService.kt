package vc.fatfukkers.service

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import vc.fatfukkers.db.WeightEntries
import java.awt.*
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO

object WeightChartService {

    private val MONTH_RU = DateTimeFormatter.ofPattern("MMM yy", Locale("ru", "RU"))

    private data class Point(val date: LocalDate, val weight: Double)

    fun buildChartBytes(
        telegramUserId: Long,
        goal: BigDecimal?,
        forecast: ForecastResult? = null
    ): ByteArray? {
        val points = transaction {
            WeightEntries.selectAll()
                .where { WeightEntries.telegramUserId eq telegramUserId }
                .orderBy(WeightEntries.dateIso to SortOrder.ASC)
                .map { row ->
                    Point(
                        LocalDate.parse(row[WeightEntries.dateIso]),
                        row[WeightEntries.weightKg].toDouble()
                    )
                }
        }

        if (points.isEmpty()) return null

        val W = 900; val H = 480
        val padL = 76; val padR = 28; val padT = 30; val padB = 58

        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        g.color = Color(26, 26, 32)
        g.fillRect(0, 0, W, H)

        val plotW = W - padL - padR
        val plotH = H - padT - padB

        val goalD = goal?.toDouble()

        // Y range: учитываем все реальные точки + прогноз + цель
        val allWeights = points.map { it.weight } +
            (forecast?.forecastPoints?.map { it.second } ?: emptyList()) +
            listOfNotNull(goalD)
        val yMin = Math.floor((allWeights.min() - 3.0) / 5.0) * 5.0
        val yMax = Math.ceil((allWeights.max() + 3.0) / 5.0) * 5.0
        val yRange = (yMax - yMin).coerceAtLeast(5.0)

        // X range: расширяем до конца прогноза если он есть
        val xMinDay = points.first().date.toEpochDay()
        val dataEndDay = if (points.size > 1) points.last().date.toEpochDay() else xMinDay + 1
        val chartEndDay = forecast?.forecastPoints?.lastOrNull()?.first?.toEpochDay()
            ?.let { maxOf(dataEndDay, it) } ?: dataEndDay
        val xRange = (chartEndDay - xMinDay).toDouble().coerceAtLeast(1.0)

        fun xPx(epochDay: Long) = padL + (epochDay - xMinDay).toDouble() / xRange * plotW
        fun xPx(date: LocalDate) = xPx(date.toEpochDay())
        fun yPx(w: Double) = padT + (1.0 - (w - yMin) / yRange) * plotH

        // Horizontal grid lines
        g.stroke = BasicStroke(1f)
        var tick = yMin
        while (tick <= yMax + 0.01) {
            g.color = Color(50, 50, 62)
            g.draw(Line2D.Float(padL.toFloat(), yPx(tick).toFloat(), (W - padR).toFloat(), yPx(tick).toFloat()))
            tick += 5.0
        }

        // Разделитель «данные / прогноз» — вертикальная пунктирная линия
        if (forecast != null) {
            val sepX = xPx(points.last().date).toFloat()
            g.color = Color(80, 80, 95)
            g.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(4f, 4f), 0f)
            g.draw(Line2D.Float(sepX, padT.toFloat(), sepX, (padT + plotH).toFloat()))
        }

        // Goal line (dashed, amber)
        if (goalD != null) {
            val gy = yPx(goalD).toFloat()
            g.color = Color(255, 185, 55, 210)
            g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(12f, 7f), 0f)
            g.draw(Line2D.Float(padL.toFloat(), gy, (W - padR).toFloat(), gy))
            g.stroke = BasicStroke(1f)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
            g.color = Color(255, 205, 90)
            val goalLabel = "цель ${BigDecimal.valueOf(goalD).setScale(1, RoundingMode.HALF_UP)} кг"
            g.drawString(goalLabel, padL + 6, (gy - 5).toInt().coerceAtLeast(padT + 14))
        }

        // Forecast dashed line (зелёная пунктирная)
        if (forecast != null && forecast.forecastPoints.size >= 2) {
            val fPath = Path2D.Double()
            forecast.forecastPoints.forEachIndexed { i, (date, w) ->
                if (i == 0) fPath.moveTo(xPx(date), yPx(w))
                else fPath.lineTo(xPx(date), yPx(w))
            }
            g.color = Color(120, 220, 100, 210)
            g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(10f, 6f), 0f)
            g.draw(fPath)

            // Маркер в точке достижения цели (звёздочка-кружок)
            if (forecast.goalDate != null) {
                val gx = xPx(forecast.goalDate).toInt()
                val gy = yPx(forecast.forecastPoints.last().second).toInt()
                g.stroke = BasicStroke(2f)
                g.color = Color(26, 26, 32)
                g.fillOval(gx - 7, gy - 7, 14, 14)
                g.color = Color(120, 220, 100)
                g.fillOval(gx - 6, gy - 6, 12, 12)
                g.color = Color(220, 255, 210)
                g.drawOval(gx - 6, gy - 6, 12, 12)
            }
        }

        // Filled area under real data line
        if (points.size >= 2) {
            val fillPath = Path2D.Double()
            fillPath.moveTo(xPx(points.first().date), yPx(points.first().weight))
            points.drop(1).forEach { fillPath.lineTo(xPx(it.date), yPx(it.weight)) }
            fillPath.lineTo(xPx(points.last().date), (padT + plotH).toDouble())
            fillPath.lineTo(xPx(points.first().date), (padT + plotH).toDouble())
            fillPath.closePath()
            g.color = Color(65, 145, 255, 38)
            g.fill(fillPath)

            val linePath = Path2D.Double()
            linePath.moveTo(xPx(points.first().date), yPx(points.first().weight))
            points.drop(1).forEach { linePath.lineTo(xPx(it.date), yPx(it.weight)) }
            g.color = Color(75, 155, 255)
            g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(linePath)
        }

        // Dots
        for (p in points) {
            val px = xPx(p.date).toInt()
            val py = yPx(p.weight).toInt()
            g.color = Color(26, 26, 32); g.fillOval(px - 5, py - 5, 10, 10)
            g.color = Color(75, 155, 255); g.fillOval(px - 4, py - 4, 8, 8)
            g.color = Color(200, 225, 255)
            g.stroke = BasicStroke(1.5f)
            g.drawOval(px - 4, py - 4, 8, 8)
        }

        // Y axis labels
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        g.color = Color(185, 185, 205)
        val fm = g.fontMetrics
        var tick2 = yMin
        while (tick2 <= yMax + 0.01) {
            val py = yPx(tick2).toInt()
            val lbl = "${tick2.toInt()} кг"
            g.drawString(lbl, padL - fm.stringWidth(lbl) - 7, py + fm.ascent / 2 - 1)
            tick2 += 5.0
        }

        // X axis month labels (по всему диапазону включая прогноз)
        val totalDisplayDays = chartEndDay - xMinDay
        val monthStep: Long = when {
            totalDisplayDays <= 60  -> 1
            totalDisplayDays <= 180 -> 2
            totalDisplayDays <= 540 -> 3
            else                    -> 6
        }
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        g.color = Color(165, 165, 188)
        val xFm = g.fontMetrics
        val chartEndDate = LocalDate.ofEpochDay(chartEndDay)
        var d = points.first().date.withDayOfMonth(1)
        while (!d.isAfter(chartEndDate)) {
            val px = xPx(d).toInt()
            if (px in padL..(W - padR)) {
                val lbl = d.format(MONTH_RU).replaceFirstChar { it.uppercase() }
                // Метки прогнозного периода рисуем чуть тусклее
                val isForecastZone = d.isAfter(points.last().date)
                g.color = if (isForecastZone) Color(110, 110, 135) else Color(165, 165, 188)
                g.drawString(lbl, px - xFm.stringWidth(lbl) / 2, H - padB + 20)
                g.color = Color(65, 65, 78)
                g.stroke = BasicStroke(1f)
                g.drawLine(px, padT + plotH, px, padT + plotH + 5)
            }
            d = d.plusMonths(monthStep)
        }

        // Plot border
        g.color = Color(60, 60, 72)
        g.stroke = BasicStroke(1f)
        g.drawRect(padL, padT, plotW, plotH)

        g.dispose()

        val out = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }
}
