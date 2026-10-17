package com.kazaf.mirrorbackup.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kazaf.mirrorbackup.R
import com.kazaf.mirrorbackup.core.CpuDetector
import com.kazaf.mirrorbackup.core.UnpackEngine
import com.kazaf.mirrorbackup.core.fmtSize
import com.kazaf.mirrorbackup.ui.components.CircularProgressRing
import com.kazaf.mirrorbackup.ui.components.FormatChip
import com.kazaf.mirrorbackup.ui.components.LiquidGlassCard
import com.kazaf.mirrorbackup.ui.components.MiuiButton
import com.kazaf.mirrorbackup.ui.components.SectionTitle
import com.kazaf.mirrorbackup.ui.components.TerminalLog
import com.kazaf.mirrorbackup.ui.icons.IconBox
import com.kazaf.mirrorbackup.ui.icons.IconCpu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UnpackViewModel(app: Application) : AndroidViewModel(app) {
    val engine = UnpackEngine(app)
    var threads by mutableStateOf(4)
        private set
    var pickedFile by mutableStateOf<File?>(null)
        private set
    var pickedName by mutableStateOf("")
        private set

    fun chooseThreads(n: Int) {
        threads = n
    }

    fun pick(uri: Uri) {
        viewModelScope.launch {
            val f = withContext(Dispatchers.IO) {
                try {
                    val name = queryName(uri) ?: "input.bin"
                    val dir = File(getApplication<Application>().cacheDir, "unpack")
                    dir.mkdirs()
                    val target = File(dir, name)
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> input.copyTo(out, 512 * 1024) }
                    }
                    target
                } catch (e: Exception) {
                    null
                }
            }
            if (f != null) {
                pickedFile = f
                pickedName = f.name
                engine.log.clear()
                engine.log.info("已选择文件: ${f.name} (${fmtSize(f.length())})")
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        val ctx = getApplication<Application>()
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun start() {
        val f = pickedFile ?: return
        if (engine.state.value.running) return
        viewModelScope.launch { engine.unpack(f, threads.coerceIn(1, 16)) }
    }

    fun stop() {
        engine.cancelled = true
    }
}

/** 解包工具箱页面（ZIP / payload.bin，支持 1 / 2 / 5 / 满核 并行解包） */
@Composable
fun UnpackScreen(vm: UnpackViewModel) {
    val ctx = LocalContext.current
    val state by vm.engine.state.collectAsState()
    val lines by vm.engine.log.lines.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pick(uri)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // 标题卡
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBox(28.dp, MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.unpack_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.unpack_desc),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // 选择文件
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.unpack_pick))
            Text(
                if (vm.pickedName.isEmpty()) stringResource(R.string.unpack_no_file) else vm.pickedName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            MiuiButton(
                text = stringResource(R.string.unpack_pick),
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.unpack_output) + ": /storage/emulated/0/解包工具箱/",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))

        // 线程
        LiquidGlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.unpack_threads))
            Text(
                "⚡ 检测到 ${CpuDetector.cores} 核 → 满核 ${CpuDetector.cores} 线程",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatChip(
                    title = "1",
                    desc = "1 线程",
                    selected = vm.threads == 1,
                    onClick = { vm.chooseThreads(1) },
                    modifier = Modifier.weight(1f)
                )
                FormatChip(
                    title = "2",
                    desc = "2 线程",
                    selected = vm.threads == 2,
                    onClick = { vm.chooseThreads(2) },
                    modifier = Modifier.weight(1f)
                )
                FormatChip(
                    title = "5",
                    desc = "5 线程",
                    selected = vm.threads == 5,
                    onClick = { vm.chooseThreads(5) },
                    modifier = Modifier.weight(1f)
                )
                FormatChip(
                    title = "满核",
                    desc = "${CpuDetector.cores} 线程",
                    selected = vm.threads == CpuDetector.cores,
                    onClick = { vm.chooseThreads(CpuDetector.cores) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        // 进度
        if (state.running || state.total > 0) {
            LiquidGlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val pct = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    CircularProgressRing(pct = pct, size = 92.dp, stroke = 10.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(state.message, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${state.done} / ${state.total}", fontSize = 12.sp)
                        if (state.totalBytes > 0) {
                            Text(
                                fmtSize(state.doneBytes) + " / " + fmtSize(state.totalBytes),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // 操作按钮
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.running) {
                MiuiButton(
                    text = stringResource(R.string.unpack_stop),
                    onClick = { vm.stop() },
                    danger = true,
                    modifier = Modifier.weight(1f)
                )
            } else {
                MiuiButton(
                    text = stringResource(R.string.unpack_start),
                    onClick = { vm.start() },
                    enabled = vm.pickedFile != null,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        SectionTitle(stringResource(R.string.unpack_log))
        TerminalLog(lines, Modifier.fillMaxWidth(), height = 280.dp)
        Spacer(Modifier.height(96.dp))
    }
}
