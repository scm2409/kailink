package org.box44.kailink.testing

import org.box44.kailink.domain.TimelinePatch
import org.box44.kailink.domain.TimelineReducer
import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.MessageDirection

fun timelineReducerChecks() {
    fun message(id: String, body: String = "text-$id") = Message(
        id = id,
        roomId = "!r:hs",
        sender = "@alice:hs",
        body = body,
        direction = MessageDirection.INCOMING,
        state = org.box44.kailink.domain.model.DeliveryState.SENT,
        timestampMillis = 1_000L,
    )

    Checks.check("Reset replaces the list") {
        val result = TimelineReducer.apply(
            listOf(message("a")),
            listOf(TimelinePatch.Reset(listOf(message("b"), message("c")))),
        )
        expectEquals(listOf("b", "c"), result.map(Message::id), "Order after Reset")
    }

    Checks.check("PushBack appends at the end") {
        val result = TimelineReducer.apply(listOf(message("a")), TimelinePatch.PushBack(message("b")))
        expectEquals(listOf("a", "b"), result.map(Message::id), "Order after PushBack")
    }

    Checks.check("PushFront inserts at the front") {
        val result = TimelineReducer.apply(listOf(message("b")), TimelinePatch.PushFront(message("a")))
        expectEquals(listOf("a", "b"), result.map(Message::id), "Order after PushFront")
    }

    Checks.check("Insert forces a valid index") {
        val result = TimelineReducer.apply(
            listOf(message("a")),
            listOf(TimelinePatch.Insert(99, message("b"))),
        )
        expectEquals(listOf("a", "b"), result.map(Message::id), "Index 99 is forced to the end")
    }

    Checks.check("Set updates the existing element") {
        val result = TimelineReducer.apply(
            listOf(message("a"), message("b")),
            TimelinePatch.Set(1, message("b", body = "changed")),
        )
        expectEquals("changed", result[1].body, "Body after Set")
    }

    Checks.check("Set with an invalid index is a no-op") {
        val original = listOf(message("a"))
        val result = TimelineReducer.apply(original, TimelinePatch.Set(5, message("z")))
        expectEquals(original, result, "List stays unchanged")
    }

    Checks.check("Remove, PopFront and PopBack reduce correctly") {
        val base = listOf(message("a"), message("b"), message("c"))
        expectEquals(
            listOf("a", "c"),
            TimelineReducer.apply(base, TimelinePatch.Remove(1)).map(Message::id),
            "Remove(1)",
        )
        expectEquals(
            listOf("b", "c"),
            TimelineReducer.apply(base, TimelinePatch.PopFront).map(Message::id),
            "PopFront",
        )
        expectEquals(
            listOf("a", "b"),
            TimelineReducer.apply(base, TimelinePatch.PopBack).map(Message::id),
            "PopBack",
        )
    }

    Checks.check("Truncate keeps the last elements") {
        val result = TimelineReducer.apply(
            listOf(message("a"), message("b"), message("c")),
            TimelinePatch.Truncate(2),
        )
        expectEquals(listOf("b", "c"), result.map(Message::id), "Sliding window")
    }

    Checks.check("Patch sequence applies in order") {
        val result = TimelineReducer.apply(
            emptyList(),
            listOf(
                TimelinePatch.Reset(listOf(message("a"), message("b"))),
                TimelinePatch.PushBack(message("c")),
                TimelinePatch.Remove(0),
            ),
        )
        expectEquals(listOf("b", "c"), result.map(Message::id), "Sequential application")
    }
}
