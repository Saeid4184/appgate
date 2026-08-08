package ir.factory.entryexit.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ir.factory.entryexit.R
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.databinding.ActivityAdminDashboardBinding
import ir.factory.entryexit.databinding.ItemPersonBadgeBinding
import ir.factory.entryexit.viewmodel.FactoryViewModel

/** Live monitoring hub: real-time counts per category, a combined "everyone currently inside"
 *  list across the whole factory, and shortcuts into Reports and initial Setup. */
class AdminDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminDashboardBinding
    private lateinit var viewModel: FactoryViewModel
    private lateinit var adapter: CombinedInsideAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]

        binding.toolbar.title = getString(R.string.dashboard_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = CombinedInsideAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnOpenReports.setOnClickListener { startActivity(Intent(this, ReportActivity::class.java)) }
        binding.btnOpenSetup.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

        viewModel.insideByType(PersonType.PERSONNEL).observe(this) { binding.tvCountPersonnel.text = it.size.toString() }
        viewModel.insideByType(PersonType.MACHINERY).observe(this) { binding.tvCountMachinery.text = it.size.toString() }
        viewModel.insideByType(PersonType.VISITOR).observe(this) { binding.tvCountVisitor.text = it.size.toString() }
        viewModel.insideByType(PersonType.DRIVER).observe(this) { binding.tvCountDriver.text = it.size.toString() }

        viewModel.allCurrentlyInside().observe(this) { list ->
            adapter.submit(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        val startOfToday = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        viewModel.itemLogsSince(startOfToday).observe(this) { todaysItemLogs ->
            binding.tvCountItemsInToday.text = todaysItemLogs.count { it.direction == ir.factory.entryexit.data.Repository.ITEM_DIRECTION_IN }.toString()
            binding.tvCountItemsOutToday.text = todaysItemLogs.count { it.direction == ir.factory.entryexit.data.Repository.ITEM_DIRECTION_OUT }.toString()
        }
    }

    private class CombinedInsideAdapter : RecyclerView.Adapter<CombinedInsideAdapter.VH>() {
        private var items: List<PersonEntity> = emptyList()

        fun submit(list: List<PersonEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemPersonBadgeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        class VH(private val binding: ItemPersonBadgeBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(person: PersonEntity) {
                val context = binding.root.context
                binding.tvName.text = person.name
                val type = runCatching { PersonType.valueOf(person.type) }.getOrDefault(PersonType.PERSONNEL)
                val iconRes = when (type) {
                    PersonType.PERSONNEL -> R.drawable.ic_personnel
                    PersonType.MACHINERY -> R.drawable.ic_machinery
                    PersonType.VISITOR -> R.drawable.ic_visitor
                    PersonType.DRIVER -> R.drawable.ic_driver
                }
                binding.ivPhoto.visibility = View.GONE
                binding.ivTypeIcon.visibility = View.VISIBLE
                binding.ivTypeIcon.setImageResource(iconRes)

                binding.tvSubtitle.text = listOfNotNull(type.displayName, person.group).joinToString(" · ")

                binding.tvStatusBadge.text = context.getString(R.string.status_inside)
                binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_inside)
                binding.tvStatusBadge.setTextColor(context.getColor(R.color.status_green))

                binding.root.setOnClickListener {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_JUMP_TO_TYPE, person.type)
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                }
            }
        }
    }
}
