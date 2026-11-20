package com.taocrypt.micmask.ui2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taocrypt.micmask.tr
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 关于 → 参考项目：逐项列出，点击打开项目主页。 */
@Composable
fun RefsPage() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 12.dp, bottom = 24.dp)) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            AboutLinks.REFS.forEach { (name, desc, url) ->
                ArrowPreference(title = name, summary = desc, onClick = { openUrl(ctx, url) })
            }
        }
        Text(tr("感谢以上项目的作者与贡献者"), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp))
    }
}
