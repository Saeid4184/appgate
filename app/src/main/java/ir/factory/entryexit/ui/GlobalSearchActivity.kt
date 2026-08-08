package ir.factory.entryexit.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import ir.factory.entryexit.databinding.ActivityGlobalSearchBinding
import ir.factory.entryexit.databinding.ItemPersonBadgeBinding
import ir.factory.entryexit.viewmodel.FactoryViewModel

/** Lets a guard find any registered person/machine/visitor/driver by name across all four tabs. */
class GlobalSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGlobalSearchBinding
    private lateinit var viewModel: FactoryViewModel
    private lateinit var adapter: SearchResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlobalSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]

        binding.toolbar.title = getString(R.string.global_search_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = SearchResultAdapter { person ->
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_JUMP_TO_TYPE, person.type)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        viewModel.searchResults.observe(this) { results ->
            adapter.submit(results)
            val hasQuery = binding.etSearch.text?.toString()?.isNotBlank() == true
            binding.tvEmpty.visibility = if (hasQuery && results.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private class SearchResultAdapter(private val onClick: (PersonEntity) -> Unit) :
        RecyclerView.Adapter<SearchResultAdapter.VH>() {

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

        inner class VH(private val binding: ItemPersonBadgeBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(person: PersonEntity) {
                val context = binding.root.context
                binding.tvName.text = person.name
                val type = PersonType.valueOf(person.type)
                val iconRes = when (type) {
                    PersonType.PERSONNEL -> R.drawable.ic_personnel
                    PersonType.MACHINERY -> R.drawable.ic_machinery
                    PersonType.VISITOR -> R.drawable.ic_visitor
                    PersonType.DRIVER -> R.drawable.ic_driver
                }
                binding.ivTypeIcon.setImageResource(iconRes)

                if (person.imageUri != null) {
                    binding.ivTypeIcon.visibility = View.GONE
                    binding.ivPhoto.visibility = View.VISIBLE
                    com.bumptech.glide.Glide.with(context)
                        .load(android.net.Uri.parse(person.imageUri))
                        .placeholder(iconRes)
                        .error(iconRes)
                        .circleCrop()
                        .into(binding.ivPhoto)
                } else {
                    binding.ivPhoto.visibility = View.GONE
                    binding.ivTypeIcon.visibility = View.VISIBLE
                }

                val subtitle = listOfNotNull(type.displayName, person.group).joinToString(" · ")
                binding.tvSubtitle.text = subtitle

                if (person.isInside) {
                    binding.tvStatusBadge.text = context.getString(R.string.status_inside)
                    binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_inside)
                    binding.tvStatusBadge.setTextColor(context.getColor(R.color.status_green))
                } else {
                    binding.tvStatusBadge.text = context.getString(R.string.status_outside)
                    binding.tvStatusBadge.setBackgroundResource(R.drawable.bg_status_outside)
                    binding.tvStatusBadge.setTextColor(context.getColor(R.color.concrete_500))
                }

                binding.root.setOnClickListener { onClick(person) }
            }
        }
    }
}
