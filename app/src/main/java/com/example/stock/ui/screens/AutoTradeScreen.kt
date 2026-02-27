package com.example.stock.ui.screens

import androidx.compose.animation.AnimatedVisibility
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.AutoTradeBrokerCredentialDto
import com.example.stock.data.api.AutoTradeCandidateItemDto
import com.example.stock.data.api.AutoTradeOrderItemDto
import com.example.stock.data.api.AutoTradePerformanceItemDto
import com.example.stock.data.api.AutoTradeReasonDetailDto
import com.example.stock.data.api.AutoTradeReservationItemDto
import com.example.stock.data.api.AutoTradeRunResponseDto
import com.example.stock.data.api.AutoTradeSettingsDto
import com.example.stock.data.api.AutoTradeAccountSnapshotResponseDto
import com.example.stock.data.api.AutoTradeSymbolRuleItemDto
import com.example.stock.data.api.AutoTradeSymbolRuleUpsertDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.data.repository.UiSource
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.CommonFilterCard
import com.example.stock.ui.common.CommonPillChip
import com.example.stock.ui.common.CommonReportItemCard
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.common.CommonReportList
import com.example.stock.ui.common.GlossaryPresets
import com.example.stock.ui.common.MetricUi
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.AutoTradeViewModel
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun environmentTitle(env: String): String = when (env) {
    "demo" -> "한국투자 모의투자"
    "prod" -> "한국투자 실전투자"
    else -> "모의 실행"
}

private fun accountSourceLabel(raw: String?, environment: String? = null): String {
    val upper = raw?.trim()?.uppercase().orEmpty()
    return when (upper) {
        "BROKER_LIVE" -> "실계좌 연동"
        "LOCAL_ESTIMATE" -> if (environment == "paper") "모의 장부" else "연동 실패(추정치 미표시)"
        "UNAVAILABLE", "" -> "미연동"
        else -> "미확인"
    }
}

private fun accountSourceShortLabel(raw: String?, environment: String? = null): String {
    val upper = raw?.trim()?.uppercase().orEmpty()
    return when (upper) {
        "BROKER_LIVE" -> "실계좌"
        "LOCAL_ESTIMATE" -> if (environment == "paper") "모의" else "연동필요"
        "UNAVAILABLE", "" -> "미연동"
        else -> "미확인"
    }
}

private fun isDisplayableSnapshotSource(source: String?): Boolean {
    return source?.trim()?.uppercase() == "BROKER_LIVE"
}

private fun sourceLabel(raw: String?): String = when (raw?.trim()?.uppercase()) {
    "DAYTRADE" -> "단타"
    "MOVERS", "MOVERS2" -> "급등"
    "SUPPLY" -> "수급"
    "PAPERS" -> "논문"
    "LONGTERM" -> "장투"
    "FAVORITES" -> "관심"
    "RECENT" -> "최근이력"
    else -> "기타"
}

private fun friendlyBrokerRejectReason(raw: String?): String {
    val msg = raw?.trim().orEmpty()
    if (msg.isBlank()) return "거부(사유 미수신)"
    return when {
        msg.contains("주문가능금액을 초과") -> "거부(주문가능금액 초과)"
        msg.contains("잔고내역이 없습니다") -> "거부(보유잔고 없음)"
        msg.contains("장시작전") || msg.contains("장운영시간이 아닙니다") -> "거부(장시간 외)"
        else -> "거부($msg)"
    }
}

private fun orderStatusLabel(raw: String?, reason: String? = null): String = when (raw?.trim()?.uppercase()) {
    "PAPER_FILLED" -> "내부모의체결"
    "BROKER_SUBMITTED" -> "증권사 접수(체결대기)"
    "BROKER_FILLED" -> "증권사 체결"
    "BROKER_CANCELED" -> "증권사 접수취소 완료"
    "BROKER_REJECTED" -> "증권사 ${friendlyBrokerRejectReason(reason)}"
    "SKIPPED" -> "스킵"
    "ERROR" -> "오류"
    else -> "기타"
}

private fun runMarketPhaseLabel(msgUpper: String): String = when {
    msgUpper.contains("PREOPEN") -> "장 시작 전"
    msgUpper.contains("CLOSED") -> "장 마감 후"
    msgUpper.contains("HOLIDAY") -> "휴장 시간"
    else -> "장시간 외"
}

private fun friendlyRunMessage(raw: String?): String {
    val msg = raw?.trim().orEmpty()
    if (msg.isBlank()) return "응답 없음"
    val up = msg.uppercase()
    return when {
        up.startsWith("RUN_OK") -> "실행 완료"
        up.startsWith("DRY_RUN") -> "전략 점검 완료(주문 없음)"
        up.contains("RESERVATION_AVAILABLE") -> "${runMarketPhaseLabel(up)}입니다. 예약 주문으로 전환할 수 있습니다."
        up.contains("_RESERVED") -> "${runMarketPhaseLabel(up)} 예약이 등록되었습니다."
        up.contains("_BLOCKED") -> "${runMarketPhaseLabel(up)}이며 예약 설정이 꺼져 있어 실행을 중단했습니다."
        up.startsWith("BROKER_CREDENTIAL_MISSING") -> "증권사 계정정보가 준비되지 않아 주문을 실행하지 못했습니다."
        up.startsWith("BROKER_BALANCE_UNAVAILABLE") -> "증권사 계좌 조회에 실패해 주문을 실행하지 못했습니다."
        up.startsWith("DAILY_LOSS_LIMIT_REACHED") -> "일 손실 한도 도달로 신규 매수를 차단했습니다."
        up.contains("KIS_TOKEN_RATE_LIMIT") || up.contains("EGW00133") ->
            "증권사 토큰 발급 제한(1분/1회)으로 실패했습니다. 1분 후 다시 실행하세요."
        else -> "실행 응답 확인 필요"
    }
}

private fun friendlyRunReason(raw: String?): String {
    val msg = raw?.trim().orEmpty()
    if (msg.isBlank()) return "-"
    val up = msg.uppercase()
    return when {
        up == "ORDERABLE_CASH_LIMIT" -> "주문가능현금 한도 초과"
        up == "SEED_LIMIT_EXCEEDED" -> "총 시드 한도 초과"
        up == "ALREADY_OPEN_POSITION" -> "이미 보유중 종목"
        up == "ENTRY_BLOCKED_MANUAL" -> "종목별 진입 차단"
        up == "PENDING_BUY_ORDER" -> "접수 대기 중복매수 방지"
        up == "PENDING_SELL_ORDER" -> "접수 대기 중복매도 방지"
        up == "STOPLOSS_REENTRY_COOLDOWN" -> "손절 후 재진입 대기시간"
        up == "STOPLOSS_REENTRY_BLOCKED_TODAY" -> "손절 당일 재진입 차단"
        up == "STOPLOSS_REENTRY_BLOCKED_MANUAL" -> "손절 수동 재진입 차단"
        up == "TAKEPROFIT_REENTRY_COOLDOWN" -> "익절 후 재진입 대기시간"
        up == "TAKEPROFIT_REENTRY_BLOCKED_TODAY" -> "익절 당일 재진입 차단"
        up == "TAKEPROFIT_REENTRY_BLOCKED_MANUAL" -> "익절 수동 재진입 차단"
        up == "QTY_ZERO" -> "종목당 예산 대비 수량 0"
        up == "SELLABLE_QTY_ZERO" -> "매도가능수량 0"
        msg.contains("주문가능금액을 초과") -> "주문가능금액 초과"
        msg.contains("잔고내역이 없습니다") -> "보유잔고 없음"
        msg.contains("장시작전") || msg.contains("장운영시간이 아닙니다") -> "장시간 외 주문불가"
        msg.contains("주문 전송 완료") -> "주문 전송 완료"
        else -> "사유 확인 필요"
    }
}

private fun buildRunAlertBody(
    runResult: AutoTradeRunResponseDto?,
    error: String?,
    accountSnapshot: AutoTradeAccountSnapshotResponseDto?,
): String {
    if (!error.isNullOrBlank()) {
        val err = if (error.contains("EGW00133", ignoreCase = true)) {
            "증권사 토큰 발급 제한(1분/1회)으로 실행에 실패했습니다. 1분 후 다시 시도하세요."
        } else error
        return err
    }
    if (runResult == null) {
        return "실행 결과가 없습니다."
    }
    val lines = mutableListOf<String>()
    lines += "상태: ${friendlyRunMessage(runResult.message)}"
    if (runResult.queued == true && runResult.reservationId != null) {
        lines += "예약 ID: #${runResult.reservationId} (${runResult.reservationStatus ?: "QUEUED"})"
        val previewCount = runResult.reservationPreviewCount ?: runResult.requestedCount ?: 0
        lines += "예약 검토 대상 ${previewCount}건"
        val previewNames = runResult.reservationPreviewItems.orEmpty()
            .mapNotNull { item -> item.name?.trim()?.takeIf { it.isNotBlank() } ?: item.ticker?.trim()?.takeIf { it.isNotBlank() } }
        if (previewNames.isNotEmpty()) {
            val topNames = previewNames.take(6)
            val extra = if (previewCount > topNames.size) " 외 ${previewCount - topNames.size}건" else ""
            lines += "예약 종목: ${topNames.joinToString(", ")}$extra"
        }
        lines += "접수/체결/스킵은 예약 실행 시점(장중) 이후 집계됩니다."
    } else {
        lines += "요청 ${runResult.requestedCount ?: 0}건 · 접수 ${runResult.submittedCount ?: 0}건 · 체결 ${runResult.filledCount ?: 0}건 · 스킵 ${runResult.skippedCount ?: 0}건"
    }

    val reasons = runResult.orders.orEmpty()
        .mapNotNull { it.reason?.trim()?.takeIf { txt -> txt.isNotBlank() } }
        .groupingBy { friendlyRunReason(it) }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
    if (reasons.isNotEmpty()) {
        lines += "주요 사유: " + reasons.take(3).joinToString(" / ") { "${it.key} ${it.value}건" }
    }

    val orderableCash = accountSnapshot?.orderableCashKrw
    if (orderableCash != null) {
        lines += "주문가능현금: ${"%,.0f".format(orderableCash)}원"
    }
    return lines.joinToString("\n")
}

private fun buildReservationPromptBody(
    runResult: AutoTradeRunResponseDto?,
    settings: AutoTradeSettingsDto?,
): String {
    val up = runResult?.message?.trim()?.uppercase().orEmpty()
    val phaseLabel = runMarketPhaseLabel(up)
    val mode = if ((settings?.offhoursReservationMode ?: "auto").equals("confirm", ignoreCase = true)) "확인 후 실행" else "자동 실행"
    val timeoutMin = settings?.offhoursConfirmTimeoutMin ?: 3
    val timeoutAction = if ((settings?.offhoursConfirmTimeoutAction ?: "cancel").equals("auto", ignoreCase = true)) {
        "시간초과 시 자동실행"
    } else {
        "시간초과 시 취소"
    }
    val previewCount = runResult?.reservationPreviewCount ?: runResult?.requestedCount ?: 0
    val previewNames = runResult?.reservationPreviewItems.orEmpty()
        .mapNotNull { item -> item.name?.trim()?.takeIf { it.isNotBlank() } ?: item.ticker?.trim()?.takeIf { it.isNotBlank() } }
        .take(6)
    val previewLine = if (previewNames.isEmpty()) {
        "예약 검토 대상: ${previewCount}건"
    } else {
        val extra = if (previewCount > previewNames.size) " 외 ${previewCount - previewNames.size}건" else ""
        "예약 종목: ${previewNames.joinToString(", ")}$extra"
    }
    return "$phaseLabel 상태입니다.\n지금 예약 주문을 등록하면 다음 거래 가능 시점에 실행됩니다.\n$previewLine\n예약 모드: $mode\n확인 대기: ${timeoutMin}분 ($timeoutAction)"
}

private fun flagText(v: Boolean?): String = if (v == true) "준비됨" else "미준비"

private fun friendlyAccountMessage(raw: String?): String? {
    val msg = raw?.trim().orEmpty()
    if (msg.isBlank()) return null
    val up = msg.uppercase()
    return when {
        up.startsWith("PAPER_MODE_ESTIMATE") -> "내부 모드 계좌입니다. 테스트(모의투자) 또는 실전으로 전환하세요."
        up.startsWith("KIS_TRADING_DISABLED") -> "서버 실주문 엔진이 비활성화되어 계좌 연동을 사용할 수 없습니다."
        up.startsWith("BROKER_CREDENTIAL_NOT_READY") -> "증권사 계정정보가 준비되지 않아 계좌 연동을 사용할 수 없습니다."
        up.startsWith("BROKER_RATE_LIMIT") -> "증권사 호출 한도 도달로 계좌 연동이 일시 중단되었습니다. 잠시 후 새로고침하세요."
        up.startsWith("BROKER_CREDENTIAL_INVALID") -> "증권사 계정정보가 유효하지 않습니다. 계정정보를 다시 확인하세요."
        else -> msg
    }
}

