package com.aefmusic.modules;

import com.aefmusic.MusicBotAddon;
import com.aefmusic.gui.MusicBotScreen;
import com.aefmusic.util.MusicBotSearch;
import com.aefmusic.util.MusicBotStore;
import com.aefmusic.util.MusicBotStore.PlayMode;
import com.aefmusic.util.MusicBotStore.SongEntry;
import com.aefmusic.util.MusicBotStore.SongInfo;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.Notebot;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.notebot.decoder.SongDecoders;
import meteordevelopment.orbit.EventHandler;

import java.io.File;
import java.util.List;

/**
 * MusicBot：彗星 Notebot 的「播放列表 / 随机 / 分组」管理器。
 *
 * <h3>为什么是"驱动"而不是"重写播放"</h3>
 * 播放本身完全复用 Meteor 自带的 {@code Notebot} 模块——它的
 * {@code loadSong(File)} / {@code play()} / {@code pause()} / {@code stop()} / {@code getStatus()}
 * 都是 <b>public</b>（已用 {@code javap} 从 {@code libs/meteor-client-1.21.11-He-20260725.jar} 核对，
 * 见本类末尾注释）。所以本模块<b>一行反射都没有</b>：只负责"下一首该放哪首、什么时候该换"，
 * 换歌就是 {@code notebot.loadSong(file)}，剩下的调音/对准音符盒/发包全是 Notebot 自己的事。
 *
 * <h3>怎么知道一首放完了</h3>
 * Notebot 的 {@code isPlaying} / {@code stage} / {@code song} 全是 private，没有事件也没有回调，
 * 但它有一个 public 的 {@code getStatus()}，返回给 GUI 用的中文/英文状态串
 * （"Playing song. 120/980" / "Ready to play." / "Setting up the noteblocks." / "No song loaded."）。
 * 本模块每 tick 读这个字符串：<b>上一 tick 还是"Playing song."、这一 tick 不是了</b>，
 * 就是一首结束了。这是"只用公开入口"的前提下唯一可靠的边界信号——比猜时长可靠，
 * 也不会因为用户在 Notebot GUI 里手点 Pause 而误判（Pause 后状态串仍是 "Playing song."）。
 *
 * <h3>已知取舍（写在这里免得下次又踩）</h3>
 * <ul>
 * <li>切歌时旧歌是"戛然而止"——Notebot 没有淡出，{@code loadSong} 直接 resetVariables。
 * 想让它自然收尾就等它自己放完，别按下一首。</li>
 * <li>歌曲目录固定是 {@code <gameDir>/meteor-client/notebot/}，和 Notebot 自己的
 * {@code playRandomSong()} 用的是同一个目录（{@code MeteorClient.FOLDER} + "notebot"）。
 * 不提供自定义目录：两边目录不一致的话，"Notebot 里能放的歌"和"列表里能加的歌"就对不上了。</li>
 * <li>本模块开着但 Notebot 模块被关掉时，只会在用户主动点播放时才去 enable() 它；
 * 播放中途被关掉（Notebot 自己的 stop() 也会 disable）不会被强行拉起来，只会停下并提示。</li>
 * </ul>
 */
public class MusicBot extends Module {

    /** 歌曲选择动作（GUI 下拉框）。每个动作执行完都会回到「无」。 */
    public enum Action {
        无,
        播放,
        下一首,
        上一首,
        停止
    }

    /**
     * 排队等待在 tick 里执行的操作。
     *
     * <p><b>为什么所有 GUI 回调都要排队</b>：Meteor 的设置回调（{@code onChanged}）是在
     * <b>渲染线程</b>里跑的，而这里要做的事有两类都不能在那儿干——
     * ① 碰 {@code mc.level}/{@code mc.gameMode}（{@code Notebot.loadSong/stop} 会碰），
     * ② 写磁盘（{@code store.save()} 是同步文件 IO，放渲染线程会卡帧）。
     * 所以回调里只做"记下意图"，实际执行统一在 {@link #drainQueue()} 里、跟游戏逻辑同一个 tick。
     * 顺带的好处：命令和 GUI 走的是同一条执行路径，行为不会两边不一致。
     */
    private sealed interface Op {
        record PlayAll() implements Op { }

        record Next() implements Op { }

        record Prev() implements Op { }

        record Stop() implements Op { }

        record AddSong(String query) implements Op { }

        record RemoveSong(String query) implements Op { }

        record MoveSong(String query, int delta) implements Op { }

        record ClearList() implements Op { }

        // ---- 下面 5 个是给图形界面加的（界面按"第几行"操作，比按歌名查找更准）----

        /**
         * 暂停 / 继续。原来没有这个 Op：设置面板里只有播放/下一首/上一首/停止。
         * 走的是同一条"入队 → TickEvent.Post 执行"的路，见 {@link #togglePause()}。
         */
        record Pause() implements Op { }

        /** 播放第 index 首（0 基，界面里的行号）。执行体是既有的 {@link #playIndex(int, boolean)}。 */
        record PlayIndex(int index) implements Op { }

        /** 按下标移除。执行体是既有的 {@code store.removeAt(index)}，不经过"按歌名模糊找"。 */
        record RemoveAt(int index) implements Op { }

        /** 按下标上/下移。执行体是既有的 {@code store.move(index, delta)}。 */
        record MoveAt(int index, int delta) implements Op { }

        /** 给第 index 首打组（group 为 null = 取消分组）。执行体是既有的 {@code store.setGroup(...)}。 */
        record SetGroupAt(int index, String group) implements Op { }
    }

    private final java.util.ArrayDeque<Op> queue = new java.util.ArrayDeque<>();
    /** 队列上限：GUI 里按住回车不放也不会把内存堆爆。 */
    private static final int MAX_QUEUE = 64;

    /** 播放列表（含随机袋子与抽取历史）。状态在这里，执行在下面的 tick 里。 */
    private final MusicBotStore store = new MusicBotStore();

    /**
     * 后台「扫目录 + 找歌」的执行器。
     *
     * <p>慢活（列目录、读整个 NBS 文件解析元数据）交给它，客户端线程只提交请求、只合并结果。
     * 结果合并点在 {@link #pollSearch()}：模块开着时由 {@code TickEvent.Post} 调（就是 Op 队列那条
     * 执行路径/线程），界面开着时由渲染帧调（同一条线程）。
     */
    private final MusicBotSearch search = new MusicBotSearch();

