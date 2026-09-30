# MusicBot

Minecraft **Meteor Client** 附属插件：给彗星自带的**音符盒机器人（Notebot）**加**播放列表 / 随机 / 分组**。

播放本身**完全交给彗星自己的 Notebot**（`loadSong` / `play` / `pause` / `stop` / `getStatus` 都是 public，
**零反射**），MusicBot 只决定"下一首放哪首、什么时候换"。

* 当前版本：`1.0.4`（文件名可能带 `-alpha`，那只是打包标记；包内 `fabric.mod.json` 的版本始终是合法的纯数字）
* Minecraft `1.21.11` + Meteor Client

## 安装

1. 装好 Meteor Client（Minecraft 1.21.11）
2. 把 `musicbot-<版本>.jar` 放进 `.minecraft/mods/`
   * **只放一个**。同一个 mod id 有多个 jar 时 Fabric 会直接加载失败
3. 进游戏 → 开启 MusicBot → **歌单界面会自动弹出**

## 用法

### 图形界面

* 开启模块时自动弹出
* 或 `.musicbot gui`（别名 `.mb gui`）—— 模块关着也能开
* 或给模块设置里的「界面热键」绑一个键（只在模块开着时响应）

界面里：顶部是 播放 / 暂停 / 停止 / 上一首 / 下一首 与 `当前 #N/M`；
中间是**可滚动的歌曲列表**，每行有 播放 / 移除 / 上移 / 下移 / 打组；
下面是分组面板（按组过滤、只播这个组）与添加歌曲（模糊匹配）；
底部是 **`Notebot 原始状态`** —— 排查"为什么不出声"先看这一行。

### 命令

| 命令 | 说明 |
|---|---|
| `.musicbot status` | 状态 + 列表/组数量 + 模式 + 当前曲目 |
| `.musicbot list [all]` / `songs` / `refresh` | 列列表 / 列目录里认识的歌 / 重扫目录 |
| `.musicbot add "歌名"` / `remove "歌名或序号"` | 增 / 删（模糊匹配，多首会列出来） |
| `.musicbot move <序号> up\|down` / `clear` | 移动 / 清空 |
| `.musicbot play [序号\|歌名]` / `next` / `prev` / `stop` | 播放控制 |
| `.musicbot mode seq\|shuffle\|loop` | 顺序 / 随机（一轮不重复）/ 循环 |
| `.musicbot group list\|show\|set\|add\|from\|play\|remove\|dissolve\|rename\|off` | 分组管理 |
| `.musicbot save` / `gui` | 手动落盘 / 打开界面 |

歌名带空格要加引号。

## 歌从哪来

`<游戏目录>/meteor-client/notebot/` —— 和彗星 Notebot 用的是**同一个目录**（`.nbs` 等）。

## 数据存哪

`<游戏目录>/musicbot/musicbot.json`（原子落盘；坏文件会改名 `.bad` 留档、不删）。

## 已知限制

* **"一首放完"靠读 Notebot 的英文状态串判断**（`"Playing song. %d/%d"` → 别的值）。
  彗星改文案就会失灵；有 200 tick 超时兜底，最坏退化成"不自动下一首"，不会卡死。
* 音符盒阵列摆得不对时，彗星 Notebot 会刷 `Note at tick N out of range.` ——
  那是**彗星自己的**提示，含义是"这首歌的音符放不进它找到的阵列"，即**这首放不出来**。
  同一条提示的刷屏可以去 Notebot 设置里取消 `Chat Feedback`。
* `.musicbot refresh` / `songs` / `add` **仍是同步的**（界面里的「查找」已改成异步，不会卡帧）。
* 只扫 `notebot/` **一层**，不递归子目录。
* 播放控制依赖 Notebot 的 `getStatus()` 状态串，因此在 Notebot 模块被手动禁用时会不同步。

## 构建

```
.\gradlew.bat build --console=plain
```

产物在 `build/libs/musicbot-<版本>.jar`。
依赖 `libs/` 下的本地 jar（`meteor-client`、`orbit`，已随仓库提供）。

## 许可

待定（作者保留）。