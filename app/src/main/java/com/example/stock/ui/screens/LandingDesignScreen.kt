package com.example.stock.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class PreviewSignal(
    val name: String,
    val tag: String,
    val price: String,
    val change: String,
    val positive: Boolean,
)

@Composable
fun LandingDesignScreen(
    onContinue: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { reveal = true }

    val heroAlpha by animateFloatAsState(
        targetValue = if (reveal) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "landingHeroAlpha",
    )
    val heroOffset by animateDpAsState(
        targetValue = if (reveal) 0.dp else 18.dp,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "landingHeroOffset",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (reveal) 1f else 0f,
        animationSpec = tween(durationMillis = 950, delayMillis = 120, easing = FastOutSlowInEasing),
        label = "landingContentAlpha",
    )

    val floatTransition = rememberInfiniteTransition(label = "landingBackgroundFloat")
    val orbShift by floatTransition.animateFloat(
        initialValue = -12f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbShift",
    )

    val previewSignals = remember {
        listOf(
            PreviewSignal("한화에어로스페이스", "방산 테마", "465,500", "+4.21%", true),
            PreviewSignal("엔비디아", "미장 내부자", "134.20", "+2.34%", true),
            PreviewSignal("포스코DX", "관심 추적", "52,900", "-1.08%", false),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF0D182A),
                        Color(0xFF122E4E),
                        Color(0xFF16556C),
                    ),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .offset(x = 206.dp, y = (-126f + orbShift).dp)
                .width(252.dp)
                .height(252.dp)
                .background(Color(0xFF7DE3C4).copy(alpha = 0.22f), CircleShape),
        )
        Box(
            modifier = Modifier
                .offset(x = (-68).dp, y = (440f - orbShift).dp)
                .width(210.dp)
                .height(210.dp)
                .background(Color(0xFF9BC8FF).copy(alpha = 0.20f), CircleShape),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(heroAlpha)
                    .offset(y = heroOffset),
            ) {
                Surface(
                    color = Color.White.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
                ) {
                    Text(
                        text = "디자인 미리보기",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "앱 첫 화면\n디자인 시안",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    lineHeight = MaterialTheme.typography.headlineMedium.lineHeight,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "인증 전에 브랜드 무드와 핵심 기능을 먼저 보여주는 랜딩 화면입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PreviewPill(text = "후보 종목 미리보기")
                    PreviewPill(text = "실시간/캐시 품질 라벨")
                    PreviewPill(text = "자동매매 리스크 가드")
                }
            }

            Spacer(Modifier.height(24.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha),
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = 0.90f),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "오늘의 시안 프리뷰",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D2A42),
                    )
                    Spacer(Modifier.height(12.dp))
                    previewSignals.forEach { signal ->
                        PreviewSignalRow(signal = signal)
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "실제 데이터 연결 전 데모 텍스트입니다.",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF6B7892),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(contentAlpha),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0EBE93),
                        contentColor = Color(0xFF0C1E31),
                    ),
                ) {
                    Text(
                        text = "인증 화면으로 이동",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "다음 단계에서 로그인/최초로그인을 진행합니다.",
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.86f),
                )
            }
        }
    }
}

@Composable
private fun PreviewPill(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun PreviewSignalRow(signal: PreviewSignal) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF6FAFF),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = signal.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1D2A42),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${signal.tag}  •  ${signal.price}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF6B7892),
                )
            }
            Surface(
                color = if (signal.positive) Color(0xFF0EBE93).copy(alpha = 0.16f) else Color(0xFFE25A5A).copy(alpha = 0.15f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = signal.change,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (signal.positive) Color(0xFF0E7E66) else Color(0xFFB33D46),
                )
            }
        }
    }
}
