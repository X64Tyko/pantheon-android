package com.pantheon.android.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pantheon.android.api.dto.LibraryWithSource
import com.pantheon.android.filter.FIELD_DEFS
import com.pantheon.android.filter.FilterItem
import com.pantheon.android.filter.FilterRule
import com.pantheon.android.filter.FilterTreeState
import com.pantheon.android.filter.DECADES
import com.pantheon.android.filter.RESOLUTIONS
import com.pantheon.android.filter.SORT_DEFS
import com.pantheon.android.filter.ValueType

private val BgColor = Color(0xFF1B1C29)
private val GoldColor = Color(0xFFE0B84E)
private val TextDim = Color(0xFFB5B5C4)
private val TileBg = Color(0xFF232438)

// Full-screen rule-builder overlay — mirrors hades/src/components/
// PickerFilters.tsx's FilterSection (one level of grouping, real per-field
// operators) rather than the earlier chip-row stand-in: real usage feedback
// explicitly asked for "just like the web version." Opened from a single
// "Filters" button on LibraryScreen; edits FilterTreeState live (it's
// Compose state), applyFilters() on close actually refetches.
@Composable
fun FilterPanel(
    availableFields: List<String>,
    tree: FilterTreeState,
    libraries: List<LibraryWithSource>,
    selectedLibraryIds: Set<String>,
    onToggleLibrary: (String) -> Unit,
    fetchValuesFor: suspend (String) -> List<String>,
    sortOptions: List<String>,
    sort: String,
    sortDir: String,
    onSetSort: (String) -> Unit,
    onSetSortDir: (String) -> Unit,
    onReroll: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier = Modifier.fillMaxSize().background(BgColor)) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filters", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Done", color = GoldColor) }
            }

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                if (libraries.isNotEmpty()) {
                    item {
                        Text("Libraries", color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            items(libraries, key = { it.libraryId }) { lib ->
                                FilterChip(lib.displayName, lib.libraryId in selectedLibraryIds) { onToggleLibrary(lib.libraryId) }
                            }
                        }
                    }
                }

                if (sortOptions.isNotEmpty()) {
                    item {
                        val dirless = SORT_DEFS[sort]?.dirless ?: false
                        Text("Sort", color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            items(sortOptions, key = { it }) { s ->
                                FilterChip(SORT_DEFS[s]?.label ?: s, sort == s) { onSetSort(s) }
                            }
                        }
                        if (dirless) {
                            TextButton(onClick = onReroll) { Text("🎲 Reroll", color = GoldColor) }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip("ASC", sortDir == "asc") { onSetSortDir(if (sortDir == "asc") "" else "asc") }
                                FilterChip("DESC", sortDir == "desc") { onSetSortDir(if (sortDir == "desc") "" else "desc") }
                            }
                        }
                    }
                }

                if (availableFields.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                            Text("Match", color = TextDim)
                            MatchDropdown(tree.match, tree::updateMatch, modifier = Modifier.padding(horizontal = 8.dp))
                            Text("of the following:", color = TextDim)
                        }
                    }

                    items(tree.items, key = { it.id }) { item ->
                        when (item) {
                            is FilterItem.RuleItem -> FilterRuleRow(
                                rule = item.rule,
                                availableFields = availableFields,
                                fetchValuesFor = fetchValuesFor,
                                onUpdate = { f, o, v -> tree.updateRule(item.id, f, o, v) },
                                onRemove = { tree.removeItem(item.id) },
                            )
                            is FilterItem.GroupItem -> Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, TileBg, RoundedCornerShape(8.dp)).padding(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Match", color = TextDim)
                                    MatchDropdown(item.group.match, { m -> tree.setGroupMatch(item.id, m) }, modifier = Modifier.padding(horizontal = 8.dp))
                                    Text("within this group", color = TextDim, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { tree.removeItem(item.id) }) { Text("✕ remove group", color = TextDim) }
                                }
                                item.group.rules.forEach { rule ->
                                    FilterRuleRow(
                                        rule = rule,
                                        availableFields = availableFields,
                                        fetchValuesFor = fetchValuesFor,
                                        onUpdate = { f, o, v -> tree.updateRule(rule.id, f, o, v) },
                                        onRemove = { tree.removeRuleFromGroup(item.id, rule.id) },
                                    )
                                }
                                TextButton(onClick = { tree.addRuleToGroup(item.id) }) { Text("+ Add rule to group", color = GoldColor) }
                            }
                        }
                    }

                    item {
                        Row(modifier = Modifier.padding(vertical = 16.dp)) {
                            TextButton(onClick = { tree.addRule(availableFields.first()) }) { Text("+ Add Rule", color = GoldColor) }
                            TextButton(onClick = tree::addGroup) { Text("+ Add Group", color = GoldColor) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchDropdown(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilterChip(if (value == "any") "Any" else "All", active = false, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All") }, onClick = { onChange("all"); open = false })
            DropdownMenuItem(text = { Text("Any") }, onClick = { onChange("any"); open = false })
        }
    }
}

@Composable
private fun FilterRuleRow(
    rule: FilterRule,
    availableFields: List<String>,
    fetchValuesFor: suspend (String) -> List<String>,
    onUpdate: (field: String?, op: String?, value: String?) -> Unit,
    onRemove: () -> Unit,
) {
    val def = FIELD_DEFS[rule.field]
    var fieldMenuOpen by remember { mutableStateOf(false) }
    var opMenuOpen by remember { mutableStateOf(false) }
    var suggestions by remember(rule.field) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(rule.field) {
        if (def?.valueType == ValueType.TEXT) suggestions = fetchValuesFor(rule.field)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                FilterChip(def?.label ?: rule.field, active = false, onClick = { fieldMenuOpen = true })
                DropdownMenu(expanded = fieldMenuOpen, onDismissRequest = { fieldMenuOpen = false }) {
                    availableFields.mapNotNull { FIELD_DEFS[it] }.forEach { fd ->
                        DropdownMenuItem(text = { Text(fd.label) }, onClick = { onUpdate(fd.field, null, null); fieldMenuOpen = false })
                    }
                }
            }
            Box {
                FilterChip(def?.ops?.find { it.id == rule.op }?.label ?: rule.op, active = false, onClick = { opMenuOpen = true })
                DropdownMenu(expanded = opMenuOpen, onDismissRequest = { opMenuOpen = false }) {
                    def?.ops?.forEach { op ->
                        DropdownMenuItem(text = { Text(op.label) }, onClick = { onUpdate(null, op.id, null); opMenuOpen = false })
                    }
                }
            }
            TextButton(onClick = onRemove) { Text("✕", color = TextDim) }
        }

        when (def?.valueType) {
            ValueType.RESOLUTION -> ValuePicker(RESOLUTIONS, rule.value) { onUpdate(null, null, it) }
            ValueType.DECADE -> ValuePicker(DECADES, rule.value) { onUpdate(null, null, it) }
            ValueType.NUMBER -> OutlinedTextField(
                value = rule.value, onValueChange = { onUpdate(null, null, it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { Text(if (rule.field == "year") "e.g. 2010" else "0–10") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            else -> Column {
                OutlinedTextField(
                    value = rule.value, onValueChange = { onUpdate(null, null, it) },
                    placeholder = { Text("value…") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                // Narrows to what's actually typed so far — suggestions is
                // the full fetched candidate pool for this field (cached),
                // this just re-filters it locally on every keystroke rather
                // than showing the same static top-30 regardless of input.
                val visibleSuggestions = remember(suggestions, rule.value) {
                    if (rule.value.isBlank()) suggestions.take(30)
                    else suggestions.filter { it.contains(rule.value, ignoreCase = true) }.take(30)
                }
                if (visibleSuggestions.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        items(visibleSuggestions, key = { it }) { s ->
                            FilterChip(s, active = rule.value == s) { onUpdate(null, null, s) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ValuePicker(options: List<String>, value: String, onPick: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        items(options, key = { it }) { opt -> FilterChip(opt, active = value == opt) { onPick(opt) } }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) GoldColor else Color.Transparent)
            .border(1.dp, if (active) GoldColor else TextDim, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = if (active) Color.Black else Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
