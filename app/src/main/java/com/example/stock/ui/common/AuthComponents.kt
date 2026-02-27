package com.example.stock.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AuthHeader(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .background(Color(0xFF111827), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("제임스분석기", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineSmall)
        Text(subtitle, color = Color(0xFF64748B), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun AuthCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            content()
        }
    }
}
