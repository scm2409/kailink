package org.box44.kailink.domain

import org.box44.kailink.domain.model.Message

/**
 * Intermediate states of the timeline. The Matrix adapter translates SDK
 * `TimelineDiff`s into these domain patches; [TimelineReducer] applies them.
 */
sealed interface TimelinePatch {
    data class Reset(val messages: List<Message>) : TimelinePatch
    data class PushBack(val message: Message) : TimelinePatch
    data class PushFront(val message: Message) : TimelinePatch
    data class Insert(val index: Int, val message: Message) : TimelinePatch
    data class Set(val index: Int, val message: Message) : TimelinePatch
    data class Remove(val index: Int) : TimelinePatch

    /** Keeps the last [length] elements (SDK sliding window). */
    data class Truncate(val length: Int) : TimelinePatch
    data object PopBack : TimelinePatch
    data object PopFront : TimelinePatch
    data object Clear : TimelinePatch
}

/**
 * Pure function: applies [TimelinePatch] sequences to the timeline.
 * JVM-tested (TimelineReducerTest); all indices are coerced into valid
 * ranges so that SDK edge cases cannot break the UI.
 */
object TimelineReducer {

    fun apply(messages: List<Message>, patches: List<TimelinePatch>): List<Message> {
        var result = messages
        for (patch in patches) {
            result = apply(result, patch)
        }
        return result
    }

    fun apply(messages: List<Message>, patch: TimelinePatch): List<Message> = when (patch) {
        is TimelinePatch.Reset -> patch.messages.toList()
        is TimelinePatch.PushBack -> messages + patch.message
        is TimelinePatch.PushFront -> listOf(patch.message) + messages
        is TimelinePatch.Insert -> {
            val index = patch.index.coerceIn(0, messages.size)
            messages.subList(0, index) + patch.message + messages.subList(index, messages.size)
        }
        is TimelinePatch.Set -> messages.toMutableList().apply {
            if (patch.index in indices) this[patch.index] = patch.message
        }
        is TimelinePatch.Remove -> messages.toMutableList().apply {
            if (patch.index in indices) removeAt(patch.index)
        }
        is TimelinePatch.Truncate -> {
            val length = patch.length.coerceAtLeast(0)
            if (messages.size > length) messages.takeLast(length) else messages
        }
        is TimelinePatch.PopBack -> messages.dropLast(1)
        is TimelinePatch.PopFront -> messages.drop(1)
        is TimelinePatch.Clear -> emptyList()
    }
}
