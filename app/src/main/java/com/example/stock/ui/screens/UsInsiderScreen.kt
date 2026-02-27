package com.example.stock.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.UsInsiderItemDto
import com.example.stock.data.repository.UiSource
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.common.CommonReportList
import com.example.stock.ui.common.CommonSortThemeBar
import com.example.stock.ui.common.GlossaryPresets
import com.example.stock.ui.common.MetricUi
import com.example.stock.ui.common.SelectOptionUi
import com.example.stock.ui.common.SortOptions
import com.example.stock.ui.common.rememberFavoritesController
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.UsInsiderViewModel
import java.util.Locale

private fun asMetric(v: Double): Double = if (v.isFinite()) v else 0.0
private fun fmtUsd(v: Double): String = "$" + String.format(Locale.US, "%,.2f", asMetric(v))
private fun fmtShares(v: Double): String = String.format(Locale.US, "%,.0f", asMetric(v))

private fun toUi(item: UsInsiderItemDto): CommonReportItemUi {
    val ticker = item.ticker.orEmpty().uppercase(Locale.US)
    val name = item.companyName?.takeIf { it.isNotBlank() } ?: ticker
    val role = item.executiveRole?.takeIf { it.isNotBlank() } ?: "-"
    val txCode = item.transactionCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.US) ?: "-"
    val adCode = item.acquiredDisposedCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
    val owner = item.executiveName?.takeIf { it.isNotBlank() } ?: "-"
    val txDate = item.transactionDate?.takeIf { it.isNotBlank() } ?: "-"
    val filingDate = item.filingDate?.takeIf { it.isNotBlank() } ?: "-"
    val totalValue = item.totalValueUsd ?: 0.0
    val shares = item.totalShares ?: 0.0
    val avgPrice = item.avgPriceUsd ?: 0.0
    val repeat = item.repeatBuy90d == true
    val repeatCount = item.repeatCount90d ?: 0
    val has10b51 = item.has10b51 == true
    val buyRange = item.buyDateRange?.takeIf { it.isNotBlank() }
        ?: item.buyDates.orEmpty().joinToString("~").takeIf { it.isNotBlank() }
    val patternSummary = item.patternSummary?.takeIf { it.isNotBlank() }
    val sourceUrl = item.sourceUrl?.takeIf { it.isNotBlank() }

    val extra = mutableListOf<String>()
    val codeLabel = if (adCode.isNullOrBlank()) "구분 $txCode" else "구분 $txCode ($adCode)"
    extra += "$role · $owner · $codeLabel"
    extra += "거래일 $txDate · 제출일 $filingDate"
    if (!buyRange.isNullOrBlank()) extra += "매수일 범위 $buyRange"
    if (repeat && repeatCount >= 2) extra += "반복매수(90거래일) ${repeatCount}회"
    if (has10b51) extra += "사전 계획 매매 표기 거래"
    item.accessionNo?.takeIf { it.isNotBlank() }?.let { extra += "Form4 $it" }
    patternSummary?.let { extra += it }

    return CommonReportItemUi(
        ticker = ticker,
        name = name,
        title = "$name ($ticker)",
        quote = null,
        fallbackPrice = avgPrice,
        fallbackChangePct = null,
        fallbackLabel = "평균단가(USD)",
        metrics = listOf(
            MetricUi("거래금액", asMetric(totalValue), "$" + String.format(Locale.US, "%,.0f", asMetric(totalValue))),
            MetricUi("수량", asMetric(shares), fmtShares(shares) + "주"),
            MetricUi("평균단가", asMetric(avgPrice), fmtUsd(avgPrice)),
        ),
        extraLines = extra,
        thesis = item.notes.orEmpty().takeIf { it.isNotEmpty() }?.joinToString(" · "),
        sortPrice = avgPrice,
        sortChangePct = null,
        sortName = name,
        actionLinkLabel = if (!sourceUrl.isNullOrBlank()) "내부자 공시 원문 보기" else null,
        actionLinkUrl = sourceUrl,
        currencyCode = "USD",
        badgeLabel = "미장 포켓",
        eventTags = listOfNotNull("구분 $txCode", buyRange, patternSummary).take(5),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsInsiderScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = ServiceLocator.repository(context)
    val vm: UsInsiderViewModel = viewModel(factory = AppViewModelFactory(repo))
    val state = vm.state.value

    var sortId by remember { mutableStateOf(SortOptions.DEFAULT) }
    var query by remember { mutableStateOf("") }
    var refreshToken by remember { mutableIntStateOf(0) }
    var baseTradingDays by remember { mutableIntStateOf(10) }
    var transactionFilter by remember { mutableStateOf("ALL") }
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = rememberFavoritesController(repo, snackbarHostState)

    val loadData: (Boolean) -> Unit = { force ->
        refreshToken += 1
        vm.load(
            targetCount = 10,
            tradingDays = baseTradingDays,
            expandDays = baseTradingDays,
            maxCandidates = 120,
            transactionCodes = transactionFilter,
            force = force,
        )
        favorites.refresh()
    }

    LaunchedEffect(baseTradingDays, transactionFilter) {
        loadData(false)
    }

    val rawItems = state.data?.items.orEmpty()
    val uiItems = rawItems.map(::toUi)
    val topNotes = state.data?.notes.orEmpty()
    val shortage = state.data?.shortageReason
    val status = when {
        state.loading -> "미국 공시 원문 스캔 중..."
        !state.error.isNullOrBlank() -> "오류: ${state.error}"
        else -> {
            val d = state.data
            val req = d?.requestedTradingDays ?: baseTradingDays
            val eff = d?.effectiveTradingDays ?: baseTradingDays
            val parsed = d?.formsParsed ?: 0
            val merged = d?.candidateMerged ?: 0
            val rowsEff = d?.purchaseRowsInEffective ?: 0
            "기준 ${req}거래일(실적용 ${eff}거래일) · 후보 ${merged}건 · Form4 파싱 ${parsed}건 · 거래행 ${rowsEff}건"
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopBar(
                title = "미장",
                showRefresh = true,
                onRefresh = { loadData(true) },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        CommonReportList(
            source = UiSource.LIVE,
            statusMessage = status,
            updatedAt = state.refreshedAt,
            header = "내부자 거래 (미국 공시)",
            glossaryDialogTitle = "미장 용어 설명집",
            glossaryItems = GlossaryPresets.US,
            items = uiItems,
            emptyText = if (!state.error.isNullOrBlank()) "미장 데이터를 불러오지 못했습니다." else "조건에 맞는 종목이 없습니다.",
            initialDisplayCount = 20,
            refreshToken = refreshToken,
            refreshLoading = state.loading,
            onRefresh = { loadData(true) },
            snackbarHostState = snackbarHostState,
            receivedCount = uiItems.size,
            query = query,
            onQueryChange = { query = it },
            selectedSortId = sortId,
            onSortChange = { sortId = it },
            favoriteTickers = favorites.favoriteTickers,
            onToggleFavorite = { item, desired ->
                favorites.setFavorite(item = item, sourceTab = "미장", desiredFavorite = desired)
            },
            onItemClick = { item ->
                val raw = item.actionLinkUrl.orEmpty()
                if (raw.isBlank()) return@CommonReportList
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
                }
            },
            filtersContent = {
                val sortOptions = listOf(
                    SelectOptionUi(SortOptions.DEFAULT, "기본"),
                    SelectOptionUi(SortOptions.PRICE_ASC, "가격↑"),
                    SelectOptionUi(SortOptions.PRICE_DESC, "가격↓"),
                    SelectOptionUi(SortOptions.NAME_ASC, "이름↑"),
                    SelectOptionUi(SortOptions.NAME_DESC, "이름↓"),
                )
                CommonSortThemeBar(
                    sortOptions = sortOptions,
                    selectedSortId = sortId,
                    onSortChange = { sortId = it },
                    themeOptions = null,
                    selectedThemeId = "",
                    onThemeChange = {},
                )
            },
            topContent = {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFEEF1F4), RoundedCornerShape(14.dp))
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        @Composable
                        fun WindowPill(text: String, selected: Boolean, onClick: () -> Unit) {
                            val bg = if (selected) Color.White else Color.Transparent
                            val fg = if (selected) Color(0xFF111827) else Color(0xFF9AA5B1)
                            Box(
                                modifier = Modifier
                                    .background(bg, RoundedCornerShape(999.dp))
                                    .clickable { onClick() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = text,
                                    color = fg,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        WindowPill("10거래일", baseTradingDays == 10) { baseTradingDays = 10 }
                        WindowPill("20거래일", baseTradingDays == 20) { baseTradingDays = 20 }
                        WindowPill("30거래일", baseTradingDays == 30) { baseTradingDays = 30 }
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFEEF1F4), RoundedCornerShape(14.dp))
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        @Composable
                        fun CodePill(label: String, value: String) {
                            val selected = transactionFilter == value
                            val bg = if (selected) Color.White else Color.Transparent
                            val fg = if (selected) Color(0xFF111827) else Color(0xFF9AA5B1)
                            Box(
                                modifier = Modifier
                                    .background(bg, RoundedCornerShape(999.dp))
                                    .clickable { transactionFilter = value }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = fg,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        CodePill("전체", "ALL")
                        CodePill("P", "P")
                        CodePill("A", "A")
                        CodePill("M", "M")
                        CodePill("F", "F")
                    }
                }
                if (!shortage.isNullOrBlank()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Text(
                                text = shortage,
                                color = Color(0xFF7C2D12),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
                if (topNotes.isNotEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        ) {
                            Text(
                                text = topNotes.joinToString(" "),
                                color = Color(0xFF475569),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            modifier = Modifier.padding(inner).fillMaxSize(),
        )
    }
}