private fun crossEnvironmentNotice(
    environment: String,
    orders: List<AutoTradeOrderItemDto>,
): String? {
    val hasDemoOrderSignal = orders.any { (it.reason ?: "").contains("모의투자") }
    return when {
        environment == "prod" && hasDemoOrderSignal ->
            "현재 실행환경은 실전인데 최근 주문 이력은 모의투자에서 발생했습니다. 실전 보유/손익과 다르게 보일 수 있습니다."
        else -> null
    }
}

private fun brokerNotReadyMessage(
    environment: String,
    broker: AutoTradeBrokerCredentialDto?,
): String {
    if (broker?.kisTradingEnabled != true) {
        return "서버 실주문 엔진이 꺼져 있습니다. 관리자에게 KIS_TRADING_ENABLED 상태를 확인하세요."
    }
    val usingUser = broker.useUserCredentials == true
    return when (environment) {
        "prod" -> when {
            usingUser && (broker.hasProdAppKey != true || broker.hasProdAppSecret != true || broker.hasProdAccountNo != true) ->
                "사용자별 계정 사용 활성 상태인데 실전 앱 키/시크릿/계좌가 준비되지 않았습니다."
            !usingUser && broker.prodReadyServer != true ->
                "서버 공용 실전 계정(AppKey/Secret/계좌)이 서버에 등록되지 않았습니다."
            else -> "실전 증권사 계정정보가 준비되지 않았습니다."
        }
        "demo" -> when {
            usingUser && (broker.hasDemoAppKey != true || broker.hasDemoAppSecret != true || broker.hasDemoAccountNo != true) ->
                "사용자별 계정 사용 활성 상태인데 모의 앱 키/시크릿/계좌가 준비되지 않았습니다."
            !usingUser && broker.demoReadyServer != true ->
                "서버 공용 모의 계정(AppKey/Secret/계좌)이 서버에 등록되지 않았습니다."
            else -> "모의 증권사 계정정보가 준비되지 않았습니다."
        }
        else -> "증권사 계정정보를 확인하세요."
    }
}

private enum class AutoTradeViewMode(val label: String) {
    OVERVIEW("개요"),
    HOLDINGS("보유"),
    EXECUTIONS("체결"),
    CANDIDATES("후보"),
}

private data class AutoTradeHoldingUi(
    val ticker: String,
    val name: String,
    val sourceTab: String,
    val qty: Int,
    val avgPrice: Double,
    val currentPrice: Double,
    val pnlPct: Double,
) {
    val evalAmount: Double get() = currentPrice * qty.toDouble()
}

