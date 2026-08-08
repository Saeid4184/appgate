package ir.factory.entryexit.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import ir.factory.entryexit.R
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.databinding.ItemRosterEntryBinding
import ir.factory.entryexit.databinding.ItemSectionHeaderBinding

/** A single row shown in the roster list: either a section header or a person/machine entry. */
sealed class RosterRow {
    data class Header(val title: String) : RosterRow()
    data class Item(val person: PersonEntity) : RosterRow()
}

/**
 * Displays a roster grouped into sections (by department or fleet group). Pass a flat,
 * already-sorted [List]<[PersonEntity]> to [submit]; the adapter inserts header rows itself
 * whenever the group changes. When [showGroups] is false (visitors/drivers), no headers are
 * inserted at all.
 */
class GroupedPersonAdapter(
    private val type: PersonType,
    private val showGroups: Boolean,
    private val onCheckIn: (PersonEntity) -> Unit,
    private val onCheckOut: (PersonEntity) -> Unit,
    private val onLongClick: (PersonEntity) -> Unit = {},
    /** Machinery only: holding the خروج button down instead of tapping it — shows the full
     *  cargo/load-type list instead of the one-tap "routine" default. */
    private val onCheckOutLongPress: (PersonEntity) -> Unit = {},
    /** Machinery/mixers only: today's dispatch count for this vehicle, or null to hide the
     *  badge (every other type/category). Looked up fresh on each bind rather than stored on
     *  [PersonEntity] itself, since it's a derived, constantly-changing tally, not roster data. */
    private val serviceCountProvider: (PersonEntity) -> Int? = { null }
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<RosterRow> = emptyList()

    fun submit(persons: List<PersonEntity>) {
        rows = if (showGroups) buildGroupedRows(persons) else persons.map { RosterRow.Item(it) }
        notifyDataSetChanged()
    }

    /** Call when only the derived service-count badges need refreshing (e.g. a new dispatch
     *  just landed) — cheaper than re-submitting/re-grouping the whole roster. */
    fun refreshBadges() = notifyDataSetChanged()

    /** Returns the [PersonEntity] at [position], or null if that row is a section header. */
    fun personAt(position: Int): PersonEntity? {
        if (position < 0 || position >= rows.size) return null
        return (rows[position] as? RosterRow.Item)?.person
    }

    private fun buildGroupedRows(persons: List<PersonEntity>): List<RosterRow> {
        val result = mutableListOf<RosterRow>()
        var currentGroup: String? = null
        for (p in persons) {
            val groupLabel = p.group ?: "سایر"
            if (groupLabel != currentGroup) {
                result += RosterRow.Header(groupLabel)
                currentGroup = groupLabel
            }
            result += RosterRow.Item(p)
        }
        return result
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is RosterRow.Header -> VIEW_TYPE_HEADER
        is RosterRow.Item -> VIEW_TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val binding = ItemSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            HeaderViewHolder(binding)
        } else {
            val binding = ItemRosterEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            ItemViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is RosterRow.Header -> (holder as HeaderViewHolder).bind(row.title)
            is RosterRow.Item -> (holder as ItemViewHolder).bind(row.person)
        }
    }

    override fun getItemCount(): Int = rows.size

    inner class HeaderViewHolder(private val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            (binding.root as android.widget.TextView).text = title
        }
    }

    inner class ItemViewHolder(private val binding: ItemRosterEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(person: PersonEntity) {
            val context = binding.root.context
            binding.tvName.text = person.name

            val iconRes = when (type) {
                PersonType.PERSONNEL -> R.drawable.ic_personnel
                PersonType.MACHINERY -> R.drawable.ic_machinery
                PersonType.VISITOR -> R.drawable.ic_visitor
                PersonType.DRIVER -> R.drawable.ic_driver
            }

            if (person.imageUri != null) {
                binding.ivTypeIcon.visibility = View.GONE
                binding.ivPhoto.visibility = View.VISIBLE
                Glide.with(context)
                    .load(android.net.Uri.parse(person.imageUri))
                    .placeholder(iconRes)
                    .error(iconRes)
                    .circleCrop()
                    .into(binding.ivPhoto)
            } else {
                binding.ivPhoto.visibility = View.GONE
                binding.ivTypeIcon.visibility = View.VISIBLE
                binding.ivTypeIcon.setImageResource(iconRes)
            }

            val subtitleParts = mutableListOf<String>()
            person.extraInfo?.takeIf { it.isNotBlank() }?.let { subtitleParts += it }
            subtitleParts += context.getString(
                R.string.last_status_format,
                if (person.isInside) context.getString(R.string.status_inside)
                else context.getString(R.string.status_outside)
            )
            serviceCountProvider(person)?.let { count ->
                subtitleParts += context.getString(R.string.service_count_today_format, count)
            }
            binding.tvSubtitle.text = subtitleParts.joinToString(" · ")

            if (person.isBlacklisted) {
                // Blacklisted: neither action makes sense — both buttons dim and inert.
                binding.btnCheckIn.isEnabled = false
                binding.btnCheckIn.alpha = 0.35f
                binding.btnCheckOut.isEnabled = false
                binding.btnCheckOut.alpha = 0.35f
            } else if (person.isInside) {
                // Currently inside -> checking out is the valid action, so خروج (red) is lit;
                // ورود (green) dims since they can't check in again while already inside.
                binding.btnCheckOut.isEnabled = true
                binding.btnCheckOut.alpha = 1f
                binding.btnCheckIn.isEnabled = false
                binding.btnCheckIn.alpha = 0.35f
            } else {
                // Currently outside -> checking in is the valid action, so ورود (green) is lit;
                // خروج (red) dims since they can't check out again while already outside.
                binding.btnCheckIn.isEnabled = true
                binding.btnCheckIn.alpha = 1f
                binding.btnCheckOut.isEnabled = false
                binding.btnCheckOut.alpha = 0.35f
            }
            binding.btnCheckIn.setOnClickListener { onCheckIn(person) }
            binding.btnCheckOut.setOnClickListener { onCheckOut(person) }
            binding.btnCheckOut.setOnLongClickListener {
                if (binding.btnCheckOut.isEnabled) onCheckOutLongPress(person)
                true
            }
            binding.root.setOnLongClickListener {
                onLongClick(person)
                true
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ITEM = 1
    }
}