    /**
     * Notebot 的 {@code getStatus()} 在"没有歌"时返回 {@code "Stage: None."}。
     * 抽成常量是因为它是**跨版本最脆的一环**：彗星改一个字这里就失效。
     * 所以下面判定"一首放完了"时是"三个可能的状态串里命中任意一个"，不是只认这一个；
     * 另外还有 {@link #START_TIMEOUT_TICKS} 兜底，改了文案也只是退化成"等 10 秒"而不是永久卡死。
     */
    private static final String STAGE_NONE = "Stage: None";

    /** 发出播放指令后最多等多少 tick（20t = 1 秒）还没起播就放弃。彗星内部加载超时是 60 秒，那个太久了。 */
    private static final int START_TIMEOUT_TICKS = 200;

    /** 下一首要放的下标；-1 = 没在放。 */
    private int currentIndex = -1;

    /** 已发出播放指令、正在等 Notebot 把歌加载完。 */
    private boolean waitingForStart;

    /** 等了几个 tick（见 {@link #START_TIMEOUT_TICKS}）。 */
    private int waitTicks;

    /** 上一 tick Notebot 是不是"正在放歌"；用来抓"上一 tick 在放、这一 tick 不放了"这个边沿。 */
    private boolean notebotWasPlaying;

    /** 上一 tick 的 Notebot 状态串，只用于日志（出问题时能看出到底卡在哪一步）。 */
    private String lastNotebotStatus = "";

    /** 通知气泡最多这么频繁地弹一次（tick 计），免得 Notebot 没调好时刷屏。 */
    private int lastWarnTick = -100;
    private int tick;

    // ================================================================ 设置

    private final SettingGroup sgPlay = settings.createGroup("播放");
    private final SettingGroup sgList = settings.createGroup("列表");
    private final SettingGroup sgGroup = settings.createGroup("分组");

    private final Setting<PlayMode> playMode = sgPlay.add(new EnumSetting.Builder<PlayMode>()
        .name("播放模式")
        .description("顺序 = 按列表从头放到尾就停；随机 = 一轮内不重复；循环 = 按当前顺序一直放。")
        .defaultValue(PlayMode.SEQUENTIAL)
        .build());

    private final Setting<Boolean> resetCycle = sgPlay.add(new BoolSetting.Builder()
        .name("└ 一轮放完自动重开")
        .description("只对「随机」有效。开 = 放完一整轮重新洗牌继续；关 = 放完一轮就停下。")
        .defaultValue(true)
        .visible(() -> playMode.get() == PlayMode.SHUFFLE)
        .build());

