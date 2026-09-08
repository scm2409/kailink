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

    Checks.check("Reset ersetzt die Liste") {
        val result = TimelineReducer.apply(
            listOf(message("a")),
            listOf(TimelinePatch.Reset(listOf(message("b"), message("c")))),
        )
        expectEquals(listOf("b", "c"), result.map(Message::id), "Reihenfolge nach Reset")
    }

    Checks.check("PushBack hängt am Ende an") {
        val result = TimelineReducer.apply(listOf(message("a")), TimelinePatch.PushBack(message("b")))
        expectEquals(listOf("a", "b"), result.map(Message::id), "Reihenfolge nach PushBack")
    }

    Checks.check("PushFront fügt vorne ein") {
        val result = TimelineReducer.apply(listOf(message("b")), TimelinePatch.PushFront(message("a")))
        expectEquals(listOf("a", "b"), result.map(Message::id), "Reihenfolge nach PushFront")
    }

    Checks.check("Insert erzwingt gültigen Index") {
        val result = TimelineReducer.apply(
            listOf(message("a")),
            listOf(TimelinePatch.Insert(99, message("b"))),
        )
        expectEquals(listOf("a", "b"), result.map(Message::id), "Index 99 wird ans Ende gezwungen")
    }

    Checks.check("Set aktualisiert vorhandenes Element") {
        val result = TimelineReducer.apply(
            listOf(message("a"), message("b")),
            TimelinePatch.Set(1, message("b", body = "geändert")),
        )
        expectEquals("geändert", result[1].body, "Body nach Set")
    }

    Checks.check("Set mit ungültigem Index ist keine Operation") {
        val original = listOf(message("a"))
        val result = TimelineReducer.apply(original, TimelinePatch.Set(5, message("z")))
        expectEquals(original, result, "Liste bleibt unverändert")
    }

    Checks.check("Remove, PopFront und PopBack reduzieren korrekt") {
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

    Checks.check("Truncate behält die letzten Elemente") {
        val result = TimelineReducer.apply(
            listOf(message("a"), message("b"), message("c")),
            TimelinePatch.Truncate(2),
        )
        expectEquals(listOf("b", "c"), result.map(Message::id), "Sliding-Window")
    }

    Checks.check("Patch-Sequenz wendet Reihenfolge an") {
        val result = TimelineReducer.apply(
            emptyList(),
            listOf(
                TimelinePatch.Reset(listOf(message("a"), message("b"))),
                TimelinePatch.PushBack(message("c")),
                TimelinePatch.Remove(0),
            ),
        )
        expectEquals(listOf("b", "c"), result.map(Message::id), "Sequenzielle Anwendung")
    }
}
