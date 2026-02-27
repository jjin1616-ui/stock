package com.example.stock.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class V3Metric(
    val label: String,
    val value: String,
    val positive: Boolean,
)

private data class V3Tile(
    val title: String,
    val subtitle: String,
    val color: Color,
)

@Composable
fun LandingDesignScreenV3(
    onContinue: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { reveal = true }

    val heroAlpha by animateFloatAsState(
        targetValue = if (reveal) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "v3HeroAlpha",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (reveal) 1f else 0f,
        animationSpec = tween(durationMillis = 920, delayMillis = 120, easing = FastOutSlowInEasing),
        label = "v3ContentAlpha",
    )

    val metrics = remember {
        listOf(
            V3Metric("KOSPI 무버", "+3.9%", true),
            V3Metric("US Form4", "+2.1%", true),
            V3Metric("관심 성과", "-0.8%", false),
        )
    }
    val tiles = remember {
        listOf(
            V3Tile("세션 기반 급등", "장전/정규/시간외 라벨링", Color(0xFFFFE6A6)),
            V3Tile("미장 신호강도", "신호등급과 이유", Color(0xFFFFD4C7)),
            V3Tile("자동매매 안전장치", "일손실/주문수/예산 가드", Color(0xFFFFC9B3)),
            V3Tile("공통 컴포넌트", "탭 간 스타일 일관 유지", Color(0xFFFFF0C9)),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFFF6E8),
                        Color(0xFFFFE9D3),
                        Color(0xFFFFDFC3),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(heroAlpha),
            ) {
                Surface(
                    shape = RoundedCornerShape(99.dp),
                    color = Color(0xFF8A2D1C),
                ) {
                    Text(
                        text = "THIRD CONCEPT",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color(0xFFFFF5EA),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "세번째 랜딩 시안",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF3E1A10),
                    lineHeight = MaterialTheme.typography.displaySmall.lineHeight,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "웜톤 포스터 무드와 데이터 타일을 합친 시작 화면입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF6A3A2A),
                )
            }

            Spacer(Modifier.height(14.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF3E1A10),
                border = BorderStroke(1.dp, Color(0xFF632C1E)),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "오늘의 테이프",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFFFDCC3),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        metrics.forEach { metric ->
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                color = if (metric.positive) Color(0xFF0D7A5E) else Color(0xFF9A2A3B),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = metric.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFEFF7FF),
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = metric.value,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (row in 0..1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (col in 0..1) {
                            val tile = tiles[row * 2 + col]
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(18.dp),
                                color = tile.color,
                                border = BorderStroke(1.dp, Color(0xFFFFC79B)),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = tile.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color(0xFF4A2418),
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = tile.subtitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF6A3A2A),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFFFF8EF),
                border = BorderStroke(1.dp, Color(0xFFFFD3B2)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "이번 시안 포인트",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF7A3A2B),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "정보 밀도는 높이고 첫인상은 따뜻하게",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF8F4E39),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .alpha(contentAlpha),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8A2D1C),
                    contentColor = Color(0xFFFFF5EA),
                ),
            ) {
                Text(
                    text = "인증으로 이동",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