    private final Setting<Boolean> autosave = sgPlay.add(new BoolSetting.Builder()
        .name("自动保存列表")
        .description("列表/分组每次改动立刻落盘。关掉的话只有关模块和 .musicbot save 才写盘。")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> logEachSong = sgPlay.add(new BoolSetting.Builder()
        .name("换歌写调试日志")
        .description("每次切歌往插件调试日志写一行（前缀 MUSICBOT）。排查「为什么没自动下一首」时开。")
        .defaultValue(false)
        .build());

    private final Setting<Action> action = sgPlay.add(new EnumSetting.Builder<Action>()
        .name("立即执行")
        .description("选一个动作立刻做一次，做完自动回到「无」。游戏里更常用 .musicbot play/next/stop。")
        .defaultValue(Action.无)
        .onChanged(this::onActionChanged)
        .build());

    private final Setting<String> addSong = sgList.add(new StringSetting.Builder()
        .name("添加歌曲")
        .description("填歌名（可只填一部分，模糊匹配）。填完按回车就加进列表末尾。")
        .defaultValue("")
        .onChanged(this::onAddSongChanged)
        .build());

    private final Setting<String> removeSong = sgList.add(new StringSetting.Builder()
        .name("移除歌曲")
        .description("从列表里删掉第一首名字匹配的歌（模糊匹配）。")
        .defaultValue("")
        .onChanged(this::onRemoveSongChanged)
        .build());

    private final Setting<String> moveSong = sgList.add(new StringSetting.Builder()
        .name("上移/下移")
        .description("填歌名后按回车 = 往列表后面挪一格（下移）；前面加个减号（如 -歌名）= 往前挪一格（上移）。")
        .defaultValue("")
        .onChanged(this::onMoveSongChanged)
        .build());

    private final Setting<Integer> action2 = sgList.add(new IntSetting.Builder()
        .name("清空列表")
        .description("把值加 1 就清空整份列表（含分组）。故意做成「计数器」而不是按钮：GUI 里手滑点不到。")
        .defaultValue(0)
        .min(0)
        .onChanged(this::onClearCounterChanged)
        .build());

    private final Setting<String> groupName = sgGroup.add(new StringSetting.Builder()
        .name("组名")
        .description("下面这个开关作用的对象。留空 = 不启用组过滤。改它会立刻重算候选集，但不打断正在放的那首。")
        .defaultValue("")
        .onChanged(v -> onGroupSettingChanged())
        .build());

    private final Setting<Boolean> onlyThisGroup = sgGroup.add(new BoolSetting.Builder()
        .name("只播这个组")
        .description("开：播放/下一首只在这个组里挑歌（随机不重复也只在这个组内算）。关：整个列表都在候选里。")
        .defaultValue(false)
        .onChanged(v -> onGroupSettingChanged())
        .build());

    /**
     * 图形界面的热键（默认不绑定）。
     *
     * <p><b>为什么用 {@code KeybindSetting} 而不是自己写一个 {@code KeyEvent} 处理器</b>：
     * 模块自己的 {@code @EventHandler} 只在模块开着时才订阅，而 {@code KeybindSetting} 是在构造期
     * 自己订阅事件总线的（javap 已核实：{@code onKey} 在 {@code KeyEvent.action == Release}、
     * {@code keybind.matches(input)}，并且 {@code module == null || module.isActive()} 时才 run 那个
     * action）。也就是说它同样是"模块开着才响应"——这是彗星对设置项热键的一贯语义，没有特殊理由不该绕过。
     * 模块关着时请用 {@code .musicbot gui}。
     */
    private final Setting<Keybind> guiKey = sgPlay.add(new KeybindSetting.Builder()
        .name("界面热键")
        .description("按一下打开 MusicBot 图形界面（模块开着时才响应，等价于 .musicbot gui）。默认不绑定。")
        .defaultValue(Keybind.none())
        .action(MusicBotScreen::open)
        .build());

    public MusicBot() {
        super(MusicBotAddon.CATEGORY, "MusicBot",
            "给彗星 Notebot 加播放列表/随机/分组：按顺序、随机不重复、只放某个组，放完自动下一首。"
                + "歌还是 Notebot 在放，本模块只管选曲；歌要从 <游戏目录>/meteor-client/notebot/ 里选。",
            "musicbot");

        // 构造期就把盘读进来：聊天命令必须能在模块关着的时候查列表，
        // 如果等到 onActivate 才 load，用户关着模块敲 .musicbot 会看到"空列表"而以为数据丢了。
        store.load();
    }

    // ================================================================ 生命周期

    @Override
    public void onActivate() {
        super.onActivate();
        tick = 0;
        lastWarnTick = -100;
        notebotWasPlaying = false;
        waitingForStart = false;
        currentIndex = -1;
        store.resetShuffleState();

        if (!store.isLoaded()) store.load();

        // 2026-10-01 修（用户实测原话「搜索改异步啊喂卡死了」）：这里原来是同步 store.refresh()。
        // 列目录本身还好，真正的坑是它把索引铺好之后，界面对每个候选的 NBS 元数据解析全压在渲染线程上，
        // 而且 onActivate 往往就是热键在渲染帧里按出来的——一按就卡。
        // 现在只提交一个"只扫目录"的后台请求（立即返回），结果在 pollSearch() 里合并并给用户反馈。
        search.request(store, "");

        info("MusicBot 已开启：列表 %d 首 / %d 组，正在后台扫描歌曲目录…用 .musicbot 增删改查。",
            store.size(), store.groupNames().size());
        Alog("开启 列表=" + store.size() + " 组=" + store.groupNames().size()
            + " 模式=" + playMode.get() + " 目录=" + MusicBotStore.notebotDirText());

        // 2026-10-01：开启模块就直接把歌单界面弹出来。
        //
        // 用户实测反馈（截图）：开了模块看到的是 Meteor 那张**设置表单** ——「添加歌曲」是个
        // 输入框、「清空列表」是个复选框，他一句「你管他这叫歌单吗」。**他说得对。**
        // 真界面当时只挂在 ①一个默认没绑的热键 和 ②`.musicbot gui` 命令后面，
        // 两个都不是"开箱就能看到"的入口，等于没给。
        //
        // 这个 Meteor build 里**没有 ButtonSetting**（javap 确认，`settings.ButtonSetting`
        // 不存在），所以没法像别的 addon 那样在设置面板顶部放一个「打开歌单」按钮 ——
        // 只能走"开模块即开界面"这条路。
        //
        // 不弹的话，用户永远只会看到那张表单，然后再来骂一次。
        MusicBotScreen.open();
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        // 关模块 = 停播 + 落盘。不落盘的话"改完列表直接关模块"这条最常见的路径会丢数据。
        if (waitingForStart || currentIndex >= 0) {
            stopNotebot(false);
        }
        if (store.save()) {
            Alog("关闭，已保存列表 " + store.size() + " 首");
        }
    }

    // ================================================================ 主循环

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        tick++;

        Notebot notebot = notebot();
        if (notebot == null) {
            // 只在开启时提示一次：Notebot 是彗星自带模块，正常必然存在；
            // 找不到说明用户把 meteor 换成了别的版本，这时候硬报错比默默不动好
            if (tick == 1) {
                warning("MusicBot：找不到彗星自带的 Notebot 模块，本模块无法播放。");
                Alog("找不到 Notebot 模块实例");
            }
            return;
        }

        boolean nowPlaying = isNotebotPlaying(notebot.getStatus());

        // ---- 等歌加载完（Notebot.loadSong 是异步解析，指令发出后还要等几个 tick）----
        if (waitingForStart) {
            waitTicks++;
            if (nowPlaying) {
                waitingForStart = false;
                store.recordPlayed(currentIndex);
                store.commitShuffleDraw(currentIndex);
                if (logEachSong.get()) {
                    Alog("开始播放 #" + (currentIndex + 1) + " " + describe(currentIndex));
                }
            } else if (notebot.getStatus().startsWith("Stage: None")) {
                // 加载失败/被取消：Notebot 会把 stage 打回 None。**不在这里自动跳下一首**——
                // 一首坏歌会让整个列表在几秒内被"唰"地跳过一遍，用户只看到歌名乱闪。
                // 停下来 + 说清楚是哪一首，由用户决定。
                waitingForStart = false;
                currentIndex = -1;
                warnThrottled("这首歌加载失败了（文件坏了或格式不认识），已停下。");
                Alog("加载失败，停下");
            } else if (waitTicks > START_TIMEOUT_TICKS) {
                // 兜底：状态串既不是"在放"也不是"Stage: None"（比如彗星改了状态文案）。
                // 没有这个超时的话 waitingForStart 会永远挂着，整个自动下一首链路静默死掉。
                waitingForStart = false;
                currentIndex = -1;
                warnThrottled("等 Notebot 起播超时（10 秒），已放弃这一首。当前 Notebot 状态：" + notebot.getStatus());
                Alog("起播超时，status=" + notebot.getStatus());
            }
            notebotWasPlaying = nowPlaying;
            lastNotebotStatus = notebot.getStatus();
            return;
        }
        waitTicks = 0;

        // ---- 抓"一首放完了"的边沿 ----
        if (notebotWasPlaying && !nowPlaying) {
            String status = notebot.getStatus();
            if (status.startsWith(STAGE_NONE) || status.startsWith("No song loaded")
                || status.startsWith("Ready to play")) {
                if (logEachSong.get()) {
                    Alog("一首结束 status=" + status);
                }
                advanceToNext();
            }
        }

        if (logEachSong.get() && !notebot.getStatus().equals(lastNotebotStatus)) {
            Alog("Notebot 状态 " + lastNotebotStatus + " → " + notebot.getStatus());
        }
        notebotWasPlaying = nowPlaying;
        lastNotebotStatus = notebot.getStatus();
    }

