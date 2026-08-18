package app.lunchwidget

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class QuickAddActivity : Activity() {

    private enum class Screen { ENTRY, SPLIT, ITEMIZE, PERSONS, SETTLE, INCOME, UNDO }

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
    private var transfer = false
    private var modeRepayment = true
    private var entryAmountText = ""
    private var entryCategoryText = ""
    private var entryNoteText = ""
    private var entryTagsText = ""

    // Account the money left or landed in. Also the FROM side of a transfer.
    private var assetId = 0L
    private var toAssetId = 0L

    // Date every transaction from this dialog carries. Hidden and pinned to today
    // when the date picker is switched off in settings.
    private var entryDate: LocalDate = LocalDate.now()

    // The post is held for UNDO_MS so UNDO can drop it before it ever reaches the
    // network — Lunch Money's v1 API has no way to delete a transaction, so the
    // only honest undo is one that hasn't sent yet.
    private val undoTimer = Handler(Looper.getMainLooper())
    private var pendingPost: (() -> Unit)? = null

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
        assetId = prefs.defaultAssetId
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
            Screen.ENTRY, Screen.UNDO -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // Leaving commits. You already pressed save; UNDO is the only thing that cancels,
    // so dismissing the dialog or going home must not silently drop the transaction.
    override fun onPause() {
        super.onPause()
        undoTimer.removeCallbacksAndMessages(null)
        flushPost()
    }

    private fun flushPost() {
        val post = pendingPost ?: return
        pendingPost = null
        post()
    }

    /**
     * Swap the dialog for a one-line receipt with an UNDO button, then send [post]
     * when the window closes. Nothing has been written to Lunch Money yet.
     */
    private fun showUndo(label: String, post: () -> Unit) {
        screen = Screen.UNDO
        pendingPost = post
        setContentView(R.layout.undo)
        findViewById<TextView>(R.id.undo_label).text = label
        findViewById<TextView>(R.id.undo_btn).setOnClickListener {
            pendingPost = null
            undoTimer.removeCallbacksAndMessages(null)
            Toast.makeText(this, R.string.undone, Toast.LENGTH_SHORT).show()
            finish()
        }
        undoTimer.postDelayed({ flushPost(); finish() }, UNDO_MS)
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

    // Lunch Money creates tag names on insert, so a typo silently makes a new
    // tag. The field autocompletes against the ones you already have to keep
    // that from happening.
    private fun typedTags(): List<String> =
        entryTagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun chipOn(tv: TextView, on: Boolean) {
        tv.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip)
        tv.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF5A5A5A.toInt())
    }

    // Tap-to-pick account field. Same chip on every screen that posts money;
    // index 0 of the dialog is "no account", which posts without asset_id.
    private fun wireAccount(
        viewId: Int,
        labelRes: Int,
        noneRes: Int,
        get: () -> Long,
        set: (Long) -> Unit,
    ) {
        val chip = findViewById<TextView>(viewId)
        fun render() {
            val name = prefs.assets.firstOrNull { it.id == get() }?.name
            chip.text = if (name == null) getString(noneRes)
            else getString(labelRes, name.uppercase(Locale.US))
        }
        chip.setOnClickListener {
            val assets = prefs.assets
            if (assets.isEmpty()) {
                Toast.makeText(this, R.string.no_assets, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val labels = (listOf(getString(R.string.no_default_account)) + assets.map { it.name })
                .toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.pick_account)
                .setItems(labels) { _, i ->
                    set(if (i == 0) 0L else assets[i - 1].id)
                    render()
                }
                .show()
        }
        render()
    }

    // Tap-to-pick date. Absent entirely when the setting is off, which pins every
    // transaction to today — the quick-add default.
    private fun wireDate(viewId: Int) {
        val chip = findViewById<TextView>(viewId)
        if (!prefs.dateEntry) {
            chip.visibility = View.GONE
            return
        }
        fun render() {
            val today = LocalDate.now()
            val label = when (entryDate) {
                today -> getString(R.string.today_label)
                today.minusDays(1) -> getString(R.string.yesterday_label)
                else -> entryDate.format(DateTimeFormatter.ofPattern("dd MMM")).uppercase(Locale.US)
            }
            chip.text = getString(R.string.date_label, label)
        }
        chip.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m, d -> entryDate = LocalDate.of(y, m + 1, d); render() },
                entryDate.year, entryDate.monthValue - 1, entryDate.dayOfMonth,
            ).apply {
                // Backfilling is the point; scheduling isn't.
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        render()
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

        val tagsField = findViewById<MultiAutoCompleteTextView>(R.id.tags)
        // owed:<person> tags are written by the split flow and read back as the
        // pending ledger — they aren't yours to pick, so keep them out of the list.
        val pickable = prefs.tags.filterNot { it.startsWith(prefs.tagPrefix) }
        tagsField.setAdapter(ArrayAdapter(this, R.layout.dropdown_item, pickable))
        // Autocomplete each comma-separated token rather than the whole field.
        tagsField.setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
        tagsField.setText(entryTagsText)

        val title = findViewById<TextView>(R.id.title)
        val minus = findViewById<TextView>(R.id.sign_minus)
        val plusBtn = findViewById<TextView>(R.id.sign_plus)
        val transferBtn = findViewById<TextView>(R.id.sign_transfer)
        val expense = findViewById<LinearLayout>(R.id.expense_section)
        val income = findViewById<LinearLayout>(R.id.income_section)
        val transferSection = findViewById<LinearLayout>(R.id.transfer_section)
        val modeRepay = findViewById<TextView>(R.id.mode_repayment)
        val modeIncome = findViewById<TextView>(R.id.mode_income)
        val modeHint = findViewById<TextView>(R.id.mode_hint)

        wireAccount(R.id.account, R.string.account_label, R.string.account_none,
            { assetId }, { assetId = it })
        wireDate(R.id.date)

        fun applySign() {
            title.setText(
                when {
                    transfer -> R.string.transfer
                    plus -> R.string.add_money_in
                    else -> R.string.add_expense
                }
            )
            val minusOn = !plus && !transfer
            expense.visibility = if (minusOn) View.VISIBLE else View.GONE
            income.visibility = if (plus) View.VISIBLE else View.GONE
            transferSection.visibility = if (transfer) View.VISIBLE else View.GONE
            chipOn(minus, minusOn)
            chipOn(plusBtn, plus)
            chipOn(transferBtn, transfer)
            if (minusOn) amountField.requestFocus()
        }
        fun applyMode() {
            chipOn(modeRepay, modeRepayment)
            chipOn(modeIncome, !modeRepayment)
            modeHint.setText(if (modeRepayment) R.string.repayment_hint else R.string.income_hint)
        }
        minus.setOnClickListener { plus = false; transfer = false; applySign() }
        plusBtn.setOnClickListener { plus = true; transfer = false; applySign() }
        transferBtn.setOnClickListener { plus = false; transfer = true; applySign() }
        modeRepay.setOnClickListener { modeRepayment = true; applyMode() }
        modeIncome.setOnClickListener { modeRepayment = false; applyMode() }
        applySign(); applyMode(); wireTransfer()

        findViewById<Button>(R.id.continue_btn).setOnClickListener {
            if (modeRepayment) showPersons() else showIncome()
        }

        fun stashEntry() {
            entryAmountText = amountField.text.toString()
            entryCategoryText = category.text.toString()
            entryNoteText = noteField.text.toString()
            entryTagsText = tagsField.text.toString()
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
            stashEntry()
            val amount = amountField.text.toString().toDouble()
            val note = noteField.text.toString()
            val txn = NewTxn(
                entryDate, amount, chosen.id, note,
                tags = typedTags(), assetId = assetId,
            )
            showUndo(getString(R.string.added_amount, money(amount))) {
                PostWorker.enqueue(applicationContext, listOf(txn))
            }
        }
    }

    // ---------------------------------------------------------------- transfer

    private fun wireTransfer() {
        wireAccount(R.id.transfer_from, R.string.transfer_from, R.string.transfer_from_none,
            { assetId }, { assetId = it })
        wireAccount(R.id.transfer_to, R.string.transfer_to, R.string.transfer_to_none,
            { toAssetId }, { toAssetId = it })
        wireDate(R.id.transfer_date)

        val amountField = findViewById<EditText>(R.id.transfer_amount)
        val save = findViewById<Button>(R.id.save_transfer)
        save.setOnClickListener {
            val amount = amountField.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0) {
                amountField.error = getString(R.string.bad_amount)
                return@setOnClickListener
            }
            if (assetId == 0L || toAssetId == 0L) {
                Toast.makeText(this, R.string.pick_both_accounts, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (assetId == toAssetId) {
                Toast.makeText(this, R.string.same_account, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // ponytail: match Lunch Money's own transfer category by name rather than
            // adding a setting for it — it ships with one and it's already excluded.
            val cat = prefs.categories
                .firstOrNull { !it.isGroup && it.name.contains("transfer", ignoreCase = true) }
            if (cat == null) {
                Toast.makeText(this, R.string.no_transfer_category, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val from = prefs.assets.first { it.id == assetId }.name
            val to = prefs.assets.first { it.id == toAssetId }.name
            val payee = "Transfer — $from → $to"
            // Two legs: positive leaves the source, negative lands in the
            // destination. The category is excluded, so the allowance ignores both.
            val legs = listOf(
                NewTxn(entryDate, amount, cat.id, payee, assetId = assetId),
                NewTxn(entryDate, -amount, cat.id, payee, assetId = toAssetId),
            )
            showUndo(getString(R.string.added_amount, money(amount))) {
                PostWorker.enqueue(applicationContext, legs)
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
        if (resultCode != RESULT_OK) { if (requestCode == 2) scanFile().delete(); return }
        when (requestCode) {
            1 -> {
                val uri = data?.data ?: return
                contentResolver.query(
                    uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null
                )?.use {
                    if (it.moveToFirst()) addFriend(it.getString(0) ?: return)
                }
            }
            2 -> recognizeScan()
        }
    }

    // ------------------------------------------------------------- receipt scan

    private fun scanFile() = File(cacheDir, "scan.jpg")

    private fun launchScan() {
        val uri = FileProvider.getUriForFile(this, "app.lunchwidget.fileprovider", scanFile())
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            startActivityForResult(intent, 2)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_SHORT).show()
        }
    }

    // Photo → text → rows; the capture never outlives the recognizer
    // (spec-receipt-ocr.md §0: scan-and-discard, nothing retained).
    private fun recognizeScan() {
        val file = scanFile()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromFilePath(this, Uri.fromFile(file)))
            .addOnSuccessListener { text ->
                // Positions matter: printed receipts column-ize and ML Kit
                // returns the columns as separate lines; rows() re-joins them.
                val ocrLines = text.textBlocks.flatMap { it.lines }.mapIndexed { i, l ->
                    val b = l.boundingBox
                    ReceiptParser.OcrLine(
                        l.text,
                        b?.top ?: (i * 1000),
                        b?.bottom ?: (i * 1000 + 1),
                        b?.left ?: 0,
                    )
                }
                val parsed = ReceiptParser.parse(ReceiptParser.rows(ocrLines))
                if (parsed.isEmpty()) {
                    Toast.makeText(this, R.string.scan_nothing, Toast.LENGTH_SHORT).show()
                } else {
                    // Untouched placeholder rows make way for the scanned ones.
                    billItems.removeAll {
                        it.amount == null && it.label.isBlank() && it.assignees.isEmpty()
                    }
                    billItems.addAll(parsed.map { UiItem(it.amount, it.label) })
                    renderItemize()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, R.string.scan_nothing, Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener {
                file.delete()
                recognizer.close()
            }
    }

    private fun saveSplit() {
        if (friends.isEmpty()) {
            Toast.makeText(this, R.string.need_people, Toast.LENGTH_SHORT).show()
            return
        }
        val note = entryNoteText
        val myShare = if (includeMe) me.amount else 0.0
        val owed = friends.filter { it.amount > 0 }
        val tags = typedTags()
        val txns = mutableListOf<NewTxn>()
        if (myShare > 0) {
            txns.add(
                NewTxn(entryDate, myShare, splitCategoryId, note,
                    tags = tags, assetId = assetId)
            )
        }
        // The owed portions are the same bill, so they carry the same tags —
        // with the person's owed: tag in front, where verifyTagPrefix expects it.
        owed.forEach {
            txns.add(
                NewTxn(entryDate, it.amount, PostWorker.REIMBURSEMENTS, note,
                    tags = listOf(prefs.tagPrefix + it.slug) + tags, assetId = assetId)
            )
        }
        // Recency is a local nicety — record it now, whether or not the post lands.
        owed.forEach { prefs.touchPerson(it.slug, it.display) }
        copySplitSummary()
        showUndo(getString(R.string.added_amount, money(splitTotal))) {
            PostWorker.enqueue(
                applicationContext, txns,
                slugs = owed.map { it.slug },
                tagOffset = if (myShare > 0) 1 else 0,
            )
        }
    }

    // The next stop after saving a split is GPay/Splitwise, where the same
    // shares get re-entered by hand — so hand them over via the clipboard.
    // Each distinct amount is copied on its own first: the system keeps only
    // the last clip as primary, but keyboard clipboard history (Gboard) keeps
    // every one, so single amounts can be pasted straight into amount fields.
    // The summary block goes last and stays primary: note · total, then a
    // name: amount line per person.
    private fun copySplitSummary() {
        val cm = getSystemService(ClipboardManager::class.java)
        val amounts = (friends.map { it.amount } +
            (if (includeMe) listOf(me.amount) else emptyList()) +
            splitTotal).filter { it > 0 }.distinct()
        amounts.forEach { cm.setPrimaryClip(ClipData.newPlainText("share", fmtA(it))) }
        val text = buildString {
            append(entryNoteText.ifBlank { getString(R.string.split_receipt) })
            append(" · ").append(fmtA(splitTotal))
            if (includeMe) append('\n').append("You: ").append(fmtA(me.amount))
            for (f in friends) append('\n').append(f.display).append(": ").append(fmtA(f.amount))
        }
        cm.setPrimaryClip(ClipData.newPlainText("split", text))
        // Android 13+ shows its own "copied" overlay; only speak up below that.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, R.string.split_copied, Toast.LENGTH_SHORT).show()
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
        findViewById<TextView>(R.id.scan_item).setOnClickListener { launchScan() }
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

        wireAccount(R.id.account, R.string.account_label, R.string.account_none,
            { assetId }, { assetId = it })
        wireDate(R.id.date)
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
        val txn = NewTxn(
            entryDate, -amount, PostWorker.REIMBURSEMENTS,
            "Repayment — ${displayName(p.slug)}",
            tags = listOf(prefs.tagPrefix + p.slug), assetId = assetId,
        )
        prefs.touchPerson(p.slug, displayName(p.slug))
        showUndo(getString(R.string.added_amount, money(amount))) {
            PostWorker.enqueue(applicationContext, listOf(txn))
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
        wireAccount(R.id.account, R.string.account_label, R.string.account_none,
            { assetId }, { assetId = it })
        wireDate(R.id.date)

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
            val txn = NewTxn(entryDate, -amount, chosen.id, null, assetId = assetId)
            showUndo(getString(R.string.added_amount, money(amount))) {
                PostWorker.enqueue(applicationContext, listOf(txn))
            }
        }
    }

    companion object {
        // Long enough to catch a fat-fingered amount, short enough not to feel like
        // a confirmation step — the widget's whole point is four-second logging.
        private const val UNDO_MS = 4000L
    }
}
