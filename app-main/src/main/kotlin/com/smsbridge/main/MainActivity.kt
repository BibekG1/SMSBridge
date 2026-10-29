package com.smsbridge.main

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smsbridge.main.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val messageAdapter = MessageAdapter()
    private val handler = Handler(Looper.getMainLooper())

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        FilterManager.init(this)
        requestNotificationPermission()
        setupUI()
        updateStatus()

        MessageStore.onNewMessage = { msg ->
            handler.post { messageAdapter.addMessage(msg) }
        }

        // Load existing messages into the adapter
        MessageStore.messages.forEach { messageAdapter.addMessage(it) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupUI() {
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = messageAdapter
        }

        binding.switchBridge.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startBridgeService() else stopBridgeService()
        }

        binding.btnNotifSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnSmsSettings.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS), 100)
                }
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }

        binding.switchFilter.isChecked = FilterManager.isFilterEnabled
        binding.switchFilter.setOnCheckedChangeListener { _, checked ->
            FilterManager.isFilterEnabled = checked
            binding.tvFilterHint.text = if (checked)
                "Filtering: only forwarding selected apps"
            else
                "Forwarding: ALL apps (no filter)"
        }

        binding.tvFilterHint.text = if (FilterManager.isFilterEnabled)
            "Filtering: only forwarding selected apps"
        else
            "Forwarding: ALL apps (no filter)"

        binding.btnClearMessages.setOnClickListener {
            MessageStore.clear()
            messageAdapter.clearAll()
        }
    }

    private fun startBridgeService() {
        startForegroundService(Intent(this, BridgeService::class.java))
        updateStatusUI(true)
    }

    private fun stopBridgeService() {
        stopService(Intent(this, BridgeService::class.java))
        updateStatusUI(false)
    }

    private fun updateStatus() {
        val notifEnabled = NotificationListenerService.isEnabled(this)
        binding.switchBridge.isChecked = notifEnabled
        updateStatusUI(notifEnabled)

        val notifStatus = if (notifEnabled) "✓ Granted" else "✗ Not granted"
        binding.tvNotifStatus.text = "Notification Access: $notifStatus"
    }

    private fun updateStatusUI(active: Boolean) {
        binding.tvBridgeStatus.text = if (active) "Bridge: ACTIVE ✓" else "Bridge: INACTIVE"
        binding.tvBridgeStatus.setTextColor(
            if (active) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }
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