    /** 放完一首后推进。顺序模式下到头就停，随机模式按袋子取，循环模式取模。 */
    private void advanceToNext() {
        int next = store.pickNext(currentIndex, playMode.get(), resetCycle.get());
        if (next < 0) {
            currentIndex = -1;
            stopNotebot(false);
            info("MusicBot：这一轮放完了（%s）。", describeEndReason());
            Alog("一轮结束，停下");
            return;
        }
        playIndex(next, false);
    }

    private String describeEndReason() {
        PlayMode m = playMode.get();
        if (m == PlayMode.SHUFFLE) return isGroupFilterOn() ? "组「" + currentGroup() + "」已随机放完一轮" : "随机一轮已不重复地放完";
        return isGroupFilterOn() ? "组「" + currentGroup() + "」已顺序放完" : "顺序播放到列表末尾";
    }

    // ================================================================ 播放控制

    /**
     * 播放列表里第 index 首。
     *
     * <p>顺序很重要：先把歌加载进 Notebot（这会 enable 它并 resetVariables），
     * 再等它自己调 {@code play()}——{@code loadSong} 的加载是异步的，
     * 这时候直接调 {@code play()} 会因为 {@code stage != Playing} 而报 "No song loaded."。
     */
    public void playIndex(int index, boolean manual) {
        Notebot notebot = notebot();
        if (notebot == null) {
            error("找不到 Notebot 模块，放不了。");
            return;
        }
        if (store.isEmpty()) {
            warning("播放列表是空的。先 .musicbot add <歌名> 加几首（.musicbot songs 看有哪些）。");
            return;
        }
        if (index < 0 || index >= store.size()) {
            warning("没有第 " + (index + 1) + " 首。");
            return;
        }

        SongEntry entry = store.entryAt(index);
        File file = store.resolveAt(index);
        if (file == null || !file.isFile()) {
            // 缺失的条目**不自动跳过**：直接跳会让用户以为模块在乱放歌。
            // 说清楚是哪一首、怎么清理，由他决定。
            warning("第 %d 首找不到文件（%s）。用 .musicbot remove 清掉它，或把文件放回 notebot 目录。",
                index + 1, entry == null ? "?" : entry.relative());
            return;
        }
        if (!SongDecoders.hasDecoder(file)) {
            warning("第 %d 首的格式 Notebot 不认识：%s", index + 1, file.getName());
            return;
        }
        if (onlyThisGroup.get() && !groupMatches(entry)) {
            warning("第 %d 首不在组「%s」里。", index + 1, groupName.get());
            return;
        }

        currentIndex = index;
        if (manual) store.recordPlayed(index);
        waitingForStart = true;
        waitTicks = 0;

        if (!notebot.isActive()) notebot.enable();
        notebot.loadSong(file);
        info("MusicBot 播放 #%d/%d：%s", index + 1, store.size(), store.displayNameAt(index));
    }

    /** 按当前模式从列表里挑一首开始放（不管现在在放什么）。 */
    public void playFromList() {
        syncFilter();
        store.resetShuffleState();
        if (playMode.get() == PlayMode.SHUFFLE) store.reshuffle();

        int first = store.pickNext(-1, playMode.get(), resetCycle.get());
        if (first < 0) {
            // 组过滤开着但组里一首都没有时会走到这里（pickNext 的候选集是空的）
            if (isGroupFilterOn()) {
                warning("组「%s」里没有歌。用 .musicbot group list 看有哪些组，或在「分组」设置里换个组名。",
                    currentGroup());
            } else {
                warning("播放列表是空的。先 .musicbot add <歌名> 加几首（.musicbot songs 看有哪些）。");
            }
            return;
        }
        playIndex(first, false);
    }

    /** 下一首。顺序模式到头 = 停（并告知），随机模式从袋子里取。 */
    public void next() {
        if (store.isEmpty()) {
            warning("播放列表是空的。");
            return;
        }
        syncFilter();
        int base = currentIndex;
        if (base < 0) {
            // 没在放 / 刚放完一轮：重新开一轮（随机模式下要重洗，否则永远从固定顺序开头）
            store.resetShuffleState();
            if (playMode.get() == PlayMode.SHUFFLE) store.reshuffle();
            base = -1;
        }
        int next = store.pickNext(base, playMode.get(), resetCycle.get());
        if (next < 0) {
            warning("没有下一首了（%s）。", describeEndReason());
            return;
        }
        playIndex(next, false);
    }

    /** 上一首。随机模式走"抽取历史"回退，不是重新随机一首。 */
    public void previous() {
        if (store.isEmpty()) {
            warning("播放列表是空的。");
            return;
        }
        syncFilter();
        int prev = store.pickPrevious(currentIndex, playMode.get());
        if (prev < 0) {
            warning("已经是第一首了。");
            return;
        }
        waitingForStart = false; // 手动跳转时把"等加载"状态清掉，否则新的一次加载会被旧状态挡一次
        playIndex(prev, true);
    }

    /**
     * 停止播放。
     *
     * @param alsoDisableNotebot true = 连 Notebot 模块一起关掉；false = 只让它停，模块留着
     */
    public void stopPlaying(boolean alsoDisableNotebot) {
        currentIndex = -1;
        stopNotebot(alsoDisableNotebot);
        info("MusicBot 已停止。");
    }

    private void stopNotebot(boolean alsoDisable) {
        waitingForStart = false;
        Notebot notebot = notebot();
        if (notebot == null) return;
        try {
            notebot.stop(); // public：内部 resetVariables + enable，不会把模块关掉
            if (alsoDisable && notebot.isActive()) notebot.disable();
        } catch (Throwable t) {
            Alog("停止 Notebot 时出错：" + t);
        }
    }

    // ================================================================ 给命令用的口子

    public MusicBotStore store() {
        return store;
    }

    public PlayMode mode() {
        return playMode.get();
    }

    public boolean isGroupFilterOn() {
        return onlyThisGroup.get() && groupName.get() != null && !groupName.get().trim().isEmpty();
    }

