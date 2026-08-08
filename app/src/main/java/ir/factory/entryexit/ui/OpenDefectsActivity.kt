package ir.factory.entryexit.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ir.factory.entryexit.R
import ir.factory.entryexit.data.InspectionEntity
import ir.factory.entryexit.data.InspectionJson
import ir.factory.entryexit.data.InspectionPartResult
import ir.factory.entryexit.databinding.ActivityOpenDefectsBinding
import ir.factory.entryexit.databinding.ItemOpenDefectBinding
import ir.factory.entryexit.viewmodel.FactoryViewModel

/**
 * The repair-closure loop for proposal #5 from chat: every currently-open defect (WARN or BAD,
 * on each vehicle's LATEST inspection, never marked repaired) in one list, with a one-tap
 * "ثبت تعمیر" so a fixed mirror doesn't just sit red forever in the reports. Deliberately only
 * looks at the latest inspection per vehicle — an older defect that a newer inspection already
 * shows as fixed (or that's simply been overwritten by a fresh weekly result) isn't "open"
 * anymore even if nobody ever pressed the button, same logic [InspectionListFragment] already
 * uses to show "this week's" status.
 */
class OpenDefectsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOpenDefectsBinding
    private lateinit var viewModel: FactoryViewModel
    private lateinit var adapter: DefectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOpenDefectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = DefectAdapter { row ->
            viewModel.markPartRepaired(row.inspection.id, row.part.name) { result ->
                result.onSuccess {
                    Toast.makeText(this, R.string.open_defects_repaired_success, Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Toast.makeText(this, e.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
                }
            }
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        viewModel.allInspections().observe(this) { inspections ->
            val latestByVehicle = inspections.groupBy { it.personId }.mapValues { (_, list) -> list.maxByOrNull { it.timestamp }!! }
            val rows = latestByVehicle.values.flatMap { inspection ->
                InspectionJson.parse(inspection.partsJson)
                    .filter { it.ok.not() && it.repairedAt == null }
                    .map { part -> DefectRow(inspection, part) }
            }.sortedByDescending { it.inspection.timestamp }

            adapter.submit(rows)
            binding.tvEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private data class DefectRow(val inspection: InspectionEntity, val part: InspectionPartResult)

    private class DefectAdapter(
        private val onRepair: (DefectRow) -> Unit
    ) : RecyclerView.Adapter<DefectAdapter.ViewHolder>() {

        private var items: List<DefectRow> = emptyList()

        fun submit(newItems: List<DefectRow>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemOpenDefectBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemOpenDefectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val row = items[position]
            val ctx = holder.b.root.context
            holder.b.tvVehicle.text = "${row.inspection.group.orEmpty()} — پلاک ${row.inspection.personName}"
            val statusLabel = if (row.part.status.name == "WARN") ctx.getString(R.string.inspection_status_warn) else ctx.getString(R.string.inspection_status_defect)
            holder.b.tvPart.text = ctx.getString(R.string.open_defects_item_format, row.part.name, statusLabel)
            holder.b.tvRecurringBadge.visibility = if (row.part.recurringSinceTimestamp != null) View.VISIBLE else View.GONE
            holder.b.tvNote.text = row.part.note.orEmpty()
            holder.b.tvNote.visibility = if (row.part.note.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.b.btnRepaired.setOnClickListener { onRepair(row) }
        }

        override fun getItemCount(): Int = items.size
    }
}
