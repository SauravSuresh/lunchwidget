package app.lunchwidget

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import java.time.LocalDate
import kotlin.concurrent.thread

class QuickAddActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.quick_add)

        val prefs = Prefs(this)
        val recent = prefs.recentCategoryIds
        val rank = recent.withIndex().associate { (i, id) -> id to i }
        // Recently used first, the rest alphabetical.
        val selectable = prefs.categories.filter { !it.isGroup }
            .sortedWith(
                compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.name.lowercase() })
            )
        if (selectable.isEmpty()) {
            Toast.makeText(this, R.string.no_categories, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val category = findViewById<AutoCompleteTextView>(R.id.category)
        category.setAdapter(
            ArrayAdapter(this, R.layout.dropdown_item, selectable.map { it.name })
        )
        category.threshold = 1
        category.setOnClickListener { category.showDropDown() }
        category.setOnFocusChangeListener { _, focused -> if (focused) category.showDropDown() }
        // Default to the last-used category, else first tracked.
        val default = selectable.firstOrNull { it.id == recent.firstOrNull() }
            ?: selectable.firstOrNull { it.name in prefs.trackedCategories }
        default?.let { category.setText(it.name, false) }

        val amountField = findViewById<EditText>(R.id.amount)
        val noteField = findViewById<EditText>(R.id.note)
        val save = findViewById<Button>(R.id.save)
        amountField.requestFocus()

        save.setOnClickListener {
            val amount = amountField.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0) {
                amountField.error = getString(R.string.bad_amount)
                return@setOnClickListener
            }
            val typed = category.text.toString().trim()
            val chosen = selectable.firstOrNull { it.name.equals(typed, ignoreCase = true) }
            if (chosen == null) {
                category.error = getString(R.string.bad_category)
                return@setOnClickListener
            }
            val note = noteField.text.toString()
            save.isEnabled = false
            thread {
                try {
                    LunchMoneyApi(prefs.token)
                        .insertTransaction(LocalDate.now(), amount, chosen.id, note)
                    RefreshWorker.refreshNow(this)
                    runOnUiThread {
                        Toast.makeText(this, R.string.added, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                        save.isEnabled = true
                    }
                }
            }
        }
    }
}