    public String currentGroup() {
        String g = groupName.get();
        return g == null ? "" : g.trim();
    }

    /** 正在放第几首（1 基）；0 = 没在放。 */
    public int currentNumber() {
        return currentIndex >= 0 ? currentIndex + 1 : 0;
    }

    public String currentDescription() {
        if (currentIndex < 0) return null;
        return describe(currentIndex);
    }

    /**
     * 与 {@link #currentDescription()} 同义，但用不解析文件的"浅"显示名。
     *
     * <p>给界面用的：这条文字每帧都要刷新，而 {@link #describe(int)} 会走
     * {@code store.displayNameAt} → 缓存未命中时读整个 NBS 文件。渲染帧里不能有那个可能，
     * 所以界面走这一版（扫描结果到了之后两者输出逐字相同）。
     */
    public String currentDescriptionShallow() {
        if (currentIndex < 0) return null;
        SongEntry e = store.entryAt(currentIndex);
        if (e == null) return "?";
        File f = store.resolveAt(currentIndex);
        return store.displayNameShallow(currentIndex)
            + (e.group() == null ? "" : " [组:" + e.group() + "]")
            + (f == null ? " (文件缺失)" : "");
    }

    public boolean isWaitingForStart() {
        return waitingForStart;
    }

    /**
     * 把「只播这个组」翻译成 store 的候选下标集合。
     *
     * <p>每次挑歌之前都要调一次，因为候选集会随列表/组名/开关变化。
     * 候选集变了 store 会自己发现并重洗随机袋子（比较 filterKey），不需要这里额外处理。
     */
    public void syncFilter() {
        if (!isGroupFilterOn()) {
            store.clearFilter();
            return;
        }
        String g = currentGroup();
        List<Integer> allowed = new java.util.ArrayList<>();
        for (int i = 0; i < store.size(); i++) {
            SongEntry e = store.entryAt(i);
            if (e != null && e.group() != null && e.group().equalsIgnoreCase(g)) allowed.add(i);
        }
        store.setFilter(allowed, g);
    }

    /** 歌名 + 组 + 文件在不在，一行说完。 */
    private String describe(int index) {
        SongEntry e = store.entryAt(index);
        if (e == null) return "?";
        File f = store.resolveAt(index);
        return store.displayNameAt(index)
            + (e.group() == null ? "" : " [组:" + e.group() + "]")
            + (f == null ? " (文件缺失)" : "");
    }

    /** 重新扫描歌曲目录；返回一句给用户看的结果。 */
    public String refreshSongs() {
        try {
            store.refresh();
            return "扫描完成：" + MusicBotStore.notebotDirText()
                + " 下 " + store.availableCount() + " 首可用歌曲。";
        } catch (MusicBotStore.ScanException e) {
            return "扫描失败：" + e.getMessage();
        }
    }

    /** 主动存盘（命令用）。 */
    public boolean saveNow() {
        return store.save();
    }

    // ================================================================ 给图形界面用的口子
    //
    // 屏幕（com.aefmusic.gui.MusicBotScreen）**只允许**调这一组方法，外加只读的
    // store()/currentNumber()/currentDescriptionShallow()/isWaitingForStart()/notebotStatus()。
    //
    // 为什么：这些方法体只有一句"入队"，和设置回调、聊天命令走的是同一个队列、同一条
    // TickEvent.Post 执行路径。屏幕自己在渲染帧里碰 Notebot 或改 store 就等于另开一条并发路径，
    // 队列（以及它挡住的"渲染帧里动世界 / 写盘"）就白做了。
    //
    // 例外只有"查找"这一组（guiSearch/searchXxx/pollSearch）：它不改游戏状态，只是把慢活丢给后台
    // 线程、再把结果拿回客户端线程合并——所以不进队列（进了反而要等一个 tick 才发起搜索）。
    // 详见 MusicBotSearch 的类注释。

    public void guiPlayAll() {
        enqueue(new Op.PlayAll());
    }

    public void guiPause() {
        enqueue(new Op.Pause());
    }

    public void guiStop() {
        enqueue(new Op.Stop());
    }

    public void guiNext() {
        enqueue(new Op.Next());
    }

    public void guiPrev() {
        enqueue(new Op.Prev());
    }

    /** @param index 0 基行号（界面里显示的是 index + 1） */
    public void guiPlayIndex(int index) {
        enqueue(new Op.PlayIndex(index));
    }

    /**
     * 按歌名/相对路径加歌（界面里的"加入"传的是候选曲的相对路径，等于精确指定那一首）。
     * 匹配与去重仍然是 {@link #doAddSong(String)} 那一套，界面只是先把候选列出来给人选。
     */
    public void guiAddSong(String query) {
        enqueue(new Op.AddSong(query));
    }

    public void guiRemoveAt(int index) {
        enqueue(new Op.RemoveAt(index));
    }

    public void guiMoveAt(int index, int delta) {
        enqueue(new Op.MoveAt(index, delta));
    }

    /** @param group null / 空白 = 取消分组 */
    public void guiSetGroupAt(int index, String group) {
        enqueue(new Op.SetGroupAt(index, group));
    }

    /**
     * 「查找」：把"扫目录 + 按歌名找"提交给后台线程，**立即返回**。
     *
     * <p>和上面那批 {@code guiXxx} 的区别：那些是"入队一个会改游戏状态的操作"，必须等 tick；
     * 这个不改任何游戏状态（只读快照 + 后台算），所以直接提交，界面下一帧就能显示"搜索中…"。
     * 结果由 {@link #pollSearch()} 在客户端线程上合并，界面再从 {@link #searchMatches()} 取。
     *
     * <p>同一时刻只会有一个搜索在跑：搜索进行中再点一次 = 记下"最后一次查询"，等当前这次回来
     * 立刻补跑（见 {@link MusicBotSearch} 的单飞说明）。
     */
    public void guiSearch(String query) {
        search.request(store, query);
    }

    /** 后台是否正在扫/查（界面据此把按钮文案改成「搜索中…」）。 */
    public boolean isSearching() {
        return search.isRunning();
    }

    /** 最近一次搜索的结果（不可变列表；还没查过或正在查时是空列表）。 */
    public List<SongInfo> searchMatches() {
        return search.matches();
    }

