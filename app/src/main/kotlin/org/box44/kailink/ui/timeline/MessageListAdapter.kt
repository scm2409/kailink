package org.box44.kailink.ui.timeline

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import org.box44.kailink.R
import org.box44.kailink.domain.model.DeliveryState
import org.box44.kailink.domain.model.Message
import org.box44.kailink.domain.model.MessageDirection

/** Row adapter for the timeline (framework views, no external UI libraries). */
class MessageListAdapter(private val context: Context) : BaseAdapter() {

    private var messages: List<Message> = emptyList()

    fun update(messages: List<Message>) {
        this.messages = messages
        notifyDataSetChanged()
    }

    override fun getCount(): Int = messages.size

    override fun getItem(position: Int): Message = messages[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val message = messages[position]
        val container = convertView as? LinearLayout ?: buildContainer()
        val bubble = container.getChildAt(0) as LinearLayout
        val sender = bubble.getChildAt(0) as TextView
        val body = bubble.getChildAt(1) as TextView
        sender.text = message.sender
        body.text = message.body
        val background = bubble.background as GradientDrawable
        background.setColor(
            if (message.direction == MessageDirection.OUTGOING) {
                context.getColor(R.color.bubble_outgoing)
            } else {
                context.getColor(R.color.bubble_incoming)
            },
        )
        body.setTextColor(
            if (message.state == DeliveryState.UNDECRYPTABLE) {
                context.getColor(R.color.error_text)
            } else {
                context.getColor(android.R.color.primary_text_light)
            },
        )
        return container
    }

    private fun buildContainer(): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
        }
        val sender = TextView(context).apply {
            textSize = 12f
            setTextColor(context.getColor(R.color.secondary_text))
        }
        val body = TextView(context).apply { textSize = 16f }
        bubble.addView(sender)
        bubble.addView(body)
        container.addView(bubble)
        return container
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