private data class AutoTradeHoldingDetailUi(
    val ticker: String,
    val name: String,
    val qty: Int,
    val avgPrice: Double,
    val currentPrice: Double,
    val evalAmount: Double,
    val investedAmount: Double,
    val pnlAmount: Double,
    val pnlPct: Double,
    val dayChangePct: Double?,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AutoTradeScreen(
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: AutoTradeViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    val settingsState = vm.settingsState.value
    val symbolRulesState = vm.symbolRulesState.value
    val brokerState = vm.brokerState.value
    val candidatesState = vm.candidatesState.value
    val ordersState = vm.ordersState.value
    val reservationsState = vm.reservationsState.value
    val accountState = vm.accountState.value
    val runState = vm.runState.value
    val reservationActionState = vm.reservationActionState.value
    val orderCancelState = vm.orderCancelState.value
    val pendingCancelState = vm.pendingCancelState.value
    val snackbarHostState = remember { SnackbarHostState() }
    val holdingDetailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var refreshToken by remember { mutableStateOf(0) }
    var prodConfirmChecked by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showHoldingCurrentPrice by remember { mutableStateOf(true) }
    var selectedHoldingDetail by remember { mutableStateOf<AutoTradeHoldingDetailUi?>(null) }
    var showHoldings by remember { mutableStateOf(false) }
    var showPending by remember { mutableStateOf(true) }
    var showExecutions by remember { mutableStateOf(false) }
    var showSkipped by remember { mutableStateOf(false) }
    var showChecklist by remember { mutableStateOf(false) }
    var showAlgorithm by remember { mutableStateOf(false) }
    var showEntryControl by remember { mutableStateOf(false) }
    var showRiskGuide by remember { mutableStateOf(false) }
    var runAlertTitle by remember { mutableStateOf<String?>(null) }
    var runAlertBody by remember { mutableStateOf<String?>(null) }
    var reservePromptTitle by remember { mutableStateOf<String?>(null) }
    var reservePromptBody by remember { mutableStateOf<String?>(null) }
    var lastHandledRunKey by remember { mutableStateOf<String?>(null) }
    var lastHandledOrderCancelKey by remember { mutableStateOf<String?>(null) }
    var lastHandledPendingCancelKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.loadAll() }

    val settings = settingsState.data?.settings
    val brokerInfo = brokerState.data
    val environment = settings?.environment ?: "demo"
    val enabled = settings?.enabled == true

    LaunchedEffect(environment) {
        if (environment != "prod" && prodConfirmChecked) {
            prodConfirmChecked = false
        }
    }

    val doRefresh = {
        refreshToken += 1
        vm.loadAll()
    }

    val statusMsg = runState.data?.message
        ?: runState.error
        ?: orderCancelState.error
        ?: pendingCancelState.error
        ?: reservationActionState.error
        ?: reservationsState.error
        ?: ordersState.error
        ?: accountState.error
        ?: brokerState.error
        ?: symbolRulesState.error
    val rawOrders = ordersState.data?.items.orEmpty()
    val scopedOrders = rawOrders.filter { isOrderForEnvironment(it, environment) }
    val rawReservations = reservationsState.data?.items.orEmpty()
    val scopedReservations = rawReservations.filter { isReservationForEnvironment(it, environment) }
    val candidateItems = candidatesState.data?.items.orEmpty()
    val sourceBreakdown = ((candidatesState.data?.sourceCounts ?: emptyMap()).entries)
        .filter { it.value > 0 }
        .associate { entry ->
            val label = sourceLabel(entry.key)
            label to (entry.value)
        }
        .toSortedMap()
        .ifEmpty {
            candidateItems
                .groupingBy { sourceLabel(it.sourceTab) }
                .eachCount()
                .toSortedMap()
        }
    val candidateWarnings = candidatesState.data?.warnings.orEmpty()
    val sourceSummaryText = if (sourceBreakdown.isEmpty()) {
        "없음"
    } else {
        sourceBreakdown.entries.joinToString("/") { "${it.key}:${it.value}" }
    }
    val accountSnapshot = accountState.data
    val displaySnapshot = accountSnapshot
    val snapshotSource = displaySnapshot?.source?.uppercase()
    val brokerLiveAccount = snapshotSource == "BROKER_LIVE"
    val holdings = if (isDisplayableSnapshotSource(snapshotSource)) buildHoldingSummaryFromAccount(displaySnapshot) else emptyList()
    val holdingQuotes = vm.holdingQuoteState.value
    val recentExecutions = scopedOrders
        .filter { isOrderFilled(it.status) }
        .sortedByDescending { it.filledAt ?: it.requestedAt ?: "" }
        .take(10)
    val pendingOrders = scopedOrders
        .filter { isOrderPending(it.status) }
        .sortedByDescending { it.requestedAt ?: "" }
        .take(20)
    val failedOrders = scopedOrders
        .filter { isOrderFailed(it.status) }
        .sortedByDescending { it.requestedAt ?: "" }
        .take(20)
    val skippedOrders = scopedOrders
        .filter { isOrderSkipped(it.status) }
        .sortedByDescending { it.requestedAt ?: "" }
        .take(20)
    val pendingReservations = scopedReservations
        .filter { isReservationPending(it.status) }
        .sortedByDescending { it.updatedAt ?: it.requestedAt ?: "" }
        .take(20)
    val closedReservations = scopedReservations
        .filter { !isReservationPending(it.status) }
        .sortedByDescending { it.updatedAt ?: it.requestedAt ?: "" }
        .take(20)
    val recentBuyAttempts = scopedOrders
        .filter { (it.side ?: "BUY").uppercase() == "BUY" }
        .sortedByDescending { it.requestedAt ?: "" }
        .distinctBy { normalizeTicker(it.ticker) }
        .take(10)
    val symbolRuleMap = symbolRulesState.data?.items.orEmpty()
        .mapNotNull { row ->
            val ticker = normalizeTicker(row.ticker)
            ticker.takeIf { it.isNotBlank() }?.let { it to row }
        }
        .toMap()
    val takeProfitPct = settings?.takeProfitPct ?: 0.0
    val stopLossPct = settings?.stopLossPct ?: 0.0
    val maxDailyLossPct = settings?.maxDailyLossPct ?: 0.0
    val offhoursReservationEnabled = settings?.offhoursReservationEnabled != false
    val offhoursReservationMode = settings?.offhoursReservationMode ?: "auto"
    val offhoursConfirmTimeoutMin = settings?.offhoursConfirmTimeoutMin ?: 3
    val offhoursConfirmTimeoutAction = settings?.offhoursConfirmTimeoutAction ?: "cancel"

    val brokerReadyForEnvironment = when (environment) {
        "demo" -> brokerInfo?.demoReadyEffective == true
        "prod" -> brokerInfo?.prodReadyEffective == true
        else -> true
    }
    val brokerCheckRequired = environment in setOf("demo", "prod") && (brokerInfo?.kisTradingEnabled == true)
    val runEnabled = enabled &&
        !runState.loading &&
        (!brokerCheckRequired || brokerReadyForEnvironment) &&
        (environment != "prod" || prodConfirmChecked)
    val dryRunEnabled = !runState.loading
    val validationMessage = when {
        settings == null -> "자동매매 설정을 불러오는 중입니다."
        brokerCheckRequired && !brokerReadyForEnvironment -> brokerNotReadyMessage(environment, brokerInfo)
        !enabled -> "자동매매 활성화를 켜야 실행할 수 있습니다."
        environment == "prod" && !prodConfirmChecked -> "실전투자 실행 전 확인 체크가 필요합니다."
        else -> null
    }
    val runButtonText = when (environment) {
        "demo" -> "모의 주문 실행"
        "prod" -> "실전 주문 실행"
        else -> "모의 주문 실행"
    }
    val listEmptyText = ""

    LaunchedEffect(holdings.map { it.ticker }.joinToString(",")) {
        vm.loadHoldingQuotes(holdings.map { it.ticker })
    }

    LaunchedEffect(runState.loading, runState.refreshedAt, runState.error, runState.data?.runId) {
        if (runState.loading || runState.refreshedAt.isNullOrBlank()) return@LaunchedEffect
        val key = listOf(runState.refreshedAt, runState.data?.runId, runState.error).joinToString("|")
        if (key == lastHandledRunKey) return@LaunchedEffect
        if (runState.data != null || !runState.error.isNullOrBlank()) {
            val msgUpper = runState.data?.message?.trim()?.uppercase().orEmpty()
            if (msgUpper.contains("RESERVATION_AVAILABLE")) {
                reservePromptTitle = if (environment == "prod") "실전 예약 주문" else "예약 주문"
                reservePromptBody = buildReservationPromptBody(runState.data, settings)
                lastHandledRunKey = key
                return@LaunchedEffect
            }
            runAlertTitle = when {
                !runState.error.isNullOrBlank() ->
                    if (environment == "prod") "실전 주문 실행 실패" else "주문 실행 실패"
                runState.data?.message?.uppercase()?.startsWith("DRY_RUN") == true -> "전략 점검 결과"
                runState.data?.message?.uppercase()?.contains("_RESERVED") == true -> "예약 등록 결과"
                environment == "prod" -> "실전 주문 실행 결과"
                else -> "주문 실행 결과"
            }
            runAlertBody = buildRunAlertBody(runState.data, runState.error, accountSnapshot)
            lastHandledRunKey = key
        }
    }

    LaunchedEffect(reservationActionState.loading, reservationActionState.refreshedAt, reservationActionState.error) {
        if (reservationActionState.loading) return@LaunchedEffect
        val actionData = reservationActionState.data
        val message = when {
            !reservationActionState.error.isNullOrBlank() -> reservationActionState.error
            actionData?.reservation?.status?.uppercase() == "CANCELED" -> "예약 주문을 취소했습니다."
            actionData?.reservation?.status?.uppercase() in setOf("DONE", "PARTIAL", "FAILED") ->
                "예약 주문 실행 결과: ${reservationStatusLabel(actionData?.reservation?.status)}"
            else -> null
        }
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(orderCancelState.loading, orderCancelState.refreshedAt, orderCancelState.error) {
        if (orderCancelState.loading) return@LaunchedEffect
        val key = listOf(orderCancelState.refreshedAt, orderCancelState.error, orderCancelState.data?.order?.id).joinToString("|")
        if (key == lastHandledOrderCancelKey) return@LaunchedEffect
        val message = when {
            !orderCancelState.error.isNullOrBlank() -> orderCancelState.error
            orderCancelState.data?.ok == true -> orderCancelState.data?.message ?: "접수대기 주문 취소 완료"
            orderCancelState.data != null -> "접수취소 실패: ${orderCancelState.data?.message ?: "사유 미확인"}"
            else -> null
        }
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
        }
        lastHandledOrderCancelKey = key
    }

    LaunchedEffect(pendingCancelState.loading, pendingCancelState.refreshedAt, pendingCancelState.error) {
        if (pendingCancelState.loading) return@LaunchedEffect
        val key = listOf(
            pendingCancelState.refreshedAt,
            pendingCancelState.error,
            pendingCancelState.data?.requestedCount,
            pendingCancelState.data?.canceledCount,
            pendingCancelState.data?.failedCount,
        ).joinToString("|")
        if (key == lastHandledPendingCancelKey) return@LaunchedEffect
        val message = when {
            !pendingCancelState.error.isNullOrBlank() -> pendingCancelState.error
            pendingCancelState.data?.ok == true -> pendingCancelState.data?.message ?: "접수대기 주문 일괄취소 처리 완료"
            else -> null
        }
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
        }
        lastHandledPendingCancelKey = key
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AppTopBar(title = "자동매매 알고리즘", showRefresh = true, onRefresh = { doRefresh() }) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (settingsState.loading && settingsState.data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("자동매매 초기 데이터 동기화 중...", color = Color(0xFF64748B))
                    }
                }
            } else {
                CommonReportList(
                    source = UiSource.LIVE,
                    statusMessage = statusMsg ?: "자동매매 알고리즘 화면",
                    updatedAt = runState.refreshedAt ?: accountState.refreshedAt ?: ordersState.refreshedAt ?: settingsState.refreshedAt,
                    header = "자동매매 알고리즘",
                    glossaryDialogTitle = "자동매매 용어 설명집",
                    glossaryItems = GlossaryPresets.AUTOTRADE,
                    items = emptyList(),
                    emptyText = listEmptyText,
                    initialDisplayCount = 40,
                    refreshToken = refreshToken,
                    refreshLoading = settingsState.loading ||
                        symbolRulesState.loading ||
                        brokerState.loading ||
                        candidatesState.loading ||
                        ordersState.loading ||
                        reservationsState.loading ||
                        accountState.loading ||
                        runState.loading ||
                        reservationActionState.loading ||
                        orderCancelState.loading ||
                        pendingCancelState.loading,
                    onRefresh = { doRefresh() },
                    snackbarHostState = snackbarHostState,
                    receivedCount = null,
                    query = query,
                    onQueryChange = { query = it },
                    showSearchBar = false,
                    showSortBar = false,
                    showRiskRules = false,
                    riskRules = listOf(
                        "설정/계정정보 관리는 설정 탭에서 수행합니다.",
                        "권장 기본 모드는 테스트(모의투자)입니다.",
                        "후보 소스는 단타/급등/수급/논문/장투/관심에서 통합됩니다.",
                        "설정한 익절/손절(예: +7%, -5%)을 만족하면 자동 청산을 시도합니다.",
                    ),
                    topContent = {
                        item {
                            AutoTradeEnvironmentSwitchCard(
                                environment = environment,
                                accountSnapshot = accountSnapshot,
                                brokerInfo = brokerInfo,
                                loading = settingsState.loading,
                                onSelectEnvironment = { next ->
                                    val base = settings ?: AutoTradeSettingsDto()
                                    vm.saveSettings(base.copy(environment = next))
                                },
                            )
                        }
                        item {
                            AutoTradeExecutionCard(
                                environment = environment,
                                dryRunEnabled = dryRunEnabled,
                                runEnabled = runEnabled,
                                runLoading = runState.loading,
                                validationMessage = validationMessage,
                                offhoursReservationEnabled = offhoursReservationEnabled,
                                offhoursReservationMode = offhoursReservationMode,
                                offhoursConfirmTimeoutMin = offhoursConfirmTimeoutMin,
                                offhoursConfirmTimeoutAction = offhoursConfirmTimeoutAction,
                                prodConfirmChecked = prodConfirmChecked,
                                onProdConfirmChecked = { prodConfirmChecked = it },
                                onDryRun = { vm.run(dryRun = true, limit = settings?.maxOrdersPerRun) },
                                onRun = { vm.run(dryRun = false, limit = settings?.maxOrdersPerRun) },
                                runButtonText = runButtonText,
                            )
                        }
                        item {
                            AutoTradeAccountSnapshotCard(
                                snapshot = displaySnapshot,
                                loading = accountState.loading,
                                error = accountState.error,
                                environment = environment,
                            )
                        }
                        item {
                            AutoTradeCollapsibleCard(
                                title = "보유 종목",
                                summary = buildHoldingsSummary(
                                    count = holdings.size,
                                    source = displaySnapshot?.source,
                                    showCurrentPrice = showHoldingCurrentPrice,
                                    environment = environment,
                                ),
                                expanded = showHoldings,
                                onToggle = { showHoldings = !showHoldings },
                            ) {
                                AutoTradeHoldingsCard(
                                    holdings = holdings,
                                    source = displaySnapshot?.source ?: "UNAVAILABLE",
                                    loading = ordersState.loading || accountState.loading,
                                    maxItems = Int.MAX_VALUE,
                                    title = "보유 종목",
                                    showCurrentPrice = showHoldingCurrentPrice,
                                    quoteMap = holdingQuotes,
                                    onToggleCurrentPrice = { showHoldingCurrentPrice = it },
                                    onSelectHolding = { selectedHoldingDetail = it },
                                    environment = environment,
                                )
                            }
                        }
                        item {
                            AutoTradeCollapsibleCard(
                                title = "진행중/미체결",
                                summary = buildPendingSummary(
                                    pendingOrderCount = pendingOrders.size,
                                    pendingReservationCount = pendingReservations.size,
                                    latestAt = (
                                        pendingOrders.firstOrNull()?.requestedAt
                                            ?: pendingReservations.firstOrNull()?.updatedAt
                                            ?: pendingReservations.firstOrNull()?.requestedAt
                                        ),
                                ),
                                expanded = showPending,
                                onToggle = { showPending = !showPending },
                            ) {
                                AutoTradePendingCenterCard(
                                    pendingOrders = pendingOrders,
                                    pendingReservations = pendingReservations,
                                    environment = environment,
                                    loading = ordersState.loading || reservationsState.loading,
                                    actionLoading = reservationActionState.loading || orderCancelState.loading || pendingCancelState.loading,
                                    onConfirmReservation = { reservationId -> vm.confirmReservation(reservationId) },
                                    onCancelReservation = { reservationId -> vm.cancelReservation(reservationId) },
                                    onCancelPendingOrder = { orderId -> vm.cancelPendingOrder(orderId = orderId, environment = environment) },
                                    onCancelAllPendingOrders = { vm.cancelAllPendingOrders(environment = environment, maxCount = 100) },
                                )
                            }
                        }
                        item {
                            AutoTradeCollapsibleCard(
                                title = "체결 내역",
                                summary = buildExecutionSummary(
                                    count = recentExecutions.size,
                                    latestAt = recentExecutions.firstOrNull()?.let { it.filledAt ?: it.requestedAt },
                                ),
                                expanded = showExecutions,
                                onToggle = { showExecutions = !showExecutions },
                            ) {
                                AutoTradeExecutionTimelineCard(
                                    items = recentExecutions,
                                    loading = ordersState.loading,
                                    maxItems = 10,
                                    title = "체결 내역",
                                )
                            }
                        }
                        item {
                            AutoTradeCollapsibleCard(
                                title = "실패/스킵 내역",
                                summary = buildSkippedSummary(
                                    failedOrderCount = failedOrders.size,
                                    skippedOrderCount = skippedOrders.size,
                                    closedReservationCount = closedReservations.size,
                                ),
                                expanded = showSkipped,
                                onToggle = { showSkipped = !showSkipped },
                            ) {
                                AutoTradeSkippedCenterCard(
                                    failedOrders = failedOrders,
                                    skippedOrders = skippedOrders,
                                    closedReservations = closedReservations,
                                    loading = ordersState.loading || reservationsState.loading,
                                )
                            }
                        }
                        item {
                            AutoTradeCollapsibleCard(
                                title = "진입/분할매수 제어",
                                summary = "기본은 보유중 재진입 금지, 필요 종목만 허용하세요",
                                expanded = showEntryControl,
                                onToggle = { showEntryControl = !showEntryControl },
                            ) {
                                AutoTradeEntryControlCard(
                                    holdings = holdings,
                                    recentBuyAttempts = recentBuyAttempts,
                                    symbolRuleMap = symbolRuleMap,
                                    loading = symbolRulesState.loading,
                                    defaultTakeProfitPct = takeProfitPct,
                                    defaultStopLossPct = stopLossPct,
                                    onAllowTicker = { ticker, name ->
                                        val existing = symbolRuleMap[normalizeTicker(ticker)]
                                        vm.saveSymbolRule(
                                            AutoTradeSymbolRuleUpsertDto(
                                                ticker = normalizeTicker(ticker),
                                                name = name,
                                                takeProfitPct = existing?.takeProfitPct ?: takeProfitPct,
                                                stopLossPct = existing?.stopLossPct ?: stopLossPct,
                                                enabled = true,
                                            )
                                        )
                                    },
                                    onBlockTicker = { ticker, name ->
                                        val existing = symbolRuleMap[normalizeTicker(ticker)]
                                        vm.saveSymbolRule(
                                            AutoTradeSymbolRuleUpsertDto(
                                                ticker = normalizeTicker(ticker),
                                                name = name,
                                                takeProfitPct = existing?.takeProfitPct ?: takeProfitPct,
                                                stopLossPct = existing?.stopLossPct ?: stopLossPct,
                                                enabled = false,
                                            )
                                        )
                                    },
                                )
                            }
                        }
                        item {
                            AutoTradeCollapsibleCard(
                                title = "실행 준비 체크리스트",
                                summary = buildReadinessSummary(
                                    enabled = enabled,
                                    environment = environment,
                                    brokerReadyForEnvironment = brokerReadyForEnvironment,
                                    accountSource = accountSnapshot?.source,
                                    takeProfitPct = takeProfitPct,
                                    stopLossPct = stopLossPct,
                                    maxDailyLossPct = maxDailyLossPct,
                                ),
                                expanded = showChecklist,
                                onToggle = { showChecklist = !showChecklist },
                            ) {
                                AutoTradeReadinessChecklistCard(
                                    enabled = enabled,
                                    environment = environment,
                                    brokerReadyForEnvironment = brokerReadyForEnvironment,
                                    accountSource = accountSnapshot?.source,
                                    takeProfitPct = takeProfitPct,
                                    stopLossPct = stopLossPct,
                                    maxDailyLossPct = maxDailyLossPct,
                                    validationMessage = validationMessage,
                                )
                            }
                        }
                        item {
                            AutoTradeCollapsibleCard(
                                title = "알고리즘 선정 기준",
                                summary = "후보 ${candidateItems.size}개 · 소스 $sourceSummaryText",
                                expanded = showAlgorithm,
                                onToggle = { showAlgorithm = !showAlgorithm },
                            ) {
                                CandidatePreviewCard(
                                    count = candidatesState.data?.count ?: 0,
                                    topCandidates = candidateItems.take(3),
                                    sourceBreakdown = sourceBreakdown,
                                    warnings = candidateWarnings,
                                    loading = candidatesState.loading,
                                )
                            }
                        }
                        item {
                            AutoTradeCollapsibleCard(
                                title = "리스크 원칙",
                                summary = "평상시 숨김, 경고 시만 확인",
                                expanded = showRiskGuide,
                                onToggle = { showRiskGuide = !showRiskGuide },
                            ) {
                                AutoTradeRiskGuideCard(
                                    takeProfitPct = takeProfitPct,
                                    stopLossPct = stopLossPct,
                                    maxDailyLossPct = maxDailyLossPct,
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            selectedHoldingDetail?.let { detail ->
                ModalBottomSheet(
                    onDismissRequest = { selectedHoldingDetail = null },
                    sheetState = holdingDetailSheetState,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    containerColor = Color.White,
                ) {
                    AutoTradeHoldingDetailSheet(
                        detail = detail,
                        onClose = { selectedHoldingDetail = null },
                    )
                }
            }
            if (!runAlertTitle.isNullOrBlank() && !runAlertBody.isNullOrBlank()) {
                AlertDialog(
                    onDismissRequest = {
                        runAlertTitle = null
                        runAlertBody = null
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            runAlertTitle = null
                            runAlertBody = null
                        }) {
                            Text("확인")
                        }
                    },
                    title = { Text(runAlertTitle ?: "실행 결과") },
                    text = { Text(runAlertBody ?: "") },
                )
            }
            if (!reservePromptTitle.isNullOrBlank() && !reservePromptBody.isNullOrBlank()) {
                AlertDialog(
                    onDismissRequest = {
                        reservePromptTitle = null
                        reservePromptBody = null
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                reservePromptTitle = null
                                reservePromptBody = null
                                vm.run(dryRun = false, limit = settings?.maxOrdersPerRun, reserveIfClosed = true)
                            },
                        ) {
                            Text("예약 실행")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            reservePromptTitle = null
                            reservePromptBody = null
                        }) {
                            Text("취소")
                        }
                    },
                    title = { Text(reservePromptTitle ?: "예약 주문") },
                    text = { Text(reservePromptBody ?: "") },
                )
            }
        }
    }
}

