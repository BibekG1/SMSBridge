package com.smsbridge.work

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
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
                binding.tvStatus.text = "Receiver: ACTIVE \u2713"
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

        // Reload existing messages on open
        adapter.setMessages(MessageStore.messages)
    }

    override fun onResume() {
        super.onResume()
        adapter.setMessages(MessageStore.messages)
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
        // Full body — no truncation, shown exactly like an SMS app
        val tvBody: TextView = view.findViewById(R.id.tvBody)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        // OTP chip — visible only when OTP detected
        val tvOtp: TextView = view.findViewById(R.id.tvOtp)
        // Copy OTP button — visible only when OTP detected
        val btnCopyOtp: MaterialButton = view.findViewById(R.id.btnCopyOtp)
        // Copy Full SMS — always visible so user can paste entire raw message into any app
        val btnCopySms: MaterialButton = view.findViewById(R.id.btnCopySms)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]

        // Sender shown exactly as received: VM-HDFC, +919876543210, AM-PAYTM, etc.
        holder.tvSender.text = msg.sender

        // Full raw body — including any trailing app-hash lines (e.g., pgft1w9U6nf)
        // No maxLines limit here; the layout also has no maxLines
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

        // Copy Full SMS — always available; pastes the entire raw SMS body
        holder.btnCopySms.setOnClickListener {
            copyToClipboard(holder.view.context, msg.body, "Full SMS")
        }
    }

    override fun getItemCount() = items.size

    private fun copyToClipboard(context: Context, text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied!", Toast.LENGTH_SHORT).show()
    }
}