    /** 最近一次搜索的失败原因；没失败返回 null。 */
    public String searchError() {
        return search.error();
    }

    /** 搜索状态的版本号：变了就说明界面该重建结果表。 */
    public int searchVersion() {
        return search.version();
    }

    /**
     * Notebot 的原始状态串（界面底部那条状态行）。
     *
     * <p>给屏幕用的转发口：状态串是"一首放完"判定的唯一依据，屏幕上要原样亮出来，
     * 但屏幕自己不该去 {@code Modules.get().get(Notebot.class)}——Notebot 只在这一个类里碰。
     */
    public String notebotStatus() {
        Notebot notebot = notebot();
        return notebot == null ? "找不到 Notebot 模块" : notebot.getStatus();
    }

    /** 按当前设置同步一次"是否只播这个组"，并给出给用户看的说明。 */
    public String describeFilter() {
        if (!isGroupFilterOn()) return "组过滤：关（全部列表参与）";
        int n = store.groupSize(currentGroup());
        return "组过滤：只播组「" + currentGroup() + "」（" + n + " 首）";
    }

    /**
     * 命令侧改"只播这个组"。直接写设置值，这样 GUI 和命令看到的是同一份状态——
     * 两边各自存一份的话，用户改完命令再开 GUI 会看到开关是反的。
     */
    public void setGroupFilter(String group, boolean only) {
        groupName.set(group == null ? "" : group.trim());
        onlyThisGroup.set(only);
        syncFilter();
    }

    public void setMode(PlayMode mode) {
        playMode.set(mode == null ? PlayMode.SEQUENTIAL : mode);
        syncFilter();
    }

    /** 组名/开关一改就同步候选集；此刻正在放的那首不受影响（不打断），只影响下一次挑歌。 */
    private void onGroupSettingChanged() {
        if (isActive()) syncFilter();
    }

    // ================================================================ 设置回调
    //
    // 这些回调全部只做两件事：① 把输入框清空（让用户可以马上再输一次），② 往队列里塞一个意图。
    // 真正干活在 drainQueue()。原因见 Op 的类注释——渲染线程不能碰世界、也不该写盘。

    private void onActionChanged(Action value) {
        if (value == null || value == Action.无) return;
        switch (value) {
            case 播放 -> enqueue(new Op.PlayAll());
            case 下一首 -> enqueue(new Op.Next());
            case 上一首 -> enqueue(new Op.Prev());
            case 停止 -> enqueue(new Op.Stop());
            default -> {
            }
        }
        action.set(Action.无);
    }

    private void onAddSongChanged(String value) {
        String q = value == null ? "" : value.trim();
        if (q.isEmpty()) return;
        enqueue(new Op.AddSong(q));
        addSong.set("");
    }

    private void onRemoveSongChanged(String value) {
        String q = value == null ? "" : value.trim();
        if (q.isEmpty()) return;
        enqueue(new Op.RemoveSong(q));
        removeSong.set("");
    }

    private void onMoveSongChanged(String value) {
        String q = value == null ? "" : value.trim();
        if (q.isEmpty()) return;

        // 约定：不加符号 = delta +1 = 往列表后面挪（下移）；加 "-" = 上移。
        // GUI 里没有更省事的表达方式（两个按钮要占两行，而设置行数越少越好）。
        int delta = 1;
        if (q.startsWith("-") || q.startsWith("－")) {
            delta = -1;
            q = q.substring(1).trim();
        }
        if (q.isEmpty()) return;
        enqueue(new Op.MoveSong(q, delta));
        moveSong.set("");
    }

    private void onClearCounterChanged(Integer value) {
        // 只在计数器真的变化时动手；默认值 0（以及用户把它调回来）都不算
        if (value == null || value <= 0) return;
        enqueue(new Op.ClearList());
    }

    private void enqueue(Op op) {
        if (queue.size() >= MAX_QUEUE) {
            Alog("操作队列已满（" + MAX_QUEUE + "），丢弃：" + op);
            return;
        }
        queue.addLast(op);
    }

    /**
     * 执行队列里的操作。挂在 {@code TickEvent.Post} 上：Pre 里刚判过 Notebot 状态、
     * 也刚做完自动下一首，Post 里改状态不会和那一轮的判断打起来。
     *
     * <p>后台搜索/扫描的结果也在这条线程上合并（{@link #pollSearch()}），且放在
     * {@link #drainQueue()} 之前：后者在"不在世界里"时会直接 return，而合并结果不该依赖
     * 玩家在不在世界里。
     */
    @EventHandler
    private void onTickQueue(TickEvent.Post event) {
        pollSearch();
        drainQueue();
    }

    /**
     * 把后台扫描/搜索的结果合并进 store，并在"只扫目录"的请求上用聊天栏回一句。
     *
     * <p><b>为什么合并必须走这里</b>：store 的可变状态只允许在客户端线程上改（见
     * {@link MusicBotStore} 的线程约定，以及 Op 队列存在的理由）。后台线程只往自己的信箱里放一个
     * 不可变结果，一行 store 都不碰；合并动作落在这条线程上。
     *
     * <p>调用点有两个，都在同一条线程上（Minecraft 的 tick 与 render 都是客户端线程）：
     * ① 模块开着时是 {@code TickEvent.Post}（就是 Op 队列的执行点）；
     * ② 模块关着但界面开着时（{@code .musicbot gui}），界面每帧调一次——那种情况下模块没订阅 tick，
     * 这是唯一能合并的地方。谁先到谁合并，{@code AtomicReference.getAndSet} 保证同一个结果只合并一次。
     */
    public void pollSearch() {
        if (!search.poll(store)) return;

        // 只有"只扫目录"的请求（onActivate 发的那种）需要聊天栏反馈；用户查歌的结果由界面显示，不刷屏。
        if (!search.lastAppliedQuery().isEmpty()) return;

        if (search.lastAppliedError() != null) {
            // 目录不存在/读不了不是致命错误：列表还在，把目录建出来再 .musicbot refresh 就行
            warning("MusicBot：" + search.lastAppliedError());
            Alog("后台扫描失败：" + search.lastAppliedError());
        } else {
            info("MusicBot 扫描完成：目录里 %d 首可用歌曲。", store.availableCount());
            Alog("后台扫描完成：目录里 " + store.availableCount() + " 首");
        }
    }

