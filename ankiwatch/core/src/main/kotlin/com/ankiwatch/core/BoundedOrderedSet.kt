package com.ankiwatch.core

/**
 * Insertion-ordered set that forgets its oldest entries past [capacity]. Serialises to one
 * newline-separated string, which (unlike SharedPreferences' string sets) keeps the order,
 * so "oldest first" eviction really evicts the oldest.
 */
class BoundedOrderedSet(private val capacity: Int, initial: Iterable<String> = emptyList()) {

    private val items = LinkedHashSet<String>()

    init {
        require(capacity > 0) { "capacity must be positive" }
        for (item in initial) add(item)
    }

    val size: Int get() = items.size

    operator fun contains(item: String): Boolean = item in items

    /** Adds [item] as the newest entry (moving it if already present). */
    fun add(item: String) {
        if (item.isEmpty() || '\n' in item) return
        items.remove(item)
        items.add(item)
        while (items.size > capacity) {
            val oldest = items.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    fun toList(): List<String> = items.toList()

    fun serialize(): String = items.joinToString("\n")

    companion object {
        fun deserialize(capacity: Int, serialized: String?): BoundedOrderedSet =
            BoundedOrderedSet(capacity, serialized?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList())
    }
}
