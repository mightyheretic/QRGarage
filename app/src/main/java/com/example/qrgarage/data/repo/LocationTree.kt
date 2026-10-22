package com.example.qrgarage.data.repo

import com.example.qrgarage.data.db.LocationEntity
import com.example.qrgarage.data.db.LocationKind

/** A location's label as displayed: wheel segments never wrap internally (LOC-16). */
val LocationEntity.displayName: String get() = LocationLabel.unbreakable(name)

/**
 * Snapshot of all live locations for fast path rendering in lists (SEARCH-3). Garages have
 * hundreds of locations at most, so resolving paths in memory beats a CTE per list row.
 */
class LocationTree(locations: List<LocationEntity>) {

    val byId: Map<String, LocationEntity> = locations.associateBy { it.id }

    private val childrenOf: Map<String?, List<LocationEntity>> = locations
        .groupBy { loc -> loc.parentId?.takeIf { it in byId } }
        .mapValues { (_, list) -> list.sortedWith(ORDER) }

    val roots: List<LocationEntity> get() = childrenOf[null].orEmpty()

    fun children(id: String): List<LocationEntity> = childrenOf[id].orEmpty()

    operator fun get(id: String): LocationEntity? = byId[id]

    /** Root first, ending with [id]. Guards against cycles from corrupt data. */
    fun path(id: String): List<LocationEntity> {
        val out = ArrayList<LocationEntity>()
        val seen = HashSet<String>()
        var cur = byId[id]
        while (cur != null && seen.add(cur.id)) {
            out += cur
            cur = cur.parentId?.let { byId[it] }
        }
        return out.asReversed()
    }

    /**
     * Human-readable path for display, e.g. "Left wall > Shelf A Row 01". With a single garage
     * the root adds no information, so it is omitted unless the location *is* the root.
     * Segments never break internally (LOC-16).
     */
    fun pathString(id: String, separator: String = " > "): String {
        val path = path(id)
        if (path.isEmpty()) return "Unknown location"
        val shown = if (roots.size == 1 && path.size > 1) path.drop(1) else path
        return shown.joinToString(separator) { it.displayName }
    }

    /** Full display path including the root, e.g. "Garage › Left wall › Shelf A Row 01" (LOC-16). */
    fun fullPath(id: String, separator: String = " › "): String =
        path(id).joinToString(separator) { it.displayName }

    fun isSelfOrDescendant(candidate: String, ancestor: String): Boolean =
        path(candidate).any { it.id == ancestor }

    /** LOC-15: whether a live sibling under [parentId] (null = top level) already uses [label]. */
    fun labelTaken(parentId: String?, label: String, excludeId: String? = null): Boolean =
        (parentId?.let(::children) ?: roots).any { it.id != excludeId && it.name.equals(label, ignoreCase = true) }

    /**
     * Starting wheel values for a new child of [parentId]: inherits shelf and row from the
     * nearest wheel-labelled ancestor, then moves to the next free row (shelf) or bin (bin).
     */
    fun suggestWheels(parentId: String?, kind: LocationKind): LocationLabel.Parts {
        val inherited = parentId?.let { path(it).lastOrNull { l -> l.wheels != null }?.wheels }
        val siblings = (parentId?.let(::children) ?: roots).mapNotNull { it.wheels }
        return if (kind == LocationKind.BIN) {
            val base = (inherited ?: siblings.lastOrNull() ?: LocationLabel.Parts("A", 1)).let { it.copy(row = it.row ?: 1) }
            val used = siblings.filter { it.shelf == base.shelf && it.row == base.row }.mapNotNull { it.bin }.toSet()
            base.copy(bin = generateSequence(1) { it + 1 }.first { it !in used })
        } else {
            val last = siblings.lastOrNull { it.bin == null }
            when {
                last != null -> last.copy(row = (last.row ?: 0) + 1)
                inherited != null -> inherited.copy(row = inherited.row ?: 1, bin = null)
                else -> LocationLabel.Parts("A", 1)
            }
        }
    }

    data class Node(val location: LocationEntity, val depth: Int)

