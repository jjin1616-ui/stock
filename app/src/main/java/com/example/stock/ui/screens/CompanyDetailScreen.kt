package com.example.stock.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.data.api.CompanyDetailDto
import com.example.stock.data.api.HealthScoreDto
import com.example.stock.ServiceLocator
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.CompanyAnalysisViewModel

// Design tokens
private val CdBg = Color(0xFFF7F8FA)
private val CdCardBg = Color(0xFFFFFFFF)
private val CdCardRadius = 14.dp
private val CdTextPrimary = Color(0xFF1A1D26)
private val CdTextSecondary = Color(0xFF6B7280)
private val CdGood = Color(0xFF4CAF50)
private val CdNormal = Color(0xFFFFC107)
private val CdDanger = Color(0xFFFF4747)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyDetailScreen(
    ticker: String,
    name: String,
    onBack: () -> Unit = {},
    onBuy: (String) -> Unit = {},
    onAddWatchlist: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: CompanyAnalysisViewModel = viewModel(factory = AppViewModelFactory(repo))

    LaunchedEffect(ticker) { vm.loadDetail(ticker) }

    val state by vm.detailState
    val detail = state.data

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                title = {
                    Text(
                        "$name ($ticker)",
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
        containerColor = CdBg,
    ) { inner ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CdGood)
            }
            return@Scaffold
        }

        if (detail == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(state.error ?: "데이터를 불러올 수 없어요", color = CdDanger)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AiSummaryCard(detail)
            FinancialsCard(detail)
            HealthGaugeCard(detail.healthScore, detail.aiAnalysis.healthComment)
            InvestmentPointsCard(
                positive = detail.aiAnalysis.positivePoints,
                risks = detail.aiAnalysis.riskPoints,
            )
            ActionButtonsCard(
                ticker = ticker,
                onBuy = onBuy,
                onAddWatchlist = onAddWatchlist,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(CdCardRadius),
        colors = CardDefaults.cardColors(containerColor = CdCardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = CdTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun AiSummaryCard(detail: CompanyDetailDto) {
    SectionCard("\uD83D\uDCAC AI가 알려주는 기업 현황") {
        Text(
            detail.aiAnalysis.summary ?: "분석 데이터를 준비 중이에요.",
            color = CdTextPrimary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun FinancialsCard(detail: CompanyDetailDto) {
    val f = detail.financials
    SectionCard("\uD83D\uDCCA 핵심 재무 지표") {
        FinancialRow("매출액", formatKrw(f.revenue), "")
        FinancialRow("영업이익", formatKrw(f.operatingProfit), statusForProfit(f.operatingProfit))
        FinancialRow("순이익", formatKrw(f.netIncome), statusForProfit(f.netIncome))
        FinancialRow("부채비율", f.debtRatio?.let { "${it.toInt()}%" } ?: "-", statusForDebt(f.debtRatio))
        FinancialRow("ROE", f.roe?.let { "${String.format("%.1f", it)}%" } ?: "-", statusForRoe(f.roe))
        FinancialRow("유동비율", f.currentRatio?.let { "${it.toInt()}%" } ?: "-", statusForCurrent(f.currentRatio))
        FinancialRow("매출 성장률", f.revenueGrowth?.let { "${if (it >= 0) "+" else ""}${String.format("%.1f", it)}%" } ?: "-",
            statusForGrowth(f.revenueGrowth))
    }
}

@Composable
private fun FinancialRow(label: String, value: String, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CdTextSecondary, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                color = CdTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(6.dp))
            Text(status, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HealthGaugeCard(score: HealthScoreDto, comment: String?) {
    val total = score.total ?: 0.0
    val grade = when {
        total >= 80 -> "good"
        total >= 50 -> "normal"
        else -> "danger"
    }

    SectionCard("\uD83C\uDFE5 건전성 진단서") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("종합 점수: ", color = CdTextSecondary, fontSize = 14.sp)
            Text(
                "${total.toInt()}점",
                color = gradeColor(grade),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(" / 100점 ${gradeEmoji(grade)}", color = CdTextSecondary, fontSize = 14.sp)
        }

        Spacer(Modifier.height(12.dp))

        GaugeBar("수익성", score.profitability)
        GaugeBar("안정성", score.stability)
        GaugeBar("성장성", score.growth)
        GaugeBar("효율성", score.efficiency)
        GaugeBar("밸류에이션", score.valuation)

        if (!comment.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(comment, color = CdTextSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun GaugeBar(label: String, value: Double?) {
    val v = value ?: 0.0
    val fraction = (v / 20.0).toFloat().coerceIn(0f, 1f)
    val color = when {
        v >= 15 -> CdGood
        v >= 8 -> CdNormal
        else -> CdDanger
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CdTextSecondary, fontSize = 12.sp, modifier = Modifier.width(70.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = Color(0xFFE8EBF0),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${v.toInt()}",
            color = CdTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(24.dp),
        )
    }
}

@Composable
private fun InvestmentPointsCard(positive: List<String>, risks: List<String>) {
    SectionCard("\uD83D\uDD2E 투자 포인트") {
        if (positive.isNotEmpty()) {
            Text("\u2705 기대 요인", color = CdGood, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            positive.forEach { point ->
                Text("\u00B7 $point", color = CdTextPrimary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            }
        }
        if (risks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("\u26A0\uFE0F 리스크 요인", color = CdDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            risks.forEach { point ->
                Text("\u00B7 $point", color = CdTextPrimary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            }
        }
    }
}

@Composable
private fun ActionButtonsCard(
    ticker: String,
    onBuy: (String) -> Unit,
    onAddWatchlist: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(CdCardRadius),
        colors = CardDefaults.cardColors(containerColor = CdCardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onBuy(ticker) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CdGood),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("\uD83D\uDCC8 매수하기", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { onAddWatchlist(ticker) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("\u2665 관심종목", fontSize = 13.sp, color = CdTextPrimary)
            }
        }
    }
}

// ── Helpers ──

private fun gradeColor(grade: String): Color = when (grade) {
    "good" -> CdGood; "normal" -> CdNormal; "danger" -> CdDanger; else -> CdNormal
}

private fun gradeEmoji(grade: String): String = when (grade) {
    "good" -> "\uD83D\uDFE2"; "normal" -> "\uD83D\uDFE1"; "danger" -> "\uD83D\uDD34"; else -> "\u26AA"
}

private fun formatKrw(value: Long?): String {
    if (value == null) return "-"
    val abs = kotlin.math.abs(value)
    return when {
        abs >= 1_000_000_000_000 -> "${String.format("%.1f", value / 1_000_000_000_000.0)}조"
        abs >= 100_000_000 -> "${value / 100_000_000}억"
        else -> "${value}원"
    }
}

private fun statusForDebt(v: Double?): String = when {
    v == null -> ""; v < 50 -> "\uD83D\uDFE2"; v < 100 -> "\uD83D\uDFE1"; else -> "\uD83D\uDD34"
}
private fun statusForRoe(v: Double?): String = when {
    v == null -> ""; v >= 15 -> "\uD83D\uDFE2"; v >= 5 -> "\uD83D\uDFE1"; else -> "\uD83D\uDD34"
}
private fun statusForCurrent(v: Double?): String = when {
    v == null -> ""; v >= 200 -> "\uD83D\uDFE2"; v >= 100 -> "\uD83D\uDFE1"; else -> "\uD83D\uDD34"
}
private fun statusForGrowth(v: Double?): String = when {
    v == null -> ""; v >= 10 -> "\uD83D\uDFE2"; v >= 0 -> "\uD83D\uDFE1"; else -> "\uD83D\uDD34"
}
private fun statusForProfit(v: Long?): String = when {
    v == null -> ""; v > 0 -> "\uD83D\uDFE2"; else -> "\uD83D\uDD34"
}
