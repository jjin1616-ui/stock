package com.example.stock.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.stock.data.api.FavoriteItemDto
import com.example.stock.data.repository.StockRepository
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class FavoritesController(
    private val repository: StockRepository,
    private val scope: CoroutineScope,
    private val snackbarHostState: SnackbarHostState? = null,
) {
    var favoritesByTicker: Map<String, FavoriteItemDto> by mutableStateOf(emptyMap())
        private set

    val favoriteTickers: Set<String>
        get() = favoritesByTicker.keys

    fun refresh() {
        scope.launch {
            repository.getFavorites()
                .onSuccess { list ->
                    favoritesByTicker = list
                        .mapNotNull { f ->
                            val t = f.ticker.orEmpty()
                            if (t.isBlank()) null else t to f
                        }
                        .toMap()
                }
                .onFailure { e ->
                    showMessage("관심 목록 로드 실패: ${e.message ?: "오류"}")
                }
        }
    }

    fun setFavorite(item: CommonReportItemUi, sourceTab: String, desiredFavorite: Boolean) {
        val ticker = item.ticker.orEmpty()
        if (ticker.isBlank()) {
            showMessage("티커 정보가 없어 관심 등록을 할 수 없습니다.")
            return
        }

        if (!desiredFavorite) {
            val prev = favoritesByTicker[ticker]
            favoritesByTicker = favoritesByTicker - ticker
            scope.launch {
                repository.deleteFavorite(ticker)
                    .onFailure { e ->
                        if (prev != null) {
                            favoritesByTicker = favoritesByTicker + (ticker to prev)
                        }
                        showMessage("관심 해제 실패: ${e.message ?: "오류"}")
                    }
            }
            return
        }

        val baseline = (item.quote?.price ?: item.fallbackPrice ?: 0.0)
        if (baseline <= 0.0) {
            showMessage("현재가가 없어 관심 등록을 할 수 없습니다.")
            return
        }

        val nowIso = OffsetDateTime.now(ZoneOffset.UTC).toString()
        val optimistic = FavoriteItemDto(
            ticker = ticker,
            name = item.name ?: item.title,
            baselinePrice = baseline,
            favoritedAt = nowIso,
            sourceTab = sourceTab,
            currentPrice = item.quote?.price,
            changeSinceFavoritePct = 0.0,
            asOf = item.quote?.asOf,
            source = item.quote?.source,
            isLive = item.quote?.isLive,
        )
        favoritesByTicker = favoritesByTicker + (ticker to optimistic)

        scope.launch {
            repository.upsertFavorite(
                ticker = ticker,
                name = item.name ?: item.title,
                baselinePrice = baseline,
                sourceTab = sourceTab,
                favoritedAt = nowIso,
            )
                .onSuccess { saved ->
                    favoritesByTicker = favoritesByTicker + (ticker to saved)
                }
                .onFailure { e ->
                    favoritesByTicker = favoritesByTicker - ticker
                    showMessage("관심 등록 실패: ${e.message ?: "오류"}")
                }
        }
    }

    private fun showMessage(text: String) {
        val host = snackbarHostState ?: return
        scope.launch {
            host.showSnackbar(text)
        }
    }
}

@Composable
fun rememberFavoritesController(
    repository: StockRepository,
    snackbarHostState: SnackbarHostState? = null,
): FavoritesController {
    val scope = rememberCoroutineScope()
    val controller = remember(repository, snackbarHostState) {
        FavoritesController(repository, scope, snackbarHostState)
    }
    LaunchedEffect(controller) {
        controller.refresh()
    }
    return controller
}
