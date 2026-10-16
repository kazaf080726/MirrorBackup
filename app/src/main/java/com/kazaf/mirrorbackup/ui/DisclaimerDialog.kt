package com.kazaf.mirrorbackup.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.kazaf.mirrorbackup.core.DISCLAIMER_TEXT
import com.kazaf.mirrorbackup.ui.theme.MiuiColors

/**
 * 首次启动免责弹窗：必须手动勾选同意才可进入主界面，
 * 点击外部/返回键均不可关闭。
 */
@Composable
fun DisclaimerDialog(onAgree: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { /* 禁止直接关闭，必须勾选同意 */ }) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(
                    "⚠️ 免责声明",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MiuiColors.Red
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    DISCLAIMER_TEXT,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { checked = !checked }
                ) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text("我已阅读并同意以上全部条款", fontSize = 14.sp)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAgree,
                    enabled = checked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("同意并进入", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
