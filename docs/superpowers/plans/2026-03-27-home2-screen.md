# Home2 Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 HomeScreen을 건드리지 않고, Home2 탭을 신규 생성하여 안 C(대규모 확장) 홈 화면을 구현한다.

**Architecture:** AppTab.HOME2를 추가하고 Home2Screen.kt + Home2ViewModel을 신규 생성한다. 서버는 기존 API를 재활용하면서 4개 신규 엔드포인트(sectors, volume-surge, 52week-extremes, dividends)를 추가한다. trade-feed 응답에 summary 필드를 추가한다(기존 소비자에 영향 없는 옵셔널 필드).

**Tech Stack:** Android Kotlin + Jetpack Compose, Python 3.10 FastAPI, Retrofit, Kotlin Serialization, Pydantic, pykrx, httpx/aiohttp

**Spec:** `docs/superpowers/specs/2026-03-27-home-redesign-design.md`

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt` | Home2 화면 전체 Composable (12개 섹션) |
| `backend/app/sector_service.py` | Naver Finance 업종 시세 HTML 파싱 + 캐싱 |
| `backend/app/extremes_service.py` | 52주 신고가/신저가 배치 계산 + 캐싱 |
| `backend/app/dividend_service.py` | pykrx 배당 데이터 조회 + 캐싱 |

### Modified Files

| File | Changes |
|------|---------|
| `app/.../navigation/AppNavigation.kt` | `AppTab.HOME2` enum 추가, `composable` 라우트 추가 |
| `app/.../viewmodel/ViewModels.kt` | `Home2ViewModel` 클래스 추가, `AppViewModelFactory`에 매핑 추가 |
| `app/.../data/api/ApiModels.kt` | 신규 DTO 6개 추가 (기존 DTO 수정 없음) |
| `app/.../data/api/StockApiService.kt` | 신규 API 엔드포인트 4개 추가 |
| `app/.../data/repository/StockRepository.kt` | 신규 repository 메서드 4개 추가 |
| `backend/app/main.py` | 신규 엔드포인트 4개 추가 |
| `backend/app/schemas.py` | 신규 Pydantic 모델 추가 |
| `backend/app/movers.py` | `compute_volume_surge()` 함수 추가 |

> **경로 약칭**: `app/...` = `app/src/main/java/com/example/stock/`

---

## Phase 1: Scaffolding + 기존 API 재활용

### Task 1: Home2 탭 스캐폴딩

**Files:**
- Modify: `app/.../navigation/AppNavigation.kt:59-73` (AppTab enum)
- Modify: `app/.../navigation/AppNavigation.kt:240+` (composable 라우트)
- Create: `app/.../ui/screens/Home2Screen.kt`
- Modify: `app/.../viewmodel/ViewModels.kt:2241+` (Home2ViewModel)
- Modify: `app/.../viewmodel/ViewModels.kt:2492+` (AppViewModelFactory)

- [ ] **Step 1: AppTab enum에 HOME2 추가**

`AppNavigation.kt`에서 `HOME` 항목 바로 아래에 추가:

```kotlin
HOME2("home2", "홈2", R.drawable.ic_tab_home),  // 기존 홈 아이콘 재사용
```

- [ ] **Step 2: composable 라우트 추가**

`AppNavigation.kt`에서 `composable(AppTab.HOME.route)` 블록 아래에 추가:

```kotlin
composable(AppTab.HOME2.route) {
    Home2Screen()
}
```

import 추가: `import com.example.stock.ui.screens.Home2Screen`

- [ ] **Step 3: Home2ViewModel 빈 껍데기 작성**

`ViewModels.kt` 끝부분(AppViewModelFactory 위)에 추가:

```kotlin
class Home2ViewModel(private val repository: StockRepository) : ViewModel() {

    // ── 기존 홈 데이터 (HomeViewModel과 동일 패턴) ──
    var briefingState by mutableStateOf<String?>(null)
        private set
    var accountState by mutableStateOf<AutoTradeAccountSnapshotResponseDto?>(null)
        private set
    var performanceState by mutableStateOf<AutoTradePerformanceItemDto?>(null)
        private set
    var autoTradeEnabledState by mutableStateOf<Boolean?>(null)
        private set
    var autoTradeEnvState by mutableStateOf<String?>(null)
        private set
    var tradeFeedState by mutableStateOf<List<TradeFeedItemDto>>(emptyList())
        private set
    var tradeFeedSummaryState by mutableStateOf<TradeFeedSummaryDto?>(null)
        private set
    var liveIndicesState by mutableStateOf<MarketIndicesResponseDto?>(null)
        private set
    var regimeModeState by mutableStateOf<String?>(null)
        private set
    var marketTemperatureState by mutableStateOf<MarketTemperatureDto?>(null)
        private set
    var premarketState by mutableStateOf<UiState<PremarketReportDto>>(UiState.Loading)
        private set
    var investorFlowState by mutableStateOf<InvestorFlowSummary?>(null)
        private set
    var favoritesState by mutableStateOf<List<FavoriteItemDto>>(emptyList())
        private set
    var quoteState by mutableStateOf<Map<String, RealtimeQuoteItemDto>>(emptyMap())
        private set
    var miniChartState by mutableStateOf<Map<String, List<ChartPointDto>>>(emptyMap())
        private set
    var pnlCalendarState by mutableStateOf<PnlCalendarResponseDto?>(null)
        private set
    var newsClustersState by mutableStateOf<List<NewsClusterListItemDto>>(emptyList())
        private set
    var reservationCountState by mutableStateOf(0)
        private set
    var snapshotDateState by mutableStateOf<String?>(null)
        private set

    // ── 신규 데이터 (Home2 전용) ──
    var sectorHeatmapState by mutableStateOf<List<SectorItemDto>>(emptyList())
        private set
    var volumeSurgeState by mutableStateOf<List<VolumeSurgeItemDto>>(emptyList())
        private set
    var weekExtremeState by mutableStateOf<WeekExtremeResponseDto?>(null)
        private set
    var dividendState by mutableStateOf<List<DividendItemDto>>(emptyList())
        private set

    var sectionErrorState by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private var pollingJob: Job? = null

