package com.example.stock.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.stock.R
import com.example.stock.ServiceLocator
import com.example.stock.ui.common.TossBottomBar
import com.example.stock.ui.screens.AlertsScreen
import com.example.stock.ui.screens.AutoTradeScreen
import com.example.stock.ui.screens.AutoTrade2Screen
import com.example.stock.ui.screens.HomeScreen
import com.example.stock.ui.screens.HoldingsScreen
import com.example.stock.ui.screens.FavoritesScreen
import com.example.stock.ui.screens.LongtermScreen
import com.example.stock.ui.screens.Movers2Screen
import com.example.stock.ui.screens.NewsScreen
import com.example.stock.ui.screens.PapersScreen
import com.example.stock.ui.screens.PreMarket2Screen
import com.example.stock.ui.screens.PreMarketScreen
import com.example.stock.ui.screens.SettingsScreen
import com.example.stock.ui.screens.SupplyScreen
import com.example.stock.ui.screens.UsInsiderScreen

private const val BOTTOM_TAB_DRAG_GUIDE_TOKEN = 20260220

private data class TabAccessState(
    val loaded: Boolean = false,
    val daytradeAllowed: Boolean = true,
    val supplyAllowed: Boolean = true,
    val autotradeAllowed: Boolean = true,
    val holdingsAllowed: Boolean = true,
    val moversAllowed: Boolean = true,
    val usAllowed: Boolean = true,
    val newsAllowed: Boolean = true,
    val longtermAllowed: Boolean = true,
    val papersAllowed: Boolean = true,
    val eodAllowed: Boolean = true,
    val alertsAllowed: Boolean = true,
)

enum class AppTab(val route: String, val label: String, val iconRes: Int) {
    HOME("home", "홈", R.drawable.ic_tab_home),
    PREMARKET("premarket", "단타", R.drawable.ic_tab_lightning),
    PREMARKET2("premarket2", "단타2", R.drawable.ic_tab_lightning),
    SUPPLY("supply", "수급", R.drawable.ic_tab_supply),
    AUTOTRADE("autotrade", "자동", R.drawable.ic_tab_eod),
    AUTOTRADE2("autotrade2", "단타2", R.drawable.ic_tab_eod),
    HOLDINGS("holdings", "보유", R.drawable.ic_tab_holdings),
    MOVERS("movers", "급등", R.drawable.ic_tab_chart),
    US("us", "미장", R.drawable.ic_tab_us),
    NEWS("news", "뉴스", R.drawable.ic_tab_news),
    LONGTERM("longterm", "장투", R.drawable.ic_tab_star),
    PAPERS("papers", "논문", R.drawable.ic_tab_report),
    EOD("eod", "관심", R.drawable.ic_tab_check),
    ALERTS("alerts", "알림", R.drawable.ic_tab_alert),
    SETTINGS("settings", "설정", R.drawable.ic_tab_settings),
}

private fun normalizeLegacyRoute(route: String): String = when (route.trim()) {
    "movers2" -> "movers"
    else -> route.trim()
}

private fun resolveTabOrder(csv: String): List<AppTab> {
    val byRoute = AppTab.entries.associateBy { it.route }
    val seen = LinkedHashSet<String>()
    val ordered = mutableListOf<AppTab>()

    csv.split(",")
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { route ->
            val normalized = normalizeLegacyRoute(route)
            val tab = byRoute[normalized] ?: return@forEach
            if (seen.add(normalized)) {
                ordered += tab
            }
        }

    if (!seen.contains(AppTab.HOME.route)) {
        ordered.add(0, AppTab.HOME)
        seen.add(AppTab.HOME.route)
    }
    if (!seen.contains(AppTab.HOLDINGS.route)) {
        val insertAt = ordered.indexOfFirst { it.route == AppTab.AUTOTRADE.route }
        val targetIndex = if (insertAt >= 0) insertAt + 1 else ordered.size
        ordered.add(targetIndex, AppTab.HOLDINGS)
        seen.add(AppTab.HOLDINGS.route)
    }
    if (!seen.contains(AppTab.SUPPLY.route)) {
        val insertAt = ordered.indexOfFirst { it.route == AppTab.PREMARKET.route }
        val targetIndex = if (insertAt >= 0) insertAt + 1 else ordered.size
        ordered.add(targetIndex, AppTab.SUPPLY)
        seen.add(AppTab.SUPPLY.route)
    }

    AppTab.entries.forEach { tab ->
        if (seen.add(tab.route)) {
            ordered += tab
        }
    }
    return ordered
}

