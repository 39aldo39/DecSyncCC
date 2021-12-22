package org.decsync.cc.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.appintro.AppIntro2
import com.github.appintro.SlidePolicy
import com.nononsenseapps.filepicker.FilePickerActivity
import com.nononsenseapps.filepicker.Utils
import kotlinx.android.synthetic.main.activity_add_directory.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.decsync.cc.App
import org.decsync.cc.PrefUtils
import org.decsync.cc.R
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.library.DecsyncPrefUtils
import org.decsync.library.checkDecsyncInfo

private const val CHOOSE_DECSYNC_FILE = 1

@ExperimentalStdlibApi
class AddDirectoryActivity : AppIntro2() {
    private lateinit var slideAddDirectory: SlideAddDirectory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isWizardMode = true
        showStatusBar(true)

        addSlide(SlideAddDirectory().also { slideAddDirectory = it })

        if (!PrefUtils.getUseSaf(this)) {
            askForPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2, true)
        }
    }

    override fun onIntroFinished() {
        super.onIntroFinished()

        GlobalScope.launch {
            val directory = if (PrefUtils.getUseSaf(this@AddDirectoryActivity)) {
                DecsyncPrefUtils.getDecsyncDir(this@AddDirectoryActivity)!!.toString()
            } else {
                PrefUtils.getDecsyncFile(this@AddDirectoryActivity).path
            }
            val name = slideAddDirectory.add_directory_name.text.toString()
            val calendarAccountName = getString(R.string.account_name_calendars_with_dir_name, name)
            val taskListAccountName = getString(R.string.account_name_tasks_with_dir_name, name)
            val dirId = App.db.decsyncDirectoryDao().insert(
                DecsyncDirectory(
                    directory = directory,
                    name = name,
                    contactsFormatAccountName = "%s ($name)",
                    calendarAccountName = calendarAccountName,
                    taskListAccountName = taskListAccountName
                )
            )
            PrefUtils.putSelectedDir(this@AddDirectoryActivity, dirId)
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}

@ExperimentalStdlibApi
class SlideAddDirectory : Fragment(), SlidePolicy {
    private var buttonError: String? = null
    lateinit var existingDirs: List<String>
    lateinit var existingNames: List<String>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.activity_add_directory, container, false)

        val directoryButton = view.findViewById<Button>(R.id.add_directory_button)
        if (PrefUtils.getUseSaf(requireActivity())) {
            val decsyncDir = DecsyncPrefUtils.getDecsyncDir(requireActivity())
            if (decsyncDir != null) {
                val name = DecsyncPrefUtils.getNameFromUri(requireActivity(), decsyncDir)
                directoryButton.text = name
            }
            directoryButton.setOnClickListener {
                DecsyncPrefUtils.chooseDecsyncDir(this)
            }
        } else {
            val decsyncDir = PrefUtils.getDecsyncFile(requireActivity())
            val name = decsyncDir.path
            directoryButton.text = name
            directoryButton.setOnClickListener {
                val intent = Intent(requireActivity(), FilePickerActivity::class.java)
                intent.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true)
                intent.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_DIR)
                intent.putExtra(FilePickerActivity.EXTRA_START_PATH, decsyncDir.path)
                startActivityForResult(intent, CHOOSE_DECSYNC_FILE)
            }
        }
        val directoryName = view.findViewById<EditText>(R.id.add_directory_name)

        GlobalScope.launch {
            val existingDecsyncDirs = App.db.decsyncDirectoryDao().all()
            existingDirs = existingDecsyncDirs.map { it.directory }
            existingNames = existingDecsyncDirs.map { it.name }

            var defaultName = getString(R.string.decsync_dir_name_default)
            for (i in 2 .. 100) {
                if (!existingNames.contains(defaultName)) break
                defaultName = getString(R.string.decsync_dir_name_default_n, i)
            }
            CoroutineScope(Dispatchers.Main).launch {
                directoryName.setText(defaultName)
            }
        }

        return view
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (PrefUtils.getUseSaf(requireActivity())) {
            DecsyncPrefUtils.chooseDecsyncDirResult(requireActivity(), requestCode, resultCode, data) { uri ->
                val name = DecsyncPrefUtils.getNameFromUri(requireActivity(), uri)
                add_directory_button.text = name
            }
        } else {
            if (requestCode == CHOOSE_DECSYNC_FILE) {
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    val oldDir = PrefUtils.getDecsyncFile(requireActivity())
                    val newDir = Utils.getFileForUri(uri)
                    if (oldDir != newDir) {
                        checkDecsyncInfo(newDir)
                        PrefUtils.putDecsyncFile(requireActivity(), newDir)
                        add_directory_button.text = newDir.path
                    }
                }
            }
        }
    }

    override val isPolicyRespected: Boolean
    get() {
        var result = true
        buttonError = null
        if (PrefUtils.getUseSaf(requireActivity())) {
            val uri = DecsyncPrefUtils.getDecsyncDir(requireActivity())
            if (uri == null) {
                buttonError = getString(R.string.intro_directory_select)
                result = false
            } else if (existingDirs.contains(uri.toString())) {
                buttonError = getString(R.string.add_directory_dir_used)
                result = false
            }
        } else {
            val file = PrefUtils.getDecsyncFile(requireActivity())
            if (existingDirs.contains(file.path)) {
                buttonError = getString(R.string.add_directory_dir_used)
                result = false
            }
        }
        val name = add_directory_name.text.toString()
        if (name.isBlank()) {
            add_directory_name.error = getString(R.string.add_directory_name_empty)
            result = false
        }
        if (existingNames.contains(name)) {
            add_directory_name.error = getString(R.string.add_directory_name_used)
            result = false
        }
        return result
    }

    override fun onUserIllegallyRequestedNextPage() {
        val buttonError = buttonError
        if (buttonError != null) {
            Toast.makeText(requireActivity(), buttonError, Toast.LENGTH_SHORT).show()
        }
    }
}