package com.example.stock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.ChartPointDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.data.api.ThemeItemDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.CommonReportItemCard
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.common.MetricUi
import com.example.stock.ui.theme.BluePrimary
import com.example.stock.ui.theme.TextMain
import com.example.stock.ui.theme.TextMuted
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.HomeViewModel

private val PageBg = Color(0xFFF5F6F8)
private val CardBg = Color.White
private val UpColor = Color(0xFFE95A68)
private val DownColor = Color(0xFF2F6BFF)
private val ChipBg = Color(0xFFEFF3FF)
private val ChipText = Color(0xFF2F5BEA)

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: HomeViewModel = viewModel(factory = AppViewModelFactory(repo))
    val premarketState by vm.premarketState
    val favorites = vm.favoritesState.value
    val quotes = vm.quoteState
    val miniCharts = vm.miniChartState
    val indexCharts = vm.indexChartState

    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(Unit) { onDispose { vm.stopPolling() } }

    val themes = premarketState.data?.themes.orEmpty()
    val topStocks = premarketState.data?.daytradeTop.orEmpty().take(5)
    val loading = premarketState.loading && premarketState.data == null

    Scaffold(
        topBar = { AppTopBar(title = "홈", showRefresh = true, onRefresh = { vm.load() }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 시장 지표: 코스피/코스닥
            item {
                HomeSectionCard(title = "시장 지표") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HomeViewModel.INDEX_TICKERS.forEach { ticker ->
                            val label = HomeViewModel.INDEX_LABELS[ticker] ?: ticker
                            val q = quotes[ticker]
                            val points = indexCharts[ticker]
                            MarketIndexMiniCard(
                                label = label,
                                quote = q,
                                points = points,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 오늘의 테마
            if (themes.isNotEmpty()) {
                item {
                    HomeSectionCard(title = "오늘의 핵심 테마") {
                        ThemeChipRow(themes = themes)
                    }
                }
            }

            // 오늘의 단타 종목 - CommonReportItemCard 사용
            if (loading) {
                item {
                    HomeSectionCard(title = "오늘의 단타 종목") {
                        Box(
                            Modifier.fillMaxWidth().height(80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = BluePrimary, strokeWidth = 2.dp)
                        }
                    }
                }
            } else if (topStocks.isNotEmpty()) {
                item {
                    Text(
                        text = "오늘의 단타 종목",
                        color = TextMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                topStocks.forEach { stock ->
                    val ticker = stock.ticker.orEmpty()
                    val q = quotes[ticker]
                    val points = miniCharts[ticker]
                    item(key = "home_daytrade_$ticker") {
                        CommonReportItemCard(
                            item = CommonReportItemUi(
                                ticker = ticker,
                                name = stock.name,
                                title = stock.name.orEmpty(),
                                quote = q,
                                metrics = emptyList(),
                                extraLines = stock.tags?.let { listOf("테마: ${it.joinToString(", ")}") } ?: emptyList(),
                                thesis = stock.thesis,
                                miniPoints = points,
                                sortPrice = q?.price,
                                sortChangePct = q?.chgPct,
                            ),
                            onClick = {
                                StockDetailActivity.open(context, ticker, stock.name.orEmpty(), "home", emptyList())
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            // 관심 종목 - CommonReportItemCard 사용
            if (favorites.isNotEmpty()) {
                item {
                    Text(
                        text = "관심 종목",
                        color = TextMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                favorites.forEach { fav ->
                    val ticker = fav.ticker.orEmpty()
                    val q = quotes[ticker]
                    item(key = "home_fav_$ticker") {
                        CommonReportItemCard(
                            item = CommonReportItemUi(
                                ticker = ticker,
                                name = fav.name,
                                title = fav.name.orEmpty(),
                                quote = q,
                                fallbackPrice = fav.currentPrice,
                                fallbackChangePct = fav.changeSinceFavoritePct,
                                metrics = emptyList(),
                                sortPrice = q?.price ?: fav.currentPrice,
                                sortChangePct = q?.chgPct ?: fav.changeSinceFavoritePct,
                            ),
                            onClick = {
                                StockDetailActivity.open(context, ticker, fav.name.orEmpty(), "home", emptyList())
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun HomeSectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = title,
                color = TextMain,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MarketIndexMiniCard(
    label: String,
    quote: RealtimeQuoteItemDto?,
    points: List<ChartPointDto>?,
    modifier: Modifier = Modifier,
) {
    val price = quote?.price
    val chgPct = quote?.chgPct ?: 0.0
    val isUp = chgPct >= 0
    val pctColor = if (isUp) UpColor else DownColor
    val pctText = "${if (isUp) "+" else ""}${"%.2f".format(chgPct)}%"

    // 최근 5일 간이 스파크라인 표시용
    val lastPoints = points?.takeLast(5)?.mapNotNull { it.close } ?: emptyList()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            if (price != null) {
                Text(
                    text = "%,.2f".format(price),
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(
                    text = "--",
                    color = TextMuted,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (price != null) pctText else "-",
                color = if (price != null) pctColor else TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ThemeChipRow(themes: List<ThemeItemDto>) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        themes.take(8).forEach { theme ->
            Box(
                modifier = Modifier
                    .background(color = ChipBg, shape = RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = theme.name.orEmpty(),
                    color = ChipText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
