package com.pantheon.android.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.pantheon.android.api.dto.LibraryWithSource
import com.pantheon.android.filter.DECADES
import com.pantheon.android.filter.FIELD_DEFS
import com.pantheon.android.filter.FilterItem
import com.pantheon.android.filter.FilterRule
import com.pantheon.android.filter.FilterTreeState
import com.pantheon.android.filter.RESOLUTIONS
import com.pantheon.android.filter.SORT_DEFS
import com.pantheon.android.filter.ValueType

private val TileBorder = Color(0xFF2E2F45)

// TV counterpart of the mobile flavor's FilterPanel — same FilterTreeState/
// FIELD_DEFS rule builder (real usage feedback: "just like the web
// version," field list manifest-driven), but every selector is an inline
// TvChip row instead of a popup DropdownMenu — see LibraryScreen.kt's
// comment on why: nested popup focus scopes don't play well with D-pad
// focus-search in tv-foundation 1.0.0.
//
// Real on-device bug: an earlier version rendered this as a plain Box
// instead of a Dialog. Since it was declared *before* LibraryScreen's own
// main Box in composition order, Compose painted it underneath that
// opaque, fillMaxSize screen content — it wasn't a focus problem, it
// literally never became visible at all, and D-pad input kept landing on
// whatever was focused on the Library screen underneath. A Dialog renders
// in its own window, always on top regardless of composition order, and
// becomes the active window for D-pad/focus purposes — the same reason
// the mobile flavor's FilterPanel already uses one.
@Composable
fun TvFilterPanel(
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
    val doneFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { doneFocusRequester.requestFocus() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Box(modifier = Modifier.fillMaxSize().background(BgColor)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filters", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.weight(1f))
                TvTextButton(text = "Done", onClick = onClose, modifier = Modifier.focusRequester(doneFocusRequester))
            }

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp)) {
                if (libraries.isNotEmpty()) {
                    item {
                        Text("Libraries", color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                            items(libraries, key = { it.libraryId }) { lib ->
                                TvChip(lib.displayName, lib.libraryId in selectedLibraryIds) { onToggleLibrary(lib.libraryId) }
                            }
                        }
                    }
                }

                if (sortOptions.isNotEmpty()) {
                    item {
                        val dirless = SORT_DEFS[sort]?.dirless ?: false
                        Text("Sort", color = TextDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                            items(sortOptions, key = { it }) { s ->
                                TvChip(SORT_DEFS[s]?.label ?: s, sort == s) { onSetSort(s) }
                            }
                        }
                        if (dirless) {
                            TvTextButton(text = "🎲 Reroll", onClick = onReroll)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvChip("ASC", sortDir == "asc") { onSetSortDir(if (sortDir == "asc") "" else "asc") }
                                TvChip("DESC", sortDir == "desc") { onSetSortDir(if (sortDir == "desc") "" else "desc") }
                            }
                        }
                    }
                }

                if (availableFields.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)) {
                            Text("Match", color = TextDim)
                            Row(modifier = Modifier.padding(horizontal = 10.dp)) {
                                TvChip("All", tree.match == "all") { tree.updateMatch("all") }
                                Box(Modifier.padding(start = 8.dp)) { TvChip("Any", tree.match == "any") { tree.updateMatch("any") } }
                            }
                            Text("of the following:", color = TextDim)
                        }
                    }

                    items(tree.items, key = { it.id }) { item ->
                        when (item) {
                            is FilterItem.RuleItem -> TvFilterRuleRow(
                                rule = item.rule,
                                availableFields = availableFields,
                                fetchValuesFor = fetchValuesFor,
                                onUpdate = { f, o, v -> tree.updateRule(item.id, f, o, v) },
                                onRemove = { tree.removeItem(item.id) },
                            )
                            is FilterItem.GroupItem -> Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, TileBorder, RoundedCornerShape(8.dp)).padding(14.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Match", color = TextDim)
                                    Row(modifier = Modifier.padding(horizontal = 10.dp)) {
                                        TvChip("All", item.group.match == "all") { tree.setGroupMatch(item.id, "all") }
                                        Box(Modifier.padding(start = 8.dp)) { TvChip("Any", item.group.match == "any") { tree.setGroupMatch(item.id, "any") } }
                                    }
                                    Text("within this group", color = TextDim, modifier = Modifier.weight(1f))
                                    TvTextButton(text = "✕ remove group", onClick = { tree.removeItem(item.id) })
                                }
                                item.group.rules.forEach { rule ->
                                    TvFilterRuleRow(
                                        rule = rule,
                                        availableFields = availableFields,
                                        fetchValuesFor = fetchValuesFor,
                                        onUpdate = { f, o, v -> tree.updateRule(rule.id, f, o, v) },
                                        onRemove = { tree.removeRuleFromGroup(item.id, rule.id) },
                                    )
                                }
                                TvTextButton(text = "+ Add rule to group", onClick = { tree.addRuleToGroup(item.id) })
                            }
                        }
                    }

                    item {
                        Row(modifier = Modifier.padding(vertical = 20.dp)) {
                            TvTextButton(text = "+ Add Rule", onClick = { tree.addRule(availableFields.first()) })
                            Box(Modifier.padding(start = 10.dp)) { TvTextButton(text = "+ Add Group", onClick = tree::addGroup) }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun TvFilterRuleRow(
    rule: FilterRule,
    availableFields: List<String>,
    fetchValuesFor: suspend (String) -> List<String>,
    onUpdate: (field: String?, op: String?, value: String?) -> Unit,
    onRemove: () -> Unit,
) {
    val def = FIELD_DEFS[rule.field]
    var suggestions by remember(rule.field) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(rule.field) {
        if (def?.valueType == ValueType.TEXT) suggestions = fetchValuesFor(rule.field)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("Field", color = TextDim, style = MaterialTheme.typography.labelSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(availableFields.mapNotNull { FIELD_DEFS[it] }, key = { it.field }) { fd ->
                TvChip(fd.label, fd.field == rule.field) { onUpdate(fd.field, null, null) }
            }
        }

        def?.let { fieldDef ->
            Text("Operator", color = TextDim, style = MaterialTheme.typography.labelSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(fieldDef.ops, key = { it.id }) { op ->
                    TvChip(op.label, op.id == rule.op) { onUpdate(null, op.id, null) }
                }
            }
        }

        when (def?.valueType) {
            ValueType.RESOLUTION -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RESOLUTIONS, key = { it }) { r -> TvChip(r, r == rule.value) { onUpdate(null, null, r) } }
            }
            ValueType.DECADE -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DECADES, key = { it }) { d -> TvChip(d, d == rule.value) { onUpdate(null, null, d) } }
            }
            ValueType.NUMBER -> OutlinedTextField(
                value = rule.value, onValueChange = { onUpdate(null, null, it) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                placeholder = { androidx.compose.material3.Text(if (rule.field == "year") "e.g. 2010" else "0–10") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            else -> Column {
                OutlinedTextField(
                    value = rule.value, onValueChange = { onUpdate(null, null, it) },
                    placeholder = { androidx.compose.material3.Text("value…") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(visibleSuggestions, key = { it }) { s ->
                            TvChip(s, rule.value == s) { onUpdate(null, null, s) }
                        }
                    }
                }
            }
        }

        TvTextButton(text = "✕ remove rule", onClick = onRemove)
    }
}
