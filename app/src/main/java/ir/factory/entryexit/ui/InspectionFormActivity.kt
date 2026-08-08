package ir.factory.entryexit.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.factory.entryexit.R
import ir.factory.entryexit.data.InspectionCatalog
import ir.factory.entryexit.data.InspectionJson
import ir.factory.entryexit.data.InspectionPartResult
import ir.factory.entryexit.data.MachineryCategory
import ir.factory.entryexit.data.PartStatus
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.databinding.ActivityInspectionFormBinding
import ir.factory.entryexit.viewmodel.FactoryViewModel

/**
 * The digital equivalent of one row of the original Excel sheet, now filled out by tapping a
 * schematic diagram of the vehicle instead of flipping ~30 switches: tap a part once for
 * "نیاز به بررسی" (yellow), twice for "خراب" (red), a third time back to "سالم"; long-press
 * jumps straight to red. Anything the previous inspection left open (and never marked
 * repaired) is pre-flagged with a reminder ring so it isn't mistaken for a fresh defect.
 */
class InspectionFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInspectionFormBinding
    private lateinit var viewModel: FactoryViewModel
    private var person: PersonEntity? = null
    private var category: MachineryCategory = MachineryCategory.LOGISTICS

    /** Part name currently waiting on a photo pick from [pickPhoto]. */
    private var pendingPhotoFor: String? = null
    private var pendingPhotoNoteDraft: String = ""

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = pendingPhotoFor ?: return@registerForActivityResult
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            binding.diagramView.setNoteAndPhoto(name, pendingPhotoNoteDraft.trim().ifEmpty { null }, uri.toString())
        }
        pendingPhotoFor = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInspectionFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]
        binding.toolbar.setNavigationOnClickListener { finish() }

        val personId = intent.getStringExtra(EXTRA_PERSON_ID)
        if (personId.isNullOrBlank()) {
            finish()
            return
        }

        viewModel.getPerson(personId) { loaded ->
            if (loaded == null) {
                Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
                finish()
                return@getPerson
            }
            person = loaded
            buildForm(loaded)
        }

        binding.diagramView.onStatusChanged = { _, _ -> updateSummary() }
        binding.diagramView.onNoteBadgeTapped = { name -> showNoteDialog(name) }

        binding.btnSubmit.setOnClickListener { submit() }
    }

    private fun buildForm(person: PersonEntity) {
        binding.tvSubtitle.text = getString(R.string.inspection_form_subtitle_format, person.name, person.extraInfo ?: "-")
        category = MachineryCategory.classify(person.group)

        // Pre-flag anything still open from last time before the guard has tapped anything —
        // "still broken since last week" only becomes official (recorded with a timestamp) once
        // they actually re-confirm it's bad on submit; this is just a visual reminder to look.
        viewModel.latestInspectionFor(person.id) { previous ->
            val stillOpen = previous?.let {
                InspectionJson.parse(it.partsJson)
                    .filter { part -> part.status != PartStatus.OK && part.repairedAt == null }
                    .map { part -> part.name }
                    .toSet()
            }.orEmpty()

            binding.diagramView.setup(category, emptyList(), stillOpen)
            binding.tvStillOpenHint.visibility = if (stillOpen.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            buildNonSpatialChips()
            updateSummary()
        }
    }

    private fun buildNonSpatialChips() {
        binding.chipGroupNonSpatial.removeAllViews()
        for (name in InspectionCatalog.nonSpatialParts) {
            val chip = Chip(this).apply {
                text = name
                isCheckable = true
                isChecked = false
                setOnCheckedChangeListener { _, _ -> updateSummary() }
            }
            binding.chipGroupNonSpatial.addView(chip)
        }
    }

    private fun nonSpatialResults(): List<InspectionPartResult> =
        (0 until binding.chipGroupNonSpatial.childCount).map { i ->
            val chip = binding.chipGroupNonSpatial.getChildAt(i) as Chip
            InspectionPartResult(name = chip.text.toString(), status = if (chip.isChecked) PartStatus.BAD else PartStatus.OK)
        }

    private fun allResults(): List<InspectionPartResult> = binding.diagramView.currentResults() + nonSpatialResults()

    private fun updateSummary() {
        val all = allResults()
        val approved = all.count { it.ok }
        val rejected = all.size - approved
        binding.tvSummary.text = getString(R.string.inspection_summary_format, approved.toString(), rejected.toString())
    }

    /** A part just went WARN/BAD (or already was) and its note/photo badge was tapped:
     *  a small dialog to add an optional description and/or attach a photo. */
    private fun showNoteDialog(partName: String) {
        val current = binding.diagramView.currentResults().find { it.name == partName }
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(current?.note.orEmpty())
            hint = getString(R.string.inspection_note_hint)
        }
        val wrapper = com.google.android.material.textfield.TextInputLayout(this).apply {
            addView(input)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        wrapper.setPadding(padding, padding, padding, 0)

        val hasPhoto = !current?.photoUri.isNullOrBlank()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.inspection_note_dialog_title)
            .setView(wrapper)
            .setNeutralButton(if (hasPhoto) R.string.inspection_photo_attached else R.string.inspection_attach_photo) { _, _ ->
                pendingPhotoFor = partName
                pendingPhotoNoteDraft = input.text?.toString().orEmpty()
                pickPhoto.launch(arrayOf("image/*"))
            }
            .setPositiveButton(R.string.inspection_save_note) { _, _ ->
                binding.diagramView.setNoteAndPhoto(partName, input.text?.toString()?.trim()?.ifEmpty { null }, current?.photoUri)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun submit() {
        val current = person ?: return
        val parts = allResults()
        val notes = binding.etNotes.text?.toString()

        binding.btnSubmit.isEnabled = false
        viewModel.submitInspection(current, parts, notes) { result ->
            binding.btnSubmit.isEnabled = true
            result.onSuccess {
                Toast.makeText(this, R.string.inspection_submit_success, Toast.LENGTH_SHORT).show()
                finish()
            }.onFailure { error ->
                Toast.makeText(this, error.message ?: getString(R.string.error_generic), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_PERSON_ID = "extra_person_id"
    }
}