    private void drainQueue() {
        if (queue.isEmpty()) return;
        if (mc.player == null || mc.world == null) return;

        int processed = 0;
        int startSize = queue.size();
        while (!queue.isEmpty()) {
            // 执行过程中又塞了新东西（比如操作里改了设置、设置回调再入队）→ 这一轮就收手，
            // 剩下的下个 tick 再说。没有这个判断的话理论上能在这里死循环。
            if (processed >= startSize || processed >= MAX_QUEUE) {
                Alog("队列在本轮执行中被追加，剩余 " + queue.size() + " 条留到下个 tick");
                break;
            }
            Op op = queue.pollFirst();
            if (op == null) continue;
            processed++;
            try {
                apply(op);
            } catch (Throwable t) {
                // 单个操作炸了不能把整个队列卡死，否则用户按什么都没反应且不知道为什么
                Alog("执行 " + op + " 出错：" + t);
                warnThrottled("这一步执行出错了，详见调试日志。");
            }
        }
    }

    private void apply(Op op) {
        switch (op) {
            case Op.PlayAll ignored -> {
                // 已经在放了就别重头开始——用户按"播放"时想要的多半是"继续"
                if (currentIndex >= 0 && !waitingForStart) return;
                playFromList();
            }
            case Op.Next ignored -> next();
            case Op.Prev ignored -> previous();
            case Op.Stop ignored -> stopPlaying(false);
            case Op.AddSong a -> doAddSong(a.query());
            case Op.RemoveSong r -> doRemoveSong(r.query());
            case Op.MoveSong m -> doMoveSong(m.query(), m.delta());
            case Op.ClearList ignored -> doClearList();
            // ---- 图形界面用的 ----
            case Op.Pause ignored -> togglePause();
            case Op.PlayIndex p -> playIndex(p.index(), true);
            case Op.RemoveAt r -> doRemoveAt(r.index());
            case Op.MoveAt m -> doMoveAt(m.index(), m.delta());
            case Op.SetGroupAt g -> doSetGroupAt(g.index(), g.group());
        }
    }

    // ================================================================ 列表操作（只在 tick 里跑）

    private void doAddSong(String q) {
        if (!store.isLoaded()) store.load();

        // 先在已扫描的目录里模糊找：这样能把"用户记错歌名"挡在这里，
        // 而不是加进列表之后每次播放都报"找不到文件"。
        if (store.availableCount() == 0) {
            // 首次使用时目录还没扫过，顺手扫一次。扫不了（目录不存在）不当错误处理：
            // onActivate 和 .musicbot refresh 都已经会提示，这里再报一次就是重复刷屏。
            try {
                store.refresh();
            } catch (MusicBotStore.ScanException ignored) {
            }
        }

        List<SongInfo> found = store.findAvailable(q);
        if (found.isEmpty()) {
            warnThrottled("找不到叫「" + q + "」的歌。用 .musicbot songs 看目录里有什么（也可以先 .musicbot refresh）。");
            return;
        }
        if (found.size() > 1) {
            // 匹配到多首**不猜**：猜错了用户会以为模块放错歌。列出来让他说具体点。
            StringBuilder sb = new StringBuilder("有 " + found.size() + " 首匹配「" + q + "」，请说得更具体：");
            for (int i = 0; i < Math.min(5, found.size()); i++) sb.append(" / ").append(found.get(i).displayName());
            warnThrottled(sb.toString());
            return;
        }

        SongInfo s = found.get(0);
        // 组过滤开着时，加进来的歌顺手打进当前组——不然用户开着"只播A组"加歌，
        // 加完发现放的还是那几首，会以为加失败了。
        String group = isGroupFilterOn() ? currentGroup() : null;
        if (store.add(s.relative(), group)) {
            info("MusicBot 已加入第 %d 首：%s%s", store.size(), s.displayName(),
                group == null ? "" : "  [组:" + group + "]");
            afterChange();
        } else {
            warnThrottled("「" + s.displayName() + "」已经在列表里了。");
        }
    }

    private void doRemoveSong(String q) {
        SongEntry removed = store.removeByName(q);
        if (removed == null) {
            warnThrottled("列表里没有匹配「" + q + "」的歌。");
            return;
        }
        info("MusicBot 已移除：%s（还剩 %d 首）", removed.relative(), store.size());
        afterChange();
    }

    private void doMoveSong(String q, int delta) {
        int idx = store.indexOfInPlaylist(q);
        if (idx < 0) {
            warnThrottled("列表里没有匹配「" + q + "」的歌。");
            return;
        }
        if (store.move(idx, delta)) {
            // delta > 0 = 往列表后面挪 = 下移（和 Setting「上移/下移」的说明一致）
            info("MusicBot 已%s：%s（现在第 %d 位）", delta > 0 ? "下移" : "上移", q, idx + delta + 1);
            afterChange();
        } else {
            warnThrottled("「" + q + "」已经在" + (delta > 0 ? "最后" : "最前") + "面了。");
        }
    }

    private void doClearList() {
        int n = store.size();
        if (n == 0) {
            info("播放列表已经是空的。");
            return;
        }
        store.clearPlaylist();
        currentIndex = -1;
        store.resetShuffleState();
        stopNotebot(false);
        info("MusicBot 已清空 %d 首（含分组）。", n);
        afterChange();
    }

    // ---- 图形界面系列（都只是"按下标"版本的上面那几个）----

    /**
     * 暂停 / 继续。
     *
     * <p><b>为什么要点一下 {@code notebotWasPlaying}</b>：{@code Notebot.pause()} 只是把
     * {@code isPlaying} 取反（javap 核实），歌还在、stage 还是 Playing，于是 {@code getStatus()}
     * 从 "Playing song. x/y" 变成 "Ready to play."；而 {@link #onTick} 判定"一首放完了"时恰好把
     * "Ready to play" 算作结束状态——不干预的话，用户一按暂停就会被当成这首放完了而自动跳下一首。
     * 把上一 tick 的"在放"标记清掉，这个上升沿就不成立。既有逻辑一行没改，只是新增的暂停别被它误读。
     */
    private void togglePause() {
        Notebot notebot = notebot();
        if (notebot == null) {
            error("找不到 Notebot 模块，暂停不了。");
            return;
        }
        try {
            notebot.pause();
            notebotWasPlaying = false;
            // 不额外 info：Notebot.pause() 自己已经会打 "Pausing." / "Resuming."
            Alog("暂停/继续 → " + notebot.getStatus());
        } catch (Throwable t) {
            Alog("暂停/继续出错：" + t);
            warnThrottled("暂停/继续出错了，详见调试日志。");
        }
    }

