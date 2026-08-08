package ir.factory.entryexit.ui.fragments

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.factory.entryexit.R
import ir.factory.entryexit.data.Department
import ir.factory.entryexit.data.MachineryCategory
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.databinding.DialogAddPersonBinding
import ir.factory.entryexit.databinding.DialogLogisticsDetailBinding
import ir.factory.entryexit.databinding.DialogManualCheckinBinding
import ir.factory.entryexit.databinding.FragmentCategoryBinding
import ir.factory.entryexit.ui.GroupedPersonAdapter
import ir.factory.entryexit.util.AppPreferences
import ir.factory.entryexit.viewmodel.FactoryViewModel
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
/**
 * One fragment class drives all four tabs. Personnel/Machinery/Driver behave as a persistent,
 * roster (register once, then repeat check-in/out; Machinery is additionally grouped by
 * sub-fleet and Driver prompts once per trip for the vehicle). Visitors behave as a transient,
 * manual-entry log (a fresh record is created on every check-in, so only the currently-inside
 * list is shown).
 */
class CategoryFragment : Fragment(R.layout.fragment_category) {

    private val viewModel: FactoryViewModel by activityViewModels()
    private var _binding: FragmentCategoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var type: PersonType
    private lateinit var adapter: GroupedPersonAdapter
    private var rawList: List<PersonEntity> = emptyList()
    private var selectedMachineryCategory: ir.factory.entryexit.data.MachineryCategory? = null

    /** Mixers only: today's dispatch count per vehicle (personId -> count), kept live so the
     *  row badge updates the moment a new service is logged from any device. Not used by any
     *  other machinery category. */
    private var mixerServiceCounts: Map<String, Int> = emptyMap()

    private val isManualEntry: Boolean
        get() = type == PersonType.VISITOR || type == PersonType.DRIVER

    /** Only visitors are truly one-off now — drivers are a persistent roster like
     *  personnel/machinery (register once, then repeat check-in/out), so a driver's name and
     *  history stick around after checkout instead of disappearing and needing to be re-typed
     *  on their next visit. */
    private val isTransient: Boolean
        get() = type == PersonType.VISITOR

    /** Dialog buttons default to colorPrimary text, which can end up nearly invisible against
     *  the dialog's own surface in dark mode. This forces a guaranteed-visible color at runtime
     *  on top of the theme-level fix (see ThemeOverlay.ConcreteFactory.Dialog). */
    private fun forceVisibleButtons(dialog: AlertDialog) {
        val color = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.safety_amber_dark)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(color)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(color)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(color)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = PersonType.valueOf(requireArguments().getString(ARG_TYPE)!!)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentCategoryBinding.bind(view)

        setupList()
        setupFab()
        setupSearch()
        setupMachineryFilter()
        observeData()

