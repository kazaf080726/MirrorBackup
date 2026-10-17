package com.kazaf.mirrorbackup.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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

    var scanning by mutableStateOf(false)

    var scanResults by mutableStateOf<List<File>>(emptyList())

    /** 扫描 Download 目录中的 zip / payload.bin（深度 6，供无 SAF 快速选择） */
    fun scanDownloads() {
        if (scanning) return
        scanning = true
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                val root = File("/storage/emulated/0/Download")
                val out = mutableListOf<File>()
                fun walk(dir: File, depth: Int) {
                    if (depth > 6 || out.size >= 40) return
                    val kids = dir.listFiles() ?: return
                    for (k in kids) {
                        if (k.isDirectory) walk(k, depth + 1)
                        else {
                            val n = k.name.lowercase()
                            if ((n.endsWith(".zip") || n.contains("payload") || n.endsWith(".bin"))) {
                                out.add(k)
                            }
                        }
                    }
                }
                walk(root, 0)
                out.sortedByDescending { it.length() }
            }
            scanResults = list
            scanning = false
            engine.log.info("扫描 Download: 找到 ${list.size} 个候选包")
        }
    }

    /** 一键安装并启动内置的完整解包器（原封不动嵌入的参考 APP，root 静默安装） */
    fun launchEmbedded(ctx: android.content.Context) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val apk = File(ctx.cacheDir, "payload_dumper.apk")
                    ctx.assets.open("payload_dumper.apk").use { input ->
                        apk.outputStream().use { out -> input.copyTo(out, 1024 * 1024) }
                    }
                    val install = com.kazaf.mirrorbackup.core.SuManager
                        .exec("pm install -r -d ${com.kazaf.mirrorbackup.core.SuManager.quote(apk.absolutePath)}", 120_000)
                    val okInstall = install.stdout.contains("Success") || install.exitCode == 0
                    Thread.sleep(1500)
                    val launch = com.kazaf.mirrorbackup.core.SuManager
                        .exec("am start -n com.rhythmcache.payloaddumper/.MainActivity", 20_000)
                    engine.log.ok("✔ 内置解包器安装: ${if (okInstall) "成功" else install.stderr.take(120)}")
                    engine.log.info("启动: ${launch.stdout.take(120)}")
                    okInstall
                } catch (e: Exception) {
                    engine.log.error("❌ 安装失败: ${e.message}")
                    false
                }
            }
            if (!ok) engine.log.warn("⚠ 安装未成功，请在文件管理器手动安装 cache/payload_dumper.apk")
        }
    }
    fun probe() { viewModelScope.launch { engine.jniProbe() } }

    var showPartPicker by mutableStateOf(false)
        private set
    var selectedParts by mutableStateOf<Set<String>>(emptySet())
        private set

    fun openPartPicker() {
        if (engine.jniPartitions.isEmpty()) {
            viewModelScope.launch { engine.jniProbe() }
        }
        showPartPicker = true
    }

    fun togglePart(name: String, on: Boolean) {
        selectedParts = if (on) selectedParts + name else selectedParts - name
    }

    fun closePartPicker() { showPartPicker = false }

    fun startSelected() {
        val f = pickedFile ?: return
        val sel = selectedParts.toList()
        if (sel.isEmpty()) return
        showPartPicker = false
        if (engine.state.value.running) return
        viewModelScope.launch { engine.unpack(f, threads.coerceIn(1, 16), sel) }
    }


    fun clearScan() { scanResults = emptyList() }

    fun pick(file: File) {
        pickedFile = file
        pickedName = file.name
        engine.log.clear()
        engine.log.info("已选择文件: ${file.name} (${fmtSize(file.length())})")
    }

    fun analyze() {
        val f = pickedFile ?: return
        viewModelScope.launch { engine.analyze(f) }
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
            Spacer(Modifier.height(8.dp))
            MiuiButton(
                text = "🚀 一键使用内置完整解包器（推荐，功能最全）",
                onClick = { vm.launchEmbedded(ctx) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiuiButton(
                    text = "扫描 Download 目录",
                    onClick = { vm.scanDownloads() },
                    modifier = Modifier.weight(1f)
                )
                MiuiButton(
                    text = "JNI 探测",
                    onClick = { vm.probe() },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiuiButton(
                    text = "分析包结构",
                    onClick = { vm.analyze() },
                    enabled = vm.pickedFile != null,
                    modifier = Modifier.weight(1f)
                )
            }
            if (vm.scanResults.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { vm.clearScan() },
                    title = { Text("选择解包文件（${vm.scanResults.size} 个）") },
                    text = {
                        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            vm.scanResults.forEach { f ->
                                Text(
                                    f.name + "  (" + fmtSize(f.length()) + ")",
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .clickable { vm.pick(f); vm.clearScan() }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { vm.clearScan() }) { Text("取消") }
                    }
                )
            }
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
            if (vm.engine.jniPartitions.isNotEmpty()) {
                MiuiButton(
                    text = "分区解包（${vm.engine.jniPartitions.size}）",
                    onClick = { vm.openPartPicker() },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
            }
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

        if (vm.showPartPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.closePartPicker() },
                title = { Text("选择要解包的分区") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        vm.engine.jniPartitions.forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.togglePart(p.name, p.name !in vm.selectedParts) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = p.name in vm.selectedParts,
                                    onCheckedChange = { on -> vm.togglePart(p.name, on) }
                                )
                                Text(p.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Text(fmtSize(p.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.startSelected() }) {
                        Text("开始解包（${vm.selectedParts.size}）")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.closePartPicker() }) { Text("取消") }
                }
            )
        }

        SectionTitle(stringResource(R.string.unpack_log))
        TerminalLog(lines, Modifier.fillMaxWidth(), height = 280.dp)
        Spacer(Modifier.height(96.dp))
    }
}
