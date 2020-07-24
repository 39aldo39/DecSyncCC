package org.decsync.cc

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
import kotlinx.android.synthetic.main.activity_saf_update.*
import org.decsync.library.DecsyncPrefUtils

@ExperimentalStdlibApi
class SafUpdateActivity : AppIntro2() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isSkipButtonEnabled = false
        isIndicatorEnabled = false
        showStatusBar(true)

        addSlide(SlideSafDirectory())
    }

    override fun onIntroFinished() {
        super.onIntroFinished()

        PrefUtils.putUpdateForcesSaf(this, false)
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}

@ExperimentalStdlibApi
class SlideSafDirectory : Fragment(), SlidePolicy {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.activity_saf_update, container, false)

        val button = view.findViewById<Button>(R.id.saf_update_button)
        val decsyncDir = DecsyncPrefUtils.getDecsyncDir(requireActivity())
        if (decsyncDir != null) {
            val name = DecsyncPrefUtils.getNameFromUri(requireActivity(), decsyncDir)
            button.text = name
        }
        button.setOnClickListener {
            DecsyncPrefUtils.chooseDecsyncDir(this)
        }

        return view
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        DecsyncPrefUtils.chooseDecsyncDirResult(requireActivity(), requestCode, resultCode, data) { uri ->
            val name = DecsyncPrefUtils.getNameFromUri(requireActivity(), uri)
            saf_update_button.text = name
        }
    }

    override val isPolicyRespected: Boolean
        get() {
            return DecsyncPrefUtils.getDecsyncDir(requireActivity()) != null
        }

    override fun onUserIllegallyRequestedNextPage() {
        Toast.makeText(requireActivity(), R.string.intro_directory_select, Toast.LENGTH_SHORT).show()
    }
}