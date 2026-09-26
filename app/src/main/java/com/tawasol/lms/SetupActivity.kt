package com.tawasol.lms

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * أول شاشة تظهر للمستخدم: تطلب رابط موقع المدرسة وتحفظه محليًا.
 * لو الرابط محفوظ بالفعل من قبل، تنتقل مباشرة لـ MainActivity بدون عرض هذه الشاشة.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)

        val savedDomain = prefs.getString(Prefs.KEY_DOMAIN, null)
        if (!savedDomain.isNullOrBlank()) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_setup)

        val inputLayout = findViewById<TextInputLayout>(R.id.domainInputLayout)
        val input = findViewById<TextInputEditText>(R.id.domainInput)
        val button = findViewById<com.google.android.material.button.MaterialButton>(R.id.setupButton)
        val progress = findViewById<View>(R.id.setupProgress)

        button.setOnClickListener {
            val raw = input.text?.toString()?.trim() ?: ""
            if (raw.isEmpty()) {
                inputLayout.error = getString(R.string.setup_error_empty)
                return@setOnClickListener
            }
            val normalized = normalizeUrl(raw)
            if (normalized == null) {
                inputLayout.error = getString(R.string.setup_error_invalid)
                return@setOnClickListener
            }
            inputLayout.error = null
            progress.visibility = View.VISIBLE
            button.isEnabled = false

            prefs.edit().putString(Prefs.KEY_DOMAIN, normalized).apply()
            goToMain()
        }
    }

    /** يضيف https:// تلقائيًا لو المستخدم كتب الدومين بدون بروتوكول، ويتأكد إنه رابط صالح. */
    private fun normalizeUrl(input: String): String? {
        var url = input.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        val uri = Uri.parse(url)
        if (uri.host.isNullOrBlank()) return null
        return url
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
