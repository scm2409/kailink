package org.box44.kailink.ui.rooms

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import org.box44.kailink.R
import org.box44.kailink.domain.model.Room

/** Zeilen-Adapter für die Raumliste (Framework-Views, keine externen UI-Libs). */
class RoomListAdapter(private val context: Context) : BaseAdapter() {

    private var rooms: List<Room> = emptyList()

    fun update(rooms: List<Room>) {
        this.rooms = rooms
        notifyDataSetChanged()
    }

    fun roomAt(position: Int): Room = rooms[position]

    override fun getCount(): Int = rooms.size

    override fun getItem(position: Int): Room = rooms[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val room = rooms[position]
        val row = convertView as? LinearLayout ?: buildRow()
        val column = row.getChildAt(0) as LinearLayout
        val name = column.getChildAt(0) as TextView
        val preview = column.getChildAt(1) as TextView
        val badge = row.getChildAt(1) as TextView
        name.text = room.displayName
        preview.text = room.lastMessage?.body ?: "—"
        badge.visibility = if (room.isEncrypted) View.VISIBLE else View.GONE
        return row
    }

    private fun buildRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val name = TextView(context).apply { textSize = 16f }
        val preview = TextView(context).apply {
            textSize = 13f
            setTextColor(context.getColor(R.color.secondary_text))
        }
        val badge = TextView(context).apply { textSize = 14f }
        column.addView(name)
        column.addView(preview)
        row.addView(column)
        row.addView(badge)
        return row
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