    private void doRemoveAt(int index) {
        SongEntry removed = store.removeAt(index);
        if (removed == null) {
            warnThrottled("第 " + (index + 1) + " 首已经不在列表里了。");
            return;
        }
        info("MusicBot 已移除：%s（还剩 %d 首）", removed.relative(), store.size());
        afterChange();
    }

    private void doMoveAt(int index, int delta) {
        String name = store.displayNameAt(index);
        if (!store.move(index, delta)) {
            warnThrottled("「" + name + "」已经在" + (delta > 0 ? "最后" : "最前") + "面了。");
            return;
        }
        info("MusicBot 已%s：%s（现在第 %d 位）", delta > 0 ? "下移" : "上移", name, index + delta + 1);
        afterChange();
    }

    private void doSetGroupAt(int index, String group) {
        SongEntry entry = store.entryAt(index);
        if (entry == null) {
            warnThrottled("第 " + (index + 1) + " 首已经不在列表里了。");
            return;
        }
        // 用相对路径当查询串：扫描只会收 notebot 目录下的**直接文件**（refresh 里 !isFile 直接跳过），
        // 所以 relative 就是一个文件名，indexOfInPlaylist 的第二轮（去扩展名的文件名全等）必然精确命中它自己。
        // store 没有"按下标打组"的入口，而打组规则（setGroup 的匹配/迁移）属于不许改的那部分，故沿用查询入口。
        if (!store.setGroup(entry.relative(), group)) {
            warnThrottled("打组失败：列表里找不到「" + entry.relative() + "」。");
            return;
        }
        info("MusicBot 已把「%s」打进组：%s", store.displayNameAt(index),
            group == null || group.isBlank() ? "（无分组）" : group);
        afterChange();
    }

    /** 列表改动后的统一收尾：随机袋子重填（列表长度变了，旧位置会指错歌）+ 按需落盘。 */
    private void afterChange() {
        if (playMode.get() == PlayMode.SHUFFLE) store.reshuffle();
        if (autosave.get()) store.save();
    }

    // ================================================================ 小工具

    private Notebot notebot() {
        try {
            return Modules.get().get(Notebot.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 状态串是不是"正在放歌"。Notebot.getStatus() 的原文见 Notebot.java:605。 */
    private static boolean isNotebotPlaying(String status) {
        return status != null && status.startsWith("Playing song");
    }

    /** 组过滤开着时，这条属不属于当前组。 */
    private boolean groupMatches(SongEntry entry) {
        if (!isGroupFilterOn()) return true;
        String want = currentGroup();
        return entry != null && entry.group() != null && entry.group().equalsIgnoreCase(want);
    }

    /** 限流提示：同一类问题 3 秒内只弹一次，避免 Notebot 没调好时聊天栏被刷爆。 */
    private void warnThrottled(String msg) {
        if (tick - lastWarnTick < 60) {
            Alog("(限流未显示) " + msg);
            return;
        }
        lastWarnTick = tick;
        warning(msg);
    }

    /**
     * 写调试日志（前缀 MUSICBOT）。
     *
     * <p><b>为什么方法体是空的</b>：这个外壳原先转交给 3cHelper+ 的日志类（独立插件不能再引用它），
     * 而它在迁移前的实现是"递归调用自己 → StackOverflowError → 被 catch 吞掉"——
     * 也就是说<b>它当时就没有产生过任何输出</b>。拆出来时保持这个现状，不擅自改变行为：
     * 模块侧的诊断日志（含「换歌写调试日志」开关）依旧是静默的。
     * 要让它们真的落进 {@code logs/latest.log}，把下面方法体换成
     * {@code MeteorClient.LOG.info("MUSICBOT {}", msg);} 即可（{@code util/MusicBotStore.log} 就是这么做的）。
     */
    private static void Alog(String msg) {
        // 2026-09-30 修：迁移前这里是 `Alog("" + msg)` —— **自己调自己**（原 AefLog.write 被
        // 某次全局替换连方法体一起换掉了），无限递归 → StackOverflowError → 被 catch(Throwable)
        // 吞掉，所以它从来没有产生过任何输出，模块侧「换歌写调试日志」这个开关一直是死的。
        // 拆成独立插件时修掉：真的写进 logs/latest.log。
        meteordevelopment.meteorclient.MeteorClient.LOG.info("MUSICBOT {}", msg);
    }

    // ================================================================
    // 下面这段是设计依据，不是可执行代码——写在这里免得以后有人"顺手"改成反射：
    //
    //   $ javap -p -classpath libs/meteor-client-1.21.11-He-20260725.jar \
    //         meteordevelopment.meteorclient.systems.modules.misc.Notebot
    //   public void play();
    //   public void pause();
    //   public void stop();
    //   public void onSongEnd();
    //   public void playRandomSong();
    //   public void disableNotebot();
    //   public void loadSong(java.io.File);
    //   public void previewSong(java.io.File);
    //   public boolean loadFileToMap(java.io.File, java.lang.Runnable);
    //   public java.lang.String getStatus();
    //   public java.lang.String getInfoString();
    //   public void updateStatus();
    //   public final Setting<...> tickDelay / polyphonic / autoRotate / autoPlay / roundOutOfRange ...
    //   private meteordevelopment.meteorclient.utils.notebot.song.Song song;   // private，别碰
    //   private boolean isPlaying;                                            // private，别碰
    //   private meteordevelopment.meteorclient.systems.modules.misc.Notebot$Stage stage; // private，别碰
    //
    // 结论：loadSong + getStatus 够用 → 零反射。哪天 Meteor 把 getStatus() 改成 private，
    // 这里会编译不过（好过运行期 NoSuchMethodError 静默失效）。
    // ================================================================
}
