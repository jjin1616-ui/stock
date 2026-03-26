package com.example.stock.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.stock.data.api.ChartDailyDto
import com.example.stock.data.api.ChartPointDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import kotlin.math.abs
import kotlin.math.roundToLong

enum class ChartRange(val label: String, val days: Int) {
    D1("1일", 1),
    D7("1주", 7),
    M3("3달", 90),
    Y1("1년", 365),
    Y5("5년", 365 * 5),
    ALL("전체", 2000),
}

@Composable
fun StockChartSheet(
    title: String,
    ticker: String,
    quote: RealtimeQuoteItemDto?,
    loading: Boolean,
    error: String?,
    data: ChartDailyDto?,
    range: ChartRange,
    onRangeChange: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val context = LocalContext.current
        val points = data?.points.orEmpty()
        val latest = points.lastOrNull()
        val prev = if (points.size >= 2) points[points.size - 2] else null
        val latestClose = latest?.close ?: 0.0
        val baseClose = when {
            range == ChartRange.D1 && prev?.close != null -> prev.close
            points.firstOrNull()?.close != null -> points.firstOrNull()!!.close
            else -> latestClose
        } ?: latestClose
        val chgAbs = latestClose - baseClose
        val chgPct = if (baseClose == 0.0) 0.0 else (chgAbs / baseClose) * 100.0
        val chgColor = if (chgPct >= 0) Color.Red else Color.Blue
        val periodLabel = when (range) {
            ChartRange.D1 -> "전일 대비"
            ChartRange.D7 -> "지난 1주보다"
            ChartRange.M3 -> "지난 3달보다"
            ChartRange.Y1 -> "지난 1년보다"
            ChartRange.Y5 -> "지난 5년보다"
            ChartRange.ALL -> "전체기간 대비"
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title.ifBlank { ticker },
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.widthIn(max = 200.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = {
                openNaverStockPage(context, ticker)
            }) { Text("네이버 가격보기") }
        }
        if (latestClose > 0) {
            if (quote != null && (quote.price ?: 0.0) > 0 && (quote.prevClose ?: 0.0) > 0) {
                val qPrice = quote.price ?: 0.0
                val qPrev = quote.prevClose ?: 0.0
                val qChg = ((qPrice / qPrev) - 1.0) * 100.0
                Text(
                    text = fmt(qPrice),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "전일 대비 ${if (qPrice - qPrev >= 0) "+" else ""}${fmt(abs(qPrice - qPrev))} (${if (qChg >= 0) "+" else ""}${"%.1f".format(qChg)}%)",
                    color = if (qChg >= 0) Color.Red else Color.Blue,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(
                    text = fmt(latestClose),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "$periodLabel ${if (chgAbs >= 0) "+" else ""}${fmt(abs(chgAbs))} (${if (chgPct >= 0) "+" else ""}${"%.1f".format(chgPct)}%)",
                    color = chgColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("차트 로딩 중...", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
            }
            return
        }
        if (error != null) {
            Text(error, color = Color.Red, modifier = Modifier.padding(top = 12.dp))
            return
        }
        if (points.isEmpty()) {
            Text("차트 데이터가 없습니다.", modifier = Modifier.padding(top = 12.dp))
            return
        }
        LineChart(
            points = points,
            baseline = if (range == ChartRange.D1) baseClose else null,
            modifier = Modifier.fillMaxWidth().height(210.dp).padding(top = 12.dp)
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChartRange.entries.forEach { opt ->
                if (range == opt) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFE5E7EB), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text(opt.label, color = Color(0xFF111827), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                } else {
                    Text(
                        opt.label,
                        color = Color(0xFF9CA3AF),
                        modifier = Modifier
                            .clickable { onRangeChange(opt) }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

private fun openNaverStockPage(context: android.content.Context, rawTicker: String) {
    val digits = rawTicker.filter(Char::isDigit)
    val ticker = when {
        digits.length >= 6 -> digits.takeLast(6)
        digits.isNotBlank() -> digits.padStart(6, '0')
        else -> ""
    }
    if (ticker.length != 6) {
        Toast.makeText(context, "유효한 종목코드가 없습니다.", Toast.LENGTH_SHORT).show()
        return
    }
    val urls = listOf(
        "https://m.stock.naver.com/domestic/stock/$ticker/total",
        "https://finance.naver.com/item/main.naver?code=$ticker",
    )
    for (url in urls) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next URL candidate.
        } catch (_: Exception) {
            // Ignore and continue fallback URL attempts.
        }
    }
    Toast.makeText(context, "브라우저를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
}

@Composable
private fun LineChart(points: List<ChartPointDto>, baseline: Double? = null, modifier: Modifier = Modifier) {
    val clean = points.filter { (it.close ?: 0.0) > 0.0 }
    if (clean.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("차트 데이터가 없습니다.") }
        return
    }
    val closes = clean.mapNotNull { it.close }
    val dataMin = closes.minOrNull() ?: 0.0
    val dataMax = closes.maxOrNull() ?: 0.0
    val base = baseline?.takeIf { it > 0.0 }
    val baseForColor = base ?: closes.firstOrNull() ?: (closes.lastOrNull() ?: 0.0)
    val up = (closes.lastOrNull() ?: 0.0) >= baseForColor
    val lineColor = if (up) Color(0xFFE53935) else Color(0xFF2F6BFF)

    // For 1일(D1), the chart often contains only 2 closes (전일 종가 + 금일 종가).
    // If we scale strictly to min/max, the 기준선이 바닥에 붙고 그래프가 "가파르게" 느껴진다.
    // Instead, center the Y scale around the baseline with padding.
    val (scaleMin, scaleMax) = if (base != null) {
        val dev = maxOf(abs(dataMax - base), abs(base - dataMin))
        val paddedDev = maxOf(dev * 1.25, base * 0.01, 1.0) // min 1% band for readability
        (base - paddedDev) to (base + paddedDev)
    } else {
        val r = (dataMax - dataMin).takeIf { it > 0.0 } ?: maxOf(dataMax * 0.02, 1.0)
        val pad = r * 0.05
        (dataMin - pad) to (dataMax + pad)
    }
    val range = (scaleMax - scaleMin).takeIf { it > 0.0 } ?: 1.0
    fun pct(v: Double): String {
        if (base == null || base <= 0.0) return ""
        val p = (v / base - 1.0) * 100.0
        return "%+.1f%%".format(p)
    }
    val maxLabel = if (base != null) "최고 ${fmt(dataMax)} (${pct(dataMax)})" else "최고 ${fmt(dataMax)}"
    val minLabel = if (base != null) "최저 ${fmt(dataMin)} (${pct(dataMin)})" else "최저 ${fmt(dataMin)}"

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val count = clean.size
        val step = if (count <= 1) w else w / (count - 1)
        val path = Path()
        clean.forEachIndexed { i, p ->
            val v = p.close ?: 0.0
            val x = step * i
            val y = h - (((v - scaleMin) / range).toFloat() * h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 3f))
        if (base != null) {
            val baseY = h - (((base - scaleMin) / range).toFloat() * h)
            drawLine(
                color = Color(0xFF6B7280),
                start = Offset(0f, baseY),
                end = Offset(w, baseY),
                strokeWidth = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
            val paint = Paint().apply {
                color = 0xFF6B7280.toInt()
                textSize = 22f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText("기준", 6f, baseY - 6f, paint)
        }

        val paintAxis = Paint().apply {
            color = lineColor.toArgb()
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText(
            maxLabel,
            w - paintAxis.measureText(maxLabel) - 8f,
            24f,
            paintAxis
        )
        drawContext.canvas.nativeCanvas.drawText(
            minLabel,
            w - paintAxis.measureText(minLabel) - 8f,
            h - 6f,
            paintAxis
        )
    }
}

private fun fmt(v: Double): String = "${"%,d".format(v.roundToLong())}원"
