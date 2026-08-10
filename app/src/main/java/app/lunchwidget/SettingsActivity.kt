package app.lunchwidget

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The token is typed on this screen — keep it out of screenshots, screen
        // recordings, and the recents thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.settings)

        val prefs = Prefs(this)
        val token = findViewById<EditText>(R.id.token)
        val tracked = findViewById<EditText>(R.id.tracked)
        val startDay = findViewById<EditText>(R.id.start_day)
        val currency = findViewById<EditText>(R.id.currency)
        val reimb = findViewById<EditText>(R.id.reimb)
        val defaultAsset = findViewById<Spinner>(R.id.default_asset)

        // A saved token never goes back into the field — only the last four
        // characters come back, enough to tell which token is loaded without
        // putting it back on screen where it can be read or copied.
        val savedToken = prefs.token
        findViewById<TextView>(R.id.token_state).text =
            if (savedToken.isEmpty()) getString(R.string.token_unset)
            else getString(R.string.token_saved, savedToken.takeLast(4))

        tracked.setText(prefs.trackedCategories.joinToString(","))
        startDay.setText(prefs.startDay.toString())
        currency.setText(prefs.currency)
        reimb.setText(prefs.reimbName)

        // Index 0 is "none"; the rest mirror prefs.assets, so position−1 indexes it.
        val assets = prefs.assets
        defaultAsset.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.no_default_account)) + assets.map { it.name },
        )
        defaultAsset.setSelection(assets.indexOfFirst { it.id == prefs.defaultAssetId } + 1)

        findViewById<Button>(R.id.save).setOnClickListener {
            val day = startDay.text.toString().toIntOrNull()
            if (day == null || day !in 1..31) {
                startDay.error = getString(R.string.bad_day)
                return@setOnClickListener
            }
            // Blank means "keep what's saved"; anything typed replaces it.
            val typed = token.text.toString().trim()
            if (typed.isNotEmpty()) prefs.token = typed
            prefs.trackedCategories = tracked.text.toString().split(",")
                .map { it.trim() }.filter { it.isNotEmpty() }
            prefs.startDay = day
            prefs.currency = currency.text.toString().ifBlank { "₹" }
            prefs.defaultAssetId = assets.getOrNull(defaultAsset.selectedItemPosition - 1)?.id ?: 0L
            val reimbName = reimb.text.toString().trim().ifBlank { "Reimbursements" }
            if (!reimbName.equals(prefs.reimbName, ignoreCase = true)) {
                prefs.reimbCategoryId = 0L // re-resolve on next split
            }
            prefs.reimbName = reimbName
            RefreshWorker.schedulePeriodic(this)
            RefreshWorker.refreshNow(this)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
