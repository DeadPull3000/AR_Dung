package com.embedded.argame.navigation

/**
 * High-performance binary min-heap specialized for grid cell indices with float priorities.
 *
 * Implements an inverted position map (indexToPosition) enabling O(1) membership checks and
 * O(log N) decrease-key operations without allocating heap objects or wrapper nodes.
 *
 * Designed for embedded systems with zero GC allocation during hot search loops.
 */
class IndexedMinHeap(val capacity: Int = 6400) {

    // Flat primitive array holding cell indices at each heap position [0 until size]
    val heapIndices = IntArray(capacity)

    // Flat primitive array holding the f-cost priority keys [0 until size]
    val heapKeys = FloatArray(capacity)

    // Inverted index: maps cellIndex -> heap slot [0 until size], or -1 if not in heap
    val indexToPosition = IntArray(capacity) { -1 }

    var size: Int = 0
        private set

    fun isEmpty(): Boolean = size == 0

    fun isNotEmpty(): Boolean = size > 0

    fun contains(cellIndex: Int): Boolean {
        return cellIndex in 0 until capacity && indexToPosition[cellIndex] != -1
    }

    fun getKey(cellIndex: Int): Float {
        val pos = indexToPosition[cellIndex]
        return if (pos != -1) heapKeys[pos] else Float.POSITIVE_INFINITY
    }

    /**
     * Clears all elements in O(K) time by resetting inverted indices only for active elements.
     */
    fun clear() {
        for (i in 0 until size) {
            val cell = heapIndices[i]
            indexToPosition[cell] = -1
        }
        size = 0
    }

    /**
     * Inserts a cell index with its associated priority key in O(log N) time.
     */
    fun push(cellIndex: Int, key: Float) {
        if (size >= capacity) {
            throw IllegalStateException("IndexedMinHeap capacity exceeded: $size >= $capacity")
        }
        val pos = size
        size++
        heapIndices[pos] = cellIndex
        heapKeys[pos] = key
        indexToPosition[cellIndex] = pos
        siftUp(pos)
    }

    /**
     * Extracts the cell index with minimum key in O(log N) time.
     */
    fun pollMin(): Int {
        if (size == 0) {
            throw NoSuchElementException("IndexedMinHeap is empty")
        }
        val minCell = heapIndices[0]
        val lastPos = size - 1
        size--
        if (size > 0) {
            heapIndices[0] = heapIndices[lastPos]
            heapKeys[0] = heapKeys[lastPos]
            indexToPosition[heapIndices[0]] = 0
            siftDown(0)
        }
        indexToPosition[minCell] = -1
        return minCell
    }

    /**
     * Updates the priority of an existing element in O(log N) time if the new key is smaller.
     */
    fun decreaseKey(cellIndex: Int, newKey: Float) {
        val pos = indexToPosition[cellIndex]
        if (pos != -1 && newKey < heapKeys[pos]) {
            heapKeys[pos] = newKey
            siftUp(pos)
        }
    }

    private fun siftUp(startPos: Int) {
        var pos = startPos
        val cell = heapIndices[pos]
        val key = heapKeys[pos]
        while (pos > 0) {
            val parentPos = (pos - 1) ushr 1
            if (key >= heapKeys[parentPos]) break
            heapIndices[pos] = heapIndices[parentPos]
            heapKeys[pos] = heapKeys[parentPos]
            indexToPosition[heapIndices[pos]] = pos
            pos = parentPos
        }
        heapIndices[pos] = cell
        heapKeys[pos] = key
        indexToPosition[cell] = pos
    }

    private fun siftDown(startPos: Int) {
        var pos = startPos
        val cell = heapIndices[pos]
        val key = heapKeys[pos]
        val half = size ushr 1
        while (pos < half) {
            var childPos = (pos shl 1) + 1
            val rightPos = childPos + 1
            if (rightPos < size && heapKeys[rightPos] < heapKeys[childPos]) {
                childPos = rightPos
            }
            if (key <= heapKeys[childPos]) break
            heapIndices[pos] = heapIndices[childPos]
            heapKeys[pos] = heapKeys[childPos]
            indexToPosition[heapIndices[pos]] = pos
            pos = childPos
        }
        heapIndices[pos] = cell
        heapKeys[pos] = key
        indexToPosition[cell] = pos
    }
}
