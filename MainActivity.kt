package cg.garde.dns

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class AppItem(val pkg: String, val label: String, val icon: Drawable)

class MainActivity : Activity() {

    // Palette
    private val BG = Color.parseColor("#0E1116")
    private val CARD = Color.parseColor("#171B22")
    private val CARD2 = Color.parseColor("#1F2530")
    private val LINE = Color.parseColor("#2A313D")
    private val TXT = Color.parseColor("#EEF1F5")
    private val SUB = Color.parseColor("#8C96A5")
    private val ACCENT = Color.parseColor("#2DD4A3")
    private val ACCENT_DARK = Color.parseColor("#0B2A22")
    private val WARN = Color.parseColor("#F5B547")
    private val BAD = Color.parseColor("#F26D6D")

    private lateinit var heroTitle: TextView
    private lateinit var heroSub: TextView
    private lateinit var heroPill: TextView
    private lateinit var checks: LinearLayout
    private lateinit var dnsHint: TextView
    private lateinit var dnsInput: EditText
    private lateinit var daysSpinner: Spinner
    private lateinit var lockCard: View
    private lateinit var btnLock: TextView
    private lateinit var btnRequest: TextView
    private lateinit var btnCancel: TextView
    private lateinit var btnDisable: TextView
    private lateinit var btnUninstall: TextView
    private lateinit var btnKw: TextView
    private lateinit var appAdapter: AppAdapter
    private val items = mutableListOf<AppItem>()
    private var selected = mutableSetOf<String>()

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun rounded(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().also {
        it.setColor(color)
        it.cornerRadius = dp(radius).toFloat()
        if (stroke != 0) it.setStroke(dp(1), stroke)
    }

    private fun ripple(bg: Drawable) = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), bg, null)

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = dp(top) }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).also {
        it.text = t
        it.textSize = size
        it.setTextColor(color)
        if (bold) it.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        it.setLineSpacing(0f, 1.15f)
    }

    /** Bouton principal (plein) */
    private fun primaryButton(t: String, onClick: () -> Unit) = text(t, 16f, ACCENT_DARK, true).also {
        it.gravity = Gravity.CENTER
        it.setPadding(dp(16), dp(15), dp(16), dp(15))
        it.background = ripple(rounded(ACCENT, 14))
        it.isClickable = true
        it.setOnClickListener { onClick() }
        it.layoutParams = lp(14)
    }

    /** Bouton secondaire (contour) */
    private fun ghostButton(t: String, color: Int = TXT, onClick: () -> Unit) = text(t, 15f, color, true).also {
        it.gravity = Gravity.CENTER
        it.setPadding(dp(16), dp(13), dp(16), dp(13))
        it.background = ripple(rounded(CARD2, 14, LINE))
        it.isClickable = true
        it.setOnClickListener { onClick() }
        it.layoutParams = lp(10)
    }

    /** Ligne d'action : pastille numérotée + titre + sous-titre + flèche */
    private fun stepRow(num: String, title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(14), dp(14), dp(14))
        row.background = ripple(rounded(CARD2, 14))
        row.isClickable = true
        row.setOnClickListener { onClick() }
        row.layoutParams = lp(10)

        val badge = text(num, 14f, ACCENT, true)
        badge.gravity = Gravity.CENTER
        badge.background = rounded(ACCENT_DARK, 18)
        badge.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val cp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        cp.leftMargin = dp(14)
        col.layoutParams = cp
        col.addView(text(title, 15f, TXT, true))
        if (sub.isNotEmpty()) col.addView(text(sub, 13f, SUB))

        row.addView(badge)
        row.addView(col)
        row.addView(text("›", 22f, SUB))
        return row
    }

    private fun card(title: String, vararg children: View): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(18), dp(18), dp(18), dp(18))
        c.background = rounded(CARD, 22)
        c.layoutParams = lp(16)
        if (title.isNotEmpty()) {
            val t = text(title.uppercase(), 12f, SUB, true)
            t.letterSpacing = 0.1f
            c.addView(t)
        }
        children.forEach { c.addView(it) }
        return c
    }

    private fun fieldLabel(t: String) = text(t, 13f, SUB).also { it.layoutParams = lp(14) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        // Liste des applications installées (les applis essentielles ne sont jamais proposées)
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { ri -> AppItem(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), ri.loadIcon(pm)) }
            .filter { it.pkg != packageName && !Guard.isEssential(it.pkg) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .forEach { items.add(it) }
        selected = Guard.blocked(this).toMutableSet()

        // Premier lancement : Telegram est coché d'office s'il est installé
        if (!Guard.initDone(this)) {
            items.filter { it.pkg.startsWith("org.telegram") }.forEach { selected.add(it.pkg) }
            Guard.setBlocked(this, selected)
            Guard.markInit(this)
        }

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(18), dp(36), dp(18), dp(8))

        // ----- Bandeau d'état -----
        val app = text("GARDE DNS", 12f, ACCENT, true)
        app.letterSpacing = 0.18f
        header.addView(app)
        heroTitle = text("", 30f, TXT, true)
        heroTitle.layoutParams = lp(6)
        header.addView(heroTitle)
        heroSub = text("", 14f, SUB)
        heroSub.layoutParams = lp(4)
        header.addView(heroSub)
        heroPill = text("", 13f, ACCENT, true)
        heroPill.setPadding(dp(12), dp(6), dp(12), dp(6))
        heroPill.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(12) }
        header.addView(heroPill)

        // ----- Vérifications -----
        checks = LinearLayout(this)
        checks.orientation = LinearLayout.VERTICAL
        header.addView(card("État de la protection", checks))

        // ----- Mise en place -----
        header.addView(card("Mise en place",
            stepRow("1", "Activer la protection", "Accessibilité › Garde DNS") {
                toast("Cherche « Garde DNS » dans la liste et active-le")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            stepRow("1b", "Activation refusée ?", "Autoriser les paramètres restreints") {
                toast("Dans cet écran : menu ⋮ en haut à droite > « Autoriser les paramètres restreints », puis refais l'étape 1")
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
            stepRow("2", "Anti-désinstallation", "Administrateur de l'appareil") {
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, GardeAdmin::class.java))
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Empêche de désinstaller Garde DNS pendant le verrou.")
                startActivity(i)
            },
            stepRow("3", "Batterie", "Ne jamais mettre Garde DNS en veille") {
                toast("Choisis « Non restreint » / « Jamais en veille » pour Garde DNS (voir LISEZ-MOI)")
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        ))

        // ----- Verrou -----
        dnsInput = EditText(this)
        dnsInput.setText(Guard.dns(this))
        dnsInput.setSingleLine()
        dnsInput.setTextColor(TXT)
        dnsInput.setHintTextColor(SUB)
        dnsInput.textSize = 15f
        dnsInput.setPadding(dp(14), dp(13), dp(14), dp(13))
        dnsInput.background = rounded(CARD2, 12, LINE)
        dnsInput.layoutParams = lp(6)

        daysSpinner = Spinner(this)
        daysSpinner.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("1 jour (période d'essai)", "2 jours", "3 jours", "4 jours", "5 jours")
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.setTextColor(TXT)
                return v
            }
        }
        daysSpinner.setSelection((Guard.delayDays(this) - 1).coerceIn(0, 4))
        daysSpinner.background = rounded(CARD2, 12, LINE)
        daysSpinner.setPadding(dp(6), dp(4), dp(6), dp(4))
        daysSpinner.layoutParams = lp(6)

        dnsHint = text("", 13f, WARN)
        dnsHint.setPadding(dp(12), dp(10), dp(12), dp(10))
        dnsHint.background = rounded(Color.parseColor("#2A2213"), 12)
        dnsHint.layoutParams = lp(14)

        btnLock = primaryButton("Verrouiller") { doLock() }
        lockCard = card("Verrou",
            fieldLabel("DNS filtrant (nom d'hôte)"), dnsInput,
            fieldLabel("Délai avant de pouvoir déverrouiller"), daysSpinner,
            dnsHint, btnLock
        )
        header.addView(lockCard)

        btnRequest = primaryButton("Demander le déverrouillage") {
            if (Guard.requestUnlock(this)) {
                toast("Compte à rebours lancé")
            } else {
                toast("Heure non vérifiée : connecte-toi à internet puis réessaie")
                Guard.syncAsync(this)
            }
            refresh()
        }
        btnCancel = ghostButton("Annuler la demande") {
            Guard.cancelRequest(this)
            refresh()
        }
        btnDisable = primaryButton("Désactiver le verrou") {
            Guard.disableLock(this)
            refresh()
        }
        btnUninstall = ghostButton("Autoriser la désinstallation", BAD) {
            Guard.removeAdmin(this)
            toast("Tu peux désinstaller l'app pendant la fenêtre ouverte")
            refresh()
        }
        listOf(btnRequest, btnCancel, btnDisable, btnUninstall).forEach { header.addView(it) }

        // ----- Outils -----
        btnKw = ghostButton("") { cycleKeywords() }
        header.addView(card("Outils",
            ghostButton("J'ai envie : pause de 10 minutes", ACCENT) { showCraving() },
            ghostButton("Écrire mon texte pour ces moments") { editNote() },
            ghostButton("Voir le journal") { showJournal() },
            btnKw
        ))

        val appsTitle = text("APPLICATIONS BLOQUÉES DE 21H À 6H", 12f, SUB, true)
        appsTitle.letterSpacing = 0.1f
        appsTitle.setPadding(dp(4), dp(26), 0, dp(4))
        header.addView(appsTitle)

        val lv = ListView(this)
        lv.setBackgroundColor(BG)
        lv.divider = null
        lv.dividerHeight = 0
        lv.selector = rounded(Color.TRANSPARENT, 0)
        lv.setPadding(0, 0, 0, dp(24))
        lv.clipToPadding = false
        lv.addHeaderView(header, null, false)
        appAdapter = AppAdapter()
        lv.adapter = appAdapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            val i = pos - lv.headerViewsCount
            if (i < 0 || i >= items.size) return@setOnItemClickListener
            val pkg = items[i].pkg
            if (pkg in selected) {
                if (Guard.isProtected(this)) {
                    toast("Impossible de retirer une app pendant le verrou")
                    return@setOnItemClickListener
                }
                selected.remove(pkg)
            } else {
                selected.add(pkg)
            }
            Guard.setBlocked(this, selected)
            appAdapter.notifyDataSetChanged()
        }
        setContentView(lv)
    }

    override fun onResume() {
        super.onResume()
        Guard.syncAsync(this)
        if (Guard.isLocked(this)) Guard.startWatchdog(this)
        refresh()
    }

    private fun doLock() {
        if (!Guard.accessibilityOn(this)) {
            toast("Fais d'abord l'étape 1")
            return
        }
        val host = dnsInput.text.toString().trim()
        if (host.isEmpty()) {
            toast("Entre un DNS")
            return
        }
        if (!Guard.dnsOk(this, host) && !Guard.hasSecurePermission(this)) {
            toast("Règle d'abord le DNS privé à la main (voir le texte en haut), puis reviens ici")
            return
        }
        if (Guard.trustedNow(this) == null) {
            Guard.syncAsync(this)
            toast("Vérification de l'heure sur internet en cours : réessaie dans quelques secondes (connexion requise)")
            return
        }
        Guard.lock(this, host, daysSpinner.selectedItemPosition + 1)
        Guard.startWatchdog(this)
        toast("Verrouillé")
        refresh()
    }

    private fun cycleKeywords() {
        val cur = Guard.kwMode(this)
        val next = (cur + 1) % 3
        if (next < cur && Guard.isProtected(this)) {
            toast("Impossible de baisser ce réglage pendant le verrou")
            return
        }
        Guard.setKwMode(this, next)
        refresh()
    }

    private fun showJournal() {
        val j = Guard.journal(this)
        AlertDialog.Builder(this)
            .setTitle("Journal (rien n'est envoyé nulle part)")
            .setMessage(if (j.isBlank()) "Vide pour l'instant." else j)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showCraving() {
        val note = Guard.note(this)
        val tv = TextView(this)
        tv.textSize = 16f
        tv.setPadding(dp(20), dp(16), dp(20), dp(8))
        val dlg = AlertDialog.Builder(this)
            .setTitle("Pause de 10 minutes")
            .setView(tv)
            .setPositiveButton("Fermer", null)
            .create()
        val body = if (note.isBlank()) "Écris ton propre texte avec le bouton « Écrire mon texte »." else note
        val timer = object : CountDownTimer(600_000L, 1000L) {
            override fun onTick(ms: Long) {
                val m = ms / 60_000L
                val s = String.format("%02d", (ms / 1000L) % 60L)
                tv.text = "Il reste $m:$s\n\n$body"
            }

            override fun onFinish() {
                tv.text = "Pause terminée. Tu as tenu 10 minutes.\n\n$body"
            }
        }
        dlg.setOnDismissListener { timer.cancel() }
        dlg.show()
        timer.start()
    }

    private fun editNote() {
        val et = EditText(this)
        et.setText(Guard.note(this))
        et.minLines = 4
        et.gravity = Gravity.TOP
        AlertDialog.Builder(this)
            .setTitle("Mon texte pour les moments difficiles")
            .setView(et)
            .setPositiveButton("Enregistrer") { _, _ -> Guard.setNote(this, et.text.toString()) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun show(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun checkRow(state: Int, label: String): View {
        // state : 0 = ok, 1 = info, 2 = problème
        val color = when (state) { 0 -> ACCENT; 1 -> WARN; else -> BAD }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = lp(12)
        val dot = text(if (state == 0) "✓" else if (state == 1) "!" else "✕", 13f, color, true)
        dot.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)))
        dot.background = bg
        dot.layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        val t = text(label, 14f, TXT)
        val tp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tp.leftMargin = dp(12)
        t.layoutParams = tp
        row.addView(dot)
        row.addView(t)
        return row
    }

    private fun refresh() {
        val locked = Guard.isLocked(this)
        val pending = Guard.requestPending(this)
        val open = Guard.windowOpen(this)
        val now = Guard.now(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        checks.removeAllViews()
        checks.addView(checkRow(if (Guard.accessibilityOn(this)) 0 else 2,
            if (Guard.accessibilityOn(this)) "Protection active" else "Protection non activée (étape 1)"))
        checks.addView(checkRow(if (Guard.adminActive(this)) 0 else 2,
            if (Guard.adminActive(this)) "Anti-désinstallation active" else "Anti-désinstallation non activée (étape 2)"))
        val batt = pm.isIgnoringBatteryOptimizations(packageName)
        checks.addView(checkRow(if (batt) 0 else 1,
            if (batt) "Batterie : pas de mise en veille" else "Batterie à régler (étape 3)"))
        checks.addView(checkRow(if (Guard.dnsOk(this)) 0 else 2,
            if (Guard.dnsOk(this)) "DNS privé : ${Guard.dns(this)}" else "DNS privé différent de ${Guard.dns(this)}"))
        val t = Guard.trustedNow(this) != null
        checks.addView(checkRow(if (t) 0 else 1,
            if (t) "Heure vérifiée sur internet" else "Heure non vérifiée (internet requis)"))

        val pillColor: Int
        when {
            !locked -> {
                heroTitle.text = "Non verrouillé"
                heroSub.text = "Termine la mise en place, puis verrouille."
                heroPill.text = "●  INACTIF"; pillColor = WARN
            }
            open -> {
                heroTitle.text = "Fenêtre ouverte"
                heroSub.text = "Se referme dans ${Guard.fmt(Guard.windowEnd(this) - now)}"
                heroPill.text = "●  DÉVERROUILLABLE"; pillColor = WARN
            }
            pending -> {
                heroTitle.text = "Verrouillé"
                heroSub.text = "Déverrouillage possible dans ${Guard.fmt(Guard.readyAt(this) - now)}"
                heroPill.text = "●  DEMANDE EN COURS"; pillColor = WARN
            }
            else -> {
                heroTitle.text = "Verrouillé"
                heroSub.text = "DNS filtrant : ${Guard.dns(this)}"
                heroPill.text = "●  ACTIF"; pillColor = ACCENT
            }
        }
        heroPill.setTextColor(pillColor)
        heroPill.background = rounded(Color.argb(38, Color.red(pillColor), Color.green(pillColor), Color.blue(pillColor)), 20)

        val needDns = !locked && !Guard.dnsOk(this, dnsInput.text.toString().trim()) && !Guard.hasSecurePermission(this)
        dnsHint.text = "Avant de verrouiller, règle le DNS à la main : Réglages › Connexions › " +
            "Plus de paramètres de connexion › DNS privé › Nom d'hôte du fournisseur : ${Guard.dns(this)}"
        show(dnsHint, needDns)

        btnKw.text = "Mots-clés : ${Guard.kwName(Guard.kwMode(this))}  ·  changer"
        show(lockCard, !locked)
        show(btnRequest, locked && !pending && !open)
        show(btnCancel, pending)
        show(btnDisable, open)
        show(btnUninstall, open)
        appAdapter.notifyDataSetChanged()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val on = item.pkg in selected
            val outer = LinearLayout(this@MainActivity)
            outer.setPadding(dp(18), dp(4), dp(18), dp(4))

            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(14), dp(10), dp(10), dp(10))
            row.background = rounded(CARD, 16, if (on) ACCENT else 0)
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            val icon = ImageView(this@MainActivity)
            icon.setImageDrawable(item.icon)
            icon.layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))

            val name = text(item.label, 15f, TXT, true)
            val nameParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            nameParams.leftMargin = dp(14)
            name.layoutParams = nameParams

            val sw = Switch(this@MainActivity)
            sw.isChecked = on
            sw.isClickable = false
            sw.isFocusable = false
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            sw.thumbTintList = ColorStateList(states, intArrayOf(ACCENT, SUB))
            sw.trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#1F6B55"), LINE))

            row.addView(icon)
            row.addView(name)
            row.addView(sw)
            outer.addView(row)
            return outer
        }
    }
}
