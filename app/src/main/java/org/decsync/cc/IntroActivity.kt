package org.decsync.cc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.appintro.AppIntro2
import com.github.appintro.SlidePolicy
import com.nononsenseapps.filepicker.FilePickerActivity
import com.nononsenseapps.filepicker.Utils
import kotlinx.android.synthetic.main.activity_intro_directory.*
import org.decsync.library.DecsyncPrefUtils
import org.decsync.library.checkDecsyncInfo

const val CHOOSE_DECSYNC_FILE = 0

@ExperimentalStdlibApi
class IntroActivity : AppIntro2() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isWizardMode = true
        showStatusBar(true)

        addSlide(SlideWelcome())
        addSlide(SlideDirectory())

        if (!PrefUtils.getUseSaf(this)) {
            askForPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2, true)
        }
    }

    override fun onIntroFinished() {
        super.onIntroFinished()

        PrefUtils.putIntroDone(this, true)
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}

class SlideWelcome : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.activity_intro_welcome, container, false)
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
                        checkDecsyncInfo(newDir)
                        PrefUtils.putDecsyncFile(requireActivity(), newDir)
                        intro_directory_button.text = newDir.path
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