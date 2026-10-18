package com.example.gamehub

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : Activity() {
    private val sr = 16000
    private var recorded = ShortArray(0)
    private val h = Handler(Looper.getMainLooper())
    private lateinit var monitor: TextView
    private lateinit var status: TextView
    private lateinit var content: FrameLayout
    private val tick = object : Runnable {
        override fun run() { monitor.text = stats(); h.postDelayed(this, 1000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#101418")) }
        val tabs = LinearLayout(this)
        content = FrameLayout(this)
        val views = listOf("Monitor" to monitorView(), "Voice" to voiceView(), "Games" to gamesView())
        views.forEach { (name, v) ->
            tabs.addView(btn(name) { content.removeAllViews(); content.addView(v) },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(tabs); root.addView(content)
        content.addView(views[0].second)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
    private fun tv() = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(32, 24, 32, 24) }

    private fun monitorView(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        monitor = tv()
        l.addView(monitor)
        l.addView(btn("BOOST (background apps band karo)") {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            packageManager.getInstalledApplications(0).forEach {
                if (it.packageName != packageName) am.killBackgroundProcesses(it.packageName)
            }
            Toast.makeText(this, "Boost done", Toast.LENGTH_SHORT).show()
        })
        return l
    }

    private fun stats(): String {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        val total = mi.totalMem / 1048576; val free = mi.availMem / 1048576
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val cores = (0 until Runtime.getRuntime().availableProcessors()).joinToString("\n") { i ->
            val f = try { File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000 } catch (e: Exception) { -1 }
            "  Core $i: " + if (f > 0) "$f MHz" else "N/A"
        }
        return "RAM: ${total - free} / $total MB (${(total - free) * 100 / total}%)\n\nCPU:\n$cores\n\nBattery: $level%  |  Temp: $temp C"
    }

    private val voices = listOf("Chipmunk", "Deep", "Robot", "Echo", "Alien", "Monster")

    private fun voiceView(): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = tv().apply { text = "Pehle Record dabao (5 sec)" }
        l.addView(status)
        l.addView(btn("Record 5 sec") { record() })
        voices.forEachIndexed { i, n -> l.addView(btn("Play: $n") { play(i) }) }
        return l
    }

    private fun record() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Mic permission do"; return
        }
        status.text = "Recording..."
        thread {
            val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
            val data = ShortArray(sr * 5); var n = 0
            rec.startRecording()
            while (n < data.size) {
                val r = rec.read(data, n, minOf(1024, data.size - n)); if (r <= 0) break; n += r
            }
            rec.stop(); rec.release()
            recorded = data.copyOf(n)
            runOnUiThread { status.text = "Recorded OK, ab koi voice dabao" }
        }
    }

    private fun play(i: Int) {
        if (recorded.isEmpty()) { status.text = "Pehle record karo"; return }
        val d = recorded.copyOf(); var rate = sr
        fun ring(hz: Double) { for (k in d.indices) d[k] = (d[k] * sin(2 * PI * hz * k / sr)).toInt().toShort() }
        when (i) {
            0 -> rate = sr * 3 / 2
            1 -> rate = sr * 2 / 3
            2 -> ring(50.0)
            3 -> { val dl = sr / 4; for (k in dl until d.size) d[k] = (d[k] + d[k - dl] * 0.5).toInt().coerceIn(-32768, 32767).toShort() }
            4 -> { rate = sr * 5 / 4; ring(30.0) }
            5 -> { rate = sr * 11 / 20; ring(20.0) }
        }
        thread {
            val t = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, d.size * 2, AudioTrack.MODE_STATIC)
            t.write(d, 0, d.size); t.play()
        }
    }

    private fun gamesView(): View {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0)
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val lv = ListView(this)
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            apps.map { it.loadLabel(packageManager).toString() })
        lv.setOnItemClickListener { _, _, pos, _ ->
            packageManager.getLaunchIntentForPackage(apps[pos].activityInfo.packageName)?.let { startActivity(it) }
        }
        return lv
    }
}
