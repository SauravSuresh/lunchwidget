package app.lunchwidget

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)

        val prefs = Prefs(this)
        val token = findViewById<EditText>(R.id.token)
        val tracked = findViewById<EditText>(R.id.tracked)
        val startDay = findViewById<EditText>(R.id.start_day)
        val currency = findViewById<EditText>(R.id.currency)
        val reimb = findViewById<EditText>(R.id.reimb)

        token.setText(prefs.token)
        tracked.setText(prefs.trackedCategories.joinToString(","))
        startDay.setText(prefs.startDay.toString())
        currency.setText(prefs.currency)
        reimb.setText(prefs.reimbName)

        findViewById<Button>(R.id.save).setOnClickListener {
            val day = startDay.text.toString().toIntOrNull()
            if (day == null || day !in 1..31) {
                startDay.error = getString(R.string.bad_day)
                return@setOnClickListener
            }
            prefs.token = token.text.toString().trim()
            prefs.trackedCategories = tracked.text.toString().split(",")
                .map { it.trim() }.filter { it.isNotEmpty() }
            prefs.startDay = day
            prefs.currency = currency.text.toString().ifBlank { "₹" }
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
