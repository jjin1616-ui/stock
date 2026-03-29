package com.example.stock.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.stock.R

private data class V2Feature(
    val title: String,
    val subtitle: String,
    val accent: Color,
)

@Composable
fun LandingDesignScreenV2(
    onContinue: () -> Unit,
) {
    var enter by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { enter = true }

    val heroAlpha by animateFloatAsState(
        targetValue = if (enter) 1f else 0f,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "v2HeroAlpha",
    )
    val cardsAlpha by animateFloatAsState(
        targetValue = if (enter) 1f else 0f,
        animationSpec = tween(durationMillis = 900, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "v2CardsAlpha",
    )
    val statPct by animateIntAsState(
        targetValue = if (enter) 87 else 0,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "v2StatPct",
    )

    val features = remember {
        listOf(
            V2Feature("급등2 세션 스캔", "전용 세션 품질 라벨 포함", Color(0xFF0EA5E9)),
            V2Feature("미장 내부자 공시", "신호등급과 이유 동시 표시", Color(0xFF0EA97A)),
            V2Feature("자동매매 점검", "paper/demo/prod 환경 혼선 경고", Color(0xFFF97316)),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFF7F7F2),
                        Color(0xFFEFE9DE),
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
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(heroAlpha),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF0F172A),
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = "앱 아이콘",
                            modifier = Modifier
                                .size(46.dp)
                                .padding(7.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "제임스분석기",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF0F172A),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(cardsAlpha),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF111827),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "오늘의 준비도",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFCBD5E1),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$statPct%",
                            style = MaterialTheme.typography.displaySmall,
                            color = Color(0xFFF8FAFC),
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(99.dp),
                            color = Color(0xFF0EA97A).copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, Color(0xFF0EA97A).copy(alpha = 0.6f)),
                        ) {
                            Text(
                                text = "시장 열림 전 점검 완료",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF86EFAC),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(cardsAlpha),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                features.forEach { feature ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFFFFFCF7),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .height(10.dp)
                                    .fillMaxWidth(0.02f)
                                    .clip(CircleShape)
                                    .background(feature.accent),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = feature.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                )
                                Text(
                                    text = feature.subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF64748B),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .alpha(cardsAlpha),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0F172A),
                    contentColor = Color(0xFFF8FAFC),
                ),
            ) {
                Text(
                    text = "인증 시작",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
