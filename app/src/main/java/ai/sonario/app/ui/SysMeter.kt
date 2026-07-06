package ai.sonario.app.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.system.Os
import android.system.OsConstants
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.RandomAccessFile

/**
 * A small live system meter, pinned bottom-left, echoing the desktop app's
 * resource readout. Android only lets an app read a few things without root:
 *
 *   RAM   - the app's own used memory, plus how much system RAM is free.
 *   CPU   - the app process's CPU load, estimated from /proc/self/stat deltas.
 *
 * GPU utilization and VRAM are NOT exposed to apps on Android (no public API for
 * Adreno), so they are deliberately omitted rather than shown as fake numbers.
 * If inference pegs CPU near 100%, the model is running on CPU; if CPU stays
 * modest while tokens stream, GPU offload is doing the work.
 */
@Composable
fun SysMeter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ramUsedMb by remember { mutableStateOf(0L) }
    var ramFreeMb by remember { mutableStateOf(0L) }
    var cpuPct by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val cpuReader = CpuReader()
        while (true) {
            ramUsedMb = appUsedMemoryMb()
            ramFreeMb = systemFreeMemoryMb(context)
            cpuPct = cpuReader.sample()
            delay(1000)
        }
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x66101A24))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Stat("CPU", "$cpuPct%", cpuColor(cpuPct))
        Spacer(Modifier.width(12.dp))
        Stat("RAM", "${ramUsedMb}MB", SonarioColors.Teal)
        Spacer(Modifier.width(12.dp))
        Stat("FREE", "${ramFreeMb}MB", SonarioColors.InkSoft)
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = SonarioColors.Muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun cpuColor(pct: Int): Color = when {
    pct >= 85 -> Color(0xFFE0765C)   // hot: near-certain CPU inference
    pct >= 50 -> Color(0xFFE0B85C)
    else -> SonarioColors.Green
}

private fun appUsedMemoryMb(): Long {
    val rt = Runtime.getRuntime()
    val used = rt.totalMemory() - rt.freeMemory()
    val native = Debug.getNativeHeapAllocatedSize()
    return (used + native) / (1024 * 1024)
}

private fun systemFreeMemoryMb(context: Context): Long {
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    } catch (_: Exception) { 0L }
}

/**
 * Estimates this process's CPU load by reading utime+stime from /proc/self/stat
 * and dividing the delta by wall-clock time across all cores. Returns 0-100 as a
 * percentage of one core's worth of time normalized to the core count, clamped.
 */
private class CpuReader {
    private var lastProcTicks = 0L
    private var lastTimeMs = 0L
    private val clkTck = try { Os.sysconf(OsConstants._SC_CLK_TCK) } catch (_: Exception) { 100L }
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun sample(): Int {
        return try {
            val ticks = readProcTicks()
            val now = System.currentTimeMillis()
            if (lastTimeMs == 0L) {
                lastProcTicks = ticks; lastTimeMs = now
                return 0
            }
            val dTicks = ticks - lastProcTicks
            val dMs = (now - lastTimeMs).coerceAtLeast(1)
            lastProcTicks = ticks; lastTimeMs = now
            // ticks -> ms:  dTicks / clkTck * 1000
            val cpuMs = dTicks.toDouble() / clkTck * 1000.0
            // percent of total available CPU (all cores)
            val pct = (cpuMs / (dMs.toDouble() * cores)) * 100.0
            pct.toInt().coerceIn(0, 100)
        } catch (_: Exception) { 0 }
    }

    private fun readProcTicks(): Long {
        RandomAccessFile("/proc/self/stat", "r").use { raf ->
            val line = raf.readLine() ?: return 0L
            // fields 14 (utime) and 15 (stime), but comm (field 2) may contain
            // spaces/parens; split after the closing paren to be safe.
            val after = line.substringAfterLast(") ")
            val parts = after.split(" ")
            // after the ") ", index 0 = state (field 3). utime = field 14 -> index 11
            val utime = parts.getOrNull(11)?.toLongOrNull() ?: 0L
            val stime = parts.getOrNull(12)?.toLongOrNull() ?: 0L
            return utime + stime
        }
    }
}
