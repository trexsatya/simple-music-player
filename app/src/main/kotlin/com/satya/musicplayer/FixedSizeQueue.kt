package com.satya.musicplayer

class FixedSizeQueue<T>(private val maxSize: Int) {
    private val deque = ArrayDeque<T>()

    fun add(item: T) {
        if (deque.size == maxSize) deque.removeFirst()
        deque.addLast(item)
    }

    fun remove(): T? = if (deque.isNotEmpty()) deque.removeFirst() else null

    fun toList(): List<T> = deque.toList()

    override fun toString(): String = deque.joinToString(", ", "[", "]")
}
