package ir.factory.entryexit.ui.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.factory.entryexit.R
import ir.factory.entryexit.data.Department
import ir.factory.entryexit.data.ItemLogEntity
import ir.factory.entryexit.data.Repository
import ir.factory.entryexit.databinding.DialogItemLogInBinding
import ir.factory.entryexit.databinding.DialogItemLogOutBinding
import ir.factory.entryexit.databinding.FragmentItemLogListBinding
import ir.factory.entryexit.databinding.ItemItemLogBinding
import ir.factory.entryexit.viewmodel.FactoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab 6: "ورود و خروج اقلام و کالاها". A FAB opens a chooser between the two forms (see chat
 * proposal #3 — FAB + dialog instead of a dedicated screen per direction); the chip row filters
 * the same list into ورود / خروج / در انتظار برگشت (proposals #1 + #7 — a dedicated sub-tab for
 * items that went out and haven't come back, shown as a plain filter rather than any kind of
 * alert since return status isn't considered urgent). Every free-text field on both forms
 * autocompletes from values entered before (proposal #9), refreshed from the DB each time a
 * dialog opens so brand-new entries from any device show up immediately.
 */
class ItemLogListFragment : Fragment(R.layout.fragment_item_log_list) {

    private val viewModel: FactoryViewModel by activityViewModels()
    private var _binding: FragmentItemLogListBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ItemLogAdapter

    private var inLogs: List<ItemLogEntity> = emptyList()
    private var outLogs: List<ItemLogEntity> = emptyList()
    private var pendingReturnLogs: List<ItemLogEntity> = emptyList()
    private var selectedFilter: Filter = Filter.IN

    private enum class Filter { IN, OUT, PENDING_RETURN }

    private fun forceVisibleButtons(dialog: AlertDialog) {
        val color = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.safety_amber_dark)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(color)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(color)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(color)
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentItemLogListBinding.bind(view)

        adapter = ItemLogAdapter { log -> markReturned(log) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.chipGroupItemLogFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFilter = when (checkedIds.firstOrNull()) {
                binding.chipItemLogOut.id -> Filter.OUT
                binding.chipItemLogPendingReturn.id -> Filter.PENDING_RETURN
                else -> Filter.IN
            }
            render()
        }

        binding.fabAdd.setOnClickListener { showDirectionChooser() }

        viewModel.itemLogsByDirection(Repository.ITEM_DIRECTION_IN).observe(viewLifecycleOwner) {
            inLogs = it
            render()
        }
        viewModel.itemLogsByDirection(Repository.ITEM_DIRECTION_OUT).observe(viewLifecycleOwner) {
            outLogs = it
            render()
        }
        viewModel.pendingReturnItemLogs().observe(viewLifecycleOwner) {
            pendingReturnLogs = it
            render()
        }
    }

    private fun render() {
        val list = when (selectedFilter) {
            Filter.IN -> inLogs
            Filter.OUT -> outLogs
            Filter.PENDING_RETURN -> pendingReturnLogs
        }
        adapter.submit(list, showReturnAction = selectedFilter != Filter.IN)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmpty.text = if (selectedFilter == Filter.PENDING_RETURN) {
            getString(R.string.item_log_empty_pending_return)
        } else {
            getString(R.string.item_log_empty_list)
        }
    }

    private fun markReturned(log: ItemLogEntity) {
        viewModel.markItemReturned(log.id) { result ->
            result.onSuccess {
                toast(getString(R.string.item_log_marked_returned_success))
            }.onFailure { error ->
                toast(error.message ?: getString(R.string.error_generic))
            }
        }
    }

    // ---- FAB: choose ورود or خروج ----

    private fun showDirectionChooser() {
        val options = arrayOf(getString(R.string.item_log_add_in), getString(R.string.item_log_add_out))
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.item_log_choose_direction_title)
            .setItems(options) { _, which -> if (which == 0) showInDialog() else showOutDialog() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
        forceVisibleButtons(dialog)
    }

    private fun dropdown(list: List<String>) = ArrayAdapter(requireContext(), R.layout.item_dropdown_option, list)

    // ---- ثبت ورود کالا ----

    private fun showInDialog() {
        val dialogBinding = DialogItemLogInBinding.inflate(LayoutInflater.from(requireContext()))

        viewModel.itemLogSuggestions { suggestions ->
            if (!isAdded) return@itemLogSuggestions
            dialogBinding.etItemName.setAdapter(dropdown(suggestions.itemNames))
            dialogBinding.etStore.setAdapter(dropdown(suggestions.stores))
            dialogBinding.etBuyer.setAdapter(dropdown(suggestions.buyers))
            dialogBinding.etOrderedBy.setAdapter(dropdown(suggestions.orderedBy))
            val departmentOptions = (Department.values().map { it.displayName } + suggestions.departments).distinct()
            dialogBinding.etDepartment.setAdapter(dropdown(departmentOptions))
        }
        listOf(dialogBinding.etItemName, dialogBinding.etStore, dialogBinding.etBuyer, dialogBinding.etOrderedBy, dialogBinding.etDepartment)
            .forEach { it.threshold = 1 }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.item_log_add_in)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            forceVisibleButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val itemName = dialogBinding.etItemName.text?.toString().orEmpty()
                if (itemName.isBlank()) {
                    dialogBinding.tilItemName.error = getString(R.string.error_name_empty)
                    return@setOnClickListener
                }
                dialogBinding.tilItemName.error = null
                viewModel.addItemLogIn(
                    itemName = itemName,
                    storeName = dialogBinding.etStore.text?.toString(),
                    buyerName = dialogBinding.etBuyer.text?.toString(),
                    orderedByName = dialogBinding.etOrderedBy.text?.toString(),
                    department = dialogBinding.etDepartment.text?.toString(),
                    invoiceNumber = dialogBinding.etInvoiceNumber.text?.toString()
                ) { result ->
                    result.onSuccess {
                        toast(getString(R.string.item_log_add_success))
                        dialog.dismiss()
                    }.onFailure { error ->
                        dialogBinding.tilItemName.error = error.message ?: getString(R.string.error_generic)
                    }
                }
            }
        }
        dialog.show()
    }

    // ---- ثبت خروج کالا ----

    private fun showOutDialog() {
        val dialogBinding = DialogItemLogOutBinding.inflate(LayoutInflater.from(requireContext()))

        viewModel.itemLogSuggestions { suggestions ->
            if (!isAdded) return@itemLogSuggestions
            dialogBinding.etItemName.setAdapter(dropdown(suggestions.itemNames))
            dialogBinding.etCarrier.setAdapter(dropdown(suggestions.carriers))
            dialogBinding.etOrderedBy.setAdapter(dropdown(suggestions.orderedBy))
            dialogBinding.etReason.setAdapter(dropdown(suggestions.reasons))
        }
        listOf(dialogBinding.etItemName, dialogBinding.etCarrier, dialogBinding.etOrderedBy, dialogBinding.etReason)
            .forEach { it.threshold = 1 }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.item_log_add_out)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            forceVisibleButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val itemName = dialogBinding.etItemName.text?.toString().orEmpty()
                if (itemName.isBlank()) {
                    dialogBinding.tilItemName.error = getString(R.string.error_name_empty)
                    return@setOnClickListener
                }
                dialogBinding.tilItemName.error = null
                viewModel.addItemLogOut(
                    itemName = itemName,
                    exitSlipNumber = dialogBinding.etExitSlipNumber.text?.toString(),
                    carrierName = dialogBinding.etCarrier.text?.toString(),
                    orderedByName = dialogBinding.etOrderedBy.text?.toString(),
                    reason = dialogBinding.etReason.text?.toString()
                ) { result ->
                    result.onSuccess {
                        toast(getString(R.string.item_log_add_success))
                        dialog.dismiss()
                    }.onFailure { error ->
                        dialogBinding.tilItemName.error = error.message ?: getString(R.string.error_generic)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---- Row adapter ----

    private inner class ItemLogAdapter(
        private val onMarkReturned: (ItemLogEntity) -> Unit
    ) : RecyclerView.Adapter<ItemLogAdapter.ViewHolder>() {

        private var items: List<ItemLogEntity> = emptyList()
        private var showReturnAction: Boolean = false

        fun submit(newItems: List<ItemLogEntity>, showReturnAction: Boolean) {
            items = newItems
            this.showReturnAction = showReturnAction
            notifyDataSetChanged()
        }

        inner class ViewHolder(val b: ItemItemLogBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val log = items[position]
            val ctx = holder.b.root.context
            val fmt = SimpleDateFormat("yyyy/MM/dd — HH:mm", Locale.US)
            val isIn = log.direction == Repository.ITEM_DIRECTION_IN

            holder.b.tvDirectionBadge.text = if (isIn) getString(R.string.item_log_filter_in) else getString(R.string.item_log_filter_out)
            holder.b.tvDirectionBadge.setBackgroundResource(if (isIn) R.drawable.bg_status_inside else R.drawable.bg_status_outside)
            holder.b.tvDirectionBadge.setTextColor(
                androidx.core.content.ContextCompat.getColor(ctx, if (isIn) R.color.status_green else R.color.danger_red)
            )
            holder.b.tvItemName.text = log.itemName
            holder.b.tvTimestamp.text = fmt.format(Date(log.timestamp))

            holder.b.tvDetails.text = if (isIn) {
                listOfNotNull(
                    log.storeName?.let { getString(R.string.item_log_detail_store_format, it) },
                    log.buyerName?.let { getString(R.string.item_log_detail_buyer_format, it) },
                    log.orderedByName?.let { getString(R.string.item_log_detail_ordered_by_format, it) },
                    log.department?.let { getString(R.string.item_log_detail_department_format, it) },
                    log.invoiceNumber?.let { getString(R.string.item_log_detail_invoice_format, it) }
                ).joinToString(" — ")
            } else {
                listOfNotNull(
                    log.exitSlipNumber?.let { getString(R.string.item_log_detail_exit_slip_format, it) },
                    log.carrierName?.let { getString(R.string.item_log_detail_carrier_format, it) },
                    log.orderedByName?.let { getString(R.string.item_log_detail_ordered_by_format, it) },
                    log.reason?.let { getString(R.string.item_log_detail_reason_format, it) }
                ).joinToString(" — ")
            }

            val eligibleForReturnTracking = !isIn
            holder.b.tvReturnStatus.visibility = if (eligibleForReturnTracking && log.isReturned) View.VISIBLE else View.GONE
            holder.b.tvReturnStatus.text = getString(R.string.item_log_returned_badge)

            holder.b.btnMarkReturned.visibility =
                if (eligibleForReturnTracking && !log.isReturned && showReturnAction) View.VISIBLE else View.GONE
            holder.b.btnMarkReturned.setOnClickListener { onMarkReturned(log) }
        }

        override fun getItemCount(): Int = items.size
    }
}
