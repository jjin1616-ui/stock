package com.example.stock.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val COMPACT_CORNER_RADIUS = 11.dp
private val COMPACT_MIN_HEIGHT = 42.dp
private val COMPACT_H_PADDING = 10.dp
private val COMPACT_V_PADDING = 6.dp
private val COMPACT_FONT_SIZE = 13.sp
private val COMPACT_ITEM_GAP = 6.dp
private val COMPACT_ICON_SIZE = 19.dp

data class SelectOptionUi(val id: String, val label: String)

@Composable
fun CommonSelect(
    label: String,
    options: List<SelectOptionUi>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CommonOutlinedSelect(
        label = label,
        options = options,
        selectedId = selectedId,
        onSelect = onSelect,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommonOutlinedSelect(
    label: String,
    options: List<SelectOptionUi>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = options.firstOrNull { it.id == selectedId } ?: options.firstOrNull() ?: SelectOptionUi("", "")
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, fontSize = 11.sp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = COMPACT_MIN_HEIGHT)
                .menuAnchor(),
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                focusedBorderColor = Color(0xFFCBD5E1),
                unfocusedBorderColor = Color(0xFFCBD5E1),
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
            ),
            shape = RoundedCornerShape(COMPACT_CORNER_RADIUS),
            textStyle = TextStyle(fontSize = COMPACT_FONT_SIZE),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = COMPACT_FONT_SIZE) },
                    onClick = {
                        onSelect(opt.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun CommonPillMenu(
    label: String,
    options: List<SelectOptionUi>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = options.firstOrNull { it.id == selectedId } ?: options.firstOrNull() ?: SelectOptionUi("", "")
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEEF1F4)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.clickable { expanded = !expanded },
            shape = RoundedCornerShape(999.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = COMPACT_H_PADDING, vertical = COMPACT_V_PADDING),
            ) {
                Text(
                    "$label: ${selected.label}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF111827),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = COMPACT_FONT_SIZE,
                )
                Spacer(Modifier.padding(horizontal = 1.dp))
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(COMPACT_ICON_SIZE),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = COMPACT_FONT_SIZE) },
                    onClick = {
                        onSelect(opt.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun CommonFilterMenu(
    label: String,
    options: List<SelectOptionUi>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = options.firstOrNull { it.id == selectedId } ?: options.firstOrNull() ?: SelectOptionUi("", "")
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedCard(
            colors = CardDefaults.outlinedCardColors(containerColor = Color.White),
            shape = RoundedCornerShape(COMPACT_CORNER_RADIUS),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = COMPACT_MIN_HEIGHT)
                .clickable { expanded = !expanded },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = COMPACT_FONT_SIZE,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.45f),
                )
                Row(
                    modifier = Modifier.weight(0.55f),
                ) {
                    Text(
                        selected.label,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = COMPACT_FONT_SIZE,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(COMPACT_ICON_SIZE),
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = COMPACT_FONT_SIZE) },
                    onClick = {
                        onSelect(opt.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun CommonSortThemeBar(
    sortOptions: List<SelectOptionUi>,
    selectedSortId: String,
    onSortChange: (String) -> Unit,
    themeOptions: List<SelectOptionUi>?,
    selectedThemeId: String,
    onThemeChange: (String) -> Unit,
    themeLabel: String = "테마",
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val hasPricePair = sortOptions.any { it.id == SortOptions.PRICE_ASC } && sortOptions.any { it.id == SortOptions.PRICE_DESC }
    val hasChangePair = sortOptions.any { it.id == SortOptions.CHANGE_ASC } && sortOptions.any { it.id == SortOptions.CHANGE_DESC }
    val hasNamePair = sortOptions.any { it.id == SortOptions.NAME_ASC } && sortOptions.any { it.id == SortOptions.NAME_DESC }
    val aiSortOptionById = sortOptions.associateBy { it.id }
    val aiOrder = listOf(
        SortOptions.AI_STRONG,
        SortOptions.AI_BUY,
        SortOptions.AI_WATCH,
        SortOptions.AI_CAUTION,
        SortOptions.AI_AVOID,
    )
    val aiMenuOptions = aiOrder.mapNotNull { id -> aiSortOptionById[id] }
    val hasAiSelect = aiMenuOptions.size == aiOrder.size

    data class SortChipModel(
        val id: String,
        val label: String,
        val selected: Boolean,
        val onClick: () -> Unit,
    )

    val chips = buildList {
        var priceToggleAdded = false
        var changeToggleAdded = false
        var nameToggleAdded = false
        for (opt in sortOptions) {
            if (hasPricePair && (opt.id == SortOptions.PRICE_ASC || opt.id == SortOptions.PRICE_DESC)) {
                if (priceToggleAdded) continue
                priceToggleAdded = true
                val selected = selectedSortId == SortOptions.PRICE_ASC || selectedSortId == SortOptions.PRICE_DESC
                val currentLabel = when (selectedSortId) {
                    SortOptions.PRICE_ASC -> "가격↑"
                    SortOptions.PRICE_DESC -> "가격↓"
                    else -> "가격↓"
                }
                add(
                    SortChipModel(
                        id = "price_toggle",
                        label = currentLabel,
                        selected = selected,
                        onClick = {
                            val next = when (selectedSortId) {
                                SortOptions.PRICE_ASC -> SortOptions.PRICE_DESC
                                SortOptions.PRICE_DESC -> SortOptions.PRICE_ASC
                                else -> SortOptions.PRICE_DESC
                            }
                            onSortChange(next)
                        },
                    )
                )
                continue
            }
            if (hasChangePair && (opt.id == SortOptions.CHANGE_ASC || opt.id == SortOptions.CHANGE_DESC)) {
                if (changeToggleAdded) continue
                changeToggleAdded = true
                val selected = selectedSortId == SortOptions.CHANGE_ASC || selectedSortId == SortOptions.CHANGE_DESC
                val currentLabel = when (selectedSortId) {
                    SortOptions.CHANGE_ASC -> "등락↑"
                    SortOptions.CHANGE_DESC -> "등락↓"
                    else -> "등락↓"
                }
                add(
                    SortChipModel(
                        id = "change_toggle",
                        label = currentLabel,
                        selected = selected,
                        onClick = {
                            val next = when (selectedSortId) {
                                SortOptions.CHANGE_ASC -> SortOptions.CHANGE_DESC
                                SortOptions.CHANGE_DESC -> SortOptions.CHANGE_ASC
                                else -> SortOptions.CHANGE_DESC
                            }
                            onSortChange(next)
                        },
                    )
                )
                continue
            }
            if (hasNamePair && (opt.id == SortOptions.NAME_ASC || opt.id == SortOptions.NAME_DESC)) {
                if (nameToggleAdded) continue
                nameToggleAdded = true
                val selected = selectedSortId == SortOptions.NAME_ASC || selectedSortId == SortOptions.NAME_DESC
                val currentLabel = when (selectedSortId) {
                    SortOptions.NAME_ASC -> "이름↑"
                    SortOptions.NAME_DESC -> "이름↓"
                    else -> "이름↑"
                }
                add(
                    SortChipModel(
                        id = "name_toggle",
                        label = currentLabel,
                        selected = selected,
                        onClick = {
                            val next = when (selectedSortId) {
                                SortOptions.NAME_ASC -> SortOptions.NAME_DESC
                                SortOptions.NAME_DESC -> SortOptions.NAME_ASC
                                else -> SortOptions.NAME_ASC
                            }
                            onSortChange(next)
                        },
                    )
                )
                continue
            }
            if (hasAiSelect && aiOrder.contains(opt.id)) {
                continue
            }
            add(
                SortChipModel(
                    id = opt.id,
                    label = opt.label,
                    selected = opt.id == selectedSortId,
                    onClick = { onSortChange(opt.id) },
                )
            )
        }
    }

    @Composable
    fun SortChip(opt: SortChipModel) {
        val selected = opt.selected
        val bg = if (selected) Color(0xFF111827) else Color(0xFFEEF1F4)
        val fg = if (selected) Color.White else Color(0xFF111827)
        Card(
            colors = CardDefaults.cardColors(containerColor = bg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.clickable { opt.onClick() },
        ) {
            Text(
                opt.label,
                color = fg,
                fontWeight = FontWeight.SemiBold,
                fontSize = COMPACT_FONT_SIZE,
                modifier = Modifier.padding(horizontal = COMPACT_H_PADDING, vertical = COMPACT_V_PADDING),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(COMPACT_ITEM_GAP),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        chips.forEach { SortChip(it) }
        if (hasAiSelect) {
            val selectedAiId = if (aiOrder.contains(selectedSortId)) selectedSortId else SortOptions.AI_STRONG
            CommonPillMenu(
                label = "인공지능",
                options = aiMenuOptions,
                selectedId = selectedAiId,
                onSelect = { onSortChange(it) },
            )
        }
        if (themeOptions != null && themeOptions.isNotEmpty()) {
            CommonPillMenu(
                label = themeLabel,
                options = themeOptions,
                selectedId = selectedThemeId,
                onSelect = onThemeChange,
            )
        }
    }
}
