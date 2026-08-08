package ir.factory.entryexit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import ir.factory.entryexit.R
import ir.factory.entryexit.data.PersonEntity
import ir.factory.entryexit.data.PersonType
import ir.factory.entryexit.databinding.ActivitySetupBinding
import ir.factory.entryexit.databinding.ItemSetupEntryBinding
import ir.factory.entryexit.util.ImagePrep
import ir.factory.entryexit.viewmodel.FactoryViewModel
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the office set up profile photos for personnel/drivers and equipment photos for
 * machinery before the app goes into daily use — a plain gallery/camera picker per roster row.
 *
 * (The previous AI-assisted headshot cleanup and bulk plate-scan flows were removed: in
 * practice they weren't reliable enough to be worth the extra taps, so this is back to a
 * straightforward manual picker for every row.)
 *
 * Every saved photo is downscaled, EXIF-corrected, and copied into the app's own storage via
 * [ImagePrep], so [ir.factory.entryexit.data.PersonEntity.imageUri] always ends up pointing at a
 * `file://` Uri we control rather than a possibly-transient content:// Uri.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var viewModel: FactoryViewModel
    private lateinit var adapter: SetupAdapter
    private var currentType: PersonType = PersonType.PERSONNEL

    /** The roster row a launched picker/camera intent is currently working on. */
    private var pendingTarget: PersonEntity? = null
    private var pendingCameraUri: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val person = pendingTarget
        if (uri != null && person != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            saveDirectly(person, uri)
        }
    }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val person = pendingTarget
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && person != null && uri != null) {
            saveDirectly(person, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[FactoryViewModel::class.java]

        binding.toolbar.title = getString(R.string.setup_title)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = SetupAdapter(currentType, ::launchGalleryPicker, ::launchCameraCapture)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.setup_subtitle_personnel)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.setup_subtitle_machinery)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.setup_subtitle_driver)))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentType = when (tab.position) {
                    0 -> PersonType.PERSONNEL
                    1 -> PersonType.MACHINERY
                    else -> PersonType.DRIVER
                }
                adapter = SetupAdapter(currentType, ::launchGalleryPicker, ::launchCameraCapture)
                binding.recyclerView.adapter = adapter
                loadRoster()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        loadRoster()
    }

    private fun launchGalleryPicker(person: PersonEntity) {
        pendingTarget = person
        pickImage.launch(arrayOf("image/*"))
    }

    private fun launchCameraCapture(person: PersonEntity) {
        pendingTarget = person
        val uri = ImagePrep.createCameraCaptureUri(this)
        pendingCameraUri = uri
        takePhoto.launch(uri)
    }

    private fun loadRoster() {
        viewModel.loadRosterOnce(currentType) { roster -> adapter.submit(roster) }
    }

    private fun saveDirectly(person: PersonEntity, uri: Uri) {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { ImagePrep.readAsJpeg(this@SetupActivity, uri) }
            if (bytes == null) {
                Toast.makeText(this@SetupActivity, R.string.setup_photo_read_error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val savedUri = withContext(Dispatchers.IO) {
                ImagePrep.savePermanently(this@SetupActivity, bytes, person.imageUri)
            }
            viewModel.updatePersonImage(person.id, savedUri.toString()) { result ->
                result.onSuccess {
                    Toast.makeText(this@SetupActivity, R.string.setup_image_updated, Toast.LENGTH_SHORT).show()
                    loadRoster()
                }.onFailure {
                    Toast.makeText(this@SetupActivity, it.message ?: getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private class SetupAdapter(
        private val type: PersonType,
        private val onPickGallery: (PersonEntity) -> Unit,
        private val onTakePhoto: (PersonEntity) -> Unit
    ) : RecyclerView.Adapter<SetupAdapter.VH>() {

        private var items: List<PersonEntity> = emptyList()

        fun submit(list: List<PersonEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemSetupEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        override fun getItemCount(): Int = items.size

        inner class VH(private val binding: ItemSetupEntryBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(person: PersonEntity) {
                binding.tvName.text = person.name
                val iconRes = when (type) {
                    PersonType.PERSONNEL -> R.drawable.ic_personnel
                    PersonType.DRIVER -> R.drawable.ic_driver
                    else -> R.drawable.ic_machinery
                }

                if (person.imageUri != null) {
                    binding.ivTypeIcon.visibility = View.GONE
                    binding.ivPhoto.visibility = View.VISIBLE
                    Glide.with(binding.root.context)
                        .load(Uri.parse(person.imageUri))
                        .placeholder(iconRes)
                        .error(iconRes)
                        .circleCrop()
                        .into(binding.ivPhoto)
                } else {
                    binding.ivPhoto.visibility = View.GONE
                    binding.ivTypeIcon.visibility = View.VISIBLE
                    binding.ivTypeIcon.setImageResource(iconRes)
                }

                binding.btnPickImage.setOnClickListener { onPickGallery(person) }
                binding.btnTakePhoto.setOnClickListener { onTakePhoto(person) }
            }
        }
    }
}