private fun buildReadinessSummary(
    enabled: Boolean,
    environment: String,
    brokerReadyForEnvironment: Boolean,
    accountSource: String?,
    takeProfitPct: Double,
    stopLossPct: Double,
    maxDailyLossPct: Double,
): String {
    val accountLive = accountSource?.uppercase() == "BROKER_LIVE"
    val brokerReady = brokerReadyForEnvironment
    val snapshotReady = accountLive
    val riskReady = takeProfitPct > 0.0 && stopLossPct > 0.0 && maxDailyLossPct > 0.0
    val okCount = listOf(enabled, brokerReady, snapshotReady, riskReady).count { it }
    return "준비 상태 ${okCount}/4"
}

private fun buildHoldingsSummary(
    count: Int,
    source: String?,
    showCurrentPrice: Boolean,
    environment: String,
): String {
    val sourceLabel = accountSourceShortLabel(source, environment)
    val modeLabel = if (showCurrentPrice) "현재가 보기" else "평가금액 보기"
    return "보유 ${count}개 · $sourceLabel · $modeLabel"
}

private fun buildExecutionSummary(
    count: Int,
    latestAt: String?,
): String {
    val latest = compactTimeLabel(latestAt)
    return if (count > 0) "최근 ${count}건 · 최신 $latest" else "최근 내역 없음"
}

private fun buildPendingSummary(
    pendingOrderCount: Int,
    pendingReservationCount: Int,
    latestAt: String?,
): String {
    val total = pendingOrderCount + pendingReservationCount
    if (total <= 0) return "진행중 주문 없음"
    val latest = compactTimeLabel(latestAt)
    return "미체결 ${pendingOrderCount}건 · 예약 ${pendingReservationCount}건 · 최신 $latest"
}

private fun buildSkippedSummary(
    failedOrderCount: Int,
    skippedOrderCount: Int,
    closedReservationCount: Int,
): String {
    val total = failedOrderCount + skippedOrderCount + closedReservationCount
    if (total <= 0) return "실패/스킵 내역 없음"
    return "거부 ${failedOrderCount}건 · 스킵 ${skippedOrderCount}건 · 예약결과 ${closedReservationCount}건"
}

