package com.smsbridge.work

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.smsbridge.work.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val adapter = WorkMessageAdapter()
    private val handler = Handler(Looper.getMainLooper())

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
            if (isChecked) {
                ReceiverService.start(this)
                binding.tvStatus.text = "Receiver: ACTIVE ✓"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            } else {
                ReceiverService.stop(this)
                binding.tvStatus.text = "Receiver: INACTIVE"
                binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            }
        }

        binding.btnClear.setOnClickListener {
            MessageStore.clear()
            adapter.clearAll()
        }

        MessageStore.onNewMessage = { msg ->
            handler.post { adapter.addMessage(msg) }
        }

        // Reload existing messages
        adapter.setMessages(MessageStore.messages)
    }

    override fun onResume() {
        super.onResume()
        adapter.setMessages(MessageStore.messages)
    }
}

class WorkMessageAdapter : RecyclerView.Adapter<WorkMessageAdapter.VH>() {
    private val items = ArrayDeque<ReceivedMessage>()

    fun addMessage(msg: ReceivedMessage) {
        if (items.size >= 3) items.removeFirst()
        items.addLast(msg)
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
        val divider: View = view.findViewById(R.id.divider)
        val layoutButtons: View = view.findViewById(R.id.layoutButtons)
        val btnCopyOtp: Button = view.findViewById(R.id.btnCopyOtp)
        val btnCopyFull: Button = view.findViewById(R.id.btnCopyFull)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]

        // Sender: raw as received — "VM-HDFC", "+919876543210", "AM-PAYTM", etc.
        holder.tvSender.text = msg.sender

        // Full body — NO truncation, entire text including trailing app hashes
        holder.tvBody.text = msg.body
        holder.tvBody.maxLines = Int.MAX_VALUE
        holder.tvBody.ellipsize = null

        // Timestamp
        holder.tvTime.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(msg.timestamp))

        // OTP section
        if (msg.otp != null) {
            holder.divider.visibility = View.VISIBLE
            holder.tvOtp.visibility = View.VISIBLE
            holder.tvOtp.text = "OTP: ${msg.otp}"
            holder.layoutButtons.visibility = View.VISIBLE
            holder.btnCopyOtp.visibility = View.VISIBLE
            holder.btnCopyOtp.text = "Copy OTP: ${msg.otp}"
            holder.btnCopyOtp.setOnClickListener {
                copyToClipboard(holder.view.context, msg.otp, "OTP copied: ${msg.otp}")
            }
        } else {
            holder.divider.visibility = View.GONE
            holder.tvOtp.visibility = View.GONE
            holder.layoutButtons.visibility = View.VISIBLE   // still show Copy Full SMS
            holder.btnCopyOtp.visibility = View.GONE
        }

        // Copy Full SMS — always visible
        holder.btnCopyFull.setOnClickListener {
            copyToClipboard(holder.view.context, msg.body, "Full SMS copied")
        }
    }

    override fun getItemCount() = items.size

    private fun copyToClipboard(context: android.content.Context, text: String, toastMsg: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("SMS", text))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }
}
