package com.tverona.scpanywhere.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.tverona.scpanywhere.R
import com.tverona.scpanywhere.databinding.FragmentAboutBinding
import com.tverona.scpanywhere.utils.loge
import kotlinx.android.synthetic.main.fragment_about.*


/**
 * Fragment to display about page
 */
class AboutFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return FragmentAboutBinding.inflate(inflater, container, false)
            .also {
                it.aboutVersion.text=getString(R.string.about_version, getString(R.string.app_version))
                it.playStore.setOnClickListener { _ ->
                    try {
                        try {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=${requireContext().packageName}")
                                )
                            )
                        } catch (activityNotFoundException: ActivityNotFoundException) {
                            // Google Play Store is not installed, fall back to URL
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=${requireContext().packageName}")
                                )
                            )
                        }
                    } catch (e: Exception) {
                        loge("Error trying to open Google Play store")
                    }
                }
                it.github.setOnClickListener { _ ->
                    val intent = Intent()
                    intent.action = Intent.ACTION_VIEW
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    intent.data = Uri.parse(getString(R.string.github_link))
                    startActivity(intent)
                }
            }
            .root
    }
}