@Composable
private fun AutoTradeCollapsibleCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CommonFilterCard(
            title = title,
            actions = {
                Text(
                    if (expanded) "접기" else "펼치기",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF1D4ED8),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onToggle() },
                )
            },
        ) {
            Text(summary, style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
private fun AutoTradeRiskGuideCard(
    takeProfitPct: Double,
    stopLossPct: Double,
    maxDailyLossPct: Double,
) {
    CommonFilterCard(title = "리스크 가이드") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("익절: +${"%.1f".format(takeProfitPct)}%", style = MaterialTheme.typography.bodySmall, color = Color(0xFF334155))
            Text("손절: -${"%.1f".format(stopLossPct)}%", style = MaterialTheme.typography.bodySmall, color = Color(0xFF334155))
            Text("일 손실 한도: ${"%.1f".format(maxDailyLossPct)}%", style = MaterialTheme.typography.bodySmall, color = Color(0xFF334155))
            Text("주의: 평상시에는 접어두고, 경고/차단이 뜰 때만 확인하면 됩니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
        }
    }
}

@Composable
private fun AutoTradeReadinessChecklistCard(
    enabled: Boolean,
    environment: String,
    brokerReadyForEnvironment: Boolean,
    accountSource: String?,
    takeProfitPct: Double,
    stopLossPct: Double,
    maxDailyLossPct: Double,
    validationMessage: String?,
) {
    val accountLive = accountSource?.uppercase() == "BROKER_LIVE"
    val brokerReady = brokerReadyForEnvironment
    val snapshotReady = accountLive
    val riskReady = takeProfitPct > 0.0 && stopLossPct > 0.0 && maxDailyLossPct > 0.0
    CommonFilterCard(title = "실행 준비 체크리스트") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AutoTradeChecklistRow(
                title = "자동매매 활성화",
                ok = enabled,
                detail = if (enabled) "설정에서 활성 상태입니다." else "설정 탭에서 활성화를 켜야 실행됩니다.",
            )
            AutoTradeChecklistRow(
                title = "실행환경 계정 준비",
                ok = brokerReady,
                detail = when {
                    environment == "paper" -> "내부 모드는 지원하지 않습니다. 테스트(모의투자) 또는 실전으로 전환하세요."
                    brokerReadyForEnvironment -> "선택 환경의 계정키/계좌 정보가 준비되었습니다."
                    else -> "설정 > 자동매매 계정정보에서 키/계좌를 확인하세요."
                },
            )
            AutoTradeChecklistRow(
                title = "계좌 잔액 동기화",
                ok = snapshotReady,
                detail = when {
                    environment == "paper" -> "내부 모드 금액은 표시하지 않습니다."
                    accountLive -> "증권사 실계좌 잔액을 사용 중입니다."
                    else -> "계좌 연동 실패로 금액이 표시되지 않습니다. 연동 상태를 확인하세요."
                },
            )
            AutoTradeChecklistRow(
                title = "리스크 규칙",
                ok = riskReady,
                detail = "익절 +${"%.1f".format(takeProfitPct)}% / 손절 -${"%.1f".format(stopLossPct)}% / 일손실 ${"%.1f".format(maxDailyLossPct)}%",
            )
            if (!validationMessage.isNullOrBlank()) {
                Text("실행 차단 사유: $validationMessage", color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AutoTradeChecklistRow(
    title: String,
    ok: Boolean,
    detail: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Text(detail, color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = if (ok) "완료" else "확인필요",
            color = if (ok) Color(0xFF166534) else Color(0xFFB45309),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AutoTradeControlCenterCard(
    enabled: Boolean,
    environment: String,
    accountSource: String?,
    brokerReadyForEnvironment: Boolean,
    validationMessage: String?,
    accountMessage: String?,
    environmentNotice: String?,
    holdingsCount: Int,
    executionCount: Int,
) {
    val source = accountSource?.uppercase() ?: "UNAVAILABLE"
    val ready = validationMessage.isNullOrBlank()
    val headerColor = if (ready) Color(0xFF166534) else Color(0xFFB45309)
    val sourceLabel = when (source) {
        "BROKER_LIVE" -> "실계좌 동기화"
        "LOCAL_ESTIMATE" -> "미연동(추정치 미표시)"
        else -> "미연동"
    }
    val envLabel = environmentTitle(environment)
    val actionHint = when {
        !enabled -> "설정에서 자동매매 활성화를 먼저 켜세요."
        !brokerReadyForEnvironment && environment in setOf("demo", "prod") -> "설정 > 자동매매 계정정보에서 모의/실전 키를 확인하세요."
        source != "BROKER_LIVE" && environment in setOf("demo", "prod") -> "계좌 연동 점검 후 새로고침해서 실계좌 금액을 확인하세요."
        else -> "실행 버튼으로 모의 점검 후 실주문을 진행하세요."
    }
    CommonFilterCard(title = "자동매매 컨트롤 센터") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (ready) "현재 상태: 실행 준비 완료" else "현재 상태: 실행 전 점검 필요", color = headerColor, fontWeight = FontWeight.SemiBold)
            Text("환경: $envLabel · 계좌데이터: $sourceLabel", style = MaterialTheme.typography.bodySmall, color = Color(0xFF334155))
            Text("보유 ${holdingsCount}개 · 최근 체결/접수 ${executionCount}건", style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
            if (!validationMessage.isNullOrBlank()) {
                Text("차단 사유: $validationMessage", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB45309))
            }
            if (!accountMessage.isNullOrBlank()) {
                Text("계좌 메시지: $accountMessage", style = MaterialTheme.typography.bodySmall, color = Color(0xFF92400E))
            }
            if (!environmentNotice.isNullOrBlank()) {
                Text("환경 주의: $environmentNotice", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB45309))
            }
            Text("다음 액션: $actionHint", style = MaterialTheme.typography.bodySmall, color = Color(0xFF0F172A))
        }
    }
}

@Composable
private fun AutoTradeExecutiveSummaryCard(
    environment: String,
    enabled: Boolean,
    brokerLiveAccount: Boolean,
    candidateCount: Int,
    holdingsCount: Int,
    executionCount: Int,
    roiPct: Double,
    unrealizedPnlKrw: Double,
    cashKrw: Double,
    totalAssetKrw: Double,
) {
    val envLabel = environmentTitle(environment)
    val runModeLabel = when {
        environment == "prod" && brokerLiveAccount -> "실전 운영"
        environment == "demo" && brokerLiveAccount -> "모의투자 운영"
        environment == "demo" -> "모의투자 연동 대기"
        environment == "prod" -> "실전 연동 대기"
        else -> "테스트 모드"
    }
    val roiColor = if (roiPct >= 0.0) Color(0xFF166534) else Color(0xFFB91C1C)
    CommonFilterCard(title = "한눈에 보는 운영 요약") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$envLabel · $runModeLabel · 자동매매 ${if (enabled) "활성" else "비활성"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoTradeExecutiveMetric(label = "보유 종목", value = "${holdingsCount}개")
                AutoTradeExecutiveMetric(label = "체결 건수", value = "${executionCount}건")
                AutoTradeExecutiveMetric(label = "후보 종목", value = "${candidateCount}개")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoTradeExecutiveMetric(label = "수익률", value = "${"%.2f".format(roiPct)}%", accent = roiColor)
                AutoTradeExecutiveMetric(label = "미실현 손익", value = formatWon(unrealizedPnlKrw), accent = roiColor)
                AutoTradeExecutiveMetric(label = "가용 현금", value = formatWon(cashKrw))
            }
            Text(
                "총자산 ${formatWon(totalAssetKrw)}",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AutoTradeExecutiveMetric(
    label: String,
    value: String,
    accent: Color = Color(0xFF0F172A),
) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        modifier = Modifier.weight(1f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AutoTradeEnvironmentSwitchCard(
    environment: String,
    accountSnapshot: AutoTradeAccountSnapshotResponseDto?,
    brokerInfo: AutoTradeBrokerCredentialDto?,
    loading: Boolean,
    onSelectEnvironment: (String) -> Unit,
) {
    val accountSource = accountSnapshot?.source?.uppercase().orEmpty()
    val sourceLabel = when (accountSource) {
        "BROKER_LIVE" -> "실계좌 연동"
        "LOCAL_ESTIMATE" -> "미연동(추정치 미표시)"
        else -> "미연동"
    }
    val accountLabel = when (environment) {
        "demo" -> "${brokerInfo?.maskedDemoAccountNo ?: "-"}-${brokerInfo?.accountProductCodeDemo ?: "01"}"
        "prod" -> "${brokerInfo?.maskedProdAccountNo ?: "-"}-${brokerInfo?.accountProductCodeProd ?: "01"}"
        else -> "테스트(모의투자)로 전환 필요"
    }
    CommonFilterCard(title = "실행환경 선택") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CommonPillChip(
                    text = "테스트(모의투자)",
                    selected = environment == "demo",
                    onClick = { if (environment != "demo" && !loading) onSelectEnvironment("demo") },
                )
                CommonPillChip(
                    text = "실전",
                    selected = environment == "prod",
                    onClick = { if (environment != "prod" && !loading) onSelectEnvironment("prod") },
                )
            }
            Text(
                "현재: ${environmentTitle(environment)} · 계좌: $accountLabel · 데이터: $sourceLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )
            if (environment == "paper") {
                Text(
                    "내부 모의는 사용하지 않습니다. 테스트(모의투자) 또는 실전으로 전환하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB45309),
                )
            }
            if (loading) {
                Text("실행환경 저장 중...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
        }
    }
}

@Composable
private fun AutoTradeActiveSymbolsCard(
    holdings: List<AutoTradeHoldingUi>,
    recentBuyAttempts: List<AutoTradeOrderItemDto>,
    loading: Boolean,
) {
    val holdingCards = toHoldingItems(holdings)
    val buyAttemptCards = toOrderItems(recentBuyAttempts)
    CommonFilterCard(title = "지금 어떤 종목을 거래 중인가") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (loading && holdings.isEmpty() && recentBuyAttempts.isEmpty()) {
                Text("거래 종목을 확인 중입니다...", color = Color(0xFF64748B))
                return@Column
            }

            if (holdings.isNotEmpty()) {
                Text("보유 중 (${holdings.size})", style = MaterialTheme.typography.bodySmall, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                holdingCards.forEach { card ->
                    AutoTradeTickerCard(item = card, origin = "자동매매_보유중")
                }
                AutoTradeHoldingRoiSection(holdings = holdings)
            } else {
                Text("보유 중인 포지션이 없습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }

            if (recentBuyAttempts.isNotEmpty()) {
                Text("최근 진입/분할매수 요청 (${recentBuyAttempts.size})", style = MaterialTheme.typography.bodySmall, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                buyAttemptCards.forEach { card ->
                    AutoTradeTickerCard(item = card, origin = "자동매매_진입요청")
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AutoTradeEntryControlCard(
    holdings: List<AutoTradeHoldingUi>,
    recentBuyAttempts: List<AutoTradeOrderItemDto>,
    symbolRuleMap: Map<String, AutoTradeSymbolRuleItemDto>,
    loading: Boolean,
    defaultTakeProfitPct: Double,
    defaultStopLossPct: Double,
    onAllowTicker: (ticker: String, name: String?) -> Unit,
    onBlockTicker: (ticker: String, name: String?) -> Unit,
) {
    val holdingsCards = toHoldingItems(holdings)
    val attemptCards = toOrderItems(recentBuyAttempts)
    CommonFilterCard(title = "진입/분할매수 제어") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "기준가: 실시간 현재가 우선(없으면 신호가). 상태를 '차단'으로 두면 다음 자동 실행에서 해당 종목 진입 요청을 건너뜁니다.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF475569),
            )
            Text(
                "기본 익절/손절: +${"%.1f".format(defaultTakeProfitPct)}% / -${"%.1f".format(defaultStopLossPct)}%",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            if (loading && holdings.isEmpty() && recentBuyAttempts.isEmpty()) {
                Text("제어 목록을 불러오는 중...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                return@Column
            }
            if (holdings.isEmpty() && recentBuyAttempts.isEmpty()) {
                Text("제어 가능한 종목이 없습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                return@Column
            }
            Text("보유중 섹션 (${holdings.size})", style = MaterialTheme.typography.bodySmall, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
            if (holdings.isEmpty()) {
                Text("현재 보유 종목이 없습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            } else {
                holdings.forEachIndexed { index, holding ->
                    val ticker = normalizeTicker(holding.ticker)
                    val name = holding.name
                    val rule = symbolRuleMap[ticker]
                    val blocked = rule?.enabled == false
                    val statusText = when {
                        blocked -> "추가진입 차단"
                        rule != null -> "추가진입 허용(등록)"
                        else -> "기본 정책(보유중 재진입 금지 권장)"
                    }
                    AutoTradeTickerCard(item = holdingsCards[index], origin = "자동매매_보유제어")
                    Text("상태: $statusText", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AutoTradeCompactActionChip(text = "분할매수 허용", selected = !blocked, onClick = { onAllowTicker(ticker, name) })
                        AutoTradeCompactActionChip(text = "분할매수 차단", selected = blocked, onClick = { onBlockTicker(ticker, name) })
                    }
                }
            }

            Text("진입 요청 섹션 (${recentBuyAttempts.size})", style = MaterialTheme.typography.bodySmall, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
            if (recentBuyAttempts.isEmpty()) {
                Text("최근 진입 요청 종목이 없습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            } else {
                recentBuyAttempts.forEachIndexed { index, attempt ->
                    val ticker = normalizeTicker(attempt.ticker)
                    val name = attempt.name
                    val rule = symbolRuleMap[ticker]
                    val blocked = rule?.enabled == false
                    val statusText = when {
                        blocked -> "초기진입 차단"
                        rule != null -> "초기진입 허용(등록)"
                        else -> "기본 허용"
                    }
                    AutoTradeTickerCard(item = attemptCards[index], origin = "자동매매_진입제어")
                    Text("상태: $statusText", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AutoTradeCompactActionChip(text = "초기진입 허용", selected = !blocked, onClick = { onAllowTicker(ticker, name) })
                        AutoTradeCompactActionChip(text = "초기진입 차단", selected = blocked, onClick = { onBlockTicker(ticker, name) })
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AutoTradeViewModeCard(
    selected: AutoTradeViewMode,
    onSelect: (AutoTradeViewMode) -> Unit,
) {
    CommonFilterCard(title = "보기 모드") {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AutoTradeViewMode.entries.forEach { mode ->
                CommonPillChip(
                    text = mode.label,
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@Composable
private fun AutoTradeConfigSnapshotCard(
    settingsLoaded: Boolean,
    enabled: Boolean,
    environment: String,
    settings: AutoTradeSettingsDto?,
    broker: AutoTradeBrokerCredentialDto?,
    onOpenSettings: () -> Unit,
) {
    CommonFilterCard(title = "자동매매 설정 상태") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!settingsLoaded) {
                Text("설정을 불러오는 중입니다.", color = Color(0xFF64748B))
            } else {
                Text("활성화: ${if (enabled) "활성" else "비활성"}", fontWeight = FontWeight.SemiBold)
                Text("실행환경: ${environmentTitle(environment)}", color = Color(0xFF334155))
                Text(
                    "소스: 단타 ${if (settings?.includeDaytrade == true) "활성" else "비활성"} · 급등 ${if (settings?.includeMovers == true) "활성" else "비활성"} · 수급 ${if (settings?.includeSupply == true) "활성" else "비활성"} · 논문 ${if (settings?.includePapers == true) "활성" else "비활성"} · 장투 ${if (settings?.includeLongterm == true) "활성" else "비활성"} · 관심 ${if (settings?.includeFavorites == true) "활성" else "비활성"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569),
                )
                Text(
                    "예산/주문/손실: ${"%,.0f".format(settings?.orderBudgetKrw ?: 0.0)}원 / ${settings?.maxOrdersPerRun ?: 0}건 / ${"%.2f".format(settings?.maxDailyLossPct ?: 0.0)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569),
                )
                Text(
                    "시드/익절/손절: ${"%,.0f".format(settings?.seedKrw ?: 0.0)}원 / +${"%.2f".format(settings?.takeProfitPct ?: 0.0)}% / -${"%.2f".format(settings?.stopLossPct ?: 0.0)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569),
                )
            }
            Text(
                "증권사 소스: ${if (broker?.source == "USER") "사용자 계정" else "서버 공용 계정"} · 실주문엔진 ${if (broker?.kisTradingEnabled == true) "활성" else "비활성"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF334155),
            )
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("설정 탭에서 자동매매 설정 수정")
            }
        }
    }
}

@Composable
private fun AutoTradeExecutionCard(
    environment: String,
    dryRunEnabled: Boolean,
    runEnabled: Boolean,
    runLoading: Boolean,
    validationMessage: String?,
    offhoursReservationEnabled: Boolean,
    offhoursReservationMode: String,
    offhoursConfirmTimeoutMin: Int,
    offhoursConfirmTimeoutAction: String,
    prodConfirmChecked: Boolean,
    onProdConfirmChecked: (Boolean) -> Unit,
    onDryRun: () -> Unit,
    onRun: () -> Unit,
    runButtonText: String,
) {
    val reservationModeLabel = if (offhoursReservationMode.equals("confirm", ignoreCase = true)) "확인 후 실행" else "자동 실행"
    val timeoutActionLabel = if (offhoursConfirmTimeoutAction.equals("auto", ignoreCase = true)) "시간초과 시 자동실행" else "시간초과 시 취소"
    CommonFilterCard(title = "자동매매 실행") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!validationMessage.isNullOrBlank()) {
                Text(validationMessage, style = MaterialTheme.typography.bodySmall, color = Color(0xFFB45309))
            } else {
                Text("실행 준비 완료 · 주문 전송은 실행 버튼에서만 동작합니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF166534))
            }
            Text(
                if (offhoursReservationEnabled) {
                    "장외 예약: 사용 · $reservationModeLabel · ${offhoursConfirmTimeoutMin}분 · $timeoutActionLabel"
                } else {
                    "장외 예약: 미사용(장시간 외 실행 차단)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (offhoursReservationEnabled) Color(0xFF334155) else Color(0xFFB45309),
            )
            if (environment == "prod") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Checkbox(
                        checked = prodConfirmChecked,
                        onCheckedChange = onProdConfirmChecked,
                    )
                    Text(
                        "실전 주문 위험을 이해했고, 실제 주문 실행에 동의합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7F1D1D),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDryRun, enabled = dryRunEnabled, modifier = Modifier.weight(1f)) { Text("전략 점검") }
                Button(onClick = onRun, enabled = runEnabled, modifier = Modifier.weight(1f)) {
                    Text(if (runLoading) "실행 중..." else runButtonText)
                }
            }
            Text(
                "전략 점검은 계산만 수행하며 주문을 전송하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
        }
    }
}

@Composable
private fun AutoTradeRunResultCard(
    runResult: AutoTradeRunResponseDto?,
    loading: Boolean,
    error: String?,
) {
    CommonFilterCard(title = "최근 실행 결과") {
        if (loading) {
            Text("실행 요청 처리 중...", color = Color(0xFF475569))
            return@CommonFilterCard
        }
        if (!error.isNullOrBlank()) {
            Text(error, color = Color(0xFFB91C1C))
            return@CommonFilterCard
        }
        if (runResult == null) {
            Text("아직 실행 기록이 없습니다.", color = Color(0xFF6B7280))
            return@CommonFilterCard
        }
        Text(runResult.message ?: "실행 완료", fontWeight = FontWeight.SemiBold)
        val isDryRun = runResult.message?.trim()?.uppercase()?.startsWith("DRY_RUN") == true
        if (isDryRun) {
            Text("최근 요청은 모의 점검입니다. 실제 주문은 제출되지 않았습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF92400E))
        }
        Text("실행 ID: ${runResult.runId ?: "-"}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
        Text(
            "요청 ${runResult.requestedCount ?: 0} | 접수 ${runResult.submittedCount ?: 0} | 체결 ${runResult.filledCount ?: 0} | 스킵 ${runResult.skippedCount ?: 0}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF334155),
        )
    }
}

@Composable
private fun AutoTradeSummaryCard(
    summary: AutoTradePerformanceItemDto?,
    summarySource: String,
    statusBreakdown: Map<String, Int>,
) {
    CommonFilterCard(title = "수익 지표") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (summary == null) {
                Text("집계 데이터 없음", color = Color(0xFF6B7280))
            } else {
                Text("집계 출처: $summarySource", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                Text("수익률: ${"%.2f".format(summary.roiPct ?: 0.0)}%")
                Text("승률: ${"%.2f".format((summary.winRate ?: 0.0) * 100.0)}%")
                Text("미실현 손익: ${"%,.0f".format(summary.unrealizedPnlKrw ?: 0.0)} 원")
                Text("최대낙폭: ${"%.2f".format(summary.mddPct ?: 0.0)}%")
                Text("주문/체결: ${(summary.ordersTotal ?: 0)} / ${(summary.filledTotal ?: 0)}")
            }
            if (statusBreakdown.isNotEmpty()) {
                Text(
                    "상태 분포: " + statusBreakdown.entries.joinToString(" | ") { "${it.key}:${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569),
                )
            }
        }
    }
}

@Composable
private fun AutoTradeAccountSnapshotCard(
    snapshot: AutoTradeAccountSnapshotResponseDto?,
    loading: Boolean,
    error: String?,
    environment: String,
) {
    CommonFilterCard(title = "계좌 현황(실계좌 기준)") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val source = snapshot?.source ?: "UNAVAILABLE"
            val env = snapshot?.environment?.trim()?.lowercase() ?: environment
            val envLabel = environmentTitle(env)
            val sourceUpper = source.uppercase()
            val liveSource = sourceUpper == "BROKER_LIVE"
            val showAmounts = liveSource
            val sourceLabel = accountSourceLabel(source, env)
            val accountMasked = snapshot?.accountNoMasked

            if (loading && snapshot == null) {
                Text("계좌 스냅샷 동기화 중...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
            Text("환경: $envLabel", style = MaterialTheme.typography.bodySmall, color = Color(0xFF334155))
            if (!accountMasked.isNullOrBlank()) {
                Text("계좌: $accountMasked", style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
            }
            Text(
                "데이터 출처: $sourceLabel",
                style = MaterialTheme.typography.bodySmall,
                color = if (showAmounts) Color(0xFF166534) else Color(0xFFB45309),
                fontWeight = FontWeight.SemiBold,
            )
            if (!snapshot?.updatedAt.isNullOrBlank()) {
                Text("동기화 시각: ${compactTimeLabel(snapshot?.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }

            if (!showAmounts) {
                Text(
                    "계좌 연동 전에는 금액을 표시하지 않습니다. 연동 후 다시 확인하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF92400E),
                )
                val accountMessage = friendlyAccountMessage(snapshot?.message)
                if (!accountMessage.isNullOrBlank()) {
                    Text("연동 메시지: $accountMessage", style = MaterialTheme.typography.bodySmall, color = Color(0xFF92400E))
                } else if (!error.isNullOrBlank()) {
                    Text("연동 오류: $error", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB91C1C))
                }
                return@Column
            }

            if (!liveSource && sourceUpper == "LOCAL_ESTIMATE") {
                Text(
                    "추정치는 사용자 화면에서 표시하지 않습니다. 계좌 연동 후 다시 확인하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB45309),
                )
            }
            Text("주문가능현금: ${formatWonExact(snapshot?.orderableCashKrw ?: snapshot?.cashKrw)}", fontWeight = FontWeight.SemiBold)
            Text("주식평가금액: ${formatWonExact(snapshot?.stockEvalKrw)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
            Text("총자산: ${formatWonExact(snapshot?.totalAssetKrw)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF475569))
            Text(
                "손익(실현/미실현): ${formatWonExact(snapshot?.realizedPnlKrw)} / ${formatWonExact(snapshot?.unrealizedPnlKrw)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF475569),
            )
        }
    }
}

@Composable
private fun AutoTradeHoldingsCard(
    holdings: List<AutoTradeHoldingUi>,
    source: String,
    loading: Boolean,
    maxItems: Int = Int.MAX_VALUE,
    title: String = "현재 보유 포지션",
    showCurrentPrice: Boolean,
    quoteMap: Map<String, RealtimeQuoteItemDto>,
    onToggleCurrentPrice: (Boolean) -> Unit,
    onSelectHolding: (AutoTradeHoldingDetailUi) -> Unit,
    environment: String,
) {
    CommonFilterCard(title = title) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val sourceUpper = source.uppercase()
            val sourceText = when {
                sourceUpper == "BROKER_LIVE" -> "실계좌 보유"
                sourceUpper == "LOCAL_ESTIMATE" -> "추정치 미표시"
                else -> "연동 필요"
            }
            Text("데이터 출처: $sourceText", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("내 주식", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                Row(
                    modifier = Modifier
                        .background(Color(0xFFF3F4F6), RoundedCornerShape(999.dp))
                        .clickable { onToggleCurrentPrice(!showCurrentPrice) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        color = if (showCurrentPrice) Color(0xFF2F7BF6) else Color(0xFFE5E7EB),
                        shape = CircleShape,
                        modifier = Modifier.size(16.dp),
                    ) {
                        if (showCurrentPrice) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.padding(2.dp),
                            )
                        }
                    }
                    Text("현재가", style = MaterialTheme.typography.labelLarge, color = Color(0xFF6B7280))
                }
            }
            Text("국내", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF374151))
            if (loading && holdings.isEmpty()) {
                Text("보유 종목 동기화 중...", color = Color(0xFF64748B))
                return@Column
            }
            if (holdings.isEmpty()) {
                Text("보유 종목이 없습니다.", color = Color(0xFF6B7280))
                return@Column
            }
            holdings.take(maxItems).forEach { row ->
                val detail = toHoldingDetailUi(row, quoteMap[row.ticker])
                AutoTradeHoldingListRow(
                    detail = detail,
                    showCurrentPrice = showCurrentPrice,
                    onClick = { onSelectHolding(detail) },
                )
            }
            if (holdings.size > maxItems) {
                Text("외 ${holdings.size - maxItems}개 포지션", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
        }
    }
}

private fun toHoldingDetailUi(
    holding: AutoTradeHoldingUi,
    quote: RealtimeQuoteItemDto?,
): AutoTradeHoldingDetailUi {
    val current = quote?.price?.takeIf { it > 0.0 } ?: holding.currentPrice
    val invested = holding.avgPrice * holding.qty.toDouble()
    val eval = current * holding.qty.toDouble()
    val pnl = eval - invested
    val pnlPct = if (invested > 0.0) (pnl / invested) * 100.0 else 0.0
    return AutoTradeHoldingDetailUi(
        ticker = holding.ticker,
        name = holding.name,
        qty = holding.qty,
        avgPrice = holding.avgPrice,
        currentPrice = current,
        evalAmount = eval,
        investedAmount = invested,
        pnlAmount = pnl,
        pnlPct = pnlPct,
        dayChangePct = quote?.chgPct,
    )
}

@Composable
private fun AutoTradeHoldingListRow(
    detail: AutoTradeHoldingDetailUi,
    showCurrentPrice: Boolean,
    onClick: () -> Unit,
) {
    val metricColor = if (showCurrentPrice) movementColor(detail.dayChangePct ?: 0.0) else movementColor(detail.pnlAmount)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoTradeTickerLogo(ticker = detail.ticker)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        detail.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF1F2937),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${detail.qty}주", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9CA3AF))
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (showCurrentPrice) formatWonExact(detail.currentPrice) else formatWonExact(detail.evalAmount),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF374151),
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
                Text(
                    text = if (showCurrentPrice) {
                        detail.dayChangePct?.let { "어제보다 ${formatSignedPctTight(it)}" } ?: "어제보다 --"
                    } else {
                        "${formatSignedWon(detail.pnlAmount)} (${formatSignedPctTight(detail.pnlPct)})"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = metricColor,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AutoTradeTickerLogo(
    ticker: String,
) {
    val logoUrl = remember(ticker) {
        if (ticker.length == 6 && ticker.all(Char::isDigit)) {
            "https://ssl.pstatic.net/imgstock/fn/real/logo/png/stock/Stock$ticker.png"
        } else {
            null
        }
    }
    val logoBitmap = rememberRemoteLogoBitmapForAuto(logoUrl)
    if (logoBitmap != null) {
        Image(
            bitmap = logoBitmap,
            contentDescription = "종목 로고",
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE8EEF9))
                .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(ticker.takeLast(2), style = MaterialTheme.typography.labelSmall, color = Color(0xFF4B5563), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun rememberRemoteLogoBitmapForAuto(url: String?): ImageBitmap? {
    val safeUrl = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val state by produceState<ImageBitmap?>(initialValue = null, key1 = safeUrl) {
        value = null
        if (safeUrl.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                URL(safeUrl).openStream().use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return state
}

@Composable
private fun AutoTradeHoldingDetailSheet(
    detail: AutoTradeHoldingDetailUi,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val dayColor = movementColor(detail.dayChangePct ?: 0.0)
    val pnlColor = movementColor(detail.pnlAmount)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(52.dp)
                .height(4.dp)
                .background(Color(0xFFE5E7EB), RoundedCornerShape(999.dp)),
        )
        Text(detail.name, style = MaterialTheme.typography.titleLarge, color = Color(0xFF111827), fontWeight = FontWeight.ExtraBold)
        Text("${detail.qty}주", style = MaterialTheme.typography.titleMedium, color = Color(0xFF6B7280), fontWeight = FontWeight.Medium)
        Text("자동매매 계좌 ${detail.qty}주", style = MaterialTheme.typography.titleMedium, color = Color(0xFF6B7280), fontWeight = FontWeight.Medium)

        Spacer(Modifier.height(6.dp))
        AutoTradeHoldingDetailRow(label = "총 투자한 금액", value = formatWonExact(detail.investedAmount))
        AutoTradeHoldingDetailRow(label = "총 현재 손익", value = formatSignedWon(detail.pnlAmount), valueColor = pnlColor)
        AutoTradeHoldingDetailRow(label = "총 평가 금액", value = formatWonExact(detail.evalAmount))
        HorizontalDivider(thickness = 1.dp, color = Color(0xFFE5E7EB))
        AutoTradeHoldingDetailRow(label = "내 매수 평균가격", value = formatWonExact(detail.avgPrice))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("현재가격", style = MaterialTheme.typography.titleMedium, color = Color(0xFF374151), fontWeight = FontWeight.Medium)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(formatWonExact(detail.currentPrice), style = MaterialTheme.typography.titleMedium, color = dayColor, fontWeight = FontWeight.Bold)
                Text(
                    detail.dayChangePct?.let { "어제보다 ${formatSignedPctTight(it)}" } ?: "어제보다 --",
                    style = MaterialTheme.typography.titleMedium,
                    color = dayColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF0F1F5), contentColor = Color(0xFF6B7280)),
            ) { Text("닫기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            Button(
                onClick = {
                    StockDetailActivity.open(
                        context = context,
                        ticker = detail.ticker,
                        name = detail.name,
                        origin = "자동매매_보유상세",
                        eventTags = emptyList(),
                    )
                },
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F7BF6), contentColor = Color.White),
            ) { Text("차트보기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun AutoTradeHoldingDetailRow(
    label: String,
    value: String,
    valueColor: Color = Color(0xFF4B5563),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color(0xFF374151), fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AutoTradeExecutionTimelineCard(
    items: List<AutoTradeOrderItemDto>,
    loading: Boolean,
    maxItems: Int = Int.MAX_VALUE,
    title: String = "최근 체결/접수",
) {
    val cardItems = toOrderItems(items.take(maxItems))
    CommonFilterCard(title = title) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (loading && items.isEmpty()) {
                Text("체결 이력 갱신 중...", color = Color(0xFF64748B))
                return@Column
            }
            if (items.isEmpty()) {
                Text("최근 체결/접수 기록이 없습니다.", color = Color(0xFF6B7280))
                return@Column
            }
            cardItems.forEach { card ->
                AutoTradeTickerCard(item = card, origin = "자동매매_최근체결")
            }
            if (items.size > maxItems) {
                Text("외 ${items.size - maxItems}건", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
        }
    }
}

@Composable
private fun AutoTradePendingCenterCard(
    pendingOrders: List<AutoTradeOrderItemDto>,
    pendingReservations: List<AutoTradeReservationItemDto>,
    environment: String,
    loading: Boolean,
    actionLoading: Boolean,
    onConfirmReservation: (Int) -> Unit,
    onCancelReservation: (Int) -> Unit,
    onCancelPendingOrder: (Int) -> Unit,
    onCancelAllPendingOrders: () -> Unit,
) {
    val orderCards = toOrderItems(pendingOrders)
    val cancelableOrderCount = pendingOrders.count { (it.id ?: 0) > 0 }
    CommonFilterCard(title = "진행중/미체결 주문") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading && pendingOrders.isEmpty() && pendingReservations.isEmpty()) {
                Text("진행 상태를 불러오는 중...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                return@Column
            }
            if (pendingOrders.isEmpty() && pendingReservations.isEmpty()) {
                Text("진행중 주문이 없습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
                return@Column
            }

            if (pendingOrders.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "체결 대기 주문 ${pendingOrders.size}건",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (cancelableOrderCount > 1) {
                        TextButton(
                            onClick = onCancelAllPendingOrders,
                            enabled = !actionLoading,
                        ) { Text("전체 접수 취소") }
                    }
                }
                pendingOrders.zip(orderCards).forEach { (order, card) ->
                    AutoTradeTickerCard(item = card, origin = "자동매매_미체결주문")
                    val orderId = order.id ?: 0
                    if (orderId > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { onCancelPendingOrder(orderId) },
                                enabled = !actionLoading,
                            ) { Text("접수 취소") }
                        }
                    }
                }
            }

            if (pendingReservations.isNotEmpty()) {
                Text(
                    "예약 주문 (${if (environment == "prod") "실전" else "모의"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.SemiBold,
                )
                pendingReservations.forEach { reservation ->
                    AutoTradeReservationStatusCard(
                        reservation = reservation,
                        showActions = true,
                        actionLoading = actionLoading,
                        onConfirmReservation = onConfirmReservation,
                        onCancelReservation = onCancelReservation,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoTradeSkippedCenterCard(
    failedOrders: List<AutoTradeOrderItemDto>,
    skippedOrders: List<AutoTradeOrderItemDto>,
    closedReservations: List<AutoTradeReservationItemDto>,
    loading: Boolean,
) {
    val failedCards = toOrderItems(failedOrders)
    val skippedCards = toOrderItems(skippedOrders)
    CommonFilterCard(title = "실패/스킵 상세") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading && failedOrders.isEmpty() && skippedOrders.isEmpty() && closedReservations.isEmpty()) {
                Text("실패/스킵 내역을 불러오는 중...", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
                return@Column
            }
            if (failedOrders.isEmpty() && skippedOrders.isEmpty() && closedReservations.isEmpty()) {
                Text("실패/스킵 내역이 없습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
                return@Column
            }

            if (failedOrders.isNotEmpty()) {
                Text("주문 실패", style = MaterialTheme.typography.bodySmall, color = Color(0xFF7F1D1D), fontWeight = FontWeight.SemiBold)
                failedCards.forEach { card ->
                    AutoTradeTickerCard(item = card, origin = "자동매매_주문실패")
                }
            }

            if (skippedOrders.isNotEmpty()) {
                Text("주문 스킵", style = MaterialTheme.typography.bodySmall, color = Color(0xFF92400E), fontWeight = FontWeight.SemiBold)
                skippedCards.forEach { card ->
                    AutoTradeTickerCard(item = card, origin = "자동매매_주문스킵")
                }
            }

            if (closedReservations.isNotEmpty()) {
                Text("예약 처리 결과", style = MaterialTheme.typography.bodySmall, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                closedReservations.forEach { reservation ->
                    AutoTradeReservationStatusCard(
                        reservation = reservation,
                        showActions = false,
                        actionLoading = false,
                        onConfirmReservation = {},
                        onCancelReservation = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoTradeReservationStatusCard(
    reservation: AutoTradeReservationItemDto,
    showActions: Boolean,
    actionLoading: Boolean,
    onConfirmReservation: (Int) -> Unit,
    onCancelReservation: (Int) -> Unit,
) {
    val reservationId = reservation.id ?: 0
    val statusLabel = reservationStatusLabel(reservation.status)
    val statusColor = when (reservation.status?.uppercase()) {
        "QUEUED", "WAIT_CONFIRM", "RUNNING" -> Color(0xFF1D4ED8)
        "DONE" -> Color(0xFF166534)
        "PARTIAL" -> Color(0xFFB45309)
        else -> Color(0xFF7F1D1D)
    }
    val previewItems = reservation.previewItems.orEmpty()
    val previewCards = toReservationPreviewItems(previewItems)
    val previewCount = reservation.previewCount ?: previewItems.size
    val result = reservation.resultSummary
    val canConfirm = showActions && reservation.status?.uppercase() == "WAIT_CONFIRM" && reservationId > 0
    val canCancel = showActions && reservation.status?.uppercase() in setOf("QUEUED", "WAIT_CONFIRM", "RUNNING") && reservationId > 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "예약 #$reservationId · $statusLabel",
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "요청 ${compactTimeLabel(reservation.requestedAt)} · 실행예정 ${compactTimeLabel(reservation.executeAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            if (!reservation.reasonMessage.isNullOrBlank()) {
                Text(
                    reservation.reasonMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569),
                )
            }
            if (previewCount > 0) {
                Text(
                    "예약 대상 ${previewCount}건",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF334155),
                    fontWeight = FontWeight.SemiBold,
                )
                previewCards.forEach { item ->
                    AutoTradeTickerCard(item = item, origin = "자동매매_예약대상")
                }
                if (previewCount > previewCards.size) {
                    Text(
                        "외 ${previewCount - previewCards.size}개",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF64748B),
                    )
                }
            }
            if (result != null) {
                Text(
                    "결과 요청 ${result.requestedCount ?: 0} · 접수 ${result.submittedCount ?: 0} · 체결 ${result.filledCount ?: 0} · 스킵 ${result.skippedCount ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF334155),
                )
            }
            if (showActions && (canConfirm || canCancel)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canConfirm) {
                        Button(
                            onClick = { onConfirmReservation(reservationId) },
                            enabled = !actionLoading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("지금 실행")
                        }
                    }
                    if (canCancel) {
                        Button(
                            onClick = { onCancelReservation(reservationId) },
                            enabled = !actionLoading,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFE2E8F0),
                                contentColor = Color(0xFF1E293B),
                            ),
                        ) {
                            Text("예약 취소")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidatePreviewCard(
    count: Int,
    topCandidates: List<AutoTradeCandidateItemDto>,
    sourceBreakdown: Map<String, Int>,
    warnings: List<String>,
    loading: Boolean,
) {
    CommonFilterCard(title = "후보 요약") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("현재 후보 수: $count" + if (loading) " (갱신 중)" else "")
            if (sourceBreakdown.isNotEmpty()) {
                Text(
                    "소스 분포: " + sourceBreakdown.entries.joinToString(" | ") { "${it.key}:${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569),
                )
            }
            if (topCandidates.isNotEmpty()) {
                Text("상위 후보", style = MaterialTheme.typography.bodySmall, color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                toCandidateItems(topCandidates).forEach { card ->
                    AutoTradeTickerCard(item = card, origin = "자동매매_후보요약")
                }
            }
            if (warnings.isNotEmpty()) {
                Text(
                    "진단: ${warnings.joinToString(" | ") { candidateWarningLabel(it) }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF92400E),
                )
            }
        }
    }
}

@Composable
private fun AutoTradeCandidateListCard(
    items: List<AutoTradeCandidateItemDto>,
    loading: Boolean,
    maxItems: Int = Int.MAX_VALUE,
) {
    CommonFilterCard(title = "후보 상세 (무엇을 살지)") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (loading && items.isEmpty()) {
                Text("후보 종목을 계산 중입니다...", color = Color(0xFF64748B))
                return@Column
            }
            if (items.isEmpty()) {
                Text("표시할 후보가 없습니다. 설정의 소스 선택과 계좌 연동 상태를 확인하세요.", color = Color(0xFF6B7280))
                return@Column
            }
            toCandidateItems(items.take(maxItems)).forEach { card ->
                AutoTradeTickerCard(item = card, origin = "자동매매_후보상세")
            }
            if (items.size > maxItems) {
                Text("외 ${items.size - maxItems}개 후보", style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
        }
    }
}

@Composable
private fun AutoTradeTickerCard(
    item: CommonReportItemUi,
    origin: String,
) {
    val context = LocalContext.current
    val ticker = item.ticker.orEmpty()
    val onClick = if (ticker.isBlank()) {
        null
    } else {
        {
            StockDetailActivity.open(
                context = context,
                ticker = ticker,
                name = item.name ?: item.title,
                origin = origin,
                eventTags = item.eventTags,
            )
        }
    }
    CommonReportItemCard(item = item, onClick = onClick)
}

@Composable
private fun AutoTradeCompactActionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Color(0xFF1E3A8A) else Color(0xFFE2E8F0)
    val fg = if (selected) Color.White else Color(0xFF1E293B)
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = bg),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            text = text,
            color = fg,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun AutoTradeHoldingRoiSection(
    holdings: List<AutoTradeHoldingUi>,
) {
    if (holdings.isEmpty()) return
    val avgPnl = holdings.map { it.pnlPct }.average()
    val winRate = holdings.count { it.pnlPct > 0.0 }.toDouble() / holdings.size.toDouble() * 100.0
    val maxProfit = holdings.maxOfOrNull { it.pnlPct } ?: 0.0
    val maxLoss = holdings.minOfOrNull { it.pnlPct } ?: 0.0
    val avgPrice = holdings.map { it.avgPrice }.average()
    val currentPrice = holdings.map { it.currentPrice }.average()
    val totalEval = holdings.sumOf { it.evalAmount }
    val totalCost = holdings.sumOf { it.avgPrice * it.qty.toDouble() }

    CommonFilterCard(title = "수익률 현황") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AutoTradeRoiMetricCell(label = "평균 수익률", value = "${"%.2f".format(avgPnl)}%")
                AutoTradeRoiMetricCell(label = "승률", value = "${"%.1f".format(winRate)}%")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AutoTradeRoiMetricCell(label = "최대수익", value = "${"%.2f".format(maxProfit)}%")
                AutoTradeRoiMetricCell(label = "최대손실", value = "${"%.2f".format(maxLoss)}%")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AutoTradeRoiMetricCell(label = "평균 매수가", value = formatWon(avgPrice))
                AutoTradeRoiMetricCell(label = "평균 현재가", value = formatWon(currentPrice))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AutoTradeRoiMetricCell(label = "총 매입금액", value = formatWon(totalCost))
                AutoTradeRoiMetricCell(label = "총 평가금액", value = formatWon(totalEval))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AutoTradeRoiMetricCell(
    label: String,
    value: String,
) {
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        modifier = Modifier.weight(1f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
        }
    }
}

private fun candidateWarningLabel(raw: String): String {
    val key = raw.trim()
    return when {
        key == "PREMARKET_REPORT_UNAVAILABLE" -> "프리마켓 리포트가 없어 일부 소스가 비활성입니다"
        key == "SOURCE_EMPTY_FALLBACK_RECENT_ORDERS" -> "후보 소스가 비어 최근 자동매매 이력으로 대체했습니다"
        key == "NO_CANDIDATES" -> "후보가 없습니다"
        key.startsWith("MOVERS_SOURCE_ERROR") -> "급등 소스 계산에 일시 오류가 있습니다"
        key.startsWith("SUPPLY_SOURCE_ERROR") -> "수급 소스 계산에 일시 오류가 있습니다"
        else -> key
    }
}

private fun normalizeTicker(raw: String?): String {
    val v = raw?.trim().orEmpty()
    return if (v.length in 1..6 && v.all { it.isDigit() }) v.padStart(6, '0') else v
}

private fun isOrderForEnvironment(
    order: AutoTradeOrderItemDto,
    environment: String,
): Boolean {
    val explicitEnv = order.environment?.trim()?.lowercase()
    if (explicitEnv in setOf("paper", "demo", "prod")) {
        return when (environment) {
            "paper" -> explicitEnv == "paper"
            "demo" -> explicitEnv == "demo"
            "prod" -> explicitEnv == "prod"
            else -> true
        }
    }
    val status = order.status?.trim()?.uppercase().orEmpty()
    val hintText = listOf(order.reason.orEmpty(), order.brokerOrderNo.orEmpty())
        .joinToString(" ")
        .trim()
        .uppercase()
    val hasDemoHint = hintText.contains("모의투자") || hintText.contains("DEMO") || hintText.contains("VTS")
    val hasProdHint = hintText.contains("실전투자") || hintText.contains("PROD")
    return when (environment) {
        "paper" -> {
            if (status.startsWith("PAPER")) true
            else if (!status.startsWith("BROKER")) false
            else if (hasDemoHint || hasProdHint) hasDemoHint
            else false
        }
        "demo" -> {
            if (!status.startsWith("BROKER")) false
            else if (hasDemoHint || hasProdHint) hasDemoHint
            else true
        }
        "prod" -> {
            if (!status.startsWith("BROKER")) false
            else if (hasDemoHint || hasProdHint) hasProdHint
            else true
        }
        else -> true
    }
}

private fun formatWon(v: Double?): String = if (v == null || v <= 0.0) "-" else "${"%,.0f".format(v)}원"
private fun formatWonExact(v: Double?): String = if (v == null) "-" else "${"%,.0f".format(v)}원"

private fun formatSignedPct(v: Double?): String {
    if (v == null) return "-"
    return "${if (v >= 0.0) "+" else ""}${"%.2f".format(v)}%"
}

private fun formatSignedPctTight(v: Double): String = "${if (v >= 0.0) "+" else ""}${"%.2f".format(v)}%"

private fun formatSignedWon(v: Double): String = "${if (v >= 0.0) "+" else "-"}${"%,.0f".format(kotlin.math.abs(v))}원"

private fun movementColor(v: Double): Color = if (v >= 0.0) Color(0xFFD15267) else Color(0xFF2D6CB3)

private fun reasonEvidenceSummary(detail: AutoTradeReasonDetailDto?): String? {
    val evidence = detail?.evidence.orEmpty().entries
        .filter { it.key.isNotBlank() && it.value.isNotBlank() && it.value != "-" }
        .take(3)
    if (evidence.isEmpty()) return null
    return evidence.joinToString(" · ") { "${it.key} ${it.value}" }
}

private fun toHoldingItems(rows: List<AutoTradeHoldingUi>): List<CommonReportItemUi> {
    return rows.map { h ->
        val ticker = h.ticker
        val quote = RealtimeQuoteItemDto(
            ticker = ticker,
            price = h.currentPrice,
            prevClose = if (h.avgPrice > 0.0) h.avgPrice else h.currentPrice,
            chgPct = h.pnlPct,
            asOf = null,
            source = "AUTOTRADE_HOLDING",
            isLive = true,
        )
        CommonReportItemUi(
            ticker = ticker,
            name = h.name,
            title = "${h.name} ($ticker)",
            quote = quote,
            fallbackPrice = h.currentPrice,
            fallbackChangePct = h.pnlPct,
            fallbackLabel = "손익%",
            metrics = listOf(
                MetricUi("수량", h.qty.toDouble(), formatted = "${h.qty}주"),
                MetricUi("평균가", h.avgPrice, formatted = formatWon(h.avgPrice)),
                MetricUi("평가금액", h.evalAmount, formatted = formatWon(h.evalAmount)),
            ),
            extraLines = listOf(
                "현재가 ${formatWon(h.currentPrice)} · 손익 ${formatSignedPct(h.pnlPct)}",
                "소스: ${sourceLabel(h.sourceTab)}",
            ),
            sortPrice = h.currentPrice,
            sortChangePct = h.pnlPct,
            sortName = h.name,
        )
    }
}

private fun toCandidateItems(rows: List<AutoTradeCandidateItemDto>): List<CommonReportItemUi> {
    return rows.map { row ->
        val ticker = row.ticker.orEmpty()
        val name = row.name?.takeIf { it.isNotBlank() } ?: ticker
        val price = row.currentPrice ?: row.signalPrice
        CommonReportItemUi(
            ticker = ticker,
            name = name,
            title = "$name ($ticker)",
            quote = null,
            fallbackPrice = price,
            fallbackChangePct = row.chgPct,
            fallbackLabel = "등락%",
            metrics = listOf(
                MetricUi("신호가", row.signalPrice ?: 0.0, formatted = formatWon(row.signalPrice)),
                MetricUi("현재가", row.currentPrice ?: row.signalPrice ?: 0.0, formatted = formatWon(row.currentPrice ?: row.signalPrice)),
            ),
            extraLines = listOfNotNull(
                "소스: ${sourceLabel(row.sourceTab)}",
                row.note?.takeIf { it.isNotBlank() }?.let { "사유: $it" },
            ),
            sortPrice = price,
            sortChangePct = row.chgPct,
            sortName = name,
        )
    }
}

private fun toOrderItems(rows: List<AutoTradeOrderItemDto>): List<CommonReportItemUi> {
    return rows.map { o ->
        val ticker = o.ticker.orEmpty()
        val name = o.name?.takeIf { it.isNotBlank() } ?: ticker
        val detail = o.reasonDetail
        val current = o.currentPrice
        val fill = o.filledPrice
        val quote = if (current != null) {
            RealtimeQuoteItemDto(
                ticker = ticker,
                price = current,
                prevClose = fill ?: current,
                chgPct = o.pnlPct,
                asOf = o.requestedAt,
                source = "AUTOTRADE",
                isLive = true,
            )
        } else {
            null
        }
        CommonReportItemUi(
            ticker = ticker,
            name = name,
            title = "$name ($ticker)",
            quote = quote,
            fallbackPrice = fill ?: o.requestedPrice,
            fallbackChangePct = o.pnlPct,
            fallbackLabel = "손익%",
            statusTag = detail?.conclusion?.takeIf { it.isNotBlank() } ?: orderStatusLabel(o.status, o.reason),
            metrics = listOf(
                MetricUi("수량", (o.qty ?: 0).toDouble(), formatted = (o.qty ?: 0).toString()),
                MetricUi("주문가", o.requestedPrice ?: 0.0),
                MetricUi("체결가", o.filledPrice ?: 0.0),
            ),
            extraLines = listOfNotNull(
                "시각: ${compactTimeLabel(o.filledAt ?: o.requestedAt)}",
                "구분: ${if ((o.side ?: "BUY").uppercase() == "SELL") "매도" else "매수"}",
                "주문가 ${formatWon(o.requestedPrice)} · 현재가 ${formatWon(o.currentPrice ?: o.filledPrice ?: o.requestedPrice)} · 수익률 ${formatSignedPct(o.pnlPct)}",
                "소스: ${sourceLabel(o.sourceTab)}",
                "상태: ${orderStatusLabel(o.status, o.reason)}",
                detail?.reasonCode?.takeIf { it.isNotBlank() }?.let { "코드: $it" },
                reasonEvidenceSummary(detail)?.let { "근거: $it" },
                detail?.action?.takeIf { it.isNotBlank() }?.let { "조치: $it" },
                o.brokerOrderNo?.takeIf { it.isNotBlank() }?.let { "증권사 주문번호: $it" },
                o.reason?.takeIf { it.isNotBlank() }?.let { "증권사 사유: $it" },
            ),
            thesis = "실행ID=${o.runId}",
            sortPrice = current ?: o.filledPrice,
            sortChangePct = o.pnlPct,
            sortName = name,
        )
    }
}

private fun isOrderActuallyFilled(raw: String?): Boolean = when (raw?.trim()?.uppercase()) {
    "PAPER_FILLED", "BROKER_FILLED" -> true
    else -> false
}

private fun isOrderFilled(raw: String?): Boolean = isOrderActuallyFilled(raw)

private fun isOrderPending(raw: String?): Boolean = when (raw?.trim()?.uppercase()) {
    "BROKER_SUBMITTED" -> true
    else -> false
}

private fun isOrderFailed(raw: String?): Boolean = when (raw?.trim()?.uppercase()) {
    "BROKER_REJECTED", "ERROR" -> true
    else -> false
}

private fun isOrderSkipped(raw: String?): Boolean = when (raw?.trim()?.uppercase()) {
    "SKIPPED", "BROKER_CANCELED" -> true
    else -> false
}

private fun isReservationForEnvironment(
    reservation: AutoTradeReservationItemDto,
    environment: String,
): Boolean {
    val env = reservation.environment?.trim()?.lowercase()
    if (env.isNullOrBlank()) return true
    return env == environment
}

private fun isReservationPending(raw: String?): Boolean = when (raw?.trim()?.uppercase()) {
    "QUEUED", "WAIT_CONFIRM", "RUNNING" -> true
    else -> false
}

private fun reservationStatusLabel(raw: String?): String = when (raw?.trim()?.uppercase()) {
    "QUEUED" -> "예약 대기"
    "WAIT_CONFIRM" -> "실행 확인 대기"
    "RUNNING" -> "예약 실행 중"
    "DONE" -> "예약 실행 완료"
    "PARTIAL" -> "예약 일부 실패"
    "FAILED" -> "예약 실행 실패"
    "CANCELED" -> "예약 취소"
    "EXPIRED" -> "예약 만료"
    else -> "예약 상태 확인 필요"
}

private fun isOrderPositionRelevant(raw: String?): Boolean = when (raw?.trim()?.uppercase()) {
    "PAPER_FILLED", "BROKER_FILLED", "BROKER_SUBMITTED" -> true
    else -> false
}

private fun compactTimeLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return "-"
    return raw.replace("T", " ").replace("Z", "").take(16)
}

private fun toReservationPreviewItems(rows: List<com.example.stock.data.api.AutoTradeReservationPreviewItemDto>): List<CommonReportItemUi> {
    return rows.take(3).map { row ->
        val ticker = normalizeTicker(row.ticker)
        val name = row.name?.takeIf { it.isNotBlank() } ?: ticker
        CommonReportItemUi(
            ticker = ticker,
            name = name,
            title = "$name ($ticker)",
            quote = null,
            fallbackPrice = null,
            fallbackChangePct = null,
            fallbackLabel = null,
            metrics = emptyList(),
            extraLines = listOf("소스: ${sourceLabel(row.sourceTab)}"),
            sortPrice = null,
            sortChangePct = null,
            sortName = name,
        )
    }
}

private fun buildHoldingSummary(rows: List<AutoTradeOrderItemDto>): List<AutoTradeHoldingUi> {
    data class MutableHolding(
        var name: String,
        var sourceTab: String,
        var qty: Int,
        var cost: Double,
        var currentPrice: Double,
        var pnlPct: Double?,
    )

    val map = linkedMapOf<String, MutableHolding>()
    rows.sortedBy { it.requestedAt ?: "" }.forEach { row ->
        if (!isOrderPositionRelevant(row.status)) return@forEach
        val ticker = row.ticker.orEmpty().trim()
        if (ticker.isBlank()) return@forEach
        val side = row.side?.uppercase() ?: "BUY"
        val qty = (row.qty ?: 0).coerceAtLeast(0)
        if (qty <= 0) return@forEach
        val px = row.filledPrice ?: row.requestedPrice ?: 0.0
        if (px <= 0.0) return@forEach
        val cur = row.currentPrice ?: row.filledPrice ?: row.requestedPrice ?: 0.0
        val sourceTab = row.sourceTab ?: "UNKNOWN"
        val name = row.name ?: ticker

        val h = map.getOrPut(ticker) {
            MutableHolding(
                name = name,
                sourceTab = sourceTab,
                qty = 0,
                cost = 0.0,
                currentPrice = cur,
                pnlPct = row.pnlPct,
            )
        }

        if (side == "BUY") {
            h.qty += qty
            h.cost += px * qty.toDouble()
            h.currentPrice = if (cur > 0.0) cur else h.currentPrice
            h.pnlPct = row.pnlPct ?: h.pnlPct
            h.name = name
            h.sourceTab = sourceTab
        } else if (side == "SELL") {
            if (h.qty > 0) {
                val sellQty = minOf(h.qty, qty)
                val avg = if (h.qty > 0) h.cost / h.qty.toDouble() else 0.0
                h.qty -= sellQty
                h.cost = (h.cost - (avg * sellQty.toDouble())).coerceAtLeast(0.0)
                h.currentPrice = if (cur > 0.0) cur else h.currentPrice
                h.pnlPct = row.pnlPct ?: h.pnlPct
            }
            if (h.qty <= 0) {
                map.remove(ticker)
            }
        }
    }

    return map.entries.mapNotNull { (ticker, h) ->
        if (h.qty <= 0) return@mapNotNull null
        val avg = if (h.qty > 0) h.cost / h.qty.toDouble() else 0.0
        val current = if (h.currentPrice > 0.0) h.currentPrice else avg
        val pnlPct = h.pnlPct ?: if (avg > 0.0) ((current / avg) - 1.0) * 100.0 else 0.0
        AutoTradeHoldingUi(
            ticker = ticker,
            name = h.name.ifBlank { ticker },
            sourceTab = h.sourceTab,
            qty = h.qty,
            avgPrice = avg,
            currentPrice = current,
            pnlPct = pnlPct,
        )
    }.sortedByDescending { it.evalAmount }
}

private fun buildFallbackSummary(
    holdings: List<AutoTradeHoldingUi>,
    orders: List<AutoTradeOrderItemDto>,
): AutoTradePerformanceItemDto? {
    if (holdings.isEmpty() && orders.isEmpty()) return null
    val buyAmount = holdings.sumOf { it.avgPrice * it.qty.toDouble() }
    val evalAmount = holdings.sumOf { it.currentPrice * it.qty.toDouble() }
    val unrealized = evalAmount - buyAmount
    val roi = if (buyAmount > 0.0) (unrealized / buyAmount) * 100.0 else 0.0
    val winRate = if (holdings.isNotEmpty()) {
        holdings.count { it.pnlPct > 0.0 }.toDouble() / holdings.size.toDouble()
    } else {
        0.0
    }
    val mdd = holdings.minOfOrNull { it.pnlPct }?.let { minOf(0.0, it) }?.let { kotlin.math.abs(it) } ?: 0.0
    return AutoTradePerformanceItemDto(
        ymd = "fallback",
        ordersTotal = orders.size,
        filledTotal = orders.count { isOrderActuallyFilled(it.status) },
        buyAmountKrw = buyAmount,
        evalAmountKrw = evalAmount,
        realizedPnlKrw = 0.0,
        unrealizedPnlKrw = unrealized,
        roiPct = roi,
        winRate = winRate,
        mddPct = mdd,
        updatedAt = null,
    )
}

private fun buildHoldingSummaryFromAccount(snapshot: AutoTradeAccountSnapshotResponseDto?): List<AutoTradeHoldingUi> {
    val positions = snapshot?.positions.orEmpty()
    return positions.mapNotNull { row ->
        val ticker = row.ticker.orEmpty().trim()
        if (ticker.isBlank()) return@mapNotNull null
        val qty = (row.qty ?: 0).coerceAtLeast(0)
        if (qty <= 0) return@mapNotNull null
        val avg = row.avgPrice ?: 0.0
        val current = row.currentPrice ?: avg
        val pnlPct = row.pnlPct ?: if (avg > 0.0) ((current / avg) - 1.0) * 100.0 else 0.0
        AutoTradeHoldingUi(
            ticker = ticker,
            name = row.name?.ifBlank { ticker } ?: ticker,
            sourceTab = row.sourceTab ?: "UNKNOWN",
            qty = qty,
            avgPrice = avg,
            currentPrice = current,
            pnlPct = pnlPct,
        )
    }.sortedByDescending { it.evalAmount }
}
