package com.example.stock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.stock.data.api.CompanyCardDto
import com.example.stock.data.api.CompanyListResponseDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ServiceLocator
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.CompanyAnalysisViewModel

// ── Design Tokens ──
private val CaBg = Color(0xFFF7F8FA)
private val CaCardBg = Color(0xFFFFFFFF)
private val CaCardBorder = Color(0xFFE8EBF0)
private val CaCardRadius = 14.dp
private val CaTextPrimary = Color(0xFF1A1D26)
private val CaTextSecondary = Color(0xFF6B7280)
private val CaGood = Color(0xFF4CAF50)
private val CaNormal = Color(0xFFFFC107)
private val CaDanger = Color(0xFFFF4747)
private val CaUp = Color(0xFFD32F2F)
private val CaDown = Color(0xFF1565C0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyAnalysisScreen(
    onCompanyClick: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: CompanyAnalysisViewModel = viewModel(factory = AppViewModelFactory(repo))

    LaunchedEffect(Unit) { vm.loadCompanies() }

    val state by vm.listState
    val data = state.data

    Scaffold(
        topBar = { AppTopBar(title = "기업분석") },
        containerColor = CaBg,
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // ── Curation: Top companies ──
            val topCompanies = data?.curation?.topCompanies.orEmpty()
            if (topCompanies.isNotEmpty()) {
                item {
                    Text(
                        "\uD83C\uDFC6 오늘의 우량주",
                        color = CaTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(topCompanies, key = { it.ticker }) { card ->
                            CurationCard(card) { onCompanyClick(card.ticker, card.name) }
                        }
                    }
                }
            }

            // ── Filter chips ──
            item {
                Spacer(Modifier.height(12.dp))
                FilterChipRow(vm)
            }

            // ── Loading / Error ──
            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CaGood)
                    }
                }
            } else if (state.error != null) {
                item {
                    Text(
                        state.error ?: "오류 발생",
                        color = CaDanger,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // ── Company cards ──
            val companies = data?.companies.orEmpty()
            items(companies, key = { it.ticker }) { card ->
                CompanyCardItem(card) { onCompanyClick(card.ticker, card.name) }
            }

            // ── Empty state ──
            if (!state.loading && companies.isEmpty() && state.error == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("조건에 맞는 기업이 없어요", color = CaTextSecondary, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CurationCard(card: CompanyCardDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(CaCardRadius),
        colors = CardDefaults.cardColors(containerColor = gradeColor(card.grade).copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                card.name,
                color = CaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${card.healthScore?.toInt() ?: "-"}점",
                color = gradeColor(card.grade),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                gradeLabel(card.grade),
                color = gradeColor(card.grade),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun FilterChipRow(vm: CompanyAnalysisViewModel) {
    val grade by vm.selectedGrade
    val grades = listOf(null to "전체", "good" to "\uD83D\uDFE2 좋은기업", "normal" to "\uD83D\uDFE1 보통", "danger" to "\uD534 위험")

    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grades.forEach { (value, label) ->
            val selected = grade == value
            FilterChip(
                selected = selected,
                onClick = { vm.setGradeFilter(value) },
                label = { Text(label, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CaGood.copy(alpha = 0.15f),
                    selectedLabelColor = CaGood,
                ),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CompanyCardItem(card: CompanyCardDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(CaCardRadius),
        colors = CardDefaults.cardColors(containerColor = CaCardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Row 1: name + score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(gradeEmoji(card.grade), fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        card.name,
                        color = CaTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        card.ticker,
                        color = CaTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(gradeColor(card.grade).copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        "${card.healthScore?.toInt() ?: "-"}점",
                        color = gradeColor(card.grade),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Row 2: metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricMini("매출", formatKrw(card.revenue))
                MetricMini("부채비율", card.debtRatio?.let { "${it.toInt()}%" } ?: "-")
                MetricMini("성장률", card.revenueGrowth?.let { "${if (it >= 0) "+" else ""}${it.toInt()}%" } ?: "-",
                    valueColor = card.revenueGrowth?.let { if (it >= 0) CaUp else CaDown })
            }

            // Row 3: AI summary
            if (!card.aiSummary.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    card.aiSummary,
                    color = CaTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MetricMini(label: String, value: String, valueColor: Color? = null) {
    Column {
        Text(label, color = CaTextSecondary, fontSize = 10.sp)
        Text(
            value,
            color = valueColor ?: CaTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

// ── Helpers ──

private fun gradeColor(grade: String): Color = when (grade) {
    "good" -> CaGood
    "normal" -> CaNormal
    "danger" -> CaDanger
    else -> CaNormal
}

private fun gradeEmoji(grade: String): String = when (grade) {
    "good" -> "\uD83D\uDFE2"
    "normal" -> "\uD83D\uDFE1"
    "danger" -> "\uD534"
    else -> "\u26AA"
}

private fun gradeLabel(grade: String): String = when (grade) {
    "good" -> "좋은 기업"
    "normal" -> "보통"
    "danger" -> "위험"
    else -> ""
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
