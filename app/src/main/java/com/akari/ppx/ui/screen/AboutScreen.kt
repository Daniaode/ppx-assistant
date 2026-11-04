package com.akari.ppx.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.TextButton
import androidx.compose.material.Card
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akari.ppx.BuildConfig.VERSION_NAME
import com.akari.ppx.R
import com.akari.ppx.utils.VersionChecker

@Composable
fun AboutScreen(isActive: Boolean, checkingUpdate: Boolean, onCheckUpdate: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Image(painterResource(R.drawable.icon), null, Modifier.size(80.dp))
        Text("皮友助手", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("版本 $VERSION_NAME · " + if (isActive) "框架已启用" else "框架未启用",
            style = MaterialTheme.typography.caption)
        TextButton(onClick = onCheckUpdate, enabled = !checkingUpdate) {
            Text(if (checkingUpdate) "正在检查更新…" else "检查更新")
        }
        Card(elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("版本适配列表", fontWeight = FontWeight.Bold)
                VersionChecker.supportedVersions.forEach { version ->
                    Text("皮皮虾 $version · 已适配")
                }
                Text("当前安装：${VersionChecker.targetVersion}；其他版本尚未验证。", style = MaterialTheme.typography.caption)
            }
        }
    }
}
