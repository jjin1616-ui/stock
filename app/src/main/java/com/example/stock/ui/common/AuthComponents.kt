package com.example.stock.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stock.ui.theme.BluePrimary
import com.example.stock.ui.theme.MintAccent
import com.example.stock.ui.theme.TextMuted

@Composable
fun AuthHeader(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 앱 마크: 민트 도트 + 소형 트래킹 텍스트
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(MintAccent, CircleShape)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "제임스분석기",
                color = BluePrimary.copy(alpha = 0.45f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            title,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineLarge,
            color = BluePrimary,
            letterSpacing = (-0.5).sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            color = TextMuted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
fun AuthCard(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 폼 위 얇은 구분선 — 카드 박스 없이 공간 구조화
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFF23324F).copy(alpha = 0.08f))
        )
        Spacer(Modifier.height(28.dp))
        content()
    }
}
