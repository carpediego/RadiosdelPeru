package pe.radiosdelperu.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import pe.radiosdelperu.app.R
import pe.radiosdelperu.app.catalog.Station

class StationAdapter(
    private val onClick: (Station) -> Unit
) : RecyclerView.Adapter<StationAdapter.Holder>() {

    private val items = mutableListOf<Station>()
    private var activeId: String? = null

    fun submit(newItems: List<Station>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setActive(id: String?) {
        if (activeId == id) return
        activeId = id
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val station = items[position]
        val active = station.id == activeId

        holder.name.text = station.name
        holder.tagline.text = station.tagline
        holder.tagline.visibility = if (station.tagline.isBlank()) View.GONE else View.VISIBLE

        holder.monogram.text = station.initials
        holder.monogram.backgroundTintList =
            android.content.res.ColorStateList.valueOf(parseColor(station.color))

        holder.itemView.isSelected = active
        holder.state.setImageResource(if (active) R.drawable.ic_stop else R.drawable.ic_play)
        holder.state.contentDescription = if (active) {
            holder.itemView.context.getString(R.string.cd_stop, station.name)
        } else {
            holder.itemView.context.getString(R.string.cd_play, station.name)
        }

        holder.itemView.setOnClickListener { onClick(station) }
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (_: IllegalArgumentException) {
        DEFAULT_COLOR
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.txtName)
        val tagline: TextView = view.findViewById(R.id.txtTagline)
        val monogram: TextView = view.findViewById(R.id.txtMonogram)
        val state: ImageView = view.findViewById(R.id.imgState)
    }

    private companion object {
        val DEFAULT_COLOR: Int = Color.parseColor("#0B5FFF")
    }
}
