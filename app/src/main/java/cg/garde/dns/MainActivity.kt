package cg.garde.dns

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class AppItem(val pkg: String, val label: String, val icon: Drawable)

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var dnsInput: EditText
    private lateinit var daysSpinner: Spinner
    private lateinit var btnLock: Button
    private lateinit var btnRequest: Button
    private lateinit var btnCancel: Button
    private lateinit var btnDisable: Button
    private lateinit var btnUninstall: Button
    private lateinit var btnKw: Button
    private lateinit var appAdapter: AppAdapter
    private val lockViews = mutableListOf<View>()
    private val items = mutableListOf<AppItem>()
    private var selected = mutableSetOf<String>()

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun makeButton(text: String, onClick: () -> Unit) = Button(this).also {
        it.text = text
        it.setOnClickListener { onClick() }
    }

    private fun makeLabel(text: String) = TextView(this).also {
        it.text = text
        it.textSize = 14f
        it.setPadding(0, dp(12), 0, dp(4))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        // En-tête (boutons et réglages)
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(16), dp(32), dp(16), dp(8))

        status = TextView(this)
        status.textSize = 15f
        header.addView(status)

        header.addView(makeButton("1. Activer la protection (Accessibilité)") {
            toast("Cherche « Garde DNS » dans la liste et active-le")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        header.addView(makeButton("1 bis. Si l'activation est refusée (paramètres restreints)") {
            toast("Dans cet écran : menu ⋮ en haut à droite > « Autoriser les paramètres restreints », puis refais l'étape 1")
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        header.addView(makeButton("2. Activer l'anti-désinstallation") {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, GardeAdmin::class.java))
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Empêche de désinstaller Garde DNS pendant le verrou.")
            startActivity(i)
        })
        header.addView(makeButton("3. Ne pas mettre Garde DNS en veille (batterie)") {
            toast("Choisis « Non restreint » / « Jamais en veille » pour Garde DNS (voir LISEZ-MOI)")
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })

        val dnsLabel = makeLabel("DNS filtrant (nom d'hôte) :")
        dnsInput = EditText(this)
        dnsInput.setText(Guard.dns(this))
        dnsInput.setSingleLine()
        val daysLabel = makeLabel("Délai avant de pouvoir déverrouiller :")
        daysSpinner = Spinner(this)
        daysSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("1 jour (période d'essai)", "2 jours", "3 jours", "4 jours", "5 jours")
        )
        daysSpinner.setSelection((Guard.delayDays(this) - 1).coerceIn(0, 4))
        btnLock = makeButton("4. VERROUILLER") { doLock() }
        lockViews.addAll(listOf(dnsLabel, dnsInput, daysLabel, daysSpinner, btnLock))
        lockViews.forEach { header.addView(it) }

        btnRequest = makeButton("Demander le déverrouillage") {
            if (Guard.requestUnlock(this)) {
                toast("Compte à rebours lancé")
            } else {
                toast("Heure non vérifiée : connecte-toi à internet puis réessaie")
                Guard.syncAsync(this)
            }
            refresh()
        }
        btnCancel = makeButton("Annuler la demande") {
            Guard.cancelRequest(this)
            refresh()
        }
        btnDisable = makeButton("Désactiver le verrou") {
            Guard.disableLock(this)
            refresh()
        }
        btnUninstall = makeButton("Autoriser la désinstallation") {
            Guard.removeAdmin(this)
            toast("Tu peux désinstaller l'app pendant la fenêtre ouverte")
            refresh()
        }
        header.addView(btnRequest)
        header.addView(btnCancel)
        header.addView(btnDisable)
        header.addView(btnUninstall)

        header.addView(makeLabel("Outils :"))
        btnKw = makeButton("") { cycleKeywords() }
        header.addView(btnKw)
        header.addView(makeButton("Voir le journal") { showJournal() })
        header.addView(makeButton("J'ai envie : pause de 10 minutes") { showCraving() })
        header.addView(makeButton("Écrire mon texte pour ces moments") { editNote() })

        header.addView(makeLabel("Coche les applications à bloquer de 21h à 6h :"))

        val lv = ListView(this)
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

    private fun refresh() {
        val locked = Guard.isLocked(this)
        val pending = Guard.requestPending(this)
        val open = Guard.windowOpen(this)
        val now = Guard.now(this)
        val sb = StringBuilder()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        sb.append(if (Guard.accessibilityOn(this)) "✔ Protection active\n" else "✘ Protection non activée (étape 1)\n")
        sb.append(if (Guard.adminActive(this)) "✔ Anti-désinstallation active\n" else "✘ Anti-désinstallation non activée (étape 2)\n")
        sb.append(if (pm.isIgnoringBatteryOptimizations(packageName)) "✔ Batterie : pas de mise en veille\n" else "ℹ Batterie à régler (étape 3)\n")
        sb.append(if (Guard.dnsOk(this)) "✔ DNS privé réglé sur ${Guard.dns(this)}\n" else "✘ DNS privé différent de ${Guard.dns(this)}\n")
        sb.append(if (Guard.trustedNow(this) != null) "✔ Heure vérifiée sur internet\n" else "ℹ Heure non vérifiée (internet requis)\n")
        sb.append("\n")
        when {
            !locked -> sb.append("Verrou : INACTIF")
            open -> sb.append("Verrou : fenêtre ouverte, se referme dans ${Guard.fmt(Guard.windowEnd(this) - now)}")
            pending -> sb.append("Verrou : ACTIF. Déverrouillage possible dans ${Guard.fmt(Guard.readyAt(this) - now)}")
            else -> sb.append("Verrou : ACTIF (DNS ${Guard.dns(this)})")
        }
        if (!locked && !Guard.dnsOk(this, dnsInput.text.toString().trim()) && !Guard.hasSecurePermission(this)) {
            sb.append(
                "\n\nAvant de verrouiller, règle le DNS à la main : Réglages > Connexions > " +
                    "Plus de paramètres de connexion > DNS privé > Nom d'hôte du fournisseur : ${Guard.dns(this)}"
            )
        }
        status.text = sb.toString()

        btnKw.text = "Détection de mots-clés : ${Guard.kwName(Guard.kwMode(this))} (appuie pour changer)"
        lockViews.forEach { show(it, !locked) }
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
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), dp(8), dp(16), dp(8))

            val icon = ImageView(this@MainActivity)
            icon.setImageDrawable(item.icon)
            icon.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))

            val name = TextView(this@MainActivity)
            name.text = item.label
            name.textSize = 16f
            val nameParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            nameParams.leftMargin = dp(12)
            name.layoutParams = nameParams

            val cb = CheckBox(this@MainActivity)
            cb.isChecked = item.pkg in selected
            cb.isClickable = false
            cb.isFocusable = false

            row.addView(icon)
            row.addView(name)
            row.addView(cb)
            return row
        }
    }
}
