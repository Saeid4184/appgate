package ir.factory.entryexit.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import ir.factory.entryexit.data.InspectionEntity
import ir.factory.entryexit.data.InspectionJson
import ir.factory.entryexit.data.MachineryCategory
import ir.factory.entryexit.databinding.ActivityFleetHeatmapBinding
import ir.factory.entryexit.viewmodel.FactoryViewModel
import java.util.Calendar

/**
 * Proposal #1 from chat: instead of reading a text checklist per vehicle, this aggregates every
 * inspection in a date range across the WHOLE fleet of one category onto a single diagram, so
 * a pattern like "left mirror keeps breaking on the mixer fleet" jumps out visually instead of
 * needing to be spotted row-by-row in a spreadsheet.
 */
class FleetHeatmapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFleetHeatmapBinding
    private lateinit var viewModel: FactoryViewModel
    private var rangeStart: Long = 0L
    private var rangeEnd: Long = 0L
    private var allInRange: List<InspectionEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFleetHeatmapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]
        binding.toolbar.setNavigationOnClickListener { finish() }

        rangeStart = intent.getLongExtra(EXTRA_RANGE_START, defaultStart())
        rangeEnd = intent.getLongExtra(EXTRA_RANGE_END, System.currentTimeMillis())

        val chipToCategory = mapOf(
            binding.chipMixer.id to MachineryCategory.MIXER,
            binding.chipPump.id to MachineryCategory.CONCRETE_PUMP,
            binding.chipDump.id to MachineryCategory.DUMP_TRUCK,
            binding.chipLogistics.id to MachineryCategory.LOGISTICS
        )
        binding.chipGroupCategory.setOnCheckedStateChangeListener { _, checkedIds ->
            val category = chipToCategory[checkedIds.firstOrNull()] ?: MachineryCategory.MIXER
            render(category)
        }

        viewModel.inspectionsInRange(rangeStart, rangeEnd) { inspections ->
            allInRange = inspections
            render(MachineryCategory.MIXER)
        }
    }

    private fun render(category: MachineryCategory) {
        val forCategory = allInRange.filter { it.category == category.name }
        val counts = InspectionJson.defectCountsByPart(forCategory)
        binding.diagramView.setHeatmapData(category, counts)
        binding.tvEmpty.visibility = if (forCategory.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun defaultStart(): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -90)
        return cal.timeInMillis
    }

    companion object {
        private const val EXTRA_RANGE_START = "extra_range_start"
        private const val EXTRA_RANGE_END = "extra_range_end"

        fun launch(context: Context, rangeStart: Long? = null, rangeEnd: Long? = null) {
            val intent = Intent(context, FleetHeatmapActivity::class.java)
            rangeStart?.let { intent.putExtra(EXTRA_RANGE_START, it) }
            rangeEnd?.let { intent.putExtra(EXTRA_RANGE_END, it) }
            context.startActivity(intent)
        }
    }
}
