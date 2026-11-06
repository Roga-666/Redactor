package io.github.roga666.redactor

/** Maximum of five reversible edit operations, shared across document pages. */
data class UndoEntry<T>(val pageIndex: Int, val before: T)

data class UndoHistory<T>(val entries: List<UndoEntry<T>> = emptyList()) {
    val count: Int get() = entries.size

    fun record(pageIndex: Int, before: T): UndoHistory<T> =
        UndoHistory((entries + UndoEntry(pageIndex, before)).takeLast(5))

    fun canUndo(pageIndex: Int): Boolean = entries.any { it.pageIndex == pageIndex }

    fun takeLastFor(pageIndex: Int): Pair<T, UndoHistory<T>>? {
        val index = entries.indexOfLast { it.pageIndex == pageIndex }
        if (index < 0) return null
        return entries[index].before to UndoHistory(entries.filterIndexed { i, _ -> i != index })
    }
}