private fun toTabOrderCsv(order: List<AppTab>): String = order.joinToString(",") { it.route }

private fun isTabAllowed(tab: AppTab, access: TabAccessState): Boolean = when (tab) {
    AppTab.HOME -> true
    AppTab.PREMARKET -> access.daytradeAllowed
    AppTab.PREMARKET2 -> access.daytradeAllowed
    AppTab.SUPPLY -> access.supplyAllowed
    AppTab.AUTOTRADE -> access.autotradeAllowed
    AppTab.AUTOTRADE2 -> access.autotradeAllowed
    AppTab.HOLDINGS -> access.holdingsAllowed
    AppTab.MOVERS -> access.moversAllowed
    AppTab.US -> access.usAllowed
    AppTab.NEWS -> access.newsAllowed
    AppTab.LONGTERM -> access.longtermAllowed
    AppTab.PAPERS -> access.papersAllowed
    AppTab.EOD -> access.eodAllowed
    AppTab.ALERTS -> access.alertsAllowed
    AppTab.SETTINGS -> true
}

@Composable
private fun MenuBlockedScreen(tabLabel: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text("$tabLabel 메뉴는 관리자에 의해 비활성화되었습니다.")
    }
}

@Composable
fun AppNavigation(startRoute: String? = null, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val authRepo = remember(context) { ServiceLocator.authRepository(context) }
    val normalizedStart = startRoute?.let(::normalizeLegacyRoute)
    val initialTabs = remember {
        resolveTabOrder(repo.getSettings().bottomTabOrderCsv)
    }
    var orderedTabs by remember { mutableStateOf(initialTabs) }
    var tabAccess by remember { mutableStateOf(TabAccessState()) }
    var showBottomTabGuide by remember {
        mutableStateOf(repo.shouldShowBottomTabDragGuide(BOTTOM_TAB_DRAG_GUIDE_TOKEN))
    }
    var showBottomTabGuideHowTo by remember { mutableStateOf(false) }
    val visibleTabs = remember(orderedTabs, tabAccess) {
        orderedTabs.filter { isTabAllowed(it, tabAccess) }.ifEmpty { listOf(AppTab.SETTINGS) }
    }
    LaunchedEffect(Unit) {
        authRepo.getMyMenuPermissions()
            .onSuccess { res ->
                val permissions = res.permissions
                tabAccess = TabAccessState(
                    loaded = true,
                    daytradeAllowed = permissions?.menuDaytrade != false,
                    supplyAllowed = permissions?.menuSupply != false,
                    autotradeAllowed = permissions?.menuAutotrade != false,
                    holdingsAllowed = permissions?.menuHoldings != false,
                    moversAllowed = permissions?.menuMovers != false,
                    usAllowed = permissions?.menuUs != false,
                    newsAllowed = permissions?.menuNews != false,
                    longtermAllowed = permissions?.menuLongterm != false,
                    papersAllowed = permissions?.menuPapers != false,
                    eodAllowed = permissions?.menuEod != false,
                    alertsAllowed = permissions?.menuAlerts != false,
                )
            }
            .onFailure {
                tabAccess = tabAccess.copy(loaded = true)
            }
    }
    // If the user reordered bottom tabs, treat the first tab as the "home" tab on next launch.
    // Keep startDestination stable within this session to avoid rebuilding NavHost when settings change.
    val start = remember(normalizedStart, initialTabs) {
        AppTab.entries.firstOrNull { it.route == normalizedStart }?.route
            ?: initialTabs.firstOrNull()?.route
            ?: AppTab.PREMARKET.route
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val backStack by nav.currentBackStackEntryAsState()
            val route = backStack?.destination?.route
            TossBottomBar(
                currentRoute = route,
                tabs = visibleTabs,
                onSelect = { tab ->
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onReorder = { reordered ->
                    val hidden = orderedTabs.filterNot { isTabAllowed(it, tabAccess) }
                    orderedTabs = reordered + hidden
                    val s = repo.getSettings()
                    repo.saveSettings(
                        baseUrl = s.baseUrl,
                        lookbackDays = s.lookbackDays,
                        riskPreset = s.riskPreset,
                        themeCap = s.themeCap,
                        daytradeDisplayCount = s.daytradeDisplayCount,
                        longtermDisplayCount = s.longtermDisplayCount,
                        quoteRefreshSec = s.quoteRefreshSec,
                        daytradeVariant = s.daytradeVariant,
                        bottomTabOrderCsv = toTabOrderCsv(orderedTabs),
                        cardUiVersion = s.cardUiVersion,
                    )
                },
            )
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = modifier.padding(inner),
        ) {
            composable(AppTab.HOME.route) {
                HomeScreen(
                    onNavigateToNews = {
                        nav.navigate(AppTab.NEWS.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(AppTab.PREMARKET.route) {
                if (tabAccess.daytradeAllowed) {
                    PreMarketScreen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.PREMARKET.label)
                }
            }
            composable(AppTab.PREMARKET2.route) {
                if (tabAccess.daytradeAllowed) {
                    PreMarket2Screen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.PREMARKET2.label)
                }
            }
            composable(AppTab.SUPPLY.route) {
                if (tabAccess.supplyAllowed) {
                    SupplyScreen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.SUPPLY.label)
                }
            }
            composable(AppTab.AUTOTRADE.route) {
                if (tabAccess.autotradeAllowed) {
                    AutoTradeScreen(
                        onOpenSettings = {
                            nav.navigate(AppTab.SETTINGS.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.AUTOTRADE.label)
                }
            }
            composable(AppTab.AUTOTRADE2.route) {
                if (tabAccess.autotradeAllowed) {
                    AutoTrade2Screen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.AUTOTRADE2.label)
                }
            }
            composable(AppTab.HOLDINGS.route) {
                if (tabAccess.holdingsAllowed) {
                    HoldingsScreen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.HOLDINGS.label)
                }
            }
            composable(AppTab.MOVERS.route) {
                if (tabAccess.moversAllowed) {
                    Movers2Screen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.MOVERS.label)
                }
            }
            composable("movers2") {
                if (tabAccess.moversAllowed) {
                    Movers2Screen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.MOVERS.label)
                }
            }
            composable(AppTab.US.route) {
                if (tabAccess.usAllowed) {
                    UsInsiderScreen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.US.label)
                }
            }
            composable(AppTab.NEWS.route) {
                if (tabAccess.newsAllowed) {
                    NewsScreen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.NEWS.label)
                }
            }
            composable(AppTab.LONGTERM.route) {
                if (tabAccess.longtermAllowed) {
                    LongtermScreen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.LONGTERM.label)
                }
            }
            composable(AppTab.PAPERS.route) {
                if (tabAccess.papersAllowed) {
                    PapersScreen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.PAPERS.label)
                }
            }
            composable(AppTab.EOD.route) {
                if (tabAccess.eodAllowed) {
                    FavoritesScreen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.EOD.label)
                }
            }
            composable(AppTab.ALERTS.route) {
                if (tabAccess.alertsAllowed) {
                    AlertsScreen()
                } else {
                    MenuBlockedScreen(tabLabel = AppTab.ALERTS.label)
                }
            }
            composable(AppTab.SETTINGS.route) {
                SettingsScreen(
                    onAppSettingsSaved = { savedCsv ->
                        orderedTabs = resolveTabOrder(savedCsv)
                    },
                    onOpenAutoTrade = {
                        nav.navigate(AppTab.AUTOTRADE.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }

    if (showBottomTabGuide) {
        AlertDialog(
            onDismissRequest = {
                repo.markBottomTabDragGuideSeen(BOTTOM_TAB_DRAG_GUIDE_TOKEN)
                showBottomTabGuide = false
            },
            title = { Text("하단 메뉴 이동 안내") },
            text = { Text("하단 메뉴를 길게 누른 뒤 좌우로 드래그하면 순서를 바꿀 수 있어요.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        repo.markBottomTabDragGuideSeen(BOTTOM_TAB_DRAG_GUIDE_TOKEN)
                        showBottomTabGuide = false
                        showBottomTabGuideHowTo = true
                    }
                ) { Text("따라하기") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        repo.markBottomTabDragGuideSeen(BOTTOM_TAB_DRAG_GUIDE_TOKEN)
                        showBottomTabGuide = false
                    }
                ) { Text("닫기") }
            },
        )
    }

    if (showBottomTabGuideHowTo) {
        AlertDialog(
            onDismissRequest = { showBottomTabGuideHowTo = false },
            title = { Text("따라하기") },
            text = { Text("지금 하단 메뉴에서 원하는 탭을 길게 눌러 좌우로 이동해 보세요. 이동할 때 진동으로 피드백이 옵니다.") },
            confirmButton = {
                TextButton(onClick = { showBottomTabGuideHowTo = false }) { Text("확인") }
            },
        )
    }
}