    /** Depth-first flattening for tree lists and pickers. */
    fun flatten(expanded: Set<String>? = null): List<Node> {
        val out = ArrayList<Node>()
        fun visit(loc: LocationEntity, depth: Int) {
            out += Node(loc, depth)
            if (expanded == null || loc.id in expanded) children(loc.id).forEach { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }
        return out
    }

    /**
     * A picker row: a real location, or a virtual "Shelf A" header ([location] null) collecting
     * siblings that share a shelf. When one of those siblings is the bare shelf itself, it is the
     * header. Grouped members show only the rest of their label, e.g. "Row 01".
     */
    data class Entry(
        val key: String,
        val depth: Int,
        val label: String,
        val location: LocationEntity?,
        val expandable: Boolean,
        val members: List<LocationEntity> = emptyList(),
    )

    /** Siblings sharing a shelf, keyed by shelf letters; only shelves with two or more locations group. */
    private fun shelfGroups(siblings: List<LocationEntity>): Map<String, List<LocationEntity>> =
        siblings.filter { it.wheels != null }.groupBy { it.wheels!!.shelf }.filterValues { it.size > 1 }

    private fun groupKey(parentId: String?, shelf: String, group: List<LocationEntity>): String =
        group.firstOrNull { it.isBareShelf }?.id ?: "shelf:$parentId:$shelf"

    private val LocationEntity.isBareShelf: Boolean get() = wheels?.let { it.row == null && it.bin == null } == true

    /** Key of the shelf group [id] is listed under, if any; lets pickers expand to a selection. */
    fun groupKeyOf(id: String): String? {
        val loc = byId[id] ?: return null
        val shelf = loc.wheels?.shelf ?: return null
        val parentId = loc.parentId?.takeIf { it in byId }
        val group = shelfGroups(parentId?.let(::children) ?: roots)[shelf] ?: return null
        return groupKey(parentId, shelf, group).takeIf { it != id }
    }

    /** Like [flatten], but siblings on the same shelf collapse under one shelf entry. */
    fun flattenGrouped(expanded: Set<String>): List<Entry> = flattenGrouped { it in expanded }

    /** [isExpanded] receives location ids and shelf group keys alike. */
    fun flattenGrouped(isExpanded: (String) -> Boolean): List<Entry> {
        val out = ArrayList<Entry>()
        fun visitSiblings(parentId: String?, siblings: List<LocationEntity>, depth: Int) {
            val groups = shelfGroups(siblings)
            val emitted = HashSet<String>()
            for (loc in siblings) {
                val shelf = loc.wheels?.shelf
                val group = shelf?.let(groups::get)
                if (group == null) {
                    val kids = children(loc.id)
                    out += Entry(loc.id, depth, loc.displayName, loc, kids.isNotEmpty())
                    if (isExpanded(loc.id)) visitSiblings(loc.id, kids, depth + 1)
                    continue
                }
                if (!emitted.add(shelf)) continue
                val header = group.firstOrNull { it.isBareShelf }
                val key = groupKey(parentId, shelf, group)
                val members = group.filter { it !== header }
                out += Entry(key, depth, header?.displayName ?: LocationLabel.unbreakable("Shelf $shelf"), header, true, members)
                if (!isExpanded(key)) continue
                header?.let { visitSiblings(it.id, children(it.id), depth + 1) }
                for (member in members) {
                    val kids = children(member.id)
                    val rest = LocationLabel.afterShelf(member.wheels!!).ifEmpty { member.name }
                    out += Entry(member.id, depth + 1, LocationLabel.unbreakable(rest), member, kids.isNotEmpty())
                    if (isExpanded(member.id)) visitSiblings(member.id, kids, depth + 2)
                }
            }
        }
        visitSiblings(null, roots, 0)
        return out
    }

    companion object {
        val EMPTY = LocationTree(emptyList())

        /**
         * Free-text locations alphabetically, then wheel locations in wheel order, so "Shelf B"
         * comes before "Shelf AA" and "Row 02" before "Row 10".
         */
        val ORDER: Comparator<LocationEntity> =
            compareBy<LocationEntity> { it.wheels != null }
                .thenBy { it.wheels?.let { w -> LocationLabel.shelfIndex(w.shelf) } }
                .thenBy { it.wheels?.row ?: 0 }
                .thenBy { it.wheels?.bin ?: 0 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
}
