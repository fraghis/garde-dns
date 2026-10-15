package cg.garde.dns

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
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

        // Liste des applications installées
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { ri -> AppItem(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), ri.loadIcon(pm)) }
            .filter { it.pkg != packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
            .forEach { items.add(it) }
        selected = Guard.blocked(this).toMutableSet()

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
        header.addView(makeButton("2. Activer l'anti-désinstallation") {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, GardeAdmin::class.java))
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Empêche de désinstaller Garde DNS pendant le verrou.")
            startActivity(i)
        })

        val dnsLabel = makeLabel("DNS filtrant (nom d'hôte) :")
        dnsInput = EditText(this)
        dnsInput.setText(Guard.dns(this))
        dnsInput.setSingleLine()
        val daysLabel = makeLabel("Délai avant de pouvoir déverrouiller :")
        daysSpinner = Spinner(this)
        daysSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("2 jours", "3 jours", "4 jours", "5 jours")
        )
        daysSpinner.setSelection((Guard.delayDays(this) - 2).coerceIn(0, 3))
        btnLock = makeButton("3. VERROUILLER") { doLock() }
        lockViews.addAll(listOf(dnsLabel, dnsInput, daysLabel, daysSpinner, btnLock))
        lockViews.forEach { header.addView(it) }

        btnRequest = makeButton("Demander le déverrouillage") {
            Guard.requestUnlock(this)
            toast("Compte à rebours lancé")
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
        val applied = Guard.lock(this, host, daysSpinner.selectedItemPosition + 2)
        toast(if (applied) "Verrouillé, DNS appliqué" else "Verrouillé (règle le DNS à la main, voir en haut)")
        refresh()
    }

    private fun show(v: View, visible: Boolean) {
        v.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        val locked = Guard.isLocked(this)
        val pending = Guard.requestPending(this)
        val open = Guard.windowOpen(this)
        val now = System.currentTimeMillis()
        val sb = StringBuilder()

        sb.append(if (Guard.accessibilityOn(this)) "✔ Protection active\n" else "✘ Protection non activée (étape 1)\n")
        sb.append(if (Guard.adminActive(this)) "✔ Anti-désinstallation active\n" else "✘ Anti-désinstallation non activée (étape 2)\n")
        sb.append(if (Guard.hasSecurePermission(this)) "✔ DNS appliqué automatiquement\n" else "ℹ DNS à régler à la main\n")
        sb.append("\n")
        when {
            !locked -> sb.append("Verrou : INACTIF")
            open -> sb.append("Verrou : fenêtre ouverte, se referme dans ${Guard.fmt(Guard.windowEnd(this) - now)}")
            pending -> sb.append("Verrou : ACTIF. Déverrouillage possible dans ${Guard.fmt(Guard.readyAt(this) - now)}")
            else -> sb.append("Verrou : ACTIF (DNS ${Guard.dns(this)})")
        }
        if (!locked && !Guard.hasSecurePermission(this)) {
            sb.append("\n\nAvant de verrouiller, règle le DNS à la main : Réglages > Réseau > DNS privé > Nom d'hôte du fournisseur : ${Guard.dns(this)}")
        }
        status.text = sb.toString()

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
