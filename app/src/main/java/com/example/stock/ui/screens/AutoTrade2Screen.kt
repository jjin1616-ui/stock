package com.example.stock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.AutoTrade2GateHistoryItemDto
import com.example.stock.data.api.AutoTrade2OrderItemDto
import com.example.stock.data.api.AutoTrade2SettingsDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.AutoTrade2ViewModel
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val krwFmt = NumberFormat.getNumberInstance(Locale.KOREA)

private fun environmentTitle2(env: String): String = when (env) {
    "demo" -> "한국투자 모의투자"
    "prod" -> "한국투자 실전투자"
    else -> "모의 실행"
}

private fun presetLabel(name: String): String = when (name) {
    "conservative" -> "보수적"
    "balanced" -> "균형"
    "aggressive" -> "공격적"
    else -> name
}

private fun orderStatusLabel2(raw: String?): String = when (raw?.trim()?.uppercase()) {
    "PAPER_FILLED" -> "내부모의체결"
    "BROKER_SUBMITTED" -> "접수(체결대기)"
    "BROKER_FILLED" -> "체결"
    "BROKER_CANCELED" -> "접수취소"
    "BROKER_CLOSED" -> "상태정리"
    "BROKER_REJECTED" -> "거부"
    "SKIPPED" -> "조건 미충족"
    "ERROR" -> "오류"
    else -> raw ?: "기타"
}

private fun orderStatusColor2(raw: String?): Color = when (raw?.trim()?.uppercase()) {
    "BROKER_FILLED", "PAPER_FILLED" -> Color(0xFF1B5E20)
    "BROKER_SUBMITTED" -> Color(0xFF0D47A1)
    "BROKER_REJECTED", "ERROR" -> Color(0xFFC62828)
    "SKIPPED" -> Color(0xFF757575)
    else -> Color(0xFF424242)
}

private fun sourceLabel2(raw: String?): String = when (raw?.trim()?.uppercase()) {
    "DAYTRADE" -> "단타"
    "MOVERS", "MOVERS2" -> "급등"
    "SUPPLY" -> "수급"
    "PAPERS" -> "논문"
    "LONGTERM" -> "장투"
    "FAVORITES" -> "관심"
    else -> "기타"
}

private fun friendlyRunMessage2(raw: String?): String {
    val msg = raw?.trim().orEmpty()
    if (msg.isBlank()) return "응답 없음"
    val up = msg.uppercase()
    return when {
        up.startsWith("EOD_FORCE_EXIT") -> "장 마감 강제 청산이 실행되었습니다."
        up.startsWith("EOD_ENTRY_BLOCKED") -> "장 마감 임박(15:25+)으로 신규 매수가 차단되었습니다."
        up.startsWith("RUN_OK") -> "실행 완료"
        up.startsWith("DRY_RUN") -> "전략 점검 완료(주문 없음)"
        up.startsWith("RUN_ALREADY_IN_PROGRESS") -> "실행 중입니다. 완료 후 다시 시도하세요."
        up.contains("DAILY_LOSS_LIMIT_REACHED") -> "일 손실 한도 도달로 신규 매수를 차단했습니다."
        up.contains("DAILY_LOSS_THROTTLED") -> "일 손실 경고 구간이라 주문량이 축소되었습니다."
        up.contains("KIS_TOKEN_RATE_LIMIT") || up.contains("EGW00133") ->
            "증권사 토큰 발급 제한(1분/1회). 1분 후 재시도하세요."
        up.startsWith("BROKER_CREDENTIAL_MISSING") -> "증권사 계정정보가 준비되지 않았습니다."
        up.contains("_BLOCKED") -> "장시간 외이며 예약이 꺼져 있어 실행 중단."
        up.contains("_RESERVED") -> "예약이 등록되었습니다."
        else -> "실행 응답 확인 필요"
    }
}

