package com.example.stock.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.AutoTradeAccountPositionDto
import com.example.stock.data.api.AutoTradeAccountSnapshotResponseDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.viewmodel.Home2ViewModel
import com.example.stock.viewmodel.AppViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home2Screen() {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: Home2ViewModel = viewModel(factory = AppViewModelFactory(repo))

    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(Unit) { onDispose { vm.stopPolling() } }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "홈2",
                showRefresh = true,
                onRefresh = { vm.load() },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section 1: Account + Positions
            vm.accountState.value?.let { account ->
                item(key = "account") { AccountPositionsCard(account = account) }
            }
            vm.sectionErrorState["account"]?.let { err ->
                item(key = "account_error") { HomeSectionCard(title = "내 계좌", error = err) {} }
            }
        }
    }
}

// ── Utility ──────────────────────────────────────────────────────────────────

private val UpColor = Color(0xFFD32F2F)    // 상승 빨강
private val DownColor = Color(0xFF1565C0)  // 하락 파랑
private val FlatColor = Color.Gray

@Composable
private fun pnlColor(value: Double?): Color {
    if (value == null) return FlatColor
    return when {
        value > 0 -> UpColor
        value < 0 -> DownColor
        else -> FlatColor
    }
}

private fun formatKrw(value: Double?): String {
    if (value == null) return "-"
    val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.KOREA)
    return "${fmt.format(value.toLong())}원"
}

private fun formatPrice(value: Double?): String {
    if (value == null) return "-"
    val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.KOREA)
    return "${fmt.format(value.toLong())}원"
}

private fun formatPct(value: Double?): String {
    if (value == null) return "-"
    val sign = if (value > 0) "+" else ""
    return "${sign}${String.format("%.2f", value)}%"
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun HomeSectionCard(
    title: String,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                content()
            }
        }
    }
}

// ── Account + Positions ───────────────────────────────────────────────────────

@Composable
private fun AccountPositionsCard(account: AutoTradeAccountSnapshotResponseDto) {
    var expanded by remember { mutableStateOf(true) }
    val positions = account.positions ?: emptyList()
    val hasPositions = positions.isNotEmpty()

    HomeSectionCard(title = "내 계좌") {
        // Top row: total asset + PnL
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("총 평가자산", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatKrw(account.totalAssetKrw), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("평가손익", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val pnl = account.realEvalPnlKrw ?: 0.0
                val pnlPct = account.realEvalPnlPct ?: 0.0
                Text(
                    "${formatKrw(pnl)} (${formatPct(pnlPct)})",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = pnlColor(pnl),
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Mid row: cash + orderable
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabelValue("예수금", formatKrw(account.cashKrw))
            LabelValue("매수가능", formatKrw(account.orderableCashKrw))
        }
        // Positions list
        if (hasPositions) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("보유 종목 ${positions.size}개", style = MaterialTheme.typography.labelMedium)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                positions.forEach { pos -> PositionRow(pos) }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text("보유 종목 없음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PositionRow(pos: AutoTradeAccountPositionDto) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                StockDetailActivity.open(
                    context,
                    ticker = pos.ticker ?: "",
                    name = pos.name ?: "",
                    origin = "home2_positions",
                    eventTags = emptyList(),
                )
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                pos.name ?: pos.ticker ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${pos.qty ?: 0}주 · 평단 ${formatPrice(pos.avgPrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val pnl = pos.pnlAmountKrw ?: 0.0
            Text(
                formatKrw(pnl),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = pnlColor(pnl),
                ),
            )
            Text(
                formatPct(pos.pnlPct),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = pnlColor(pos.pnlPct),
                ),
            )
        }
    }
}