    fun load() {
        viewModelScope.launch {
            // Phase 1에서 구현
        }
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            delay(30_000)
            while (isActive) {
                fetchQuotes()
                delay(30_000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun fetchQuotes() {
        // Phase 1에서 구현
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
```

- [ ] **Step 4: AppViewModelFactory에 Home2ViewModel 매핑 추가**

`AppViewModelFactory.create()` 메서드의 `when` 블록에 추가:

```kotlin
modelClass.isAssignableFrom(Home2ViewModel::class.java) -> Home2ViewModel(repository) as T
```

- [ ] **Step 5: Home2Screen 빈 껍데기 작성**

새 파일 `app/.../ui/screens/Home2Screen.kt` 생성:

```kotlin
package com.example.stock.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.data.repository.StockRepository
import com.example.stock.ui.common.AppTopBar
import com.example.stock.viewmodel.Home2ViewModel
import com.example.stock.viewmodel.AppViewModelFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home2Screen() {
    val repo = remember { StockRepository.create() }
    val vm: Home2ViewModel = viewModel(factory = AppViewModelFactory(repo))

    LaunchedEffect(Unit) {
        vm.load()
    }
    DisposableEffect(Unit) {
        onDispose { vm.stopPolling() }
    }

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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 섹션들은 이후 Task에서 추가
            item {
                Text("Home2 스캐폴딩 완료", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
```

- [ ] **Step 6: 빌드 확인**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd ~/AndroidStudioProjects/stock && ./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/stock/navigation/AppNavigation.kt \
       app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt \
       app/src/main/java/com/example/stock/viewmodel/ViewModels.kt
git commit -m "feat: add Home2 tab scaffolding with empty screen and viewmodel"
```

---

### Task 2: 내 계좌 + 보유 종목 통합 섹션

**Files:**
- Modify: `app/.../data/api/ApiModels.kt` (신규 DTO)
- Modify: `app/.../viewmodel/ViewModels.kt` (Home2ViewModel.loadAccount)
- Modify: `app/.../ui/screens/Home2Screen.kt` (AccountPositionsCard)

- [ ] **Step 1: Home2용 계좌 DTO 확인**

기존 `AutoTradeAccountSnapshotResponseDto`에 이미 `positions`, `cashKrw`, `orderableCashKrw` 필드가 있다. Home2ViewModel에서 이 DTO를 그대로 사용한다. 신규 DTO 추가 불필요.

기존 DTO 확인 (ApiModels.kt):
```kotlin
// 이미 존재하는 필드:
// cashKrw: Double? = null           → 예수금
// orderableCashKrw: Double? = null  → 매수가능금액
// positions: List<AutoTradeAccountPositionDto>? = emptyList()  → 보유 종목
```

서버 `schemas.py`에도 `orderable_cash_krw` 필드가 이미 존재한다. **서버 변경 불필요.**

- [ ] **Step 2: Home2ViewModel에 loadAccount() 구현**

`ViewModels.kt`의 `Home2ViewModel.load()` 메서드에 추가:

```kotlin
fun load() {
    viewModelScope.launch {
        coroutineScope {
            // 계좌 + 자동매매 설정
            async { loadAccount() }
            // (다른 로드 함수들은 이후 Task에서 추가)
        }
        startPolling()
    }
}

private suspend fun loadAccount() {
    try {
        val bootstrap = repository.getAutoTradeBootstrap(fast = true)
        accountState = bootstrap.account
        autoTradeEnabledState = bootstrap.settings?.enabled
        autoTradeEnvState = bootstrap.settings?.environment
        reservationCountState = bootstrap.reservationCount ?: 0
        sectionErrorState = sectionErrorState - "account"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("account" to (e.message ?: "계좌 로드 실패"))
    }
}
```

- [ ] **Step 3: AccountPositionsCard Composable 작성**

`Home2Screen.kt`에 추가:

```kotlin
@Composable
private fun AccountPositionsCard(
    account: AutoTradeAccountSnapshotResponseDto,
) {
    var expanded by remember { mutableStateOf(true) }
    val positions = account.positions ?: emptyList()
    val hasPositions = positions.isNotEmpty()

    HomeSectionCard(title = "내 계좌") {
        // 상단: 총자산 + 평가손익
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("총 평가자산", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = formatKrw(account.totalAssetKrw),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("평가손익", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val pnl = account.realEvalPnlKrw ?: 0.0
                val pnlPct = account.realEvalPnlPct ?: 0.0
                Text(
                    text = "${formatKrw(pnl)} (${formatPct(pnlPct)})",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = pnlColor(pnl),
                    ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 중단: 예수금 + 매수가능
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LabelValue("예수금", formatKrw(account.cashKrw))
            LabelValue("매수가능", formatKrw(account.orderableCashKrw))
        }

        // 하단: 보유 종목 리스트 (접기/펼치기)
        if (hasPositions) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "보유 종목 ${positions.size}개",
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess
                        else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "접기" else "펼치기",
                    modifier = Modifier.size(20.dp),
                )
            }

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                positions.forEach { pos ->
                    PositionRow(pos)
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "보유 종목 없음",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PositionRow(pos: AutoTradeAccountPositionDto) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                StockDetailActivity.open(
                    context,
                    ticker = pos.ticker ?: "",
                    name = pos.name ?: "",
                    origin = "home2_positions",
                )
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = pos.name ?: pos.ticker ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${pos.qty ?: 0}주 · 평단 ${formatPrice(pos.avgPrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val pnl = pos.pnlAmountKrw ?: 0.0
            val pnlPct = pos.pnlPct ?: 0.0
            Text(
                text = formatKrw(pnl),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = pnlColor(pnl),
                ),
            )
            Text(
                text = formatPct(pnlPct),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = pnlColor(pnl),
                ),
            )
        }
    }
}
```

- [ ] **Step 4: 유틸리티 함수 추가**

`Home2Screen.kt` 파일 하단에 추가:

```kotlin
// ── 유틸리티 ──

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
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            } else {
                content()
            }
        }
    }
}
```

- [ ] **Step 5: LazyColumn에 계좌 섹션 연결**

`Home2Screen.kt`의 `LazyColumn` 블록에서 placeholder 텍스트를 교체:

```kotlin
LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    // 섹션 1: 내 계좌 + 보유 종목
    vm.accountState?.let { account ->
        item(key = "account") {
            AccountPositionsCard(account = account)
        }
    }
    vm.sectionErrorState["account"]?.let { err ->
        item(key = "account_error") {
            HomeSectionCard(title = "내 계좌", error = err) {}
        }
    }
}
```

- [ ] **Step 6: 빌드 확인**

Run:
```bash
cd ~/AndroidStudioProjects/stock && ./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt \
       app/src/main/java/com/example/stock/viewmodel/ViewModels.kt
git commit -m "feat(home2): add account + positions section with expand/collapse"
```

---

### Task 3: 자동매매 상태 + Gate 레이블 개선

**Files:**
- Modify: `app/.../ui/screens/Home2Screen.kt` (AutoTradeStatusCard2)
- Modify: `app/.../viewmodel/ViewModels.kt` (Home2ViewModel.loadPerformance)

- [ ] **Step 1: Home2ViewModel에 loadPerformance() 추가**

`ViewModels.kt`의 `Home2ViewModel.load()` 내 `coroutineScope` 블록에 추가:

```kotlin
async { loadPerformance() }
```

```kotlin
private suspend fun loadPerformance() {
    try {
        val resp = repository.getAutoTradePerformance(days = 30)
        performanceState = resp.items?.firstOrNull()
        sectionErrorState = sectionErrorState - "performance"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("performance" to (e.message ?: "성과 로드 실패"))
    }
}
```

- [ ] **Step 2: AutoTradeStatusCard2 Composable 작성**

`Home2Screen.kt`에 추가:

```kotlin
@Composable
private fun AutoTradeStatusCard2(
    enabled: Boolean?,
    environment: String?,
    performance: AutoTradePerformanceItemDto?,
    account: AutoTradeAccountSnapshotResponseDto?,
    reservationCount: Int,
    regimeMode: String?,
) {
    HomeSectionCard(title = "자동매매") {
        // 상태 칩 행
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 실행 상태
            StatusChip(
                label = if (enabled == true) "실행 중" else "중지",
                color = if (enabled == true) Color(0xFF4CAF50) else Color.Gray,
            )
            // 환경
            StatusChip(
                label = when (environment) {
                    "prod" -> "실전"
                    "demo" -> "DEMO"
                    else -> "DEMO"
                },
                color = if (environment == "prod") Color(0xFFFF9800) else Color(0xFF2196F3),
            )
            // Gate — 핵심 변경: "Gate ON/OFF" → "진입 허용/차단"
            val gateOn = regimeMode != null && regimeMode != "RISK_OFF"
            StatusChip(
                label = if (gateOn) "진입 허용" else "진입 차단",
                color = if (gateOn) Color(0xFF4CAF50) else UpColor,
            )
        }

        // 성과 지표
        performance?.let { perf ->
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MetricItem("수익률", formatPct(perf.roiPct))
                MetricItem("승률", formatPct(perf.winRate))
                MetricItem("MDD", formatPct(perf.mddPct))
                MetricItem("주문", "${perf.ordersTotal ?: 0}건")
            }
        }

        // 예약 대기
        if (reservationCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "예약 대기 ${reservationCount}건",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace))
    }
}
```

- [ ] **Step 3: LazyColumn에 자동매매 섹션 추가**

`Home2Screen.kt`의 LazyColumn에서 계좌 섹션 `item` 아래에 추가:

```kotlin
// 섹션 2: 자동매매 상태
item(key = "autotrade") {
    AutoTradeStatusCard2(
        enabled = vm.autoTradeEnabledState,
        environment = vm.autoTradeEnvState,
        performance = vm.performanceState,
        account = vm.accountState,
        reservationCount = vm.reservationCountState,
        regimeMode = vm.regimeModeState,
    )
}
```

- [ ] **Step 4: 빌드 확인 + Commit**

```bash
cd ~/AndroidStudioProjects/stock && ./gradlew assembleDebug 2>&1 | tail -5
git add app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt \
       app/src/main/java/com/example/stock/viewmodel/ViewModels.kt
git commit -m "feat(home2): add autotrade status section with Korean gate labels"
```

---

### Task 4: 오늘 체결 요약 헤더

**Files:**
- Modify: `backend/app/schemas.py` (TradeFeedSummary 추가)
- Modify: `backend/app/main.py` (trade-feed 엔드포인트에 summary 계산 추가)
- Modify: `app/.../data/api/ApiModels.kt` (TradeFeedSummaryDto 추가)
- Modify: `app/.../viewmodel/ViewModels.kt` (Home2ViewModel.loadTradeFeed)
- Modify: `app/.../ui/screens/Home2Screen.kt` (TradeFeedSummaryCard)

- [ ] **Step 1: 서버 — TradeFeedSummary Pydantic 모델 추가**

`backend/app/schemas.py`에 추가:

```python
class TradeFeedSummary(BaseModel):
    total_count: int = 0
    realized_pnl: float = 0.0
    buy_count: int = 0
    sell_count: int = 0
```

기존 `TradeFeedResponse` 수정 — `summary` 옵셔널 필드 추가:

```python
class TradeFeedResponse(BaseModel):
    items: list[TradeFeedItem] = Field(default_factory=list)
    total: int = 0
    summary: TradeFeedSummary | None = None  # 신규 — 기존 소비자에 영향 없음
```

- [ ] **Step 2: 서버 — trade-feed 엔드포인트에서 summary 계산**

`backend/app/main.py`의 `/autotrade/feed` 엔드포인트에서 반환 직전에 summary 계산 로직 추가:

```python
# 기존 items 구성 후, return 직전에 추가:
summary = TradeFeedSummary(
    total_count=len(feed_items),
    realized_pnl=sum(it.pnl or 0.0 for it in feed_items if it.side == "SELL"),
    buy_count=sum(1 for it in feed_items if it.side == "BUY"),
    sell_count=sum(1 for it in feed_items if it.side == "SELL"),
)
return TradeFeedResponse(items=feed_items, total=len(feed_items), summary=summary)
```

- [ ] **Step 3: 앱 — TradeFeedSummaryDto 추가**

`ApiModels.kt`에 추가 (기존 TradeFeedResponseDto 수정 없음 — 별도 DTO):

```kotlin
@Serializable
data class TradeFeedSummaryDto(
    val totalCount: Int? = 0,
    val realizedPnl: Double? = 0.0,
    val buyCount: Int? = 0,
    val sellCount: Int? = 0,
)
```

기존 `TradeFeedResponseDto`에 옵셔널 필드 추가:

```kotlin
@Serializable
data class TradeFeedResponseDto(
    val items: List<TradeFeedItemDto>? = emptyList(),
    val total: Int? = 0,
    val summary: TradeFeedSummaryDto? = null,  // 신규
)
```

> 주의: 기존 `TradeFeedResponseDto`에 1필드 추가이므로 기존 HomeScreen의 소비 코드에 영향 없음 (null default).

- [ ] **Step 4: Home2ViewModel에 loadTradeFeed() 추가**

`ViewModels.kt`의 `Home2ViewModel.load()` 내 `coroutineScope` 블록에 추가:

```kotlin
async { loadTradeFeed() }
```

```kotlin
private suspend fun loadTradeFeed() {
    try {
        val resp = repository.getAutoTradeFeed(limit = 20)
        tradeFeedState = resp.items ?: emptyList()
        tradeFeedSummaryState = resp.summary
        sectionErrorState = sectionErrorState - "feed"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("feed" to (e.message ?: "매매 피드 로드 실패"))
    }
}
```

- [ ] **Step 5: TradeFeedSummaryCard Composable 작성**

`Home2Screen.kt`에 추가:

```kotlin
@Composable
private fun TradeFeedSummaryCard(
    summary: TradeFeedSummaryDto?,
    items: List<TradeFeedItemDto>,
) {
    var expanded by remember { mutableStateOf(false) }

    HomeSectionCard(title = "오늘 체결") {
        // 요약 헤더
        if (summary != null && (summary.totalCount ?: 0) > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("총 체결", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${summary.totalCount}건",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("실현손익", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pnl = summary.realizedPnl ?: 0.0
                    Text(
                        formatKrw(pnl),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = pnlColor(pnl),
                        ),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("매수/매도", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "${summary.buyCount ?: 0}/${summary.sellCount ?: 0}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace),
                    )
                }
            }
        } else {
            Text(
                "오늘 체결 내역 없음",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 체결 리스트 (펼치기)
        if (items.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "접기" else "상세 보기 (${items.size}건)")
            }
            if (expanded) {
                items.take(10).forEach { item ->
                    TradeFeedRow(item)
                }
            }
        }
    }
}

@Composable
private fun TradeFeedRow(item: TradeFeedItemDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val sideColor = if (item.side == "BUY") UpColor else DownColor
            Text(
                text = if (item.side == "BUY") "매수" else "매도",
                style = MaterialTheme.typography.labelSmall,
                color = sideColor,
            )
            Text(
                text = item.name ?: item.ticker ?: "",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${item.qty ?: 0}주",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            item.pnl?.let { pnl ->
                Text(
                    text = formatKrw(pnl),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = pnlColor(pnl),
                    ),
                )
            }
        }
    }
}
```

- [ ] **Step 6: LazyColumn에 체결 요약 섹션 추가**

자동매매 섹션 아래에 추가:

```kotlin
// 섹션 2b: 오늘 체결 요약
if (vm.tradeFeedState.isNotEmpty() || vm.tradeFeedSummaryState != null) {
    item(key = "trade_feed") {
        TradeFeedSummaryCard(
            summary = vm.tradeFeedSummaryState,
            items = vm.tradeFeedState,
        )
    }
}
```

- [ ] **Step 7: 빌드 확인 + Commit**

```bash
cd ~/AndroidStudioProjects/stock && ./gradlew assembleDebug 2>&1 | tail -5
git add app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt \
       app/src/main/java/com/example/stock/viewmodel/ViewModels.kt \
       app/src/main/java/com/example/stock/data/api/ApiModels.kt \
       backend/app/schemas.py backend/app/main.py
git commit -m "feat(home2): add trade feed summary with count and realized PnL"
```

---

### Task 5: 기존 섹션 이식 (브리핑, 시장지표, 추천, 수급, 관심, 캘린더, 뉴스)

**Files:**
- Modify: `app/.../viewmodel/ViewModels.kt` (Home2ViewModel — 나머지 로드 메서드)
- Modify: `app/.../ui/screens/Home2Screen.kt` (기존 섹션 Composable + LazyColumn 연결)

이 Task는 HomeScreen에 이미 있는 섹션들을 Home2Screen에 새로 작성한다. HomeScreen의 Composable은 `private`이므로 공유 불가 — Home2 전용으로 다시 작성한다.

- [ ] **Step 1: Home2ViewModel에 나머지 로드 메서드 전부 추가**

`load()` 메서드의 `coroutineScope` 블록 최종 형태:

```kotlin
fun load() {
    viewModelScope.launch {
        coroutineScope {
            async { loadAccount() }
            async { loadPerformance() }
            async { loadTradeFeed() }
            async { loadPremarket() }
            async { loadMarketIndices() }
            async { loadNewsClusters() }
            async { loadFavorites() }
            async { loadPnlCalendar() }
        }
        // 수급은 느리므로 별도 launch
        viewModelScope.launch { loadInvestorFlow() }
        startPolling()
    }
}
```

나머지 로드 메서드 추가:

```kotlin
private suspend fun loadPremarket() {
    try {
        val today = java.time.LocalDate.now().toString().replace("-", "")
        val resp = repository.getPremarketReport(
            date = today, lookback = 60, risk = "medium",
            themeCap = 3, variant = 2,
        )
        premarketState = UiState.Success(resp)
        regimeModeState = resp.regime?.mode
        briefingState = resp.briefing
        marketTemperatureState = resp.marketTemperature
        snapshotDateState = resp.snapshotDate
        sectionErrorState = sectionErrorState - "premarket"
    } catch (e: Exception) {
        premarketState = UiState.Error(e.message ?: "")
        sectionErrorState = sectionErrorState + ("premarket" to (e.message ?: "프리마켓 로드 실패"))
    }
}

private suspend fun loadMarketIndices() {
    try {
        liveIndicesState = repository.getMarketIndices()
        sectionErrorState = sectionErrorState - "indices"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("indices" to (e.message ?: "지수 로드 실패"))
    }
}

private suspend fun loadNewsClusters() {
    try {
        val resp = repository.getNewsClusters(limit = 3)
        newsClustersState = resp.items?.take(3) ?: emptyList()
        sectionErrorState = sectionErrorState - "news"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("news" to (e.message ?: "뉴스 로드 실패"))
    }
}

private suspend fun loadFavorites() {
    try {
        val resp = repository.getFavorites()
        favoritesState = resp.items ?: emptyList()
        // 시세 + 미니차트 일괄 요청
        val tickers = favoritesState.mapNotNull { it.ticker }
        if (tickers.isNotEmpty()) {
            try {
                val quotesResp = repository.getRealtimeQuotes(tickers.joinToString(","))
                quoteState = quotesResp.items
                    ?.associateBy { it.ticker ?: "" }
                    ?.filterKeys { it.isNotEmpty() }
                    ?: emptyMap()
            } catch (_: Exception) { /* 시세 실패는 무시 */ }
            try {
                val chartResp = repository.getChartDailyBatch(tickers, days = 7)
                miniChartState = chartResp.charts ?: emptyMap()
            } catch (_: Exception) { /* 차트 실패는 무시 */ }
        }
        sectionErrorState = sectionErrorState - "favorites"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("favorites" to (e.message ?: "관심종목 로드 실패"))
    }
}

private suspend fun loadInvestorFlow() {
    try {
        val resp = repository.getMarketSupply(count = 60, days = 20)
        val dailyMap = mutableMapOf<String, DailyFlow>()
        resp.dailyFlow?.forEach { df ->
            val date = df.date ?: return@forEach
            dailyMap[date] = DailyFlow(
                date = date,
                foreign = df.foreign?.toLong() ?: 0L,
                institution = df.institution?.toLong() ?: 0L,
                individual = df.individual?.toLong() ?: 0L,
            )
        }
        val sorted = dailyMap.values.sortedByDescending { it.date }.take(3)
        investorFlowState = InvestorFlowSummary(
            individual = sorted.sumOf { it.individual },
            foreign = sorted.sumOf { it.foreign },
            institution = sorted.sumOf { it.institution },
            unit = resp.unit ?: "value",
            dailyFlow = sorted,
        )
        sectionErrorState = sectionErrorState - "supply"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("supply" to (e.message ?: "수급 로드 실패"))
    }
}

private suspend fun loadPnlCalendar() {
    try {
        val now = java.time.LocalDate.now()
        pnlCalendarState = repository.getAutoTradePnlCalendar(now.year, now.monthValue)
        sectionErrorState = sectionErrorState - "calendar"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("calendar" to (e.message ?: "캘린더 로드 실패"))
    }
}

override suspend fun fetchQuotes() {
    val tickers = mutableSetOf<String>()
    // 관심종목 시세
    favoritesState.mapNotNull { it.ticker }.let { tickers.addAll(it) }
    // 추천 종목 시세
    (premarketState as? UiState.Success)?.data?.let { report ->
        report.shortTermItems?.mapNotNull { it.ticker }?.let { tickers.addAll(it) }
        report.longTermItems?.mapNotNull { it.ticker }?.let { tickers.addAll(it) }
    }
    if (tickers.isEmpty()) return
    try {
        val resp = repository.getRealtimeQuotes(tickers.take(40).joinToString(","))
        quoteState = resp.items
            ?.associateBy { it.ticker ?: "" }
            ?.filterKeys { it.isNotEmpty() }
            ?: emptyMap()
    } catch (_: Exception) { /* 폴링 실패는 무시 */ }
}
```

- [ ] **Step 2: 기존 섹션 Composable 작성 (시장지표, 추천, 수급, 관심, 캘린더, 뉴스)**

`Home2Screen.kt`에 추가. 각 Composable은 HomeScreen의 동일 섹션과 같은 데이터를 표시하되 독립 구현:

```kotlin
// ── 한줄 브리핑 ──
@Composable
private fun BriefingBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 시장 지표 ──
@Composable
private fun MarketIndicesCard(
    indices: MarketIndicesResponseDto,
    regimeMode: String?,
    temperature: MarketTemperatureDto?,
    snapshotDate: String?,
) {
    HomeSectionCard(title = "시장 지표") {
        // 지수 행
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            indices.kospi?.let { IndexItem("코스피", it) }
            indices.kosdaq?.let { IndexItem("코스닥", it) }
            indices.usdkrw?.let { IndexItem("원/달러", it) }
        }

        // 체제 + 온도계
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            regimeMode?.let { mode ->
                StatusChip(
                    label = mode,
                    color = when {
                        mode.contains("BULL") -> Color(0xFF4CAF50)
                        mode.contains("BEAR") || mode.contains("RISK_OFF") -> UpColor
                        else -> Color(0xFFFF9800)
                    },
                )
            }
            temperature?.let { temp ->
                Text(
                    "시장 온도 ${temp.score ?: "-"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        snapshotDate?.let {
            Text(
                "기준: $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IndexItem(label: String, index: MarketIndexValueDto) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            String.format("%,.2f", index.value ?: 0.0),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        val chg = index.changePct ?: 0.0
        Text(
            formatPct(chg),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = pnlColor(chg),
            ),
        )
    }
}

// ── 오늘의 추천 ──
@Composable
private fun RecommendationCard(report: PremarketReportDto) {
    HomeSectionCard(title = "오늘의 추천") {
        val shortTerm = report.shortTermItems?.take(3) ?: emptyList()
        val longTerm = report.longTermItems?.take(3) ?: emptyList()

        if (shortTerm.isNotEmpty()) {
            Text("단타 TOP3", style = MaterialTheme.typography.labelMedium,
                color = UpColor)
            shortTerm.forEach { item ->
                RecommendRow(item)
            }
        }
        if (longTerm.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("장타 TOP3", style = MaterialTheme.typography.labelMedium,
                color = DownColor)
            longTerm.forEach { item ->
                RecommendRow(item)
            }
        }
        if (shortTerm.isEmpty() && longTerm.isEmpty()) {
            Text("추천 종목 없음", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecommendRow(item: PremarketItemDto) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                StockDetailActivity.open(context,
                    ticker = item.ticker ?: "", name = item.name ?: "",
                    origin = "home2_recommend")
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            item.name ?: item.ticker ?: "",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            item.reason ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
    }
}

// ── 투자자 수급 ──
@Composable
private fun InvestorFlowCard2(flow: InvestorFlowSummary) {
    HomeSectionCard(title = "투자자 수급 현황") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FlowItem("개인", flow.individual)
            FlowItem("외국인", flow.foreign)
            FlowItem("기관", flow.institution)
        }
        if (flow.dailyFlow.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("최근 3일 누적", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FlowItem(label: String, value: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val formatted = if (kotlin.math.abs(value) >= 100_000_000) {
            "${String.format("%+,.1f", value / 100_000_000.0)}억"
        } else {
            "${String.format("%+,d", value / 1_000_000)}백만"
        }
        Text(
            formatted,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = pnlColor(value.toDouble()),
            ),
        )
    }
}

// ── 관심 종목 ──
@Composable
private fun FavoritesSection(
    favorites: List<FavoriteItemDto>,
    quotes: Map<String, RealtimeQuoteItemDto>,
    miniCharts: Map<String, List<ChartPointDto>>,
) {
    HomeSectionCard(title = "관심 종목") {
        if (favorites.isEmpty()) {
            Text("관심 종목을 추가해보세요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            favorites.take(10).forEach { fav ->
                val ticker = fav.ticker ?: return@forEach
                val quote = quotes[ticker]
                CommonReportItemCard(
                    ticker = ticker,
                    name = fav.name ?: ticker,
                    quote = quote,
                    miniPoints = miniCharts[ticker],
                    origin = "home2_favorites",
                )
            }
            if (favorites.size > 10) {
                TextButton(onClick = { /* 관심 종목 탭 이동 */ }) {
                    Text("더보기 (${favorites.size}개)")
                }
            }
        }
    }
}

// ── 뉴스 ──
@Composable
private fun NewsSection(clusters: List<NewsClusterListItemDto>) {
    HomeSectionCard(title = "주요 뉴스") {
        if (clusters.isEmpty()) {
            Text("뉴스 없음",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            clusters.forEach { cluster ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = cluster.title ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: LazyColumn에 모든 기존 섹션 연결**

`Home2Screen.kt`의 LazyColumn 최종 형태 (Phase 1 완성):

```kotlin
LazyColumn(
    modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) {
    // 섹션 0: 한줄 브리핑
    vm.briefingState?.let { text ->
        item(key = "briefing") { BriefingBanner(text) }
    }

    // 섹션 1: 내 계좌 + 보유 종목
    vm.accountState?.let { account ->
        item(key = "account") { AccountPositionsCard(account = account) }
    }

    // 섹션 2: 자동매매 상태
    item(key = "autotrade") {
        AutoTradeStatusCard2(
            enabled = vm.autoTradeEnabledState,
            environment = vm.autoTradeEnvState,
            performance = vm.performanceState,
            account = vm.accountState,
            reservationCount = vm.reservationCountState,
            regimeMode = vm.regimeModeState,
        )
    }

    // 섹션 2b: 오늘 체결 요약
    if (vm.tradeFeedState.isNotEmpty() || vm.tradeFeedSummaryState != null) {
        item(key = "trade_feed") {
            TradeFeedSummaryCard(
                summary = vm.tradeFeedSummaryState,
                items = vm.tradeFeedState,
            )
        }
    }

    // 섹션 3: 시장 지표
    vm.liveIndicesState?.let { indices ->
        item(key = "market_indices") {
            MarketIndicesCard(
                indices = indices,
                regimeMode = vm.regimeModeState,
                temperature = vm.marketTemperatureState,
                snapshotDate = vm.snapshotDateState,
            )
        }
    }

    // ── 여기에 Phase 2 신규 섹션들이 들어갈 자리 ──
    // 섹션 3b: 업종 히트맵 (Task 6)
    // 섹션 4: 거래량 급등 (Task 7)
    // 섹션 5: 52주 신고가/신저가 (Task 8)

    // 섹션 6: 오늘의 추천
    (vm.premarketState as? UiState.Success)?.data?.let { report ->
        item(key = "recommendation") { RecommendationCard(report) }
    }

    // 섹션 7: 투자자 수급
    vm.investorFlowState?.let { flow ->
        item(key = "investor_flow") { InvestorFlowCard2(flow) }
    }

    // 섹션 8: 관심 종목
    if (vm.favoritesState.isNotEmpty()) {
        item(key = "favorites") {
            FavoritesSection(
                favorites = vm.favoritesState,
                quotes = vm.quoteState,
                miniCharts = vm.miniChartState,
            )
        }
    }

    // 섹션 9: 배당/권리락 일정 (Task 9에서 추가)

    // 섹션 10: 수익 캘린더
    vm.pnlCalendarState?.let { calendar ->
        item(key = "pnl_calendar") {
            // PnlCalendarCard는 HomeScreen에서 private이므로 간략 버전 사용
            HomeSectionCard(title = "수익 캘린더") {
                Text(
                    "이번 달 실현손익: ${formatKrw(calendar.monthTotalPnl)} (${calendar.monthTradeCount ?: 0}건)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }

    // 섹션 11: 주요 뉴스
    if (vm.newsClustersState.isNotEmpty()) {
        item(key = "news") { NewsSection(vm.newsClustersState) }
    }

    // 하단 여백
    item { Spacer(Modifier.height(16.dp)) }
}
```

- [ ] **Step 4: 에러 섹션 표시 추가**

각 섹션에 에러 처리를 위해 LazyColumn의 각 섹션 위 또는 아래에 에러 fallback 추가. 패턴:

```kotlin
vm.sectionErrorState["account"]?.let { err ->
    item(key = "account_error") { HomeSectionCard(title = "내 계좌", error = err) {} }
}
```

계좌, 성과, 수급, 뉴스, 지수, 피드, 캘린더에 대해 동일 패턴 적용.

- [ ] **Step 5: 빌드 확인 + Commit**

```bash
cd ~/AndroidStudioProjects/stock && ./gradlew assembleDebug 2>&1 | tail -5
git add app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt \
       app/src/main/java/com/example/stock/viewmodel/ViewModels.kt
git commit -m "feat(home2): add all existing sections (briefing, market, recommend, supply, favorites, calendar, news)"
```

---

## Phase 2: 서버 신규 API + 앱 연동

### Task 6: 업종별 등락 히트맵

**Files:**
- Create: `backend/app/sector_service.py`
- Modify: `backend/app/schemas.py` (SectorResponse)
- Modify: `backend/app/main.py` (/market/sectors 엔드포인트)
- Modify: `app/.../data/api/ApiModels.kt` (SectorItemDto)
- Modify: `app/.../data/api/StockApiService.kt` (getSectors)
- Modify: `app/.../data/repository/StockRepository.kt` (getSectors)
- Modify: `app/.../viewmodel/ViewModels.kt` (Home2ViewModel.loadSectors)
- Modify: `app/.../ui/screens/Home2Screen.kt` (SectorHeatmapCard)

- [ ] **Step 1: 서버 — sector_service.py 신규 생성**

새 파일 `backend/app/sector_service.py`:

```python
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from datetime import datetime

import httpx
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)

NAVER_SECTOR_URL = "https://finance.naver.com/sise/sise_group.naver?type=upjong"

@dataclass
class SectorTopStock:
    ticker: str
    name: str
    change_pct: float

@dataclass
class SectorItem:
    name: str
    change_pct: float
    volume: int = 0
    top_stocks: list[SectorTopStock] = field(default_factory=list)

# ── 캐시 ──
_sector_cache: dict[str, tuple[float, list[SectorItem]]] = {}
_MARKET_HOURS_TTL = 300    # 장중 5분
_AFTER_HOURS_TTL = 86400   # 장후 24시간

def _is_market_hours() -> bool:
    now = datetime.now()
    weekday = now.weekday()  # 0=Mon, 6=Sun
    if weekday >= 5:
        return False
    hour = now.hour
    return 9 <= hour < 16

def _cache_ttl() -> int:
    return _MARKET_HOURS_TTL if _is_market_hours() else _AFTER_HOURS_TTL


async def fetch_sectors() -> list[SectorItem]:
    """Naver Finance 업종 시세 HTML 파싱으로 업종별 등락 데이터 반환."""
    cache_key = "sectors"
    now = time.time()

    # 캐시 확인
    if cache_key in _sector_cache:
        cached_at, cached_data = _sector_cache[cache_key]
        if now - cached_at < _cache_ttl():
            return cached_data

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(
                NAVER_SECTOR_URL,
                headers={"User-Agent": "Mozilla/5.0"},
            )
            resp.raise_for_status()

        soup = BeautifulSoup(resp.text, "html.parser")
        table = soup.select_one("table.type_1")
        if not table:
            logger.warning("sector_service: table.type_1 not found")
            return _sector_cache.get(cache_key, (0, []))[1]

        items: list[SectorItem] = []
        rows = table.select("tr")
        for row in rows:
            cols = row.select("td")
            if len(cols) < 4:
                continue
            name_tag = cols[0].select_one("a")
            if not name_tag:
                continue

            name = name_tag.get_text(strip=True)
            change_pct_text = cols[1].get_text(strip=True).replace("%", "").replace("+", "")
            try:
                change_pct = float(change_pct_text)
            except ValueError:
                change_pct = 0.0

            # 등락 방향 확인
            if "navi_down" in str(row) or "down" in cols[1].get("class", [""]):
                change_pct = -abs(change_pct)

            volume_text = cols[2].get_text(strip=True).replace(",", "")
            try:
                volume = int(volume_text)
            except ValueError:
                volume = 0

            items.append(SectorItem(
                name=name,
                change_pct=change_pct,
                volume=volume,
            ))

        # 캐시 저장
        _sector_cache[cache_key] = (now, items)
        logger.info("sector_service: fetched %d sectors", len(items))
        return items

    except Exception as e:
        logger.error("sector_service error: %s", e)
        # 캐시 fallback
        return _sector_cache.get(cache_key, (0, []))[1]
```

- [ ] **Step 2: 서버 — Pydantic 모델 추가**

`backend/app/schemas.py`에 추가:

```python
class SectorTopStockItem(BaseModel):
    ticker: str = ""
    name: str = ""
    change_pct: float = 0.0

class SectorResponseItem(BaseModel):
    name: str
    change_pct: float
    volume: int = 0
    top_stocks: list[SectorTopStockItem] = Field(default_factory=list)

class SectorResponse(BaseModel):
    items: list[SectorResponseItem] = Field(default_factory=list)
    as_of: datetime | None = None
    source: str = "NAVER"
```

- [ ] **Step 3: 서버 — /market/sectors 엔드포인트 추가**

`backend/app/main.py`에 추가:

```python
from app.sector_service import fetch_sectors, SectorItem

@app.get("/market/sectors", response_model=SectorResponse)
async def get_market_sectors(ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    items = await fetch_sectors()
    return SectorResponse(
        items=[
            SectorResponseItem(
                name=it.name,
                change_pct=it.change_pct,
                volume=it.volume,
                top_stocks=[
                    SectorTopStockItem(ticker=s.ticker, name=s.name, change_pct=s.change_pct)
                    for s in it.top_stocks
                ],
            )
            for it in items
        ],
        as_of=datetime.now(),
    )
```

- [ ] **Step 4: 서버 배포 + API 테스트**

```bash
# rsync 전송
rsync -avz --exclude='__pycache__' --exclude='*.pyc' \
  -e "ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem" \
  backend/app/ ubuntu@16.176.148.77:/home/ubuntu/stock/backend/app/

# 재시작
ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem ubuntu@16.176.148.77 \
  'sudo systemctl restart stock-backend.service && sleep 2 && sudo systemctl is-active stock-backend.service'

# API 테스트
ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem ubuntu@16.176.148.77 \
  "TOKEN=\$(curl -s -X POST http://localhost:8000/auth/login \
    -H 'Content-Type: application/json' \
    -d '{\"user_code\":\"...\",\"password\":\"...\"}' \
    | python3 -c \"import sys,json; print(json.load(sys.stdin)['token'])\") && \
  curl -s http://localhost:8000/market/sectors \
    -H \"Authorization: Bearer \$TOKEN\" | python3 -m json.tool | head -30"
```

Expected: JSON 응답에 `items` 배열이 업종 리스트를 포함

- [ ] **Step 5: 앱 — SectorItemDto 추가**

`ApiModels.kt`에 추가:

```kotlin
@Serializable
data class SectorItemDto(
    val name: String? = null,
    val changePct: Double? = 0.0,
    val volume: Int? = 0,
)

@Serializable
data class SectorResponseDto(
    val items: List<SectorItemDto>? = emptyList(),
    val asOf: String? = null,
    val source: String? = null,
)
```

- [ ] **Step 6: 앱 — StockApiService + Repository에 추가**

`StockApiService.kt`에 추가:

```kotlin
@GET("market/sectors")
suspend fun getMarketSectors(): SectorResponseDto
```

`StockRepository.kt`에 추가:

```kotlin
suspend fun getMarketSectors(): SectorResponseDto = api.getMarketSectors()
```

- [ ] **Step 7: Home2ViewModel에 loadSectors() 추가**

`ViewModels.kt`의 `Home2ViewModel.load()` 내 `coroutineScope` 블록에 추가:

```kotlin
async { loadSectors() }
```

```kotlin
private suspend fun loadSectors() {
    try {
        val resp = repository.getMarketSectors()
        sectorHeatmapState = resp.items ?: emptyList()
        sectionErrorState = sectionErrorState - "sectors"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("sectors" to (e.message ?: "업종 로드 실패"))
    }
}
```

- [ ] **Step 8: SectorHeatmapCard Composable 작성**

`Home2Screen.kt`에 추가. 간략한 트리맵 — Compose Canvas로 구현:

```kotlin
@Composable
private fun SectorHeatmapCard(sectors: List<SectorItemDto>) {
    HomeSectionCard(title = "업종별 등락") {
        if (sectors.isEmpty()) {
            Text("업종 데이터 없음",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@HomeSectionCard
        }

        // 트리맵: 면적 = 거래량 비례, 색상 = 등락률
        val sorted = sectors
            .filter { (it.volume ?: 0) > 0 }
            .sortedByDescending { it.volume ?: 0 }
            .take(12)

        val totalVolume = sorted.sumOf { (it.volume ?: 0).toLong() }.toFloat()
            .coerceAtLeast(1f)

        // 간단한 그리드 대신 2열 레이아웃
        val chunked = sorted.chunked(2)
        chunked.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { sector ->
                    val weight = ((sector.volume ?: 0).toFloat() / totalVolume)
                        .coerceIn(0.1f, 0.9f)
                    val chg = sector.changePct ?: 0.0
                    val bgColor = when {
                        chg > 2.0 -> UpColor.copy(alpha = 0.8f)
                        chg > 0.5 -> UpColor.copy(alpha = 0.4f)
                        chg > 0.0 -> UpColor.copy(alpha = 0.2f)
                        chg < -2.0 -> DownColor.copy(alpha = 0.8f)
                        chg < -0.5 -> DownColor.copy(alpha = 0.4f)
                        chg < 0.0 -> DownColor.copy(alpha = 0.2f)
                        else -> Color.Gray.copy(alpha = 0.1f)
                    }

                    Surface(
                        modifier = Modifier
                            .weight(if (row.size == 2) weight.coerceAtLeast(0.3f)
                                else 1f)
                            .height(48.dp),
                        color = bgColor,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(4.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                sector.name ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White,
                            )
                            Text(
                                formatPct(chg),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace),
                                color = Color.White,
                            )
                        }
                    }
                }
                // 홀수 행 대응
                if (row.size == 1) {
                    Spacer(Modifier.weight(0.5f))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
```

- [ ] **Step 9: LazyColumn에 히트맵 섹션 추가**

시장 지표 아래, 추천 위에 추가:

```kotlin
// 섹션 3b: 업종 히트맵
if (vm.sectorHeatmapState.isNotEmpty()) {
    item(key = "sector_heatmap") {
        SectorHeatmapCard(vm.sectorHeatmapState)
    }
}
```

- [ ] **Step 10: 빌드 확인 + Commit**

```bash
cd ~/AndroidStudioProjects/stock && ./gradlew assembleDebug 2>&1 | tail -5
git add backend/app/sector_service.py backend/app/schemas.py backend/app/main.py \
       app/src/main/java/com/example/stock/data/api/ApiModels.kt \
       app/src/main/java/com/example/stock/data/api/StockApiService.kt \
       app/src/main/java/com/example/stock/data/repository/StockRepository.kt \
       app/src/main/java/com/example/stock/viewmodel/ViewModels.kt \
       app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt
git commit -m "feat(home2): add sector heatmap with Naver Finance parsing"
```

---

### Task 7: 거래량 급등 종목

**Files:**
- Modify: `backend/app/movers.py` (compute_volume_surge 함수)
- Modify: `backend/app/schemas.py` (VolumeSurgeResponse)
- Modify: `backend/app/main.py` (/market/volume-surge 엔드포인트)
- Modify: `app/.../data/api/ApiModels.kt` (VolumeSurgeItemDto)
- Modify: `app/.../data/api/StockApiService.kt`
- Modify: `app/.../data/repository/StockRepository.kt`
- Modify: `app/.../viewmodel/ViewModels.kt` (Home2ViewModel.loadVolumeSurge)
- Modify: `app/.../ui/screens/Home2Screen.kt` (VolumeSurgeCard)

- [ ] **Step 1: 서버 — movers.py에 compute_volume_surge() 추가**

`backend/app/movers.py` 파일 끝에 추가:

```python
# ── 거래량 급등 ──
_volume_surge_cache: dict[str, tuple[float, list[dict]]] = {}
_VOLUME_SURGE_TTL = 180  # 3분

async def compute_volume_surge(
    *,
    threshold: float = 5.0,
    limit: int = 20,
) -> list[dict]:
    """오늘 거래량 / 20일 평균 거래량 > threshold 인 종목 반환."""
    cache_key = f"vsurge:{threshold}:{limit}"
    now = time.time()

    if cache_key in _volume_surge_cache:
        cached_at, cached_data = _volume_surge_cache[cache_key]
        if now - cached_at < _VOLUME_SURGE_TTL:
            return cached_data

    try:
        from engine.data_sources import load_ohlcv
        from engine.universe import load_universe

        universe = load_universe()
        if universe.empty:
            return []

        today = datetime.now().date()
        start = today - timedelta(days=30)

        results = []
        # 코스피+코스닥 상위 500종목 (시총 기준)
        tickers = universe["Code"].tolist()[:500]

        for ticker in tickers:
            try:
                df = load_ohlcv(ticker, start, today)
                if df is None or len(df) < 5:
                    continue
                today_vol = df["Volume"].iloc[-1]
                avg_vol = df["Volume"].iloc[-21:-1].mean()  # 최근 20일 평균
                if avg_vol <= 0:
                    continue
                ratio = today_vol / avg_vol
                if ratio >= threshold:
                    row = universe[universe["Code"] == ticker].iloc[0]
                    results.append({
                        "ticker": ticker,
                        "name": row.get("Name", ticker),
                        "volume_ratio": round(ratio, 1),
                        "price": float(df["Close"].iloc[-1]),
                        "change_pct": round(
                            (df["Close"].iloc[-1] / df["Close"].iloc[-2] - 1) * 100, 2
                        ) if len(df) >= 2 else 0.0,
                    })
            except Exception:
                continue

        results.sort(key=lambda x: x["volume_ratio"], reverse=True)
        results = results[:limit]

        _volume_surge_cache[cache_key] = (now, results)
        logger.info("volume_surge: found %d stocks above %.1fx", len(results), threshold)
        return results

    except Exception as e:
        logger.error("volume_surge error: %s", e)
        return _volume_surge_cache.get(cache_key, (0, []))[1]
```

- [ ] **Step 2: 서버 — Pydantic 모델 + 엔드포인트**

`schemas.py`에 추가:

```python
class VolumeSurgeItem(BaseModel):
    ticker: str
    name: str
    volume_ratio: float
    price: float = 0.0
    change_pct: float = 0.0

class VolumeSurgeResponse(BaseModel):
    items: list[VolumeSurgeItem] = Field(default_factory=list)
    as_of: datetime | None = None
```

`main.py`에 추가:

```python
from app.movers import compute_volume_surge

@app.get("/market/volume-surge", response_model=VolumeSurgeResponse)
async def get_volume_surge(
    threshold: float = Query(5.0, ge=2.0, le=20.0),
    limit: int = Query(20, ge=1, le=50),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    items = await compute_volume_surge(threshold=threshold, limit=limit)
    return VolumeSurgeResponse(
        items=[VolumeSurgeItem(**it) for it in items],
        as_of=datetime.now(),
    )
```

- [ ] **Step 3: 앱 — DTO + API + Repository + ViewModel**

`ApiModels.kt`:

```kotlin
@Serializable
data class VolumeSurgeItemDto(
    val ticker: String? = null,
    val name: String? = null,
    val volumeRatio: Double? = 0.0,
    val price: Double? = 0.0,
    val changePct: Double? = 0.0,
)

@Serializable
data class VolumeSurgeResponseDto(
    val items: List<VolumeSurgeItemDto>? = emptyList(),
    val asOf: String? = null,
)
```

`StockApiService.kt`:

```kotlin
@GET("market/volume-surge")
suspend fun getVolumeSurge(
    @Query("threshold") threshold: Double = 5.0,
    @Query("limit") limit: Int = 10,
): VolumeSurgeResponseDto
```

`StockRepository.kt`:

```kotlin
suspend fun getVolumeSurge(threshold: Double = 5.0, limit: Int = 10): VolumeSurgeResponseDto =
    api.getVolumeSurge(threshold, limit)
```

`ViewModels.kt` — Home2ViewModel에 추가:

```kotlin
// load() 내 coroutineScope에 추가:
async { loadVolumeSurge() }

private suspend fun loadVolumeSurge() {
    try {
        val resp = repository.getVolumeSurge(threshold = 5.0, limit = 10)
        volumeSurgeState = resp.items ?: emptyList()
        sectionErrorState = sectionErrorState - "volume_surge"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("volume_surge" to (e.message ?: "거래량 급등 로드 실패"))
    }
}
```

- [ ] **Step 4: VolumeSurgeCard Composable 작성**

`Home2Screen.kt`에 추가:

```kotlin
@Composable
private fun VolumeSurgeCard(items: List<VolumeSurgeItemDto>) {
    HomeSectionCard(title = "거래량 급등") {
        if (items.isEmpty()) {
            Text("거래량 급등 종목 없음",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@HomeSectionCard
        }
        items.forEach { item ->
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        StockDetailActivity.open(context,
                            ticker = item.ticker ?: "", name = item.name ?: "",
                            origin = "home2_volume_surge")
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.name ?: item.ticker ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 거래량 배율 강조
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFFF9800).copy(alpha = 0.15f),
                    ) {
                        Text(
                            "${String.format("%.1f", item.volumeRatio ?: 0.0)}배",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace),
                            color = Color(0xFFFF9800),
                        )
                    }
                    // 등락률
                    Text(
                        formatPct(item.changePct),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = pnlColor(item.changePct),
                        ),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: LazyColumn에 추가 + 빌드 + Commit**

시장 지표/히트맵 아래, 추천 위에:

```kotlin
// 섹션 4: 거래량 급등
if (vm.volumeSurgeState.isNotEmpty()) {
    item(key = "volume_surge") {
        VolumeSurgeCard(vm.volumeSurgeState)
    }
}
```

```bash
cd ~/AndroidStudioProjects/stock && ./gradlew assembleDebug 2>&1 | tail -5
git add backend/app/movers.py backend/app/schemas.py backend/app/main.py \
       app/src/main/java/com/example/stock/data/api/ApiModels.kt \
       app/src/main/java/com/example/stock/data/api/StockApiService.kt \
       app/src/main/java/com/example/stock/data/repository/StockRepository.kt \
       app/src/main/java/com/example/stock/viewmodel/ViewModels.kt \
       app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt
git commit -m "feat(home2): add volume surge section with 5x threshold detection"
```

---

### Task 8: 52주 신고가/신저가

**Files:**
- Create: `backend/app/extremes_service.py`
- Modify: `backend/app/schemas.py` (WeekExtremesResponse)
- Modify: `backend/app/main.py` (/market/52week-extremes)
- Modify: `app/.../data/api/ApiModels.kt`
- Modify: `app/.../data/api/StockApiService.kt`
- Modify: `app/.../data/repository/StockRepository.kt`
- Modify: `app/.../viewmodel/ViewModels.kt`
- Modify: `app/.../ui/screens/Home2Screen.kt`

- [ ] **Step 1: 서버 — extremes_service.py 신규 생성**

새 파일 `backend/app/extremes_service.py`:

```python
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from datetime import datetime, timedelta

logger = logging.getLogger(__name__)

@dataclass
class ExtremeItem:
    ticker: str
    name: str
    price: float
    prev_extreme: float  # 이전 52주 고가 or 저가

_extremes_cache: dict[str, tuple[float, dict]] = {}
_EXTREMES_TTL = 1800  # 30분

async def compute_52week_extremes(tickers: list[str]) -> dict:
    """
    지정된 종목 범위에서 52주 신고가/신저가 탐지.
    반환: {"highs": [ExtremeItem, ...], "lows": [ExtremeItem, ...]}
    """
    cache_key = ",".join(sorted(tickers))
    now = time.time()

    if cache_key in _extremes_cache:
        cached_at, cached_data = _extremes_cache[cache_key]
        if now - cached_at < _EXTREMES_TTL:
            return cached_data

    try:
        from engine.data_sources import load_ohlcv
        from engine.universe import load_universe

        universe = load_universe()
        today = datetime.now().date()
        start = today - timedelta(days=365)  # 252거래일 ≈ 1년

        highs: list[dict] = []
        lows: list[dict] = []

        for ticker in tickers:
            try:
                df = load_ohlcv(ticker, start, today)
                if df is None or len(df) < 20:
                    continue

                current_price = float(df["Close"].iloc[-1])
                high_252 = float(df["High"].max())
                low_252 = float(df["Low"].min())

                # 이전 고가 (오늘 제외)
                prev_high = float(df["High"].iloc[:-1].max()) if len(df) > 1 else high_252
                prev_low = float(df["Low"].iloc[:-1].min()) if len(df) > 1 else low_252

                name = ticker
                match = universe[universe["Code"] == ticker]
                if not match.empty:
                    name = match.iloc[0].get("Name", ticker)

                if current_price >= prev_high:
                    highs.append({
                        "ticker": ticker,
                        "name": name,
                        "price": current_price,
                        "prev_extreme": prev_high,
                    })
                elif current_price <= prev_low:
                    lows.append({
                        "ticker": ticker,
                        "name": name,
                        "price": current_price,
                        "prev_extreme": prev_low,
                    })
            except Exception:
                continue

        result = {"highs": highs, "lows": lows}
        _extremes_cache[cache_key] = (now, result)
        logger.info("52week_extremes: %d highs, %d lows from %d tickers",
                     len(highs), len(lows), len(tickers))
        return result

    except Exception as e:
        logger.error("52week_extremes error: %s", e)
        return _extremes_cache.get(cache_key, (0, {"highs": [], "lows": []}))[1]
```

- [ ] **Step 2: 서버 — Pydantic 모델 + 엔드포인트**

`schemas.py`:

```python
class WeekExtremeItem(BaseModel):
    ticker: str
    name: str
    price: float
    prev_extreme: float

class WeekExtremesResponse(BaseModel):
    highs: list[WeekExtremeItem] = Field(default_factory=list)
    lows: list[WeekExtremeItem] = Field(default_factory=list)
    as_of: datetime | None = None
```

`main.py`:

```python
from app.extremes_service import compute_52week_extremes

@app.get("/market/52week-extremes", response_model=WeekExtremesResponse)
async def get_52week_extremes(
    tickers: str = Query(..., description="콤마 구분 종목코드"),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    ticker_list = [t.strip() for t in tickers.split(",") if t.strip()]
    if len(ticker_list) > 100:
        ticker_list = ticker_list[:100]
    result = await compute_52week_extremes(ticker_list)
    return WeekExtremesResponse(
        highs=[WeekExtremeItem(**h) for h in result.get("highs", [])],
        lows=[WeekExtremeItem(**l) for l in result.get("lows", [])],
        as_of=datetime.now(),
    )
```

- [ ] **Step 3: 앱 — DTO + API + Repository + ViewModel**

`ApiModels.kt`:

```kotlin
@Serializable
data class WeekExtremeItemDto(
    val ticker: String? = null,
    val name: String? = null,
    val price: Double? = 0.0,
    val prevExtreme: Double? = 0.0,
)

@Serializable
data class WeekExtremeResponseDto(
    val highs: List<WeekExtremeItemDto>? = emptyList(),
    val lows: List<WeekExtremeItemDto>? = emptyList(),
    val asOf: String? = null,
)
```

`StockApiService.kt`:

```kotlin
@GET("market/52week-extremes")
suspend fun get52WeekExtremes(
    @Query("tickers") tickers: String,
): WeekExtremeResponseDto
```

`StockRepository.kt`:

```kotlin
suspend fun get52WeekExtremes(tickers: String): WeekExtremeResponseDto =
    api.get52WeekExtremes(tickers)
```

`ViewModels.kt` — Home2ViewModel:

```kotlin
// load() 맨 마지막 (관심종목 로드 후 실행해야 ticker 목록이 있으므로)
// coroutineScope 블록 이후에 추가:
viewModelScope.launch { loadWeekExtremes() }

private suspend fun loadWeekExtremes() {
    try {
        val tickers = mutableSetOf<String>()
        // 보유 종목
        accountState?.positions?.mapNotNull { it.ticker }?.let { tickers.addAll(it) }
        // 관심 종목
        favoritesState.mapNotNull { it.ticker }.let { tickers.addAll(it) }
        // 추천 종목
        (premarketState as? UiState.Success)?.data?.let { report ->
            report.shortTermItems?.mapNotNull { it.ticker }?.let { tickers.addAll(it) }
            report.longTermItems?.mapNotNull { it.ticker }?.let { tickers.addAll(it) }
        }
        if (tickers.isEmpty()) return
        val resp = repository.get52WeekExtremes(tickers.joinToString(","))
        weekExtremeState = resp
        sectionErrorState = sectionErrorState - "week_extreme"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("week_extreme" to (e.message ?: "52주 고저 로드 실패"))
    }
}
```

- [ ] **Step 4: WeekExtremesCard Composable**

`Home2Screen.kt`:

```kotlin
@Composable
private fun WeekExtremesCard(data: WeekExtremeResponseDto) {
    val highs = data.highs ?: emptyList()
    val lows = data.lows ?: emptyList()

    if (highs.isEmpty() && lows.isEmpty()) return

    HomeSectionCard(title = "52주 신고가/신저가") {
        if (highs.isNotEmpty()) {
            Text("신고가", style = MaterialTheme.typography.labelMedium, color = UpColor)
            highs.forEach { item ->
                ExtremeRow(item, isHigh = true)
            }
        }
        if (lows.isNotEmpty()) {
            if (highs.isNotEmpty()) Spacer(Modifier.height(8.dp))
            Text("신저가", style = MaterialTheme.typography.labelMedium, color = DownColor)
            lows.forEach { item ->
                ExtremeRow(item, isHigh = false)
            }
        }
    }
}

@Composable
private fun ExtremeRow(item: WeekExtremeItemDto, isHigh: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                StockDetailActivity.open(context,
                    ticker = item.ticker ?: "", name = item.name ?: "",
                    origin = "home2_52week")
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            item.name ?: item.ticker ?: "",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatPrice(item.price),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = if (isHigh) UpColor else DownColor),
            )
            Text(
                "이전 ${if (isHigh) "고가" else "저가"}: ${formatPrice(item.prevExtreme)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 5: LazyColumn에 추가 + 빌드 + Commit**

거래량 급등 아래, 추천 위에:

```kotlin
// 섹션 5: 52주 신고가/신저가
vm.weekExtremeState?.let { data ->
    val hasData = (data.highs?.isNotEmpty() == true) || (data.lows?.isNotEmpty() == true)
    if (hasData) {
        item(key = "week_extremes") { WeekExtremesCard(data) }
    }
}
```

```bash
cd ~/AndroidStudioProjects/stock && ./gradlew assembleDebug 2>&1 | tail -5
git add backend/app/extremes_service.py backend/app/schemas.py backend/app/main.py \
       app/src/main/java/com/example/stock/data/api/ApiModels.kt \
       app/src/main/java/com/example/stock/data/api/StockApiService.kt \
       app/src/main/java/com/example/stock/data/repository/StockRepository.kt \
       app/src/main/java/com/example/stock/viewmodel/ViewModels.kt \
       app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt
git commit -m "feat(home2): add 52-week high/low section for owned+watched+recommended stocks"
```

---

### Task 9: 배당/권리락 일정

**Files:**
- Create: `backend/app/dividend_service.py`
- Modify: `backend/app/schemas.py` (DividendResponse)
- Modify: `backend/app/main.py` (/market/dividends)
- Modify: `app/.../data/api/ApiModels.kt`
- Modify: `app/.../data/api/StockApiService.kt`
- Modify: `app/.../data/repository/StockRepository.kt`
- Modify: `app/.../viewmodel/ViewModels.kt`
- Modify: `app/.../ui/screens/Home2Screen.kt`

- [ ] **Step 1: 서버 — dividend_service.py 신규 생성**

새 파일 `backend/app/dividend_service.py`:

```python
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from datetime import datetime, timedelta

logger = logging.getLogger(__name__)

@dataclass
class DividendItem:
    ticker: str
    name: str
    ex_date: str          # YYYY-MM-DD
    dividend_per_share: float
    dividend_yield: float  # %

_dividend_cache: dict[str, tuple[float, list[DividendItem]]] = {}
_DIVIDEND_TTL = 86400  # 1일

async def fetch_dividends(tickers: list[str]) -> list[DividendItem]:
    """pykrx를 사용하여 보유 종목의 배당 정보 조회."""
    cache_key = ",".join(sorted(tickers))
    now = time.time()

    if cache_key in _dividend_cache:
        cached_at, cached_data = _dividend_cache[cache_key]
        if now - cached_at < _DIVIDEND_TTL:
            return cached_data

    try:
        from pykrx import stock as krx_stock
        from engine.universe import load_universe

        universe = load_universe()
        today = datetime.now()
        results: list[DividendItem] = []

        for ticker in tickers:
            try:
                # pykrx로 배당 수익률 조회
                today_str = today.strftime("%Y%m%d")
                start_str = (today - timedelta(days=365)).strftime("%Y%m%d")

                df = krx_stock.get_market_cap_by_date(start_str, today_str, ticker)
                if df is None or df.empty:
                    continue

                # 배당수익률 컬럼 확인
                div_col = None
                for col in ["DIV", "배당수익률"]:
                    if col in df.columns:
                        div_col = col
                        break
                if div_col is None:
                    continue

                latest_div = df[div_col].iloc[-1]
                if latest_div <= 0:
                    continue

                name = ticker
                match = universe[universe["Code"] == ticker]
                if not match.empty:
                    name = match.iloc[0].get("Name", ticker)

                # 배당 정보가 있는 종목
                results.append(DividendItem(
                    ticker=ticker,
                    name=name,
                    ex_date="",  # pykrx에서 정확한 권리락일은 제공 안 함 — 향후 DART 보완
                    dividend_per_share=0.0,  # 향후 DART에서 채울 필드
                    dividend_yield=round(float(latest_div), 2),
                ))
            except Exception:
                continue

        results.sort(key=lambda x: x.dividend_yield, reverse=True)
        _dividend_cache[cache_key] = (now, results)
        logger.info("dividend_service: found %d dividend stocks from %d tickers",
                     len(results), len(tickers))
        return results

    except Exception as e:
        logger.error("dividend_service error: %s", e)
        return _dividend_cache.get(cache_key, (0, []))[1]
```

- [ ] **Step 2: 서버 — Pydantic 모델 + 엔드포인트**

`schemas.py`:

```python
class DividendItem(BaseModel):
    ticker: str
    name: str
    ex_date: str = ""
    dividend_per_share: float = 0.0
    dividend_yield: float = 0.0

class DividendResponse(BaseModel):
    items: list[DividendItem] = Field(default_factory=list)
    as_of: datetime | None = None
```

`main.py`:

```python
from app.dividend_service import fetch_dividends as fetch_dividend_data

@app.get("/market/dividends", response_model=DividendResponse)
async def get_dividends(
    tickers: str = Query(..., description="콤마 구분 종목코드"),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    ticker_list = [t.strip() for t in tickers.split(",") if t.strip()]
    if len(ticker_list) > 50:
        ticker_list = ticker_list[:50]
    items = await fetch_dividend_data(ticker_list)
    return DividendResponse(
        items=[DividendItem(
            ticker=it.ticker, name=it.name,
            ex_date=it.ex_date,
            dividend_per_share=it.dividend_per_share,
            dividend_yield=it.dividend_yield,
        ) for it in items],
        as_of=datetime.now(),
    )
```

> **주의**: `main.py`의 import 이름 충돌 방지 — `from app.dividend_service import fetch_dividends as fetch_dividend_data`

- [ ] **Step 3: 앱 — DTO + API + Repository + ViewModel**

`ApiModels.kt`:

```kotlin
@Serializable
data class DividendItemDto(
    val ticker: String? = null,
    val name: String? = null,
    val exDate: String? = null,
    val dividendPerShare: Double? = 0.0,
    val dividendYield: Double? = 0.0,
)

@Serializable
data class DividendResponseDto(
    val items: List<DividendItemDto>? = emptyList(),
    val asOf: String? = null,
)
```

`StockApiService.kt`:

```kotlin
@GET("market/dividends")
suspend fun getDividends(
    @Query("tickers") tickers: String,
): DividendResponseDto
```

`StockRepository.kt`:

```kotlin
suspend fun getDividends(tickers: String): DividendResponseDto = api.getDividends(tickers)
```

`ViewModels.kt` — Home2ViewModel:

```kotlin
// loadWeekExtremes() 와 같은 위치에 추가 (관심/보유 종목 로드 후 실행)
viewModelScope.launch { loadDividends() }

private suspend fun loadDividends() {
    try {
        val tickers = mutableSetOf<String>()
        accountState?.positions?.mapNotNull { it.ticker }?.let { tickers.addAll(it) }
        favoritesState.mapNotNull { it.ticker }.let { tickers.addAll(it) }
        if (tickers.isEmpty()) return
        val resp = repository.getDividends(tickers.joinToString(","))
        dividendState = resp.items ?: emptyList()
        sectionErrorState = sectionErrorState - "dividends"
    } catch (e: Exception) {
        sectionErrorState = sectionErrorState + ("dividends" to (e.message ?: "배당 로드 실패"))
    }
}
```

- [ ] **Step 4: DividendCard Composable**

`Home2Screen.kt`:

```kotlin
@Composable
private fun DividendCard(items: List<DividendItemDto>) {
    HomeSectionCard(title = "배당/권리락 일정") {
        if (items.isEmpty()) {
            Text("배당 정보 없음",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@HomeSectionCard
        }
        items.forEach { item ->
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        StockDetailActivity.open(context,
                            ticker = item.ticker ?: "", name = item.name ?: "",
                            origin = "home2_dividend")
                    }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    item.name ?: item.ticker ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "배당수익률 ${String.format("%.2f", item.dividendYield ?: 0.0)}%",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace),
                        color = Color(0xFF4CAF50),
                    )
                    if (!item.exDate.isNullOrEmpty()) {
                        Text(
                            "권리락: ${item.exDate}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: LazyColumn에 추가 + 빌드 + Commit**

관심종목 아래, 캘린더 위에:

```kotlin
// 섹션 9: 배당/권리락 일정
if (vm.dividendState.isNotEmpty()) {
    item(key = "dividends") { DividendCard(vm.dividendState) }
}
```

```bash
cd ~/AndroidStudioProjects/stock && ./gradlew assembleDebug 2>&1 | tail -5
git add backend/app/dividend_service.py backend/app/schemas.py backend/app/main.py \
       app/src/main/java/com/example/stock/data/api/ApiModels.kt \
       app/src/main/java/com/example/stock/data/api/StockApiService.kt \
       app/src/main/java/com/example/stock/data/repository/StockRepository.kt \
       app/src/main/java/com/example/stock/viewmodel/ViewModels.kt \
       app/src/main/java/com/example/stock/ui/screens/Home2Screen.kt
git commit -m "feat(home2): add dividend/ex-date section with pykrx data"
```

---

## Phase 3: 서버 배포 + 앱 배포 + 검증

### Task 10: 서버 배포 + API 검증

- [ ] **Step 1: Python 3.10 호환 확인**

모든 신규 서버 파일에 `from __future__ import annotations` 가 있는지 확인:

```bash
head -1 backend/app/sector_service.py backend/app/extremes_service.py backend/app/dividend_service.py
```

Expected: 모든 파일 첫 줄에 `from __future__ import annotations`

- [ ] **Step 2: 서버 rsync 전송**

```bash
# app/ 디렉토리
rsync -avz --exclude='__pycache__' --exclude='*.pyc' \
  --exclude='*.db' --exclude='*.db-*' \
  --exclude='.venv' --exclude='results/' --exclude='.localdata/' \
  -e "ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem" \
  backend/app/ ubuntu@16.176.148.77:/home/ubuntu/stock/backend/app/

# engine/ 디렉토리
rsync -avz --exclude='__pycache__' --exclude='*.pyc' \
  -e "ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem" \
  backend/engine/ ubuntu@16.176.148.77:/home/ubuntu/stock/backend/engine/
```

- [ ] **Step 3: 서버 재시작 + 활성 확인**

```bash
ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem ubuntu@16.176.148.77 \
  'sudo systemctl restart stock-backend.service && sleep 2 && sudo systemctl is-active stock-backend.service'
```

Expected: `active`

- [ ] **Step 4: 모든 신규 API 호출 검증**

서버에 SSH 접속 후 4개 엔드포인트 테스트:

```bash
ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem ubuntu@16.176.148.77 << 'REMOTE'
TOKEN=$(curl -s -X POST http://localhost:8000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"user_code":"...","password":"..."}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

echo "=== /market/sectors ==="
curl -s http://localhost:8000/market/sectors \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'items: {len(d.get(\"items\",[]))}'); print(json.dumps(d['items'][:2], ensure_ascii=False, indent=2))" 2>/dev/null || echo "FAIL"

echo "=== /market/volume-surge ==="
curl -s "http://localhost:8000/market/volume-surge?threshold=5&limit=5" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'items: {len(d.get(\"items\",[]))}'); print(json.dumps(d['items'][:2], ensure_ascii=False, indent=2))" 2>/dev/null || echo "FAIL"

echo "=== /market/52week-extremes ==="
curl -s "http://localhost:8000/market/52week-extremes?tickers=005930,035420,000660" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'highs: {len(d.get(\"highs\",[]))}, lows: {len(d.get(\"lows\",[]))}')" 2>/dev/null || echo "FAIL"

echo "=== /market/dividends ==="
curl -s "http://localhost:8000/market/dividends?tickers=005930,035420" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'items: {len(d.get(\"items\",[]))}')" 2>/dev/null || echo "FAIL"

echo "=== /autotrade/feed (summary 확인) ==="
curl -s "http://localhost:8000/autotrade/feed?limit=5" \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'summary: {d.get(\"summary\")}')" 2>/dev/null || echo "FAIL"
REMOTE
```

Expected: 각 API에서 정상 JSON 응답

- [ ] **Step 5: 앱 DTO vs 서버 응답 필드 대조**

각 응답의 JSON 키를 앱 DTO 필드와 비교:

| 서버 JSON 키 | 앱 DTO 필드 | 일치 |
|---|---|---|
| `items[].name` | `SectorItemDto.name` | ✓ |
| `items[].change_pct` | `SectorItemDto.changePct` | ✓ (snake→camel 자동) |
| `items[].volume` | `SectorItemDto.volume` | ✓ |
| `items[].volume_ratio` | `VolumeSurgeItemDto.volumeRatio` | ✓ |
| `highs[].prev_extreme` | `WeekExtremeItemDto.prevExtreme` | ✓ |
| `items[].dividend_yield` | `DividendItemDto.dividendYield` | ✓ |
| `summary.total_count` | `TradeFeedSummaryDto.totalCount` | ✓ |

- [ ] **Step 6: Commit**

```bash
git commit --allow-empty -m "chore: server API verification complete for home2 endpoints"
```

---

### Task 11: APK 빌드 + 배포

- [ ] **Step 1: APK 빌드 및 배포**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd ~/AndroidStudioProjects/stock
bash scripts/publish_apk_ec2.sh
```

Expected: `V3_NNN` 빌드 완료 메시지, EC2 업로드 성공

- [ ] **Step 2: 앱 설치 후 Home2 탭 확인**

수동 검증:
1. 앱 설치 후 하단 탭에 "홈2" 보이는지 확인
2. "홈2" 탭 터치 → 화면 로딩
3. 각 섹션 데이터 표시 확인:
   - [ ] 한줄 브리핑
   - [ ] 내 계좌 + 보유 종목 (접기/펼치기)
   - [ ] 자동매매 상태 ("진입 허용" / "진입 차단" 레이블)
   - [ ] 오늘 체결 요약
   - [ ] 시장 지표
   - [ ] 업종별 등락 히트맵
   - [ ] 거래량 급등 종목
   - [ ] 52주 신고가/신저가
   - [ ] 오늘의 추천
   - [ ] 투자자 수급
   - [ ] 관심 종목
   - [ ] 배당/권리락
   - [ ] 수익 캘린더
   - [ ] 주요 뉴스

- [ ] **Step 3: Commit + 히스토리 기록**

```bash
git commit --allow-empty -m "chore: APK V3_NNN deployed with Home2 tab"
```

히스토리 파일 작성:

```markdown
## 2026-03-27 HH:MM KST
### Home2 탭 구현 (안 C 대규모 확장)
#### 변경 내역
- Home2 탭 신규 추가 (기존 홈 유지, A/B 비교 가능)
- 12개 섹션: 브리핑, 계좌+보유종목, 자동매매(Gate 한글화), 체결요약, 시장지표, 업종히트맵, 거래량급등, 52주고저, 추천, 수급, 관심종목, 배당, 캘린더, 뉴스
- 서버 신규 API: /market/sectors, /market/volume-surge, /market/52week-extremes, /market/dividends
- trade-feed에 summary 필드 추가 (기존 호환)
#### 배포
- APK: V3_NNN 배포 완료
- 서버: rsync 전송 + restart 완료
#### 검증
- 4개 신규 API 응답 확인
- 앱 DTO ↔ 서버 JSON 필드 대조 완료
- Home2 탭 전체 섹션 데이터 표시 확인
---
```

---

## Appendix: Import 목록

### Home2Screen.kt 필요 import

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.stock.data.api.*
import com.example.stock.data.repository.StockRepository
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.CommonReportItemCard
import com.example.stock.ui.screens.StockDetailActivity
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.Home2ViewModel
import com.example.stock.viewmodel.UiState
import com.example.stock.viewmodel.InvestorFlowSummary
```

### ViewModels.kt 필요 추가 import

```kotlin
// Home2ViewModel에 필요한 import는 이미 ViewModels.kt 상단에 존재 (동일 패턴)
// 추가 필요: 없음
```
