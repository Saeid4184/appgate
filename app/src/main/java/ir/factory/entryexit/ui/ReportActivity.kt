package ir.factory.entryexit.ui

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import ir.factory.entryexit.R
import ir.factory.entryexit.data.InspectionCatalog
import ir.factory.entryexit.data.InspectionEntity
import ir.factory.entryexit.data.ItemLogEntity
import ir.factory.entryexit.data.LogEntity
import ir.factory.entryexit.data.MachineryCategory
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.data.Repository
import ir.factory.entryexit.databinding.ActivityReportBinding
import ir.factory.entryexit.util.AiReportAnalyzer
import ir.factory.entryexit.util.AppPreferences
import ir.factory.entryexit.util.XlsxWriter
import ir.factory.entryexit.viewmodel.FactoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Date-range filtered report screen with a one-tap Excel export, ready for accounting. */
class ReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding
    private lateinit var viewModel: FactoryViewModel

    // Default range = today, in the device's local timezone.
    private var rangeStart: Long = startOfToday()
    private var rangeEnd: Long = endOfToday()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]

        binding.toolbar.title = getString(R.string.report_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        updateDateRangeLabel()
        refreshRowCount()

        binding.btnDateRange.setOnClickListener { showDateRangePicker() }
        binding.btnExport.setOnClickListener { exportToExcel() }
        binding.btnExportInspections.setOnClickListener { exportInspectionsToExcel() }
        binding.btnFleetHeatmap.setOnClickListener { ir.factory.entryexit.ui.FleetHeatmapActivity.launch(this, rangeStart, rangeEnd) }
        binding.btnOpenDefects.setOnClickListener { startActivity(Intent(this, OpenDefectsActivity::class.java)) }
        binding.btnExportItemLogs.setOnClickListener { exportItemLogsToExcel() }
        binding.btnAiAnalyze.setOnClickListener { runAiAnalysis() }
    }

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.report_title))
            .setSelection(androidx.core.util.Pair(rangeStart, rangeEnd))
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            rangeStart = startOfDay(selection.first ?: rangeStart)
            rangeEnd = endOfDay(selection.second ?: rangeEnd)
            updateDateRangeLabel()
            refreshRowCount()
        }
        picker.show(supportFragmentManager, "date_range_picker")
    }

    private fun updateDateRangeLabel() {
        val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.US)
        binding.btnDateRange.text = "${getString(R.string.report_from_date)}: ${fmt.format(Date(rangeStart))}   |   " +
            "${getString(R.string.report_to_date)}: ${fmt.format(Date(rangeEnd))}"
    }

    private fun refreshRowCount() {
        viewModel.exportRange(rangeStart, rangeEnd) { logs ->
            binding.tvRowCount.text = getString(R.string.report_row_count_format, logs.size)
        }
    }

    private fun exportToExcel() {
        viewModel.exportRange(rangeStart, rangeEnd) { logs ->
            if (logs.isEmpty()) {
                Toast.makeText(this, R.string.report_export_empty, Toast.LENGTH_SHORT).show()
                return@exportRange
            }
            launchExport(logs)
        }
    }

    private fun launchExport(logs: List<LogEntity>) {
        lifecycleScope.launch {
            val insideCounts = withContext(Dispatchers.IO) { awaitInsideCounts() }
            val itemLogs = withContext(Dispatchers.IO) { viewModelItemLogsInRangeSuspend() }
            val file = withContext(Dispatchers.IO) { buildXlsxFile(logs, insideCounts, itemLogs) }
            withContext(Dispatchers.IO) { saveToDownloads(file) }
            Toast.makeText(this@ReportActivity, R.string.report_export_success, Toast.LENGTH_LONG).show()
            shareFile(file)
        }
    }

    /** Exports every weekly inspection in the selected date range as a 3-sheet workbook — one
     *  sheet per machinery category, laid out exactly like the security team's original
     *  Excel file (ردیف / پلاک / راننده / one column per part / مورد تایید / عدم تایید),
     *  so the digital feature produces a report management already knows how to read. */
    private fun exportInspectionsToExcel() {
        viewModel.inspectionsInRange(rangeStart, rangeEnd) { inspections ->
            if (inspections.isEmpty()) {
                Toast.makeText(this, R.string.inspection_export_empty, Toast.LENGTH_SHORT).show()
                return@inspectionsInRange
            }
            lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) { buildInspectionXlsxFile(inspections) }
                withContext(Dispatchers.IO) { saveToDownloads(file) }
                Toast.makeText(this@ReportActivity, R.string.report_export_success, Toast.LENGTH_LONG).show()
                shareFile(file)
            }
        }
    }

    private fun buildInspectionXlsxFile(inspections: List<InspectionEntity>): File {
        val fmt = SimpleDateFormat("yyyy/MM/dd", Locale.US)

        val sheets = MachineryCategory.values()
            .groupBy { InspectionCatalog.sheetNameFor(it) } // DUMP_TRUCK + LOGISTICS share one sheet name
            .map { (sheetName, categories) ->
                val partNames = InspectionCatalog.partsFor(categories.first())
                val records = inspections.filter { it.category in categories.map { c -> c.name } }
                    .sortedBy { it.timestamp }

                val headers = listOf("ردیف", "شماره پلاک", "نام راننده") + partNames +
                    listOf("تاریخ بازدید", "مورد تایید", "عدم تایید", "توضیحات")

                val rows = records.mapIndexed { index, record ->
                    val partsByName = ir.factory.entryexit.data.InspectionJson.parse(record.partsJson).associateBy { it.name }

                    // "P" = سالم (matches the original workbook's letter code), "W" = نیاز به
                    // بررسی (new — the diagram's three-state result), "O" = خراب/عدم تایید.
                    val partCells = partNames.map { name ->
                        when (partsByName[name]?.status) {
                            ir.factory.entryexit.data.PartStatus.OK -> "P"
                            ir.factory.entryexit.data.PartStatus.WARN -> "W"
                            ir.factory.entryexit.data.PartStatus.BAD -> "O"
                            null -> ""
                        }
                    }
                    val defectNotes = partsByName.values
                        .filter { it.status != ir.factory.entryexit.data.PartStatus.OK && !it.note.isNullOrBlank() }
                        .joinToString("; ") { "${it.name}: ${it.note}" }

                    listOf((index + 1).toString(), record.personName, record.driverName.orEmpty()) +
                        partCells +
                        listOf(
                            fmt.format(Date(record.timestamp)),
                            record.approvedCount.toString(),
                            record.rejectedCount.toString(),
                            listOfNotNull(record.notes, defectNotes.ifBlank { null }).joinToString(" | ")
                        )
                }

                XlsxWriter.Sheet(sheetName, headers, rows)
            }

        val outDir = File(cacheDir, "exports").apply { mkdirs() }
        val fileName = "inspection_report_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.xlsx"
        val file = File(outDir, fileName)
        XlsxWriter.write(file, sheets)
        return file
    }

    /** Bridges the ViewModel's callback-based currentlyInsideCounts() into a suspend call. */
    private suspend fun awaitInsideCounts(): Map<PersonType, Int> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            viewModel.currentlyInsideCounts { counts -> cont.resumeWith(Result.success(counts)) }
        }

    private fun buildXlsxFile(logs: List<LogEntity>, insideCounts: Map<PersonType, Int>, itemLogs: List<ItemLogEntity> = emptyList()): File {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)

        val detailHeaders = listOf(
            getString(R.string.col_name),
            getString(R.string.col_category),
            getString(R.string.col_department),
            getString(R.string.col_action),
            getString(R.string.col_timestamp)
        )
        val detailRows = logs.map { log ->
            val categoryLabel = runCatching { PersonType.valueOf(log.type).displayName }.getOrDefault(log.type)
            val actionLabel = if (log.action == "IN") getString(R.string.action_in_label) else getString(R.string.action_out_label)
            listOf(
                log.personName,
                categoryLabel,
                log.detail ?: log.group.orEmpty(),
                actionLabel,
                fmt.format(Date(log.timestamp))
            )
        }

        val summaryHeaders = listOf(getString(R.string.col_summary_metric), getString(R.string.col_summary_value))
        val summaryRows = buildSummaryRows(logs, insideCounts, itemLogs)

        val outDir = File(cacheDir, "exports").apply { mkdirs() }
        val fileName = "traffic_report_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.xlsx"
        val file = File(outDir, fileName)
        XlsxWriter.write(
            file,
            listOf(
                XlsxWriter.Sheet(getString(R.string.report_title), detailHeaders, detailRows),
                XlsxWriter.Sheet(getString(R.string.report_analytics_sheet_name), summaryHeaders, summaryRows)
            )
        )
        return file
    }

    /** Shared by both the Excel summary sheet and the AI prompt — aggregated numbers only,
     *  never personal names, so nothing identifying leaves the device when analyzed by AI. */
    private fun buildSummaryRows(logs: List<LogEntity>, insideCounts: Map<PersonType, Int>, itemLogs: List<ItemLogEntity> = emptyList()): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        rows += listOf(getString(R.string.summary_total_events), logs.size.toString())
        rows += listOf(getString(R.string.summary_total_in), logs.count { it.action == "IN" }.toString())
        rows += listOf(getString(R.string.summary_total_out), logs.count { it.action == "OUT" }.toString())
        rows += listOf("", "")
        rows += listOf(getString(R.string.summary_by_category_header), "")
        for (type in PersonType.values()) {
            val inCount = logs.count { it.type == type.name && it.action == "IN" }
            val outCount = logs.count { it.type == type.name && it.action == "OUT" }
            rows += listOf("${type.displayName} — ${getString(R.string.action_in_label)}", inCount.toString())
            rows += listOf("${type.displayName} — ${getString(R.string.action_out_label)}", outCount.toString())
        }
        rows += listOf("", "")
        rows += listOf(getString(R.string.summary_currently_inside), "")
        for (type in PersonType.values()) {
            rows += listOf(type.displayName, (insideCounts[type] ?: 0).toString())
        }
        rows += listOf("", "")
        rows += listOf(getString(R.string.summary_item_logs_header), "")
        rows += listOf(
            getString(R.string.summary_item_logs_total_in),
            itemLogs.count { it.direction == Repository.ITEM_DIRECTION_IN }.toString()
        )
        rows += listOf(
            getString(R.string.summary_item_logs_total_out),
            itemLogs.count { it.direction == Repository.ITEM_DIRECTION_OUT }.toString()
        )
        rows += listOf(
            getString(R.string.summary_item_logs_pending_return),
            itemLogs.count { it.direction == Repository.ITEM_DIRECTION_OUT && !it.isReturned }.toString()
        )
        return rows
    }

    private fun buildAiPromptSummary(logs: List<LogEntity>, insideCounts: Map<PersonType, Int>, itemLogs: List<ItemLogEntity> = emptyList()): String =
        buildSummaryRows(logs, insideCounts, itemLogs).joinToString("\n") { (metric, value) ->
            if (value.isBlank()) metric else "$metric: $value"
        }

    /** Exports every ورود/خروج کالا record in the selected range as a 2-sheet workbook (one
     *  sheet per direction), so the goods log can be handed to accounting the same way the
     *  traffic and inspection logs already are. */
    private fun exportItemLogsToExcel() {
        viewModel.itemLogsInRange(rangeStart, rangeEnd) { itemLogs ->
            if (itemLogs.isEmpty()) {
                Toast.makeText(this, R.string.item_log_export_empty, Toast.LENGTH_SHORT).show()
                return@itemLogsInRange
            }
            lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) { buildItemLogXlsxFile(itemLogs) }
                withContext(Dispatchers.IO) { saveToDownloads(file) }
                Toast.makeText(this@ReportActivity, R.string.report_export_success, Toast.LENGTH_LONG).show()
                shareFile(file)
            }
        }
    }

    private fun buildItemLogXlsxFile(itemLogs: List<ItemLogEntity>): File {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

        val inHeaders = listOf(
            getString(R.string.item_log_hint_item_name), getString(R.string.item_log_hint_store),
            getString(R.string.item_log_hint_buyer), getString(R.string.item_log_hint_ordered_by_in),
            getString(R.string.item_log_hint_department), getString(R.string.item_log_hint_invoice_number),
            getString(R.string.col_timestamp)
        )
        val inRows = itemLogs.filter { it.direction == Repository.ITEM_DIRECTION_IN }.map { log ->
            listOf(
                log.itemName, log.storeName.orEmpty(), log.buyerName.orEmpty(), log.orderedByName.orEmpty(),
                log.department.orEmpty(), log.invoiceNumber.orEmpty(), fmt.format(Date(log.timestamp))
            )
        }

        val outHeaders = listOf(
            getString(R.string.item_log_hint_item_name), getString(R.string.item_log_hint_exit_slip_number),
            getString(R.string.item_log_hint_carrier), getString(R.string.item_log_hint_ordered_by_out),
            getString(R.string.item_log_hint_reason), getString(R.string.item_log_returned_badge),
            getString(R.string.col_timestamp)
        )
        val outRows = itemLogs.filter { it.direction == Repository.ITEM_DIRECTION_OUT }.map { log ->
            listOf(
                log.itemName, log.exitSlipNumber.orEmpty(), log.carrierName.orEmpty(), log.orderedByName.orEmpty(),
                log.reason.orEmpty(),
                if (log.isReturned) getString(R.string.item_log_returned_badge) else "",
                fmt.format(Date(log.timestamp))
            )
        }

        val outDir = File(cacheDir, "exports").apply { mkdirs() }
        val fileName = "item_log_report_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.xlsx"
        val file = File(outDir, fileName)
        XlsxWriter.write(
            file,
            listOf(
                XlsxWriter.Sheet(getString(R.string.item_log_report_sheet_in), inHeaders, inRows),
                XlsxWriter.Sheet(getString(R.string.item_log_report_sheet_out), outHeaders, outRows)
            )
        )
        return file
    }

    /** Bridges the ViewModel's callback-based itemLogsInRange() into a suspend call. */
    private suspend fun viewModelItemLogsInRangeSuspend(): List<ItemLogEntity> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            viewModel.itemLogsInRange(rangeStart, rangeEnd) { list -> cont.resumeWith(Result.success(list)) }
        }

    private fun runAiAnalysis() {
        lifecycleScope.launch {
            var apiKey = AppPreferences.getAiApiKey(this@ReportActivity)
            if (apiKey.isBlank()) {
                // Might already be set from the web panel or another device — check before
                // bothering the user to type it in on this one too.
                val cloudKey = withContext(Dispatchers.IO) { ir.factory.entryexit.data.CloudSettings.fetchAiApiKey() }
                if (!cloudKey.isNullOrBlank()) {
                    apiKey = cloudKey
                    AppPreferences.setAiApiKey(this@ReportActivity, cloudKey)
                }
            }
            if (apiKey.isBlank()) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ReportActivity)
                    .setTitle(R.string.ai_key_missing_title)
                    .setMessage(R.string.ai_key_missing_message)
                    .setPositiveButton(R.string.ai_open_settings) { _, _ ->
                        startActivity(Intent(this@ReportActivity, SettingsActivity::class.java))
                    }
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
                return@launch
            }

            binding.btnAiAnalyze.isEnabled = false
            binding.progressAi.visibility = android.view.View.VISIBLE
            binding.tvAiResult.visibility = android.view.View.GONE

            val logs = withContext(Dispatchers.IO) { viewModelExportRangeSuspend() }
            val insideCounts = withContext(Dispatchers.IO) { awaitInsideCounts() }
            val inspections = withContext(Dispatchers.IO) { viewModelInspectionsInRangeSuspend() }
            val itemLogs = withContext(Dispatchers.IO) { viewModelItemLogsInRangeSuspend() }
            val summary = buildAiPromptSummary(logs, insideCounts, itemLogs) + "\n\n" + buildInspectionAiSummary(inspections)

            val result = withContext(Dispatchers.IO) { AiReportAnalyzer.analyze(apiKey, summary) }

            binding.btnAiAnalyze.isEnabled = true
            binding.progressAi.visibility = android.view.View.GONE
            binding.tvAiResult.visibility = android.view.View.VISIBLE

            result.onSuccess { analysis ->
                binding.tvAiResult.text = analysis
            }.onFailure { error ->
                binding.tvAiResult.text = error.message ?: getString(R.string.error_generic)
            }
        }
    }

    /** Bridges the ViewModel's callback-based exportRange() into a suspend call. */
    private suspend fun viewModelExportRangeSuspend(): List<LogEntity> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            viewModel.exportRange(rangeStart, rangeEnd) { logs -> cont.resumeWith(Result.success(logs)) }
        }

    private suspend fun viewModelInspectionsInRangeSuspend(): List<InspectionEntity> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            viewModel.inspectionsInRange(rangeStart, rangeEnd) { list -> cont.resumeWith(Result.success(list)) }
        }

    /** Aggregated-only, same reasoning as [buildSummaryRows] — part-level defect frequency per
     *  category, no vehicle/plate names, so the touch-diagram inspections feed the same AI
     *  analysis the traffic log already does instead of living in a separate report entirely. */
    private fun buildInspectionAiSummary(inspections: List<InspectionEntity>): String {
        if (inspections.isEmpty()) return getString(R.string.inspection_export_empty)
        val lines = mutableListOf("بازدید ظاهری هفتگی ماشین‌آلات:")
        for (category in MachineryCategory.values().distinctBy { InspectionCatalog.sheetNameFor(it) }) {
            val records = inspections.filter { it.category == category.name }
            if (records.isEmpty()) continue
            val counts = ir.factory.entryexit.data.InspectionJson.defectCountsByPart(records)
            val topDefects = counts.entries.sortedByDescending { it.value }.take(5)
                .joinToString(", ") { "${it.key} (${it.value} مورد)" }
            lines += "${InspectionCatalog.sheetNameFor(category)}: ${records.size} بازدید، " +
                if (topDefects.isEmpty()) "بدون ایراد ثبت‌شده" else "پرتکرارترین ایرادها: $topDefects"
        }
        return lines.joinToString("\n")
    }

    /** Also drops a copy in the public Downloads folder so it's easy to find without sharing. */
    private fun saveToDownloads(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, XLSX_MIME)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ConcreteFactoryReports")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val folder = File(downloads, "ConcreteFactoryReports").apply { mkdirs() }
                file.copyTo(File(folder, file.name), overwrite = true)
            }
        } catch (_: Exception) {
            // Sharing the cache copy below still works even if the Downloads copy fails.
        }
    }

    private fun shareFile(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = XLSX_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.report_export_button)))
    }

    companion object {
        private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        private fun startOfToday(): Long = startOfDay(System.currentTimeMillis())
        private fun endOfToday(): Long = endOfDay(System.currentTimeMillis())

        private fun startOfDay(timeMillis: Long): Long {
            val cal = Calendar.getInstance(TimeZone.getDefault())
            cal.timeInMillis = timeMillis
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        private fun endOfDay(timeMillis: Long): Long {
            val cal = Calendar.getInstance(TimeZone.getDefault())
            cal.timeInMillis = timeMillis
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
            return cal.timeInMillis
        }
    }
}
