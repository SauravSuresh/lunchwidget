package app.lunchwidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

class QuickAddActivity : Activity() {

    private enum class Screen { ENTRY, SPLIT, ITEMIZE, PERSONS, SETTLE, INCOME }

    // One receipt line being edited on the itemize screen.
    private class UiItem(
        var amount: Double? = null,
        var label: String = "",
        val assignees: MutableSet<String> = mutableSetOf(),
    )

    private lateinit var prefs: Prefs
    private var screen = Screen.ENTRY

    // Entry state, preserved across setContentView swaps.
    private var plus = false
    private var modeRepayment = true
    private var entryAmountText = ""
    private var entryCategoryText = ""
    private var entryNoteText = ""

    // Split state.
    private val me = Share("", "You", isMe = true)
    private val friends = mutableListOf<Share>()
    private var includeMe = true
    private var splitTotal = 0.0
    private var splitCategoryId = 0L

    // Itemize state, persists while the dialog lives so reopening resumes it.
    private val billItems = mutableListOf<UiItem>()

    // Settle state.
    private var settlePerson: PendingPerson? = null
    private var received = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.categories.none { !it.isGroup }) {
            Toast.makeText(this, R.string.no_categories, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        showEntry()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.SPLIT, Screen.PERSONS, Screen.INCOME -> showEntry()
            Screen.ITEMIZE -> showSplit()
            Screen.SETTLE -> showPersons()
            Screen.ENTRY -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun fmtA(v: Double): String =
        if (v % 1.0 == 0.0) String.format(Locale.US, "%.0f", v)
        else String.format(Locale.US, "%.2f", v)

    private fun money(v: Double): String = prefs.currency + fmtA(v)

    private fun shortDate(iso: String): String = try {
        LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd MMM"))
            .uppercase(Locale.US)
    } catch (e: Exception) {
        iso
    }

    private fun displayName(slug: String): String =
        prefs.people.firstOrNull { it.first == slug }?.second
            ?: slug.replace('-', ' ')

    // ---------------------------------------------------------------- entry

    private fun showEntry() {
        screen = Screen.ENTRY
        setContentView(R.layout.quick_add)

        val recent = prefs.recentCategoryIds
        val rank = recent.withIndex().associate { (i, id) -> id to i }
        // Recently used first, the rest alphabetical.
        val selectable = prefs.categories.filter { !it.isGroup }
            .sortedWith(
                compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.name.lowercase() })
            )

        val category = findViewById<AutoCompleteTextView>(R.id.category)
        category.setAdapter(
            ArrayAdapter(this, R.layout.dropdown_item, selectable.map { it.name })
        )
        category.threshold = 1
        category.setOnClickListener { category.showDropDown() }
        category.setOnFocusChangeListener { _, focused -> if (focused) category.showDropDown() }
        if (entryCategoryText.isNotEmpty()) {
            category.setText(entryCategoryText, false)
        } else {
            // Default to the last-used category, else first tracked.
            val default = selectable.firstOrNull { it.id == recent.firstOrNull() }
                ?: selectable.firstOrNull { it.name in prefs.trackedCategories }
            default?.let { category.setText(it.name, false) }
        }

        val amountField = findViewById<EditText>(R.id.amount)
        val noteField = findViewById<EditText>(R.id.note)
        amountField.setText(entryAmountText)
        noteField.setText(entryNoteText)

        val title = findViewById<TextView>(R.id.title)
        val minus = findViewById<TextView>(R.id.sign_minus)
        val plusBtn = findViewById<TextView>(R.id.sign_plus)
        val expense = findViewById<LinearLayout>(R.id.expense_section)
        val income = findViewById<LinearLayout>(R.id.income_section)
        val modeRepay = findViewById<TextView>(R.id.mode_repayment)
        val modeIncome = findViewById<TextView>(R.id.mode_income)
        val modeHint = findViewById<TextView>(R.id.mode_hint)

        fun applySign() {
            title.setText(if (plus) R.string.add_money_in else R.string.add_expense)
            expense.visibility = if (plus) View.GONE else View.VISIBLE
            income.visibility = if (plus) View.VISIBLE else View.GONE
            minus.setBackgroundResource(if (plus) R.drawable.chip else R.drawable.chip_on)
            minus.setTextColor(if (plus) 0xFF5A5A5A.toInt() else 0xFFFFFFFF.toInt())
            plusBtn.setBackgroundResource(if (plus) R.drawable.chip_on else R.drawable.chip)
            plusBtn.setTextColor(if (plus) 0xFFFFFFFF.toInt() else 0xFF5A5A5A.toInt())
            if (!plus) amountField.requestFocus()
        }
        fun applyMode() {
            modeRepay.setBackgroundResource(if (modeRepayment) R.drawable.chip_on else R.drawable.chip)
            modeRepay.setTextColor(if (modeRepayment) 0xFFFFFFFF.toInt() else 0xFF5A5A5A.toInt())
            modeIncome.setBackgroundResource(if (modeRepayment) R.drawable.chip else R.drawable.chip_on)
            modeIncome.setTextColor(if (modeRepayment) 0xFF5A5A5A.toInt() else 0xFFFFFFFF.toInt())
            modeHint.setText(if (modeRepayment) R.string.repayment_hint else R.string.income_hint)
        }
        minus.setOnClickListener { plus = false; applySign() }
        plusBtn.setOnClickListener { plus = true; applySign() }
        modeRepay.setOnClickListener { modeRepayment = true; applyMode() }
        modeIncome.setOnClickListener { modeRepayment = false; applyMode() }
        applySign(); applyMode()

        findViewById<Button>(R.id.continue_btn).setOnClickListener {
            if (modeRepayment) showPersons() else showIncome()
        }

        fun stashEntry() {
            entryAmountText = amountField.text.toString()
            entryCategoryText = category.text.toString()
            entryNoteText = noteField.text.toString()
        }

        fun validExpense(): Category? {
            val amount = amountField.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0) {
                amountField.error = getString(R.string.bad_amount)
                return null
            }
            val typed = category.text.toString().trim()
            val chosen = selectable.firstOrNull { it.name.equals(typed, ignoreCase = true) }
            if (chosen == null) category.error = getString(R.string.bad_category)
            return chosen
        }

        findViewById<TextView>(R.id.split).setOnClickListener {
            val chosen = validExpense() ?: return@setOnClickListener
            stashEntry()
            splitTotal = amountField.text.toString().toDouble()
            splitCategoryId = chosen.id
            SplitMath.rebalance(splitTotal, splitRows())
            showSplit()
        }

        val save = findViewById<Button>(R.id.save)
        save.setOnClickListener {
            val chosen = validExpense() ?: return@setOnClickListener
            val amount = amountField.text.toString().toDouble()
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

    // ---------------------------------------------------------------- split

    private fun splitRows(): List<Share> =
        if (includeMe) listOf(me) + friends else friends.toList()

    private fun addFriend(display: String) {
        val slug = SplitMath.slugify(display)
        if (slug.isEmpty() || friends.any { it.slug == slug }) return
        friends.add(Share(slug, display.trim(), isMe = false))
        SplitMath.rebalance(splitTotal, splitRows())
        renderSplit()
    }

    private fun showSplit() {
        screen = Screen.SPLIT
        setContentView(R.layout.split_step)

        findViewById<TextView>(R.id.total).text = fmtA(splitTotal)
        findViewById<TextView>(R.id.split_note).text =
            entryNoteText.ifBlank { entryCategoryText }

        val person = findViewById<AutoCompleteTextView>(R.id.person)
        person.setAdapter(
            ArrayAdapter(this, R.layout.dropdown_item, prefs.people.map { it.second })
        )
        person.setOnItemClickListener { parent, _, pos, _ ->
            addFriend(parent.getItemAtPosition(pos) as String)
            person.setText("")
        }
        person.setOnEditorActionListener { _, _, _ ->
            val typed = person.text.toString().trim()
            if (typed.isNotEmpty()) { addFriend(typed); person.setText("") }
            true
        }

        findViewById<TextView>(R.id.contacts).setOnClickListener {
            // ponytail: system picker instead of in-search contact query — no
            // READ_CONTACTS permission needed; upgrade to inline search if it grates.
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), 1
            )
        }

        findViewById<TextView>(R.id.me_chip).setOnClickListener {
            includeMe = !includeMe
            me.locked = false
            SplitMath.rebalance(splitTotal, splitRows())
            renderSplit()
        }

        findViewById<TextView>(R.id.equal).setOnClickListener {
            splitRows().forEach { it.locked = false }
            SplitMath.rebalance(splitTotal, splitRows())
            renderSplit()
        }

        findViewById<TextView>(R.id.itemize).setOnClickListener { showItemize() }
        findViewById<Button>(R.id.save_split).setOnClickListener { saveSplit() }
        renderSplit()
    }

    private var renderingSplit = false

    private fun renderSplit() {
        if (screen != Screen.SPLIT || renderingSplit) return
        renderingSplit = true
        val rows = splitRows()
        val offBy = SplitMath.round2(splitTotal - rows.sumOf { it.amount })

        val meChip = findViewById<TextView>(R.id.me_chip)
        meChip.setBackgroundResource(if (includeMe) R.drawable.chip_on else R.drawable.chip)
        meChip.setTextColor(if (includeMe) 0xFFFFFFFF.toInt() else 0xFF5A5A5A.toInt())
        findViewById<TextView>(R.id.itemize).visibility =
            if (friends.isEmpty()) View.GONE else View.VISIBLE

        val bar = findViewById<LinearLayout>(R.id.prop_bar)
        bar.removeAllViews()
        for (r in rows) {
            val seg = View(this)
            seg.setBackgroundColor(if (r.isMe) 0xFFFFFFFF.toInt() else 0xFF5A5A5A.toInt())
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT,
                maxOf(0.02f, r.amount.toFloat()))
            lp.marginEnd = (3 * resources.displayMetrics.density).toInt()
            bar.addView(seg, lp)
        }

        val container = findViewById<LinearLayout>(R.id.shares)
        container.removeAllViews()
        for (r in rows) {
            val row = layoutInflater.inflate(R.layout.row_share, container, false)
            val lock = row.findViewById<TextView>(R.id.lock)
            lock.text = if (r.locked) "●" else "○"
            lock.setTextColor(if (r.locked) 0xFFFFFFFF.toInt() else 0xFF5A5A5A.toInt())
            row.findViewById<TextView>(R.id.name).text = if (r.isMe) "You" else r.display
            val remove = row.findViewById<TextView>(R.id.remove)
            if (r.isMe) remove.visibility = View.GONE
            else remove.setOnClickListener {
                friends.remove(r)
                SplitMath.rebalance(splitTotal, splitRows())
                renderSplit()
            }
            val amt = row.findViewById<EditText>(R.id.share_amt)
            amt.setText(fmtA(r.amount))
            fun commit() {
                val v = amt.text.toString().toDoubleOrNull() ?: return
                if (SplitMath.round2(v) == r.amount) return
                r.locked = true
                r.amount = SplitMath.round2(v)
                SplitMath.rebalance(splitTotal, splitRows())
                renderSplit()
            }
            amt.setOnEditorActionListener { _, _, _ -> commit(); true }
            amt.setOnFocusChangeListener { _, focused -> if (!focused) commit() }
            container.addView(row)
        }

        val off = findViewById<TextView>(R.id.offby)
        off.visibility = if (offBy != 0.0) View.VISIBLE else View.GONE
        off.text = getString(R.string.off_by, money(Math.abs(offBy)))
        findViewById<TextView>(R.id.yours).text = fmtA(if (includeMe) me.amount else 0.0)
        findViewById<Button>(R.id.save_split).isEnabled = offBy == 0.0
        renderingSplit = false
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.query(
            uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null
        )?.use {
            if (it.moveToFirst()) addFriend(it.getString(0) ?: return)
        }
    }

    private fun saveSplit() {
        if (friends.isEmpty()) {
            Toast.makeText(this, R.string.need_people, Toast.LENGTH_SHORT).show()
            return
        }
        val save = findViewById<Button>(R.id.save_split)
        save.isEnabled = false
        val note = entryNoteText
        val myShare = if (includeMe) me.amount else 0.0
        val owed = friends.filter { it.amount > 0 }
        thread {
            try {
                val api = LunchMoneyApi(prefs.token)
                val reimbId = Reimbursements.ensureCategory(api, prefs)
                val today = LocalDate.now()
                val txns = mutableListOf<NewTxn>()
                if (myShare > 0) txns.add(NewTxn(today, myShare, splitCategoryId, note))
                owed.forEach {
                    txns.add(NewTxn(today, it.amount, reimbId, note,
                        tags = listOf(prefs.tagPrefix + it.slug)))
                }
                val ids = api.insertTransactions(txns)
                Reimbursements.verifyTagPrefix(
                    api, prefs,
                    ids.drop(if (myShare > 0) 1 else 0),
                    owed.map { it.slug },
                )
                owed.forEach { prefs.touchPerson(it.slug, it.display) }
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

    // ---------------------------------------------------------------- itemize

    private fun uniqueInitials(ps: List<Share>): Map<String, String> =
        SplitMath.uniqueInitials(ps.map { it.slug to (if (it.isMe) "You" else it.display) })

    private fun showItemize() {
        screen = Screen.ITEMIZE
        setContentView(R.layout.itemize)
        findViewById<TextView>(R.id.itemize_title).text =
            getString(R.string.itemize_title, fmtA(splitTotal))
        val ini = uniqueInitials(itemizeParticipants())
        findViewById<TextView>(R.id.itemize_legend).text =
            itemizeParticipants().joinToString("   ") {
                "${ini.getValue(it.slug)} ${if (it.isMe) "YOU" else it.display.uppercase(Locale.US)}"
            }
        if (billItems.isEmpty()) billItems.add(UiItem())
        findViewById<TextView>(R.id.add_item).setOnClickListener {
            billItems.add(UiItem())
            renderItemize()
        }
        findViewById<Button>(R.id.itemize_done).setOnClickListener { applyItemize() }
        renderItemize()
    }

    private fun itemizeParticipants(): List<Share> =
        if (includeMe) listOf(me) + friends else friends.toList()

    // Rows with an amount; assignees narrowed to people still in the split.
    private fun validBillItems(): List<BillItem> {
        val slugs = itemizeParticipants().map { it.slug }.toSet()
        return billItems
            .filter { (it.amount ?: 0.0) > 0 }
            .map { BillItem(it.amount!!, it.label, it.assignees intersect slugs) }
    }

    private fun renderItemize() {
        if (screen != Screen.ITEMIZE) return
        val container = findViewById<LinearLayout>(R.id.bill_items)
        container.removeAllViews()
        for (item in billItems) {
            val row = layoutInflater.inflate(R.layout.row_bill_item, container, false)
            val amt = row.findViewById<EditText>(R.id.bill_amt)
            if (item.amount != null) amt.setText(fmtA(item.amount!!))
            amt.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    item.amount = s.toString().toDoubleOrNull()
                    updateItemizeFooter()
                }
            })
            val label = row.findViewById<EditText>(R.id.bill_label)
            label.setText(item.label)
            label.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { item.label = s.toString() }
            })
            row.findViewById<TextView>(R.id.bill_remove).setOnClickListener {
                billItems.remove(item)
                renderItemize()
            }
            val chips = row.findViewById<LinearLayout>(R.id.bill_chips)
            val d = resources.displayMetrics.density
            val ini = uniqueInitials(itemizeParticipants())
            for (p in itemizeParticipants()) {
                val chip = TextView(this)
                val on = p.slug in item.assignees
                chip.text = ini.getValue(p.slug)
                chip.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF5A5A5A.toInt())
                chip.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip)
                chip.textSize = 10f
                chip.gravity = android.view.Gravity.CENTER
                chip.typeface = resources.getFont(R.font.space_mono_bold)
                chip.minWidth = (26 * d).toInt()
                val pad = (8 * d).toInt()
                chip.setPadding(pad, 0, pad, 0)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, (26 * d).toInt()
                )
                lp.marginEnd = (6 * d).toInt()
                chip.setOnClickListener {
                    if (p.slug in item.assignees) item.assignees.remove(p.slug)
                    else item.assignees.add(p.slug)
                    renderItemize()
                }
                chips.addView(chip, lp)
            }
            container.addView(row)
        }
        updateItemizeFooter()
    }

    private fun updateItemizeFooter() {
        if (screen != Screen.ITEMIZE) return
        val valid = validBillItems()
        val itemsSum = SplitMath.round2(valid.sumOf { it.amount })
        val allAssigned = valid.isNotEmpty() && valid.all { it.assignees.isNotEmpty() }
        val footer = findViewById<TextView>(R.id.items_footer)
        // Receipt-style footer: subtotal line, then the adjustment on its own line.
        var text = getString(R.string.items_vs_bill, fmtA(itemsSum), fmtA(splitTotal))
        // Only surface the derived adjustment once entry is complete — mid-typing
        // percentages are meaningless noise.
        if (itemsSum > 0 && allAssigned) {
            val pct = (splitTotal - itemsSum) / itemsSum * 100
            val pctStr = String.format(Locale.US, if (pct % 1.0 == 0.0) "%.0f" else "%.1f", Math.abs(pct))
            if (pct >= 0.05) text += "\n" + getString(R.string.tax_fees, pctStr)
            else if (pct <= -0.05) text += "\n" + getString(R.string.discount, pctStr)
        }
        if (!allAssigned) text += "\n" + getString(R.string.assign_all)
        footer.text = text
        val done = findViewById<Button>(R.id.itemize_done)
        done.isEnabled = allAssigned
        done.alpha = if (allAssigned) 1f else 0.3f
    }

    private fun applyItemize() {
        val meKey = if (includeMe) me.slug else friends.firstOrNull()?.slug ?: return
        val shares = SplitMath.itemize(splitTotal, validBillItems(), meKey)
        if (shares.isEmpty()) return
        for (r in itemizeParticipants()) {
            r.amount = shares[r.slug] ?: 0.0
            r.locked = true
        }
        showSplit()
    }

    // ---------------------------------------------------------------- repayment

    private fun showPersons() {
        screen = Screen.PERSONS
        setContentView(R.layout.person_list)

        val recencyRank = prefs.people.withIndex().associate { (i, p) -> p.first to i }
        val pending = prefs.pending.filter { it.total > 0 }
            .sortedWith(compareBy({ recencyRank[it.slug] ?: Int.MAX_VALUE }, { it.slug }))

        val container = findViewById<LinearLayout>(R.id.persons)
        if (pending.isEmpty()) {
            findViewById<TextView>(R.id.pending_hint).setText(R.string.no_pending)
            return
        }
        for (p in pending) {
            val row = layoutInflater.inflate(R.layout.row_person, container, false)
            row.findViewById<TextView>(R.id.name).text = displayName(p.slug)
            row.findViewById<TextView>(R.id.detail).text = getString(
                R.string.person_detail, p.items.size,
                p.items.firstOrNull()?.date?.let { shortDate(it) } ?: "—",
            )
            row.findViewById<TextView>(R.id.amount).text = fmtA(p.total)
            row.setOnClickListener {
                settlePerson = p
                received = p.total
                showSettle()
            }
            container.addView(row)
        }
    }

    private fun showSettle() {
        val p = settlePerson ?: return
        screen = Screen.SETTLE
        setContentView(R.layout.settle)

        findViewById<TextView>(R.id.settle_title).text =
            "${displayName(p.slug).uppercase(Locale.US)} · PENDING ${money(p.total)}"

        val field = findViewById<EditText>(R.id.received)
        var rendering = false
        field.setText(fmtA(received))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (rendering) return
                received = s.toString().toDoubleOrNull() ?: 0.0
                renderSettle()
            }
        })

        findViewById<Button>(R.id.settle_btn).setOnClickListener { saveSettle() }

        // Tapping an item fills exactly through it.
        renderSettle()
        fun refill(v: Double) {
            rendering = true
            field.setText(fmtA(v))
            field.setSelection(field.text.length)
            rendering = false
            received = v
            renderSettle()
        }
        fillThrough = { v -> refill(v) }
    }

    private var fillThrough: (Double) -> Unit = {}

    private fun renderSettle() {
        val p = settlePerson ?: return
        if (screen != Screen.SETTLE) return
        val poured = SplitMath.pour(p.items, received)

        val container = findViewById<LinearLayout>(R.id.items)
        container.removeAllViews()
        var cumulative = 0.0
        for (pd in poured) {
            cumulative = SplitMath.round2(cumulative + pd.item.amount)
            val through = cumulative
            val row = layoutInflater.inflate(R.layout.row_item, container, false)
            val label = row.findViewById<TextView>(R.id.item_label)
            label.text = "${shortDate(pd.item.date)} · ${pd.item.payee}"
            val amt = row.findViewById<TextView>(R.id.item_amt)
            amt.text = fmtA(pd.item.amount)
            val dim = pd.take <= 0.0
            label.setTextColor(if (dim) 0xFF5A5A5A.toInt() else 0xFFFFFFFF.toInt())
            amt.setTextColor(if (dim) 0xFF5A5A5A.toInt() else 0xFFFFFFFF.toInt())
            val frac = pd.frac.toFloat().coerceIn(0f, 1f)
            row.findViewById<View>(R.id.fill_on).layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, frac)
            row.findViewById<View>(R.id.fill_off).layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - frac)
            val partial = row.findViewById<TextView>(R.id.partial)
            if (frac > 0f && frac < 1f) {
                partial.visibility = View.VISIBLE
                partial.text = getString(
                    R.string.partial_left,
                    money(SplitMath.round2(pd.item.amount - pd.take)),
                )
            }
            row.setOnClickListener { fillThrough(through) }
            container.addView(row)
        }

        val over = findViewById<TextView>(R.id.overpaid)
        if (received > p.total) {
            over.visibility = View.VISIBLE
            over.text = getString(
                R.string.overpaid_by, money(SplitMath.round2(received - p.total))
            )
        } else over.visibility = View.GONE

        val btn = findViewById<Button>(R.id.settle_btn)
        btn.text = getString(R.string.settle_amount, money(received))
        btn.isEnabled = received > 0
    }

    private fun saveSettle() {
        val p = settlePerson ?: return
        val amount = received
        val btn = findViewById<Button>(R.id.settle_btn)
        btn.isEnabled = false
        thread {
            try {
                val api = LunchMoneyApi(prefs.token)
                val reimbId = Reimbursements.ensureCategory(api, prefs)
                api.insertTransactions(listOf(
                    NewTxn(LocalDate.now(), -amount, reimbId,
                        "Repayment — ${displayName(p.slug)}",
                        tags = listOf(prefs.tagPrefix + p.slug))
                ))
                prefs.touchPerson(p.slug, displayName(p.slug))
                RefreshWorker.refreshNow(this)
                runOnUiThread {
                    Toast.makeText(this, R.string.added, Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
                    btn.isEnabled = true
                }
            }
        }
    }

    // ---------------------------------------------------------------- income

    private fun showIncome() {
        val cats = prefs.categories.filter { it.isIncome && !it.isGroup }
        if (cats.isEmpty()) {
            Toast.makeText(this, R.string.no_income_categories, Toast.LENGTH_LONG).show()
            return
        }
        screen = Screen.INCOME
        setContentView(R.layout.income)

        val category = findViewById<AutoCompleteTextView>(R.id.income_category)
        category.setAdapter(ArrayAdapter(this, R.layout.dropdown_item, cats.map { it.name }))
        category.setOnClickListener { category.showDropDown() }
        category.setOnFocusChangeListener { _, focused -> if (focused) category.showDropDown() }

        val amountField = findViewById<EditText>(R.id.income_amount)
        amountField.requestFocus()

        val save = findViewById<Button>(R.id.save_income)
        save.setOnClickListener {
            val amount = amountField.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0) {
                amountField.error = getString(R.string.bad_amount)
                return@setOnClickListener
            }
            val typed = category.text.toString().trim()
            val chosen = cats.firstOrNull { it.name.equals(typed, ignoreCase = true) }
            if (chosen == null) {
                category.error = getString(R.string.bad_category)
                return@setOnClickListener
            }
            save.isEnabled = false
            thread {
                try {
                    LunchMoneyApi(prefs.token)
                        .insertTransaction(LocalDate.now(), -amount, chosen.id, null)
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
