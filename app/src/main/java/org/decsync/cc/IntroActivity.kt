package org.decsync.cc

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.paolorotolo.appintro.AppIntro2
import com.github.paolorotolo.appintro.ISlidePolicy
import kotlinx.android.synthetic.main.activity_intro_directory.*
import org.decsync.library.DecsyncPrefUtils

@ExperimentalStdlibApi
class IntroActivity : AppIntro2() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FIXME: Status bar does not get the right background
        //window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        //window.statusBarColor = Color.TRANSPARENT

        wizardMode = true
        backButtonVisibilityWithDone = true

        addSlide(SlideWelcome())
        addSlide(SlideDirectory())
    }

    @ExperimentalStdlibApi
    override fun onDonePressed(currentFragment: Fragment?) {
        super.onDonePressed(currentFragment)

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
class SlideDirectory: Fragment(), ISlidePolicy {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.activity_intro_directory, container, false)

        val directoryButton = view.findViewById<Button>(R.id.intro_directory_button)
        val decsyncDir = DecsyncPrefUtils.getDecsyncDir(activity!!)
        if (decsyncDir != null) {
            directoryButton.text = decsyncDir.name
        }
        directoryButton.setOnClickListener {
            DecsyncPrefUtils.chooseDecsyncDir(this)
        }

        return view
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        DecsyncPrefUtils.chooseDecsyncDirResult(activity!!, requestCode, resultCode, data) { decsyncDir ->
            intro_directory_button.text = decsyncDir.name
        }
    }

    override fun isPolicyRespected(): Boolean = DecsyncPrefUtils.getDecsyncDir(activity!!) != null

    override fun onUserIllegallyRequestedNextPage() {
        Toast.makeText(activity!!, "Please select a DecSync directory", Toast.LENGTH_SHORT).show()
    }
}