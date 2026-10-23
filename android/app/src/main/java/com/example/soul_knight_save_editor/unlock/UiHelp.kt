package com.example.soul_knight_save_editor.unlock

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class HelpTopic(val title: String, val text: String)
object HelpTopics {
    val saves = HelpTopic("读取与刷新存档", "先选择你要修改的游戏版本，再读取存档。读取时会关闭这个游戏。\n\n选择账号后，本次使用期间会记住文件位置。预览会重新读取当前内容；修改或恢复后自动刷新，方便连续编辑。刷新会清空待修改选择。\n\n重新查找用于文件位置改变的情况；切换版本会清空旧目标和草稿。退出应用后需要重新读取。")
    val discovery = HelpTopic("扫描游戏版本", "默认提供原版。点“切换游戏版本”可选择已保存的渠道；列表和上次选择都会保留，重启后不用重填包名。不同包名的存档不合并。\n\n首次找不到渠道时，可扫描全部已安装应用。扫描只检查本地文件，不会关闭游戏或修改存档；找到的版本自动保留，但不会自动切换。手动添加包名只需一次。\n\n读取只处理当前选中的包名，并会关闭该游戏。切换后清空旧存档和草稿，须重新读取。找到版本不代表全部功能兼容。")
    val roles = HelpTopic("角色与技能", "只修改勾选内容，预览后才写入。\n\n等级不足 7 时升到 7，已有更高等级保留。技能只解锁存档已有条目，不创建未知技能。等级与技能需要两份对应记录能够核对；不可用时可查看专家列表中的原因。")
    val pets = HelpTopic("宠物解锁", "只解锁当前账号存档里已有的宠物。使用已核对的 8.6.0 / 8.7.0 名单，包含 8.7 的鱼干之王，暂不处理 Hide。\n\n已有两份宠物记录时会先核对并同步修改，不新增缺失宠物键。")
    val items = HelpTopic("物品修改", "只处理已经确认的物品名单。磁带设为 1，蓝图完成研究，设施加入列表，饰品补齐并充能到 100。\n\n饰品已有佩戴关系、图纸与符文保留。活动材料等未确认或排除的内容不会修改。目前物品目录适配游戏 8.6.0 / 8.7.0。")
    val quantity = HelpTopic("数量与增加量", "快速模式每点击一次，此分类的每项物品增加 1000；撤销一次减少本次待增加量 1000。点击只修改草稿。\n\n专家模式的“目标数量”表示改成这个数量；“增加量”表示在现有数量上相加。目标 0 会清零；增加量 0 或留空不修改。数量范围为 0 至 2147483647。")
    val weapons = HelpTopic("武器获取次数", "增加的是游戏记录的武器获取次数。满足获取次数后，普通武器即可达到相应锻造条件；需要蓝图、科技等条件的武器，其他条件仍由游戏判断。\n\n快速模式每次应用全部 +8；专家模式只增加指定武器的次数。名单内尚无记录的武器从 0 开始，已有记录继续累加。使用 object2ObtainTime，不改使用次数。")
    val weaponSkins = HelpTopic("武器皮肤", "快速模式解锁当前版本已核对的皮肤，保留当前选择。专家模式可搜索、逐项解锁，再选择使用原皮肤或已解锁的皮肤；也可同时准备解锁和换肤。\n\n品质与索引是不同字段，品质数字来自游戏配置。只有点击“使用可选的最高品质”才会自动挑选，包含本次勾选解锁的皮肤；同品质优先保留当前选择。索引 0 为原皮肤。\n\n皮肤操作本身不修改进化等级、碎片、商店记录或抽取记录。进化操作请切换到“武器进化”标签。目录外记录保留，不按名称或最大索引猜测皮肤。")
    val expert = HelpTopic("逐项与批量操作", "搜索名称或编号，按分类筛选。勾选数量条目只是纳入批量操作，还需填参数；勾选磁带、蓝图等解锁条目会直接加入待修改内容。\n\n批量按钮会替换所选条目的草稿参数。切换页面保留草稿，快速与专家模式草稿分别保存，只有当前模式参与预览。")
    val backups = HelpTopic("备份与恢复", "每次修改及手动还原前会自动备份。也可以备份当前存档、导入本工具的 ZIP、导出或重命名备份。默认从存档#0 递增命名，不限数量；可在设置中指定每个游戏的保留上限，超出后删除最旧备份。\n\n还原会覆盖备份对应文件的后续进度，并将已有读取开关设为 0；只接受同一游戏、同一 Android 用户的备份。未完成事务仍按原规则恢复，不覆盖事务外变化。\n\n不包含 .data.new，不删除备份外文件。当前目标文件须存在。未导出的应用内备份会随助手卸载而丢失；外部副本由你自行保管，不受自动清理影响。")
}

@Composable internal fun HelpButton(topic: HelpTopic) {
    var visible by remember(topic) { mutableStateOf(false) }
    IconButton({ visible = true }, modifier = Modifier.size(40.dp).semantics { contentDescription = "${topic.title}说明" }) {
        Text("?", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    if (visible) AlertDialog(onDismissRequest = { visible = false }, title = { Text(topic.title) },
        text = { Text(topic.text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ visible = false }) { Text("知道了") } })
}

@Composable internal fun FeatureTitle(title: String, help: HelpTopic?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (help != null) HelpButton(help)
    }
}