@Composable
fun AutoTrade2Screen() {
    val context = LocalContext.current
    val vm: AutoTrade2ViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    val settingsState = vm.settingsState.value
    val ordersState = vm.ordersState.value
    val runState = vm.runState.value
    val gateHistoryState = vm.gateHistoryState.value
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showGateHistory by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadAll() }

    val settings = settingsState.data?.settings
    val environment = settings?.environment ?: "paper"
    val enabled = settings?.enabled == true
    val presetName = settings?.presetName ?: "balanced"

    LaunchedEffect(runState.data) {
        val msg = runState.data?.message
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(friendlyRunMessage2(msg))
            vm.loadOrders()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopBar(
                title = "단타2",
                showRefresh = true,
                onRefresh = { vm.loadAll() },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // 상태 요약 카드
            StatusSummaryCard2(
                environment = environment,
                enabled = enabled,
                presetName = presetName,
                throttled = runState.data?.throttled == true,
                settingsLoading = settingsState.loading,
            )

            Spacer(Modifier.height(12.dp))

            // 프리셋 선택 버튼
            PresetSelector2(
                currentPreset = presetName,
                loading = settingsState.loading,
                onSelect = { vm.applyPreset(it) },
            )

            Spacer(Modifier.height(12.dp))

            // 실행 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { vm.runAutoTrade(dryRun = true) },
                    enabled = !runState.loading,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                ) {
                    Text("전략 점검")
                }
                Button(
                    onClick = { vm.runAutoTrade(dryRun = false) },
                    enabled = !runState.loading && enabled,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (environment == "prod") Color(0xFFC62828) else Color(0xFF1565C0)
                    ),
                ) {
                    if (runState.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(if (environment == "prod") "실전 실행" else "실행")
                    }
                }
            }

            // 실행 결과
            runState.data?.let { result ->
                Spacer(Modifier.height(8.dp))
                RunResultCard2(result.message, result.requestedCount, result.submittedCount, result.filledCount, result.skippedCount)
            }
            runState.error?.let { err ->
                Spacer(Modifier.height(8.dp))
                Text(err, color = Color(0xFFC62828), fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))

            // 설정 토글
            SectionHeader2(title = "설정", expanded = showSettings, onToggle = { showSettings = !showSettings })
            if (showSettings && settings != null) {
                Spacer(Modifier.height(8.dp))
                SettingsDetailCard2(settings)
            }

            Spacer(Modifier.height(12.dp))

            // Gate 이력
            SectionHeader2(title = "Gate 시계열", expanded = showGateHistory, onToggle = { showGateHistory = !showGateHistory })
            if (showGateHistory) {
                Spacer(Modifier.height(8.dp))
                if (gateHistoryState.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    val items = gateHistoryState.data?.items.orEmpty()
                    if (items.isEmpty()) {
                        Text("Gate 이력이 없습니다.", fontSize = 13.sp, color = Color.Gray)
                    } else {
                        GateHistoryList2(items.takeLast(20))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 주문 내역
            val allOrders = ordersState.data?.items.orEmpty()
            val todayStr = remember { LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) }
            var showAllOrders by remember { mutableStateOf(false) }
            val todayOrders = remember(allOrders, todayStr) {
                allOrders.filter { (it.requestedAt ?: "").startsWith(todayStr) }
            }
            val displayOrders = if (showAllOrders) allOrders.take(50) else todayOrders

            // 오늘 요약
            if (!ordersState.loading && todayOrders.isNotEmpty()) {
                TodaySummaryCard2(todayOrders)
                Spacer(Modifier.height(12.dp))
            }

            // 헤더 + 필터 토글
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showAllOrders) "전체 주문" else "오늘 주문",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                if (!showAllOrders && todayOrders.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text("${todayOrders.size}건", fontSize = 13.sp, color = Color(0xFF1565C0), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showAllOrders = !showAllOrders }) {
                    Text(
                        if (showAllOrders) "오늘만" else "전체보기",
                        fontSize = 13.sp,
                        color = Color(0xFF1565C0),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            if (ordersState.loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (ordersState.error != null) {
                Text(ordersState.error ?: "", color = Color(0xFFC62828), fontSize = 13.sp)
            } else {
                if (displayOrders.isEmpty()) {
                    Text(
                        if (showAllOrders) "주문 내역이 없습니다." else "오늘 주문이 없습니다.",
                        fontSize = 13.sp,
                        color = Color.Gray,
                    )
                } else {
                    displayOrders.forEach { order ->
                        OrderCard2(order)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusSummaryCard2(
    environment: String,
    enabled: Boolean,
    presetName: String,
    throttled: Boolean,
    settingsLoading: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (enabled) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (enabled) "활성" else "비활성",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.weight(1f))
                if (settingsLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                InfoChip2(label = "환경", value = environmentTitle2(environment))
                Spacer(Modifier.width(8.dp))
                InfoChip2(label = "프리셋", value = presetLabel(presetName))
            }
            if (throttled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "일 손실 경고 구간 — 주문량 축소 중",
                    fontSize = 12.sp,
                    color = Color(0xFFE65100),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            // EOD 경고: 15:25 이후
            val hour = java.time.LocalTime.now().hour
            val minute = java.time.LocalTime.now().minute
            if (hour >= 15 && minute >= 25) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "장 마감 임박 — 강제 청산 모드 (신규 매수 차단)",
                    fontSize = 12.sp,
                    color = Color(0xFFC62828),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun InfoChip2(label: String, value: String) {
    Row(
        modifier = Modifier
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = Color.Gray)
        Spacer(Modifier.width(4.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PresetSelector2(
    currentPreset: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
) {
    val presets = listOf("conservative", "balanced", "aggressive")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { name ->
            val selected = name == currentPreset
            val bgColor = if (selected) Color(0xFF1565C0) else Color(0xFFEEEEEE)
            val textColor = if (selected) Color.White else Color(0xFF424242)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable(enabled = !loading) { onSelect(name) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(presetLabel(name), color = textColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun RunResultCard2(
    message: String?,
    requested: Int?,
    submitted: Int?,
    filled: Int?,
    skipped: Int?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(friendlyRunMessage2(message), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "요청 ${requested ?: 0} | 접수 ${submitted ?: 0} | 체결 ${filled ?: 0} | 스킵 ${skipped ?: 0}",
                fontSize = 12.sp,
                color = Color(0xFF546E7A),
            )
        }
    }
}

@Composable
private fun SectionHeader2(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Text(if (expanded) "접기" else "펼치기", fontSize = 12.sp, color = Color(0xFF1565C0))
    }
}

@Composable
private fun SettingsDetailCard2(s: AutoTrade2SettingsDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            SettingRow2("종목당 예산", "${krwFmt.format(s.orderBudgetKrw ?: 0)}원")
            SettingRow2("시드", "${krwFmt.format(s.seedKrw ?: 0)}원")
            SettingRow2("최대 주문/회", "${s.maxOrdersPerRun ?: 5}개")
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            SettingRow2("익절", "${s.takeProfitPct ?: 7.0}%")
            SettingRow2("손절", "${s.stopLossPct ?: 5.0}%")
            SettingRow2("부분익절", if (s.partialTpEnabled == true) "${s.partialTpPct}%에서 ${((s.partialTpRatio ?: 0.5) * 100).toInt()}% 선매도" else "비활성")
            SettingRow2("최종익절", "${s.finalTpPct ?: 7.0}%")
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            SettingRow2("일 손실 경고", "${s.dailyLossThrottlePct ?: 3.0}% (주문 ${((s.dailyLossThrottleRatio ?: 0.5) * 100).toInt()}% 축소)")
            SettingRow2("일 손실 차단", "${s.dailyLossBlockPct ?: 5.0}%")
            SettingRow2("AVG_FALLBACK 최대", "${s.avgFallbackMaxCount ?: 3}회 (강제청산: ${if (s.avgFallbackForceExit == true) "ON" else "OFF"})")
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            SettingRow2("손절 재진입", s.stoplossReentryPolicy ?: "cooldown")
            SettingRow2("익절 재진입", s.takeprofitReentryPolicy ?: "cooldown")
        }
    }
}

@Composable
private fun SettingRow2(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFF757575), modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GateHistoryList2(items: List<AutoTrade2GateHistoryItemDto>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("날짜", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                Text("Metric", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                Text("Gate", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(0.6f))
                Text("체제", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            items.reversed().forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(item.ymd?.takeLast(5) ?: "", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        String.format("%.4f", item.gateMetric ?: 0.0),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (item.gateOn == true) "ON" else "OFF",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (item.gateOn == true) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                        modifier = Modifier.weight(0.6f),
                    )
                    Text(
                        item.regime ?: "-",
                        fontSize = 11.sp,
                        color = Color(0xFF616161),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TodaySummaryCard2(todayOrders: List<AutoTrade2OrderItemDto>) {
    val filled = todayOrders.count { it.status?.uppercase() in setOf("BROKER_FILLED", "PAPER_FILLED") }
    val skipped = todayOrders.count { it.status?.uppercase() == "SKIPPED" }
    val errors = todayOrders.count { it.status?.uppercase() in setOf("ERROR", "BROKER_REJECTED") }
    val buys = todayOrders.count { it.side?.uppercase() == "BUY" }
    val sells = todayOrders.count { it.side?.uppercase() == "SELL" }
    val pnlSum = todayOrders.filter { it.pnlPct != null }.sumOf { it.pnlPct ?: 0.0 }
    val pnlColor = if (pnlSum >= 0) Color(0xFFC62828) else Color(0xFF1565C0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F8E9)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("오늘 요약", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("체결", fontSize = 11.sp, color = Color.Gray)
                    Text("$filled", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF2E7D32))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("스킵", fontSize = 11.sp, color = Color.Gray)
                    Text("$skipped", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF757575))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("에러", fontSize = 11.sp, color = Color.Gray)
                    Text("$errors", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (errors > 0) Color(0xFFC62828) else Color(0xFF757575))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("매수/매도", fontSize = 11.sp, color = Color.Gray)
                    Text("$buys/$sells", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                if (pnlSum != 0.0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("수익률합", fontSize = 11.sp, color = Color.Gray)
                        Text(String.format("%+.2f%%", pnlSum), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = pnlColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderCard2(order: AutoTrade2OrderItemDto) {
    val side = order.side?.uppercase() ?: "BUY"
    val sideColor = if (side == "BUY") Color(0xFFC62828) else Color(0xFF1565C0)
    val sideLabel = if (side == "BUY") "매수" else "매도"
    val statusColor = orderStatusColor2(order.status)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sideLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(sideColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    order.name ?: order.ticker ?: "",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    orderStatusLabel2(order.status),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    "${order.qty ?: 0}주 @ ${krwFmt.format(order.requestedPrice ?: 0)}원",
                    fontSize = 12.sp,
                    color = Color(0xFF757575),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    sourceLabel2(order.sourceTab),
                    fontSize = 11.sp,
                    color = Color(0xFF9E9E9E),
                )
            }
            // 체결가 + 수익률
            if (order.filledPrice != null) {
                Row {
                    Text(
                        "체결 ${krwFmt.format(order.filledPrice)}원",
                        fontSize = 12.sp,
                        color = Color(0xFF424242),
                    )
                    if (order.pnlPct != null) {
                        Spacer(Modifier.width(8.dp))
                        val pnlColor = if ((order.pnlPct ?: 0.0) >= 0) Color(0xFFC62828) else Color(0xFF1565C0)
                        Text(
                            String.format("%+.2f%%", order.pnlPct),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = pnlColor,
                        )
                    }
                }
            }
            // suggestion
            if (!order.suggestion.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    order.suggestion,
                    fontSize = 11.sp,
                    color = Color(0xFF0D47A1),
                    modifier = Modifier
                        .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            // reason label
            if (!order.reasonLabel.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(order.reasonLabel, fontSize = 11.sp, color = Color(0xFF757575))
            }
        }
    }
}
