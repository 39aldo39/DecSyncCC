package org.decsync.cc.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.appintro.AppIntro2
import com.github.appintro.SlidePolicy
import kotlinx.android.synthetic.main.activity_saf_update_directory.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.decsync.cc.*
import org.decsync.cc.model.DecsyncDirectory
import org.decsync.library.DecsyncPrefUtils

@ExperimentalStdlibApi
class SafUpdateActivity : AppIntro2() {
    lateinit var decsyncDirs: List<DecsyncDirectory>
    lateinit var directorySlides: List<SlideSafDirectory>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isWizardMode = true
        showStatusBar(true)

        addSlide(SlideSafWelcome())
        runBlocking {
            decsyncDirs = App.db.decsyncDirectoryDao().all()
            directorySlides = decsyncDirs.mapIndexed { i, decsyncDir ->
                SlideSafDirectory(decsyncDir) { uri ->
                    directorySlides.filterIndexed { j, _ -> j != i }.any { it.selectedDir == uri }
                }
            }
            for (slide in directorySlides) {
                addSlide(slide)
            }
        }
    }

    override fun onIntroFinished() {
        super.onIntroFinished()

        GlobalScope.launch {
            val updatedDirs = decsyncDirs.zip(directorySlides).map { (decsyncDir, slide) ->
                decsyncDir.copy(directory = slide.selectedDir!!.toString())
            }
            App.db.decsyncDirectoryDao().update(updatedDirs)
            PrefUtils.putUpdateForcesSaf(this@SafUpdateActivity, false)
            val intent = Intent(this@SafUpdateActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}

class SlideSafWelcome : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.activity_saf_update_welcome, container, false)
    }
}

@ExperimentalStdlibApi
class SlideSafDirectory(private val decsyncDir: DecsyncDirectory, private val isUsedDir: (Uri) -> Boolean) : Fragment(), SlidePolicy {
    var selectedDir: Uri? = null
    private var buttonError: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.activity_saf_update_directory, container, false)

        val title = view.findViewById<TextView>(R.id.saf_update_directory_title)
        title.text = getString(R.string.saf_update_directory_title, decsyncDir.name)

        val descPath = view.findViewById<TextView>(R.id.saf_update_directory_desc_path)
        descPath.text = decsyncDir.directory

        val button = view.findViewById<Button>(R.id.saf_update_directory_button)
        button.setOnClickListener {
            DecsyncPrefUtils.chooseDecsyncDir(this)
        }

        return view
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        DecsyncPrefUtils.chooseDecsyncDirResult(requireActivity(), requestCode, resultCode, data) { uri ->
            selectedDir = uri
            val name = DecsyncPrefUtils.getNameFromUri(requireActivity(), uri)
            saf_update_directory_button.text = name
        }
    }

    override val isPolicyRespected: Boolean
        get() {
            buttonError = null
            val selectedDir = selectedDir
            if (selectedDir == null) {
                buttonError = getString(R.string.intro_directory_select)
                return false
            } else if (isUsedDir(selectedDir)) {
                buttonError = getString(R.string.add_directory_dir_used)
                return false
            }
            return true
        }

    override fun onUserIllegallyRequestedNextPage() {
        val buttonError = buttonError
        if (buttonError != null) {
            Toast.makeText(requireActivity(), buttonError, Toast.LENGTH_SHORT).show()
        }
    }
}