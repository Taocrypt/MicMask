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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taocrypt.micmask.tr
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 关于 → 免责声明：版权归属、模型素材权利、使用者行为准则、隐私与责任限制。 */
@Composable
fun DisclaimerPage() {
    val sections = listOf(
        tr("软件性质") to tr("MicMask 是免费开源的安卓变声工具，以 GPL-3.0 许可发布，源代码公开。本软件按「现状」提供，不附带任何明示或默示的担保，使用风险由使用者自行承担。"),
        tr("版权归属") to tr("软件代码版权归 MicMask 贡献者所有，部分代码改造自 atri-vc（MIT 许可）；所用第三方组件的版权归各自作者，许可信息见项目 NOTICE 文件。「MicMask」名称与图标仅用于标识本项目，修改版请使用不同的名称与图标。"),
        tr("音色模型与素材") to tr("安装包不含任何音色模型。模型仓库与音效包由社区维护，其中模型、声音与素材的权利归原权利人所有，作者不审核、也不担保其来源与授权。下载、导入或使用前，请确认你有权使用。权利人如认为内容侵权，可在仓库提交下架申请，核实后会尽快处理。"),
        tr("使用者行为准则") to tr("禁止用于冒充他人身份、诈骗、骚扰、诽谤、传播违法或不良信息；未经本人同意，不得模仿或使用真实人物（含公众人物）的声音进行公开传播或商业用途；在游戏、直播、通话中使用时，请遵守平台规则和当地法律；公开发布合成声音内容时，应按规定显著标明为合成内容。"),
        tr("隐私") to tr("变声、录音与模型推理都在本机完成，录音和模型不会上传。联网用于检查更新、下载模型与素材、读取赞赏名单，以及获取公告与推荐内容。可选的匿名统计默认关闭，需你在同意弹窗中勾选或在 设置 → 隐私 打开后才会上报版本、机型、功能使用事件与崩溃信息（不含录音和个人信息），可随时关闭或删除。日志保存在本机；只有你主动导出分享，或在「反馈问题」中提交时，才会上传你填写的内容以及你选择附带的日志与截图。"),
        tr("责任限制") to tr("因使用或无法使用本软件造成的任何直接或间接损失，以及使用者违反法律法规或本准则所产生的一切后果，均由使用者自行承担，作者与贡献者不承担任何责任。"),
        tr("同意与更新") to tr("继续使用本软件，即表示你已阅读并同意以上内容。本声明可能随版本更新调整，以最新版本为准。"),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
        sections.forEach { (t, b) ->
            SmallTitle(t)
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(b, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(16.dp))
            }
        }
        Text(tr("本声明不构成法律意见。"), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
    }
}
