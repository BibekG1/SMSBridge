package com.smsbridge.main

import android.Manifest
import android.app.Dialog
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smsbridge.main.databinding.ActivityMainBinding
import com.smsbridge.main.databinding.DialogSelectAppsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    var isSelected: Boolean
)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val messageAdapter = MessageAdapter()
    private val handler = Handler(Looper.getMainLooper())

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.RECEIVE_SMS] ?: false
        if (!smsGranted) {
            Toast.makeText(this, "SMS permission needed to intercept incoming SMS", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        FilterManager.init(this)
        checkAndRequestPermissions()
        setupUI()
        updateStatus()
        updateFilterUI()

        MessageStore.onNewMessage = { msg ->
            handler.post { messageAdapter.addMessage(msg) }
        }

        // Load existing messages into the adapter
        MessageStore.messages.forEach { messageAdapter.addMessage(it) }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionsLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun setupUI() {
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }

        binding.switchBridge.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                if (isChecked) startBridgeService() else stopBridgeService()
            }
        }

        binding.btnNotifSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Unable to open Notification Listener settings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchFilter.isChecked = FilterManager.isFilterEnabled
        binding.switchFilter.setOnCheckedChangeListener { buttonView, checked ->
            if (buttonView.isPressed) {
                FilterManager.isFilterEnabled = checked
                updateFilterUI()
            }
        }

        binding.btnSelectApps.setOnClickListener {
            showAppPickerDialog()
        }

        binding.btnClearMessages.setOnClickListener {
            MessageStore.clear()
            messageAdapter.clearAll()
        }
    }

    private fun updateFilterUI() {
        val count = FilterManager.filteredApps.size
        binding.btnSelectApps.text = "Select Target Apps ($count selected)"

        if (FilterManager.isFilterEnabled) {
            binding.tvFilterHint.text = "Filtering: ACTIVE — forwarding from $count selected app(s) only"
            binding.tvFilterHint.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
        } else {
            binding.tvFilterHint.text = "Forwarding: ALL apps (no filter applied)"
            binding.tvFilterHint.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }

        // Summary of selected apps
        if (count == 0) {
            binding.tvSelectedAppsSummary.text = "Selected apps: None"
        } else {
            val pm = packageManager
            val names = FilterManager.filteredApps.mapNotNull { pkg ->
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai).toString()
                } catch (e: Exception) {
                    pkg
                }
            }.sorted()
            binding.tvSelectedAppsSummary.text = "Selected (${names.size}): " + names.joinToString(", ")
        }
    }

    private fun showAppPickerDialog() {
        val dialogBinding = DialogSelectAppsBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialogBinding.pbLoadingApps.visibility = View.VISIBLE
        dialogBinding.rvAppsList.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val selectedPkgs = FilterManager.filteredApps.toMutableSet()
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val seen = mutableSetOf<String>()

            val appList = resolveInfos.mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) return@mapNotNull null
                val name = ri.loadLabel(pm).toString()
                val icon = ri.loadIcon(pm)
                AppItem(name, pkg, icon, selectedPkgs.contains(pkg))
            }.sortedBy { it.name.lowercase() }

            withContext(Dispatchers.Main) {
                dialogBinding.pbLoadingApps.visibility = View.GONE
                dialogBinding.rvAppsList.visibility = View.VISIBLE

                val adapter = AppSelectionAdapter(appList) { count ->
                    dialogBinding.tvDialogSelectedCount.text = "$count apps selected"
                }

                dialogBinding.rvAppsList.apply {
                    layoutManager = LinearLayoutManager(this@MainActivity)
                    this.adapter = adapter
                }

                dialogBinding.tvDialogSelectedCount.text = "${adapter.getSelectedCount()} apps selected"

                dialogBinding.etSearchApps.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        adapter.filter(s?.toString().orEmpty())
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                dialogBinding.btnSelectAll.setOnClickListener {
                    adapter.selectAll()
                }

                dialogBinding.btnDeselectAll.setOnClickListener {
                    adapter.deselectAll()
                }

                dialogBinding.btnCancelDialog.setOnClickListener {
                    dialog.dismiss()
                }

                dialogBinding.btnSaveDialog.setOnClickListener {
                    FilterManager.filteredApps = adapter.getSelectedPackageNames()
                    updateFilterUI()
                    dialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        "Filter updated (${FilterManager.filteredApps.size} apps selected)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        dialog.show()
    }

    private fun startBridgeService() {
        try {
            startForegroundService(Intent(this, BridgeService::class.java))
            updateStatusUI(true)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start Bridge: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopBridgeService() {
        try {
            stopService(Intent(this, BridgeService::class.java))
            updateStatusUI(false)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to stop Bridge: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val notifEnabled = BridgeNotificationListener.isEnabled(this)
        binding.switchBridge.isChecked = notifEnabled
        updateStatusUI(notifEnabled)

        val notifStatus = if (notifEnabled) "✓ Granted" else "✗ Not granted"
        binding.tvNotifStatus.text = "Notification Access: $notifStatus"
    }

    private fun updateStatusUI(active: Boolean) {
        binding.tvBridgeStatus.text = if (active) "Bridge: ACTIVE ✓" else "Bridge: INACTIVE"
        binding.tvBridgeStatus.setTextColor(
            if (active) ContextCompat.getColor(this, android.R.color.holo_green_dark)
            else ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
}

class AppSelectionAdapter(
    private val masterList: List<AppItem>,
    private val onSelectionCountChanged: (Int) -> Unit
) : RecyclerView.Adapter<AppSelectionAdapter.VH>() {

    private val displayedList = ArrayList(masterList)

    fun filter(query: String) {
        val q = query.trim().lowercase()
        displayedList.clear()
        if (q.isEmpty()) {
            displayedList.addAll(masterList)
        } else {
            displayedList.addAll(masterList.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        notifyDataSetChanged()
    }

    fun selectAll() {
        displayedList.forEach { it.isSelected = true }
        notifyDataSetChanged()
        onSelectionCountChanged(getSelectedCount())
    }

    fun deselectAll() {
        displayedList.forEach { it.isSelected = false }
        notifyDataSetChanged()
        onSelectionCountChanged(getSelectedCount())
    }

    fun getSelectedCount(): Int = masterList.count { it.isSelected }

    fun getSelectedPackageNames(): Set<String> = masterList.filter { it.isSelected }.map { it.packageName }.toSet()

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val tvPackage: TextView = view.findViewById(R.id.tvAppPackage)
        val cbSelected: CheckBox = view.findViewById(R.id.cbAppSelected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_select, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = displayedList[position]
        holder.ivIcon.setImageDrawable(item.icon)
        holder.tvName.text = item.name
        holder.tvPackage.text = item.packageName
        holder.cbSelected.isChecked = item.isSelected

        holder.itemView.setOnClickListener {
            item.isSelected = !item.isSelected
            holder.cbSelected.isChecked = item.isSelected
            onSelectionCountChanged(getSelectedCount())
        }
    }

    override fun getItemCount(): Int = displayedList.size
}

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {
    private val items = ArrayList<BridgeMessage>()

    fun addMessage(msg: BridgeMessage) {
        if (items.size >= 3) items.removeAt(0)
        items.add(msg)
        notifyDataSetChanged()
    }

    fun clearAll() {
        items.clear()
        notifyDataSetChanged()
    }

    inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvBody: TextView = view.findViewById(R.id.tvBody)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvSource: TextView = view.findViewById(R.id.tvSource)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        holder.tvSender.text = msg.sender
        holder.tvBody.text = msg.body
        holder.tvSource.text = "[${msg.sourceType}]"
        holder.tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(msg.timestamp))
    }

    override fun getItemCount() = items.size
}
