package com.smsbridge.work

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.google.android.material.button.MaterialButton
import com.smsbridge.work.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = WorkMessageAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var isUpdatingUI = false

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        binding.switchReceiver.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            getSharedPreferences("smsbridge_work_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("receiver_enabled", isChecked)
                .apply()

            if (isChecked) {
                ReceiverService.start(this)
                updateStatusUI(true)
            } else {
                ReceiverService.stop(this)
                updateStatusUI(false)
            }
        }

        binding.btnClear.setOnClickListener {
            MessageStore.clear()
            adapter.clearAll()
        }

        binding.btnBatterySettings.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        MessageStore.onNewMessage = { msg ->
            handler.post { adapter.addMessage(msg) }
        }

        // Reload existing messages on open
        adapter.setMessages(MessageStore.messages)

        // Restore receiver toggle & service status
        syncReceiverState()
        updateBatteryStatus()
    }

    private fun syncReceiverState() {
        val prefs = getSharedPreferences("smsbridge_work_prefs", Context.MODE_PRIVATE)
        val userWantsEnabled = prefs.getBoolean("receiver_enabled", false)
        val serviceRunning = ReceiverService.isRunning(this)

        val active = if (userWantsEnabled && !serviceRunning) {
            ReceiverService.start(this)
            true
        } else {
            serviceRunning || userWantsEnabled
        }

        isUpdatingUI = true
        binding.switchReceiver.isChecked = active
        updateStatusUI(active)
        isUpdatingUI = false
    }

    private fun updateStatusUI(active: Boolean) {
        binding.tvStatus.text = if (active) "Receiver: ACTIVE \u2713" else "Receiver: INACTIVE"
        binding.tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (active) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
    }

    private fun updateBatteryStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnored = pm?.isIgnoringBatteryOptimizations(packageName) == true
        if (isIgnored) {
            binding.tvBatteryStatus.text = "Battery Optimization: Whitelisted (Unrestricted) ✓"
            binding.tvBatteryStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.btnBatterySettings.text = "Battery Optimization: Whitelisted ✓"
        } else {
            binding.tvBatteryStatus.text = "Battery Optimization: Optimized (May be restricted by OS)"
            binding.tvBatteryStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.btnBatterySettings.text = "Whitelist in Battery Settings"
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Unable to open battery settings: ${e2.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.setMessages(MessageStore.messages)
        syncReceiverState()
        updateBatteryStatus()
    }
}

class WorkMessageAdapter : RecyclerView.Adapter<WorkMessageAdapter.VH>() {
    private val items = ArrayList<ReceivedMessage>()

    fun addMessage(msg: ReceivedMessage) {
        if (items.size >= 3) items.removeAt(0)
        items.add(msg)
        notifyDataSetChanged()
    }

    fun setMessages(msgs: List<ReceivedMessage>) {
        items.clear()
        items.addAll(msgs)
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
        val tvOtp: TextView = view.findViewById(R.id.tvOtp)
        val btnCopyOtp: MaterialButton = view.findViewById(R.id.btnCopyOtp)
        val btnCopySms: MaterialButton = view.findViewById(R.id.btnCopySms)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]

        holder.tvSender.text = msg.sender
        holder.tvBody.text = msg.body
        holder.tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(msg.timestamp))

        // OTP section
        if (msg.otp != null) {
            holder.tvOtp.visibility = View.VISIBLE
            holder.tvOtp.text = "OTP: ${msg.otp}"
            holder.btnCopyOtp.visibility = View.VISIBLE
            holder.btnCopyOtp.text = "Copy OTP: ${msg.otp}"
            holder.btnCopyOtp.setOnClickListener {
                copyToClipboard(holder.view.context, msg.otp, "OTP")
            }
        } else {
            holder.tvOtp.visibility = View.GONE
            holder.btnCopyOtp.visibility = View.GONE
        }

        // Copy Full SMS
        holder.btnCopySms.setOnClickListener {
            copyToClipboard(holder.view.context, msg.body, "Full SMS")
        }
    }

    override fun getItemCount() = items.size

    private fun copyToClipboard(context: Context, text: String, label: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(context, "$label copied!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not copy: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
