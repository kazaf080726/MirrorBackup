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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kazaf.mirrorbackup.R
import com.kazaf.mirrorbackup.core.CpuDetector
import com.kazaf.mirrorbackup.core.UnpackEngine
import com.kazaf.mirrorbackup.core.f
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

    /** 解包线程：null = 用户还没选（全量解包前必须先选） */
    var threads by mutableStateOf<Int?>(null)
        private set
    var pickedFile by mutableStateOf<File?>(null)
        private set
    var pickedName by mutableStateOf("")
        private set

    /** 没选线程就点解包时弹出提示 */
    var showThreadHint by mutableStateOf(false)
        private set

    /** 选择文件后的读取/拷贝进度（系统文件管理器可能只给 URI，需要落地才有路径） */
    var picking by mutableStateOf(false)
        private set
    var pickStep by mutableStateOf("")
        private set

    fun chooseThreads(n: Int) {
        threads = n
        showThreadHint = false
    }

    fun dismissThreadHint() { showThreadHint = false }

    fun pick(file: File) {
        pickedFile = file
        pickedName = file.name
        engine.clearPartitions()
        selectedParts = emptySet()
        engine.log.clear()
        engine.log.info("已选择文件: ${file.name} (${fmtSize(file.length())})")
    }

    /**
     * 系统文件管理器返回的 URI 落地：
     * 1) 能直接映射成真实路径（externalstorage 文档 / MediaStore 的 _data）→ 直接用，零拷贝；
     * 2) 映射不出来才复制到应用缓存（有进度提示，不卡界面）。
     */
    fun pick(uri: Uri) {
        viewModelScope.launch {
            picking = true
            pickStep = "正在读取所选文件…"
            val f = withContext(Dispatchers.IO) {
                val direct = resolveDocumentPath(uri)
                if (direct != null && direct.exists() && direct.canRead()) {
                    engine.log.info("✔ 直接使用所选文件: ${direct.absolutePath}")
                    direct
                } else {
                    copyUriToCache(uri)
                }
            }
            picking = false
            pickStep = ""
            if (f != null) pick(f)
            else engine.log.error("❌ 无法读取所选文件（可能是云盘/网盘占位文件）")
        }
    }

    /** 把 SA F 文档 URI 映射成真实文件路径（映射不出来返回 null） */
    private fun resolveDocumentPath(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        // 内部存储 / SD 卡（系统文件管理器的“浏览”默认走这个 provider）
        if (uri.authority == "com.android.externalstorage.documents") {
            val docId = uri.lastPathSegment ?: return null
            if (docId.startsWith("raw:")) return File(docId.removePrefix("raw:"))
            val vol = docId.substringBefore(':', "")
            val rel = docId.substringAfter(':', "")
            if (rel.isEmpty()) return null
            val base = when {
                vol.equals("primary", true) -> android.os.Environment.getExternalStorageDirectory()
                vol.equals("home", true) -> File(android.os.Environment.getExternalStorageDirectory(), "Documents")
                else -> File("/storage/$vol")
            }
            val f = File(base, rel)
            if (f.exists() && f.canRead()) return f
        }
        // MediaStore / Downloads 等：查 _data 列
        return try {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                    val idx = c.getColumnIndex("_data")
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { p ->
                        File(p).takeIf { it.exists() && it.canRead() }
                    } else null
                }
        } catch (e: Exception) {
            null
        }
    }

    /** 兜底：把 URI 内容复制到缓存目录（逐块读写，带进度，不卡主线程） */
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val ctx = getApplication<Application>()
            val name = queryName(uri) ?: "input.zip"
            val dir = File(ctx.cacheDir, "unpack")
            dir.listFiles()?.forEach { it.deleteRecursively() }
            dir.mkdirs()
            val target = File(dir, name)
            val total = querySize(uri)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(4 * 1024 * 1024)
                    var done = 0L
                    var lastStep = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastStep && pct >= 0) {
                            lastStep = pct
                            pickStep = "正在复制到缓存：$pct%（${fmtSize(done)} / ${fmtSize(total)}）"
                        }
                    }
                }
            }
            engine.log.ok("✔ 已复制所选文件到缓存: ${fmtSize(target.length())}")
            target
        } catch (e: Exception) {
            engine.log.error("❌ 复制失败: ${e.message}")
            null
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

    private fun querySize(uri: Uri): Long {
        val ctx = getApplication<Application>()
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    var showPartPicker by mutableStateOf(false)
        private set
    var selectedParts by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 打开分区选择：还没有分区清单就先分析当前文件 */
    fun openPartPicker() {
        if (threads == null) { showThreadHint = true; return }
        if (engine.jniPartitions.isEmpty()) {
            val f = pickedFile
            if (f == null) {
                engine.log.warn("⚠ 请先选择要解包的卡刷包 / payload.bin")
                return
            }
            viewModelScope.launch {
                engine.analyze(f)
                if (engine.jniPartitions.isNotEmpty()) showPartPicker = true
                else engine.log.warn("⚠ 这个文件里没有可解包的分区（payload.bin）")
            }
            return
        }
        showPartPicker = true
    }

    fun togglePart(name: String, on: Boolean) {
        selectedParts = if (on) selectedParts + name else selectedParts - name
    }

    /** 全选 / 取消全选 */
    fun selectAllParts(all: Boolean) {
        selectedParts = if (all) engine.jniPartitions.map { it.name }.toSet() else emptySet()
    }

    fun closePartPicker() { showPartPicker = false }

    fun startSelected() {
        val f = pickedFile ?: return
        val t = threads
        val sel = selectedParts.toList()
        if (sel.isEmpty()) return
        if (t == null) { showThreadHint = true; return }
        showPartPicker = false
        if (engine.state.value.running) return
        viewModelScope.launch { engine.unpack(f, t.coerceIn(1, 16), sel) }
    }

    fun analyze() {
        val f = pickedFile ?: return
        viewModelScope.launch { engine.analyze(f) }
    }

    fun start() {
        val f = pickedFile ?: return
        if (engine.state.value.running) return
        // 必须先选解包线程，否则不开始全量解包
        val t = threads ?: run { showThreadHint = true; return }
        viewModelScope.launch { engine.unpack(f, t.coerceIn(1, 16)) }
    }

    fun stop() {
        engine.cancelled = true
    }
}

/** 解包工具箱页面（ZIP / payload.bin，支持 1 / 2 / 5 / 满核 并行解包） */
@Composable
fun UnpackScreen(vm: UnpackViewModel) {
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
                text = "分析包结构",
                onClick = { vm.analyze() },
                enabled = vm.pickedFile != null && !vm.picking,
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
                "⚡ 检测到 ${CpuDetector.cores} 核 → 满核 ${CpuDetector.cores} 线程（多分区并行，实测 4 线程约为 1 线程的 2 倍）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (vm.threads == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "⚠ 请先选择线程，选完才能开始解包",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
                        Text("${state.done} / ${state.total} 个分区", fontSize = 12.sp)
                        if (state.totalBytes > 0) {
                            Text(
                                fmtSize(state.doneBytes) + " / " + fmtSize(state.totalBytes) +
                                    (if (state.running && state.speedMbps > 0) "  ·  " + f("%.1f", state.speedMbps) + " MB/s" else ""),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (state.running && state.current.isNotEmpty()) {
                            Text(
                                "当前: ${state.current}  ${state.pct}%",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // 操作按钮：一键全部解包 / 按分区解包（勾选，支持全选）
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
                    text = "全部解包",
                    onClick = { vm.start() },
                    enabled = vm.pickedFile != null,
                    modifier = Modifier.weight(1f)
                )
                if (vm.engine.jniPartitions.isNotEmpty()) {
                    MiuiButton(
                        text = "分区解包（${vm.engine.jniPartitions.size}）",
                        onClick = { vm.openPartPicker() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (vm.showPartPicker) {
            val all = vm.engine.jniPartitions
            val allSelected = all.isNotEmpty() && vm.selectedParts.size >= all.size
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.closePartPicker() },
                title = { Text("选择要解包的分区") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp)) {
                        // 全选 / 取消全选
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已选 ${vm.selectedParts.size} / ${all.size}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { vm.selectAllParts(!allSelected) }
                            ) {
                                Text(
                                    if (allSelected) "取消全选" else "全选",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                            all.forEach { p ->
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
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { vm.startSelected() },
                        enabled = vm.selectedParts.isNotEmpty()
                    ) {
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

    // ── 线程未选择时的提示 ──
    if (vm.showThreadHint) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.dismissThreadHint() },
            title = { Text("请先选择解包线程") },
            text = {
                Text(
                    "先在「解包线程」里选 1 / 2 / 5 / 满核，选完才能开始解包。\n" +
                        "多分区是并行解包的，线程越多越快。",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.dismissThreadHint() }) { Text("知道了") }
            }
        )
    }

    // ── 读取/复制所选文件（系统文件管理器只给 URI 时要落地） ──
    if (vm.picking) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在读取所选文件…")
                }
            },
            text = { Text(vm.pickStep.ifEmpty { "请稍候…" }, fontSize = 12.sp) },
            confirmButton = { }
        )
    }

    // ── 分析中：明确告诉用户在干活，不是卡住 ──
    if (state.analyzing) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("正在分析…")
                }
            },
            text = {
                Text(
                    state.analyzeStep.ifEmpty { "读取包结构…" },
                    fontSize = 12.sp
                )
            },
            confirmButton = { }
        )
    }

    // ── 解包中：不弹窗，进度看页面里的进度卡（可随时点「停止」） ──
}
