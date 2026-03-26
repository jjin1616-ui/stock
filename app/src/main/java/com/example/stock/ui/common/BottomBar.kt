package com.example.stock.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.stock.navigation.AppTab
import kotlin.math.abs

@Composable
fun TossBottomBar(
    currentRoute: String?,
    tabs: List<AppTab>,
    onSelect: (AppTab) -> Unit,
    onReorder: ((List<AppTab>) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val swapThresholdPx = remember(density) { with(density) { 40.dp.toPx() } }
    var dragRoute by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    fun reorder(list: List<AppTab>, from: Int, to: Int): List<AppTab> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val mutable = list.toMutableList()
        val picked = mutable.removeAt(from)
        mutable.add(to, picked)
        return mutable.toList()
    }

    Surface(
        modifier = Modifier.navigationBarsPadding(),
        shadowElevation = 10.dp,
        color = Color.White,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
    ) {
        val scroll = rememberScrollState()

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        key(tab.route) {
                            val selected = currentRoute == tab.route
                            val dragging = dragRoute == tab.route
                            val bg = if (selected) Color(0xFFEFF3FF) else Color.Transparent
                            val fg = if (selected) Color(0xFF2F5BEA) else Color(0xFF9AA5B1)
                            val liftPx by animateFloatAsState(
                                targetValue = if (dragging) with(density) { 10.dp.toPx() } else 0f,
                                label = "bottomTabLift",
                            )
                            val scale by animateFloatAsState(
                                targetValue = if (dragging) 1.03f else 1f,
                                label = "bottomTabScale",
                            )
                            val dragModifier = if (onReorder != null) {
                                Modifier.pointerInput(tabs, onReorder) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            dragRoute = tab.route
                                            dragOffsetPx = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragCancel = {
                                            dragRoute = null
                                            dragOffsetPx = 0f
                                        },
                                        onDragEnd = {
                                            dragRoute = null
                                            dragOffsetPx = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetPx += dragAmount.x
                                            if (abs(dragOffsetPx) >= swapThresholdPx) {
                                                val dir = if (dragOffsetPx > 0f) 1 else -1
                                                val target = (index + dir).coerceIn(0, tabs.lastIndex)
                                                if (target != index) {
                                                    onReorder(reorder(tabs, index, target))
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                                dragOffsetPx = 0f
                                            }
                                        }
                                    )
                                }
                            } else {
                                Modifier
                            }

                            Column(
                                modifier = Modifier
                                    .width(64.dp)
                                    .graphicsLayer {
                                        translationX = if (dragging) dragOffsetPx else 0f
                                        translationY = -liftPx
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .then(dragModifier)
                                    .clickable { onSelect(tab) }
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    modifier = Modifier.size(30.dp),
                                    color = bg,
                                    shape = CircleShape
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(tab.iconRes),
                                            contentDescription = tab.label,
                                            tint = fg,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = tab.label,
                                    color = fg,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