        binding.swipeRefresh.setOnRefreshListener {
            // Room's LiveData is already live; this just gives reassuring pull-to-refresh feedback.
            binding.swipeRefresh.isRefreshing = false
        }
    }

    /** Machinery only: four sub-fleet chips (میکسر/کمپرسی‌وتریلی/پمپ بتن/تدارکات) that filter
     *  the roster and scope the checkout cargo-type options to that vehicle's own category —
     *  a mixer is never offered aggregate cargo, a dump truck is never offered ready-mix, etc. */
    private fun setupMachineryFilter() {
        if (type != PersonType.MACHINERY) return
        binding.scrollMachineryFilter.visibility = View.VISIBLE

        // Both this row and the enclosing ViewPager2 (Personnel/Machinery/... tabs) are
        // horizontal, so without this, ANY horizontal drag that starts on the chip row gets
        // stolen by the pager as a tab swipe after only a few pixels of movement — a
        // HorizontalScrollView, unlike a RecyclerView, doesn't claim the gesture from its
        // parent on its own. Telling the parent chain "don't intercept" for the duration of
        // this one touch sequence is the standard fix for a horizontally-scrolling child
        // inside a horizontally-swiping pager.
        binding.scrollMachineryFilter.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        val chipToCategory = mapOf(
            binding.chipMachineryMixer.id to ir.factory.entryexit.data.MachineryCategory.MIXER,
            binding.chipMachineryDumpTruck.id to ir.factory.entryexit.data.MachineryCategory.DUMP_TRUCK,
            binding.chipMachineryPump.id to ir.factory.entryexit.data.MachineryCategory.CONCRETE_PUMP,
            binding.chipMachineryLogistics.id to ir.factory.entryexit.data.MachineryCategory.LOGISTICS
        )

        binding.chipGroupMachineryFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull()
            selectedMachineryCategory = chipToCategory[checkedId] // null when "همه" is selected
            applyFilter(binding.etSearch.text?.toString().orEmpty())
        }
    }

    private fun setupList() {
        adapter = GroupedPersonAdapter(
            type,
            showGroups = !isManualEntry,
            onCheckIn = { person -> performCheckIn(person) },
            onCheckOut = { person -> performCheckOut(person) },
            onLongClick = { person -> if (!isManualEntry) showEditPersonDialog(person) },
            onCheckOutLongPress = { person -> performCheckOutFullOptions(person) },
            serviceCountProvider = { person -> serviceCountFor(person) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        binding.tvLongPressHint.visibility = if (isManualEntry) View.GONE else View.VISIBLE
        if (type == PersonType.MACHINERY) {
            binding.tvLongPressHint.text =
                getString(R.string.long_press_hint) + " · " + getString(R.string.long_press_for_options_hint)
        }
        if (type == PersonType.MACHINERY) observeMixerServiceCounts()
    }

    /** Today's per-vehicle dispatch count, shown only for mixers (see the class doc on
     *  [mixerServiceCounts] for why: exact timing doesn't matter for mixers, the running count
     *  of services in the day does). */
    private fun serviceCountFor(person: PersonEntity): Int? {
        if (type != PersonType.MACHINERY) return null
        if (ir.factory.entryexit.data.MachineryCategory.classify(person.group) != MachineryCategory.MIXER) return null
        return mixerServiceCounts[person.id] ?: 0
    }

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun observeMixerServiceCounts() {
        viewModel.machineryDispatchesSince(startOfTodayMillis()).observe(viewLifecycleOwner) { logs ->
            mixerServiceCounts = logs.groupingBy { it.personId }.eachCount()
            adapter.refreshBadges()
        }
    }

    override fun onResume() {
        super.onResume()
        // Preferences may have changed in the Settings screen since this fragment was created.
        applyFilter(binding.etSearch.text?.toString().orEmpty())
        if (!AppPreferences.isRecentActivityVisible(requireContext())) {
            binding.tvRecentActivity.visibility = View.GONE
        }
    }

    private fun setupFab() {
        binding.fabAdd.text = if (isManualEntry) {
            getString(if (type == PersonType.VISITOR) R.string.new_visitor_checkin_title else R.string.new_driver_checkin_title)
        } else {
            getString(R.string.add_new)
        }
        binding.fabAdd.setOnClickListener {
            if (isManualEntry) showManualCheckInDialog() else showAddPersonDialog()
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeData() {
        val listSource = if (isTransient) viewModel.insideByType(type) else viewModel.personsByType(type)
        listSource.observe(viewLifecycleOwner) { list ->
            rawList = list
            applyFilter(binding.etSearch.text?.toString().orEmpty())
        }

        viewModel.insideByType(type).observe(viewLifecycleOwner) { insideList ->
            binding.tvInsideCount.text = getString(R.string.inside_count_format, insideList.size)
        }

        viewModel.recentActivity(type).observe(viewLifecycleOwner) { logs ->
            if (!AppPreferences.isRecentActivityVisible(requireContext())) {
                binding.tvRecentActivity.visibility = View.GONE
                return@observe
            }
            val latest = logs.firstOrNull()
            if (latest == null) {
                binding.tvRecentActivity.visibility = View.GONE
            } else {
                binding.tvRecentActivity.visibility = View.VISIBLE
                binding.tvRecentActivity.text = if (latest.action == "IN") {
                    getString(R.string.log_entered_format, latest.personName)
                } else {
                    getString(R.string.log_exited_format, latest.personName)
                }
            }
        }
    }

    private fun applyFilter(query: String) {
        var filtered = if (query.isBlank()) {
            rawList
        } else {
            rawList.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.group?.contains(query, ignoreCase = true) == true
            }
        }

        selectedMachineryCategory?.let { category ->
            filtered = filtered.filter { ir.factory.entryexit.data.MachineryCategory.classify(it.group) == category }
        }

        if (!isTransient && AppPreferences.isInsideFirstSort(requireContext())) {
            // Keep group order intact (list already sorted by group,name from Room), but within
            // each group, show currently-inside items first.
            val groupOrder = LinkedHashSet<String>()
            for (p in filtered) groupOrder.add(p.group ?: "سایر")
            val byGroup = filtered.groupBy { it.group ?: "سایر" }
            filtered = groupOrder.flatMap { g ->
                byGroup[g].orEmpty().sortedWith(compareByDescending<PersonEntity> { it.isInside }.thenBy { it.name })
            }
        }

        adapter.submit(filtered)

        val emptyRes = when {
            filtered.isNotEmpty() -> null
            query.isNotBlank() -> R.string.empty_search
            isTransient -> R.string.empty_list_inside
            else -> R.string.empty_list_roster
        }
        binding.tvEmpty.visibility = if (emptyRes != null) View.VISIBLE else View.GONE
        emptyRes?.let { binding.tvEmpty.text = getString(it) }
    }

    // ---- Dedicated ورود/خروج buttons on each row (Personnel / Machinery / Driver) ----

    /** Every type now checks in immediately with a single tap — including drivers, since
     *  "ورود" for a driver just means arriving at the factory for their shift/service, not the
     *  start of a specific trip, so there's nothing trip-specific to ask for here.
     *  Exception: MACHINERY/LOGISTICS ("تدارکات") vehicles — no goods may enter the factory
     *  without an invoice number, so those get one quick prompt first (with an escape hatch for
     *  a vehicle that's simply arriving empty). */
    private fun performCheckIn(person: PersonEntity) {
        if (type == PersonType.MACHINERY &&
            ir.factory.entryexit.data.MachineryCategory.classify(person.group) == MachineryCategory.LOGISTICS
        ) {
            showLogisticsDocDialog(
                titleRes = R.string.logistics_invoice_dialog_title,
                hintRes = R.string.logistics_invoice_hint,
                noCargoRes = R.string.logistics_invoice_no_cargo,
                requiredErrorRes = R.string.logistics_invoice_required_error
            ) { detail -> checkInWithDetail(person, detail) }
        } else {
            checkInWithDetail(person, null)
        }
    }

    private fun checkInWithDetail(person: PersonEntity, detail: String?) {
        viewModel.checkIn(person.id, detail) { result -> handleActionResult(result.map { }, R.string.checkin_success) }
    }

    /** One tap on خروج = an instant "routine" checkout using each category's first-listed cargo
     *  option (admins order that list in Settings, so the most common option per fleet sits
     *  first) — this is deliberately friction-free since for most categories what matters is
     *  simply that the exit got logged promptly. Holding the button down instead
     *  ([performCheckOutFullOptions]) opens the full cargo-type list for the less-common cases. */
    private fun performCheckOut(person: PersonEntity) {
        if (type != PersonType.MACHINERY) {
            checkOutWithDetail(person, null)
            return
        }
        val category = ir.factory.entryexit.data.MachineryCategory.classify(person.group)
        val routine = AppPreferences.getCargoOptions(requireContext(), category).firstOrNull()
        proceedWithMachineryCheckout(person, category, routine)
    }

    /** Long-press on خروج: shows the full per-category cargo/load list so the guard can pick
     *  anything other than the one-tap routine default. */
    private fun performCheckOutFullOptions(person: PersonEntity) {
        if (type != PersonType.MACHINERY) return
        val category = ir.factory.entryexit.data.MachineryCategory.classify(person.group)
        val cargoTypes = AppPreferences.getCargoOptions(requireContext(), category).toTypedArray()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cargo_type_title)
            .setItems(cargoTypes) { _, which -> proceedWithMachineryCheckout(person, category, cargoTypes[which]) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
        forceVisibleButtons(dialog)
    }

    /** Routes a machinery checkout through whatever that category needs beyond just logging the
     *  cargo type: LOGISTICS needs an exit-slip number before it's final (unless genuinely
     *  empty), every other category checks out immediately. */
    private fun proceedWithMachineryCheckout(person: PersonEntity, category: MachineryCategory, cargoType: String?) {
        val emptyLoadOptions = setOf("بدون بار (خالی)", "بدون ماموریت")
        if (category == MachineryCategory.LOGISTICS && cargoType !in emptyLoadOptions) {
            showLogisticsDocDialog(
                titleRes = R.string.logistics_exit_slip_dialog_title,
                hintRes = R.string.logistics_exit_slip_hint,
                noCargoRes = R.string.logistics_exit_slip_no_cargo,
                requiredErrorRes = R.string.logistics_exit_slip_required_error
            ) { docNumber ->
                val detail = if (docNumber != null) "$cargoType — برگه خروج: $docNumber" else cargoType
                checkOutWithDetail(person, detail, category)
            }
        } else {
            checkOutWithDetail(person, cargoType, category)
        }
    }

    private fun checkOutWithDetail(person: PersonEntity, detail: String?, category: MachineryCategory? = null) {
        viewModel.checkOut(person.id, detail) { result ->
            result.onSuccess {
                performHaptic()
                if (category == MachineryCategory.CONCRETE_PUMP) {
                    showPumpDispatchSnackbar(person)
                } else {
                    showUndoSnackbar(person)
                }
                if (category == MachineryCategory.DUMP_TRUCK) {
                    checkDumpTruckDailyLimit(person)
                }
            }.onFailure { error ->
                toast(error.message ?: getString(R.string.error_generic))
            }
        }
    }

    /** پمپ‌ها: زمان دقیق خروج از کارخانه مهم‌ترین نکته است (باید سر وقت به پروژه برسند)، پس آن
     *  را به‌جای پیام عمومی خروج، مستقیماً در تاییدیه نشان می‌دهیم. */
    private fun showPumpDispatchSnackbar(person: PersonEntity) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, getString(R.string.pump_dispatch_time_format, person.name, time), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction(R.string.btn_undo) {
                viewModel.checkIn(person.id) { undoResult ->
                    undoResult.onFailure { error -> toast(error.message ?: getString(R.string.error_generic)) }
                }
            }
            .show()
    }

    /** کمپرسی/کمپرسی‌تریلی: در یک شبانه‌روز (۲۴ ساعت) معمولاً ۴ سرویس و گاهی با ترافیک سبک ۵
     *  سرویس معدن-تا-کارخانه طبیعی است؛ رسیدن به سرویس ششم یا بیشتر می‌تواند نشانه‌ی تعویض
     *  ناوگان بدون ثبت، خستگی/فشار بیش‌ازحد به راننده، یا مصرف مواد باشد و باید بررسی شود. این
     *  هشدار فقط اطلاع‌رسانی است — خروجی که همین الان ثبت شد لغو نمی‌شود. */
    private fun checkDumpTruckDailyLimit(person: PersonEntity) {
        viewModel.countDispatchesInLast24h(person.id) { count ->
            if (count >= 6 && isAdded) {
                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dump_truck_warning_title)
                    .setMessage(getString(R.string.dump_truck_warning_message_format, count))
                    .setPositiveButton(R.string.dump_truck_warning_ack, null)
                    .show()
                forceVisibleButtons(dialog)
            }
        }
    }

    /** Shared invoice-number (check-in) / exit-slip-number (check-out) prompt for LOGISTICS
     *  ("تدارکات") vehicles: no goods enter or leave the factory without matching paperwork,
     *  but a vehicle simply arriving/leaving empty shouldn't be blocked on a document number
     *  that doesn't exist — hence the checkbox escape hatch. [onConfirm] receives the entered
     *  number, or null when "no cargo" was checked. */
    private fun showLogisticsDocDialog(
        titleRes: Int,
        hintRes: Int,
        noCargoRes: Int,
        requiredErrorRes: Int,
        onConfirm: (String?) -> Unit
    ) {
        val dialogBinding = DialogLogisticsDetailBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.tilDocNumber.hint = getString(hintRes)
        dialogBinding.cbNoCargo.text = getString(noCargoRes)
        dialogBinding.cbNoCargo.setOnCheckedChangeListener { _, checked ->
            dialogBinding.tilDocNumber.isEnabled = !checked
            dialogBinding.tilDocNumber.error = null
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_confirm_checkout, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            forceVisibleButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (dialogBinding.cbNoCargo.isChecked) {
                    dialog.dismiss()
                    onConfirm(null)
                    return@setOnClickListener
                }
                val docNumber = dialogBinding.etDocNumber.text?.toString()?.trim().orEmpty()
                if (docNumber.isEmpty()) {
                    dialogBinding.tilDocNumber.error = getString(requiredErrorRes)
                    return@setOnClickListener
                }
                dialog.dismiss()
                onConfirm(docNumber)
            }
        }
        dialog.show()
    }

    private fun showUndoSnackbar(person: PersonEntity) {
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, getString(R.string.log_exited_format, person.name), com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction(R.string.btn_undo) {
                // Re-check the person back in, undoing the checkout that was just logged.
                viewModel.checkIn(person.id) { undoResult ->
                    undoResult.onFailure { error -> toast(error.message ?: getString(R.string.error_generic)) }
                }
            }
            .show()
    }

    private fun showAddPersonDialog() {
        val dialogBinding = DialogAddPersonBinding.inflate(LayoutInflater.from(requireContext()))

        if (type == PersonType.PERSONNEL) {
            dialogBinding.tilGroup.hint = getString(R.string.hint_department)
            val departments = Department.values().map { it.displayName }
            dialogBinding.etGroup.setAdapter(
                ArrayAdapter(requireContext(), R.layout.item_dropdown_option, departments)
            )
            dialogBinding.tilExtraInfo.hint = getString(R.string.hint_extra_info)
        } else {
            // MACHINERY: free-text fleet/model group, no fixed list.
            dialogBinding.tilGroup.hint = getString(R.string.hint_machinery_group)
            dialogBinding.etGroup.inputType = android.text.InputType.TYPE_CLASS_TEXT
            dialogBinding.tilExtraInfo.hint = getString(R.string.hint_extra_info_machinery)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_new_person_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_save, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            forceVisibleButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.etName.text?.toString().orEmpty()
                if (name.isBlank()) {
                    dialogBinding.tilName.error = getString(R.string.error_name_empty)
                    return@setOnClickListener
                }
                val group = dialogBinding.etGroup.text?.toString()
                val extra = dialogBinding.etExtraInfo.text?.toString()
                viewModel.addPerson(name, type, group, extra) { result ->
                    result.onSuccess {
                        performHaptic()
                        toast(getString(R.string.person_added_success))
                        dialog.dismiss()
                    }.onFailure { error ->
                        dialogBinding.tilName.error = error.message ?: getString(R.string.error_generic)
                    }
                }
            }
        }
        dialog.show()
    }

    // ---- Editing an existing Personnel/Machinery entry (long-press) ----

    private fun showEditPersonDialog(person: PersonEntity) {
        val dialogBinding = DialogAddPersonBinding.inflate(LayoutInflater.from(requireContext()))
        dialogBinding.etName.setText(person.name)
        dialogBinding.etExtraInfo.setText(person.extraInfo)
        dialogBinding.switchBlacklist.visibility = View.VISIBLE
        dialogBinding.switchBlacklist.isChecked = person.isBlacklisted

        if (type == PersonType.PERSONNEL) {
            dialogBinding.tilGroup.hint = getString(R.string.hint_department)
            val departments = Department.values().map { it.displayName }
            dialogBinding.etGroup.setAdapter(
                ArrayAdapter(requireContext(), R.layout.item_dropdown_option, departments)
            )
            dialogBinding.etGroup.setText(person.group, false)
            dialogBinding.tilExtraInfo.hint = getString(R.string.hint_extra_info)
        } else {
            dialogBinding.tilGroup.hint = getString(R.string.hint_machinery_group)
            dialogBinding.etGroup.inputType = android.text.InputType.TYPE_CLASS_TEXT
            dialogBinding.etGroup.setText(person.group)
            dialogBinding.tilExtraInfo.hint = getString(R.string.hint_extra_info_machinery)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.edit_person_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_edit, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            forceVisibleButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.etName.text?.toString().orEmpty()
                if (name.isBlank()) {
                    dialogBinding.tilName.error = getString(R.string.error_name_empty)
                    return@setOnClickListener
                }
                val group = dialogBinding.etGroup.text?.toString()
                val extra = dialogBinding.etExtraInfo.text?.toString()
                val blacklisted = dialogBinding.switchBlacklist.isChecked
                viewModel.updatePerson(person.id, name, group, extra) { result ->
                    result.onSuccess {
                        viewModel.setBlacklisted(person.id, blacklisted) { }
                        performHaptic()
                        toast(getString(R.string.edit_success))
                        dialog.dismiss()
                    }.onFailure { error ->
                        dialogBinding.tilName.error = error.message ?: getString(R.string.error_generic)
                    }
                }
            }
        }
        dialog.show()
    }

    // ---- Manual-entry mode (Visitor / Driver): tap FAB -> name + department/vehicle ----

    private fun showManualCheckInDialog() {
        val dialogBinding = DialogManualCheckinBinding.inflate(LayoutInflater.from(requireContext()))

        val isVisitor = type == PersonType.VISITOR
        dialogBinding.tilPrimary.hint = getString(if (isVisitor) R.string.hint_visitor_name else R.string.hint_driver_name)
        dialogBinding.tilSecondary.hint = getString(if (isVisitor) R.string.hint_visitor_department else R.string.hint_driver_vehicle)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isVisitor) R.string.new_visitor_checkin_title else R.string.new_driver_checkin_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_checkin, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()

        dialog.setOnShowListener {
            forceVisibleButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val primary = dialogBinding.etPrimary.text?.toString().orEmpty()
                val secondary = dialogBinding.etSecondary.text?.toString().orEmpty()

                var hasError = false
                if (primary.isBlank()) {
                    dialogBinding.tilPrimary.error = getString(R.string.error_name_empty)
                    hasError = true
                } else {
                    dialogBinding.tilPrimary.error = null
                }
                if (isVisitor && secondary.isBlank()) {
                    dialogBinding.tilSecondary.error = getString(R.string.error_department_empty)
                    hasError = true
                } else {
                    dialogBinding.tilSecondary.error = null
                }
                if (hasError) return@setOnClickListener

                val onResult: (Result<Unit>) -> Unit = { result ->
                    result.onSuccess {
                        performHaptic()
                        toast(getString(R.string.checkin_success))
                        dialog.dismiss()
                    }.onFailure { error ->
                        toast(error.message ?: getString(R.string.error_generic))
                    }
                }
                if (isVisitor) {
                    viewModel.checkInVisitor(primary, secondary, onResult)
                } else {
                    viewModel.checkInDriver(primary, secondary, onResult)
                }
            }
        }
        dialog.show()
    }

    // ---- Shared helpers ----

    private fun handleActionResult(result: Result<Unit>, successMessage: Int) {
        result.onSuccess {
            performHaptic()
            toast(getString(successMessage))
        }.onFailure { error ->
            toast(error.message ?: getString(R.string.error_generic))
        }
    }

    /** Confirms a successful two-tap check-in/out with a short haptic buzz. Never crashes the
     *  calling action if the device/permission doesn't cooperate — haptics are a nice-to-have. */
    private fun performHaptic() {
        if (!AppPreferences.isHapticEnabled(requireContext())) return
        runCatching {
            view?.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(35)
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TYPE = "arg_type"

        fun newInstance(type: PersonType): CategoryFragment = CategoryFragment().apply {
            arguments = Bundle().apply { putString(ARG_TYPE, type.name) }
        }
    }
}
