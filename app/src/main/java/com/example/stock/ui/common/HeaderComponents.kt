package com.example.stock.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stock.data.repository.UiSource

data class GlossaryItem(
    val term: String,
    val description: String,
)

@Composable
fun ReportHeaderCard(
    title: String,
    statusMessage: String,
    updatedAt: String?,
    source: UiSource,
    glossaryDialogTitle: String = "용어 설명집",
    glossaryItems: List<GlossaryItem> = emptyList(),
) {
    var glossaryOpen by remember(glossaryDialogTitle, glossaryItems) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            style = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.2.sp),
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (glossaryItems.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .clickable { glossaryOpen = true }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "용어설명",
                                    color = Color(0xFF2F5BEA),
                                    style = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(statusMessage, color = Color(0xFF7B8794), style = MaterialTheme.typography.bodySmall)
                    val ts = fmtUpdatedAt(updatedAt)
                    if (ts != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("업데이트 $ts", color = Color(0xFF9AA5B1), style = MaterialTheme.typography.labelSmall)
                    }
                }
                StatusPill(source)
            }
        }
    }
    if (glossaryOpen && glossaryItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { glossaryOpen = false },
            title = { Text(glossaryDialogTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    glossaryItems.forEach { item ->
                        Text(
                            text = item.term,
                            color = Color(0xFF111827),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.description,
                            color = Color(0xFF475569),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { glossaryOpen = false }) {
                    Text("닫기")
                }
            },
        )
    }
}

@Composable
private fun StatusPill(source: UiSource) {
    val (label, bg, fg) = when (source) {
        UiSource.LIVE -> Triple("실시간", Color(0xFFEAF8EF), Color(0xFF1F7A3E))
        UiSource.CACHE -> Triple("캐시", Color(0xFFEFF3FF), Color(0xFF2F5BEA))
        UiSource.FALLBACK -> Triple("대체", Color(0xFFFFF4E8), Color(0xFFB45309))
    }
    Box(
        modifier = Modifier
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(label, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
}
