package org.decsync.cc.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.appintro.AppIntro2
import com.github.appintro.SlidePolicy
import com.nononsenseapps.filepicker.FilePickerActivity
import com.nononsenseapps.filepicker.Utils
import kotlinx.android.synthetic.main.activity_intro_directory.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.decsync.cc.*
import org.decsync.cc.Utils.installApp
import org.decsync.cc.Utils.showBasicDialog
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.library.DecsyncException
import org.decsync.library.DecsyncPrefUtils
import org.decsync.library.checkDecsyncInfo

private const val CHOOSE_DECSYNC_FILE = 1

@ExperimentalStdlibApi
class IntroActivity : AppIntro2() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isWizardMode = true
        showStatusBar(true)

        addSlide(SlideIntroWelcome())
        addSlide(SlideSyncthing())
        addSlide(SlideDirectory())

        if (PrefUtils.getUseSaf(this)) {
            // Reset stored DecSync dir by libdecsync
            DecsyncPrefUtils.removeDecsyncDir(this)
        } else {
            askForPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 3, true)
        }
    }

    override fun onIntroFinished() {
        super.onIntroFinished()

        GlobalScope.launch {
            val directory = if (PrefUtils.getUseSaf(this@IntroActivity)) {
                DecsyncPrefUtils.getDecsyncDir(this@IntroActivity)!!.toString()
            } else {
                PrefUtils.getDecsyncFile(this@IntroActivity).path
            }
            val name = getString(R.string.decsync_dir_name_default)
            val calendarAccountName = getString(R.string.account_name_calendars)
            val taskListAccountName = getString(R.string.account_name_tasks)
            val dirId = App.db.decsyncDirectoryDao().insert(
                DecsyncDirectory(
                    directory = directory,
                    name = name,
                    contactsFormatAccountName = "%s",
                    calendarAccountName = calendarAccountName,
                    taskListAccountName = taskListAccountName
                )
            )
            PrefUtils.putSelectedDir(this@IntroActivity, dirId)
            PrefUtils.putIntroDone(this@IntroActivity, true)
            val intent = Intent(this@IntroActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}

class SlideIntroWelcome : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.activity_intro_welcome, container, false)
    }
}

class SlideSyncthing : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.activity_intro_syncthing, container, false)

        val installButton = view.findViewById<Button>(R.id.intro_syncthing_button)
        installButton.setOnClickListener {
            installApp(requireActivity(), "com.nutomic.syncthingandroid")
        }

        return view
    }
}

@ExperimentalStdlibApi
class SlideDirectory : Fragment(), SlidePolicy {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.activity_intro_directory, container, false)

        val directoryButton = view.findViewById<Button>(R.id.intro_directory_button)
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
        view.findViewById<TextView>(R.id.intro_directory_desc).movementMethod = LinkMovementMethod.getInstance()

        return view
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (PrefUtils.getUseSaf(requireActivity())) {
            DecsyncPrefUtils.chooseDecsyncDirResult(requireActivity(), requestCode, resultCode, data) { uri ->
                val name = DecsyncPrefUtils.getNameFromUri(requireActivity(), uri)
                intro_directory_button.text = name
            }
        } else {
            if (requestCode == CHOOSE_DECSYNC_FILE) {
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    val oldDir = PrefUtils.getDecsyncFile(requireActivity())
                    val newDir = Utils.getFileForUri(uri)
                    if (oldDir != newDir) {
                        try {
                            checkDecsyncInfo(newDir)
                            PrefUtils.putDecsyncFile(requireActivity(), newDir)
                            intro_directory_button.text = newDir.path
                        } catch (e: DecsyncException) {
                            showBasicDialog(requireActivity(), "DecSync", e.message)
                        }
                    }
                }
            }
        }
    }

    override val isPolicyRespected: Boolean
    get() {
        return if (PrefUtils.getUseSaf(requireActivity())) {
            DecsyncPrefUtils.getDecsyncDir(requireActivity()) != null
        } else {
            true
        }
    }

    override fun onUserIllegallyRequestedNextPage() {
        if (PrefUtils.getUseSaf(requireActivity())) {
            Toast.makeText(requireActivity(), R.string.intro_directory_select, Toast.LENGTH_SHORT).show()
        }
    }
}