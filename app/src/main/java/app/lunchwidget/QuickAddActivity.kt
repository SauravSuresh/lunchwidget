package app.lunchwidget

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import java.time.LocalDate
import kotlin.concurrent.thread

class QuickAddActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.quick_add)

        val prefs = Prefs(this)
        val selectable = prefs.categories.filter { !it.isGroup }
        if (selectable.isEmpty()) {
            Toast.makeText(this, R.string.no_categories, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val spinner = findViewById<Spinner>(R.id.category)
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            selectable.map { it.name }
        )
        val defaultIdx = selectable.indexOfFirst { it.name in prefs.trackedCategories }
        if (defaultIdx >= 0) spinner.setSelection(defaultIdx)

        val amountField = findViewById<EditText>(R.id.amount)
        val noteField = findViewById<EditText>(R.id.note)
        val save = findViewById<Button>(R.id.save)

        save.setOnClickListener {
            val amount = amountField.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0) {
                amountField.error = getString(R.string.bad_amount)
                return@setOnClickListener
            }
            val category = selectable[spinner.selectedItemPosition]
            val note = noteField.text.toString()
            save.isEnabled = false
            thread {
                try {
                    LunchMoneyApi(prefs.token)
                        .insertTransaction(LocalDate.now(), amount, category.id, note)
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
