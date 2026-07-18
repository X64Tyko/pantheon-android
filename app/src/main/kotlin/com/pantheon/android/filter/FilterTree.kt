package com.pantheon.android.filter

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Kotlin counterpart of hades/src/components/media/filterTree.ts's
// FilterTreeStore — one level of grouping ("(genre is Horror AND year is
// after 2015) OR studio is A24"), same bound the web rule-builder imposes.
// A Compose state holder (mutableStateOf on immutable snapshots) rather than
// MobX's makeAutoObservable — same net effect (recomposition on mutation),
// idiomatic for this codebase's own convention (see every ViewModel here).

data class FilterRule(val id: String, val field: String, val op: String, val value: String)
data class FilterGroup(val id: String, val match: String, val rules: List<FilterRule>) // match: "all" | "any"

sealed interface FilterItem {
    val id: String
    data class RuleItem(val rule: FilterRule) : FilterItem { override val id get() = rule.id }
    data class GroupItem(val group: FilterGroup) : FilterItem { override val id get() = group.id }
}

private var globalNextId = 0
private fun nextId() = (++globalNextId).toString()

fun blankRule(field: String = "genre") = FilterRule(nextId(), field, FIELD_DEFS[field]?.ops?.firstOrNull()?.id ?: "is", "")

// Mirrors filterSyntax.ts's quoteIfNeeded/serializeClause/serializeFilterSyntax.
private fun quoteIfNeeded(v: String): String {
    if (v.isEmpty()) return "\"\""
    return if (v.any { it.isWhitespace() || it == '(' || it == ')' || it == '"' }) "\"" + v.replace("\"", "\\\"") + "\"" else v
}

private fun serializeRule(r: FilterRule): String {
    val v = quoteIfNeeded(r.value)
    return when (r.op) {
        "contains" -> "${r.field}:*$v*"
        "begins_with" -> "${r.field}:$v*"
        "ends_with" -> "${r.field}:*$v"
        "is_not" -> "-${r.field}:$v"
        "does_not_contain" -> "-${r.field}:*$v*"
        "gt" -> "${r.field}:>$v"
        "gte" -> "${r.field}:>=$v"
        "lt" -> "${r.field}:<$v"
        "lte" -> "${r.field}:<=$v"
        else -> "${r.field}:$v" // "is"
    }
}

fun serializeFilterTree(match: String, items: List<FilterItem>): String {
    val parts = items.mapNotNull { item ->
        when (item) {
            is FilterItem.RuleItem -> item.rule.value.takeIf { it.isNotBlank() }?.let { serializeRule(item.rule) }
            is FilterItem.GroupItem -> {
                val groupParts = item.group.rules.filter { it.value.isNotBlank() }.map { serializeRule(it) }
                when {
                    groupParts.isEmpty() -> null
                    groupParts.size == 1 -> groupParts[0]
                    else -> "(" + groupParts.joinToString(if (item.group.match == "any") " OR " else " AND ") + ")"
                }
            }
        }
    }
    if (parts.isEmpty()) return ""
    return parts.joinToString(if (match == "any") " OR " else " AND ")
}

class FilterTreeState {
    var match by mutableStateOf("all")
        private set
    var items by mutableStateOf<List<FilterItem>>(emptyList())
        private set

    val ruleCount: Int get() = items.sumOf { if (it is FilterItem.RuleItem) 1 else (it as FilterItem.GroupItem).group.rules.size }
    val isEmpty: Boolean get() = items.isEmpty()

    // Named updateMatch, not setMatch — the latter collides at the JVM
    // signature level with the mutableStateOf property's own synthesized
    // setter (Kotlin's "platform declaration clash"), same reason
    // LibraryViewModel's update*/on*Change methods aren't named set*.
    fun updateMatch(m: String) { match = m }
    fun addRule(defaultField: String = "genre") { items = items + FilterItem.RuleItem(blankRule(defaultField)) }
    fun addGroup() { items = items + FilterItem.GroupItem(FilterGroup(nextId(), "all", listOf(blankRule()))) }
    fun removeItem(id: String) { items = items.filter { it.id != id } }
    fun reset() { items = emptyList(); match = "all" }

    // field change resets op to that field's first op + clears value —
    // mirrors filterTree.ts's updateRule exactly (a stale op/value from a
    // different field's vocabulary would silently misbehave otherwise).
    private fun patched(r: FilterRule, field: String?, op: String?, value: String?): FilterRule {
        if (field != null && field != r.field) return r.copy(field = field, op = FIELD_DEFS[field]?.ops?.firstOrNull()?.id ?: "is", value = "")
        return r.copy(op = op ?: r.op, value = value ?: r.value)
    }

    fun updateRule(id: String, field: String? = null, op: String? = null, value: String? = null) {
        items = items.map { item ->
            when (item) {
                is FilterItem.RuleItem -> if (item.id == id) FilterItem.RuleItem(patched(item.rule, field, op, value)) else item
                is FilterItem.GroupItem -> FilterItem.GroupItem(item.group.copy(rules = item.group.rules.map { r -> if (r.id == id) patched(r, field, op, value) else r }))
            }
        }
    }

    fun setGroupMatch(groupId: String, m: String) {
        items = items.map { if (it is FilterItem.GroupItem && it.id == groupId) FilterItem.GroupItem(it.group.copy(match = m)) else it }
    }
    fun addRuleToGroup(groupId: String) {
        items = items.map { if (it is FilterItem.GroupItem && it.id == groupId) FilterItem.GroupItem(it.group.copy(rules = it.group.rules + blankRule())) else it }
    }
    fun removeRuleFromGroup(groupId: String, ruleId: String) {
        items = items.map { if (it is FilterItem.GroupItem && it.id == groupId) FilterItem.GroupItem(it.group.copy(rules = it.group.rules.filter { r -> r.id != ruleId })) else it }
    }

    fun toFilterString(): String = serializeFilterTree(match, items)
}
