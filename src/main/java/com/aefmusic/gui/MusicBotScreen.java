package com.aefmusic.gui;

import com.aefmusic.modules.MusicBot;
import com.aefmusic.util.MusicBotStore;
import com.aefmusic.util.MusicBotStore.SongEntry;
import com.aefmusic.util.MusicBotStore.SongInfo;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * MusicBot 的图形界面：把原来只能在聊天栏里敲的东西（播放列表、分组、添加歌曲）摊到屏幕上。
 *
 * <h3>这个类只做三件事</h3>
 * <ol>
 * <li><b>发指令</b>：每个按钮都只调 {@code module.guiXxx(...)}，由它按既有约定入队
 * （{@code enqueue(Op)}），真正的执行仍然在 {@code MusicBot} 的 {@code TickEvent.Post} 里。
 * 屏幕**不碰** Notebot、**不自己改** store 的播放列表——否则就等于另开了一条"在渲染帧里动世界/写盘"的
 * 路径，而这正是那个操作队列存在的意义。</li>
 * <li><b>显示状态</b>：歌名/分组/当前曲目都从 store 只读地读；底部那条原始状态串来自
 * {@code Notebot.getStatus()}（经 {@code module.notebotStatus()} 转发）。</li>
 * <li><b>查找</b>（2026-10-01 加）：把「扫目录 + 找歌」丢给后台线程（{@code module.guiSearch}），
 * 自己只在每帧读一次结果。渲染帧里<b>没有任何文件 IO、没有任何 NBS 解析</b>——包括歌名那一列
 * 用的也是不解析文件的 {@code store.displayNameShallow}。这是「搜索改异步」那条修复的底线。</li>
 * </ol>
 * 唯一一处例外是 {@code module.pollSearch()}：它在客户端线程上把后台结果并进 store
 * （模块关着时界面是唯一的合并点；见 {@code MusicBot.pollSearch} 的注释）——合并的是数据，不是控件。
 *
 * <h3>为什么所有"重建控件"都挤在 {@link #onRenderBefore} 里</h3>
 * 按钮/输入框的回调是在 {@code WContainer} 遍历自己的 cells 时分发的，在回调里
 * {@code clear()}/{@code add()} 正在被遍历的那个表就是并发修改。而 {@code onRenderBefore} 由
 * {@code WidgetScreen.renderCustom} 在"画控件树之前"每帧调一次（见类末尾的字节码依据），
 * 那里改树既安全、又能在同一帧就重新排版（{@code WContainer.add/clear} → {@code invalidate()}
 * → 根节点下一帧 render 时重算 size/positions）。
 *
 * <h3>怎么知道"列表变了"</h3>
 * 队列里的 Op 要等到下一个 tick 才执行，屏幕这边没有回调可挂，所以每帧算一个廉价指纹
 * （长度 + 每条的相对路径/组 + 当前播放序号 + 组过滤状态），变了就重建行。这样无论改动来自
 * 这个界面、聊天命令还是模块设置，屏幕都会在一帧内跟上。
 */
public class MusicBotScreen extends WindowScreen {

    /** 「添加歌曲」一次最多列几条匹配——和 {@code .musicbot add} 里的 8 条保持一致。 */
    private static final int MAX_MATCHES = 8;

    /**
     * 歌名在界面上最多显示多少个字符。
     *
     * <p>为什么要截：窗口宽度是"内容撑出来的"，一个 100 多字的 NBS 标题会把窗口撑得比屏幕还宽，
     * 两边的按钮就跑到屏幕外点不到了。截断只影响显示，命令/落盘里用的仍是完整名字。
     */
    private static final int MAX_NAME_CHARS = 48;

    /** 模块实例。理论上必然存在（addon 注册过），真拿不到时界面只显示一句提示而不是崩掉。 */
    private final MusicBot module;

    // ---- 每帧刷新的"活"文字 ----
    private WLabel nowLabel;
    private WLabel filterLabel;
    private WLabel statusLabel;
    private WLabel addHintLabel;

    // ---- 需要整体重建的表格 ----
    private WTable songTable;
    private WTable groupTable;
    private WTable matchesTable;

    // ---- 输入框 ----
    private WTextBox groupNameBox;
    private WTextBox addBox;

    /** 「查找」按钮：搜索进行中要把文案改成「搜索中…」，所以要留着引用。 */
    private WButton searchButton;

    /** 当前显示在「查找」按钮上的文案（只在真的变了时才 set：{@code WButton.set} 会重算宽度并 invalidate）。 */
    private String searchButtonText = "";

    /** 上一次的列表指纹，变了就重建歌曲行/组行。 */
    private long lastFingerprint = Long.MIN_VALUE;

    /** 「查找」被按下（回车或点按钮）→ 下一帧发起一次异步搜索。 */
    private boolean searchRequested;

    /** 搜索是不是需要重做（真正的查询推迟到 {@link #onRenderBefore} 里发起）。 */
    private boolean matchesDirty = true;

    /** 上次画结果表时的搜索状态版本号；变了就重建（后台结果回来了）。 */
    private int lastSearchVersion = Integer.MIN_VALUE;

    public MusicBotScreen(GuiTheme theme) {
        super(theme, "MusicBot 播放列表");
        this.module = module();
    }

    /**
     * 打开界面。热键和 {@code .musicbot gui} 都走这里。
     *
     * <p><b>为什么不是 {@code mc.setScreen(...)}</b>：命令是从聊天栏发出去的，那一瞬间
     * {@code currentScreen} 还是聊天界面，直接设屏会被聊天界面的关闭顶掉。彗星自己有这个口子：
     * {@code Utils.screenToOpen} 会在 {@code Utils.onTick(TickEvent.Post)} 里、且"当前没有任何界面"
     * 时才真正 {@code setScreen}（{@code .setting} 命令用的就是它）。热键走同一条路也无副作用——
     * 只是晚一个 tick 弹出来。顺带它是**在主线程的执行点开屏**，不存在跨线程开屏的问题。
     */
    public static void open() {
        Utils.screenToOpen = new MusicBotScreen(GuiThemes.get());
    }

    private static MusicBot module() {
        try {
            return Modules.get().get(MusicBot.class);
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ 搭界面

    @Override
    public void initWidgets() {
        if (module == null) {
            add(theme.label("找不到「MusicBot」模块（可能没注册成功），这个界面没法用。"));
            return;
        }

        // ---------------------------------------------------------- 顶部：播放控制
        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();
        button(controls, "播放", module::guiPlayAll);
        button(controls, "暂停/继续", module::guiPause);
        button(controls, "停止", module::guiStop);
        button(controls, "上一首", module::guiPrev);
        button(controls, "下一首", module::guiNext);
        // 关闭按钮：WidgetScreen.close() 只把 closing 置位，真正的关闭发生在 renderCustom 里，
        // 所以在按钮回调里调它是安全的（不用自己去碰 mc.setScreen）
        button(controls, "关闭", this::close);

        nowLabel = add(theme.label("")).expandX().widget();

        // ---------------------------------------------------------- 歌曲列表
        add(theme.horizontalSeparator("歌曲列表"));

        WHorizontalList groupTargetRow = add(theme.horizontalList()).expandX().widget();
        groupTargetRow.add(theme.label("打组目标："));
        // 默认填当前的组过滤名：开着「只播 A 组」时点「打组」多半就是想打进 A
        groupNameBox = groupTargetRow.add(theme.textBox(module.currentGroup(), "组名（留空 = 取消分组）"))
            .expandX().widget();
        groupTargetRow.add(theme.label("← 点某行的「打组」就用这里的名字"));

        // 歌曲列表是本屏唯一需要滚动的区域：用一个 WView 把它限高，上面的控制和下面的状态行
        // 就不会跟着滚走。maxHeight 的算法抄自彗星自己的 FontFaceSettingScreen（窗口视图高度 - 128）。
        WView listView = add(theme.view()).expandX().widget();
        listView.maxHeight = window.view.maxHeight - 128;
        // true：只在鼠标停在列表上时才吃掉滚轮。列表本身没得滚（内容比 maxHeight 短）时
        // WView.onMouseScrolled 会返回 false，滚轮就会落回外层窗口视图，外层照样能滚。
        listView.scrollOnlyWhenMouseOver = true;
        listView.hasScrollBar = true;

        songTable = listView.add(theme.table()).expandX().widget();

        // ---------------------------------------------------------- 分组面板
        add(theme.horizontalSeparator("分组"));
        filterLabel = add(theme.label("")).expandX().widget();
        groupTable = add(theme.table()).expandX().widget();

        // ---------------------------------------------------------- 添加歌曲
        add(theme.horizontalSeparator("添加歌曲"));
        WHorizontalList addRow = add(theme.horizontalList()).expandX().widget();
        addRow.add(theme.label("歌名："));
        addBox = addRow.add(theme.textBox("", "可只填一部分，回车或点「查找」")).expandX().widget();
        // 回调里只置个标记：这时候控件树正在被遍历，不能动它（真正的查询发起在 onRenderBefore 里）
        addBox.action = this::onSearchPressed;
        searchButton = addRow.add(theme.button("查找")).widget();
        searchButton.action = this::onSearchPressed;
        searchButtonText = "查找";

        addHintLabel = add(theme.label("")).expandX().widget();
        matchesTable = add(theme.table()).expandX().widget();

        // ---------------------------------------------------------- 底部：Notebot 原始状态
        add(theme.horizontalSeparator());
        statusLabel = add(theme.label("")).expandX().widget();

        // 首次进屏就把内容填上（onRenderBefore 也会兜底重建，这里只是省掉第一帧的空屏）
        rebuildSongRows();
        rebuildGroupRows();
    }

    /** 同一个工厂写法的短封装：加一个按钮并挂上动作。 */
    private void button(WHorizontalList row, String text, Runnable action) {
        WButton button = row.add(theme.button(text)).widget();
        button.action = action;
    }

    /** 「查找」被按下（点按钮或输入框里回车）：只置标记，下一帧才动控件树/发请求。 */
    private void onSearchPressed() {
        searchRequested = true;
    }

    // ================================================================ 每帧：刷新文字 + 必要时重建

    @Override
    protected void onRenderBefore(DrawContext drawContext, float delta) {
        if (module == null) return;

        // 后台扫描/搜索的结果在这里合并进 store（客户端线程；模块开着时 tick 里已经合并过了）。
        // 这一句只是"取信箱"，取不到就是 O(1) 的空转，不会阻塞渲染。
        module.pollSearch();

        updateLiveLabels();

        if (searchRequested) {
            searchRequested = false;
            startSearch();
            matchesDirty = true;   // 无论查不查，结果区都要按新状态重画一次
        }
        updateSearchButton();

        int version = module.searchVersion();
        if (matchesDirty || version != lastSearchVersion) {
            matchesDirty = false;
            lastSearchVersion = version;
            rebuildMatches();
        }

        long fingerprint = fingerprint();
        if (fingerprint != lastFingerprint) {
            lastFingerprint = fingerprint;
            rebuildSongRows();
            rebuildGroupRows();
        }
    }

    /**
     * 发起一次异步查找。**立即返回**：真正列目录/读文件/解析元数据在后台线程，
     * 结果下次进来时由 {@link #rebuildMatches()} 铺成控件。
     */
    private void startSearch() {
        String query = addBox == null ? "" : addBox.get().trim();
        if (query.isEmpty()) return;    // 空查询不发请求：省掉一次白扫目录，结果区会给提示
        module.guiSearch(query);
    }

    /**
     * 「查找」按钮的文案随搜索状态变化。
     *
     * <p>只在文案真的变了时才 {@code set}：{@code WButton.set} 会重新量一次文字宽度并
     * {@code invalidate()} 整棵树（javap 核实），每帧无条件调它等于每帧重排一次界面。
     */
    private void updateSearchButton() {
        if (searchButton == null) return;
        String want = module.isSearching() ? "搜索中…" : "查找";
        if (!want.equals(searchButtonText)) {
            searchButtonText = want;
            searchButton.set(want);
        }
    }

    /** 结果区那句提示。 */
    private void hint(String text) {
        if (addHintLabel != null) addHintLabel.set(text);
    }

    /**
     * 界面关了：不再有任何东西会去碰这个界面。
     *
     * <p>后台任务从来只往自己的信箱里放结果，一个控件都不碰，所以晚到的结果不可能 NPE；
     * 这里只需要把"还没发出去的查找请求"丢掉（界面关了就不该再发起新的扫描），
     * 以及把待办结果留给模块在 tick 里合并（它合并的是 store，不是控件）。
     */
    @Override
    protected void onClosed() {
        super.onClosed();
        searchRequested = false;
    }

    /** 只改文字、不动控件树的那几条：状态串每 tick 都在变，所以必须每帧刷。 */
    private void updateLiveLabels() {
        if (nowLabel != null) {
            int current = module.currentNumber();
            int total = module.store().size();
            if (current <= 0) {
                nowLabel.set("当前：没在放　（列表 " + total + " 首）");
            } else {
                // shallow 版：这一行每帧都要刷，用会解析 NBS 文件的那个版本就等于每帧都可能读一次盘
                String desc = module.currentDescriptionShallow();
                nowLabel.set("当前：#" + current + "/" + total + "　" + (desc == null ? "" : desc)
                    + (module.isWaitingForStart() ? "（正在加载…）" : ""));
            }
        }

        if (filterLabel != null) {
            filterLabel.set("组过滤：" + (module.isGroupFilterOn()
                ? "只播组「" + module.currentGroup() + "」（" + module.store().groupSize(module.currentGroup()) + " 首）"
                : "关（整个列表参与播放）"));
        }

        if (statusLabel != null) {
            // 「一首放完」的判定就靠这个字符串：原样亮出来，出问题时一眼能看到卡在哪一步
            statusLabel.set("Notebot 原始状态：" + module.notebotStatus());
        }
    }

    /**
     * 列表内容指纹。只读、O(n) 的两三个 hash，每帧算一次的开销可以忽略；
     * 但它能覆盖"界面改的 / 命令改的 / 设置改的"三种来源。
     */
    private long fingerprint() {
        MusicBotStore store = module.store();
        long h = 17;
        h = h * 31 + store.size();
        for (int i = 0; i < store.size(); i++) {
            SongEntry e = store.entryAt(i);
            h = h * 31 + (e == null ? 0 : e.relative().hashCode());
            h = h * 31 + (e == null || e.group() == null ? 0 : e.group().hashCode());
        }
        h = h * 31 + module.currentNumber();
        h = h * 31 + (module.isWaitingForStart() ? 1 : 0);
        h = h * 31 + (module.isGroupFilterOn() ? 1 : 0);
        h = h * 31 + module.currentGroup().hashCode();
        return h;
    }

    // ================================================================ 歌曲列表

    private void rebuildSongRows() {
        if (songTable == null) return;
        songTable.clear();

        MusicBotStore store = module.store();
        if (store.isEmpty()) {
            songTable.add(theme.label("列表是空的：用下面的「添加歌曲」按歌名加几首（歌来自 notebot 目录）。"))
                .expandCellX();
            songTable.row();
            return;
        }

        for (int i = 0; i < store.size(); i++) addSongRow(i);
    }

    private void addSongRow(int index) {
        MusicBotStore store = module.store();
        boolean playing = module.currentNumber() == index + 1;

        songTable.add(theme.label((index + 1) + "."));
        // expandCellX 让歌名这一列吃掉剩余宽度，后面的按钮就被推到右边。
        // 用 displayNameShallow 而不是 displayNameAt：后者缓存未命中时会读整个 NBS 文件，
        // 而这是每行都要画一次的地方（渲染帧里一行 IO 都不许有）。
        songTable.add(theme.label(shortName(store.displayNameShallow(index)))).expandCellX();

        SongEntry entry = store.entryAt(index);
        songTable.add(theme.label(entry == null || entry.group() == null ? "—" : entry.group()));

        if (playing) {
            songTable.add(theme.label(module.isWaitingForStart() ? "▶ 加载中" : "▶ 正在播"));
        } else {
            songTable.add(theme.label(store.resolveAt(index) == null ? "⚠ 文件缺失" : ""));
        }

        // 下面每个按钮都只是"入队一个 Op"，参数是这个行号；行号在列表变动后由指纹触发重建来刷新
        WButton play = songTable.add(theme.button("播放")).widget();
        play.action = () -> module.guiPlayIndex(index);

        WButton remove = songTable.add(theme.button("移除")).widget();
        remove.action = () -> module.guiRemoveAt(index);

        WButton up = songTable.add(theme.button("上移")).widget();
        up.action = () -> module.guiMoveAt(index, -1);

        WButton down = songTable.add(theme.button("下移")).widget();
        down.action = () -> module.guiMoveAt(index, 1);

        WButton group = songTable.add(theme.button("打组")).widget();
        group.action = () -> {
            // 打组名从上面那个输入框现取：屏幕上没有"弹窗输入"这种控件，共用一格最省事
            String name = groupNameBox == null ? "" : groupNameBox.get().trim();
            module.guiSetGroupAt(index, name.isEmpty() ? null : name);
        };

        songTable.row();
    }

    // ================================================================ 分组面板

    private void rebuildGroupRows() {
        if (groupTable == null) return;
        groupTable.clear();

        MusicBotStore store = module.store();
        List<String> names = store.groupNames();
        if (names.isEmpty()) {
            groupTable.add(theme.label("还没有任何分组：把上面的「打组目标」填个组名，再点某行的「打组」。"))
                .expandCellX();
            groupTable.row();
            return;
        }

        for (String name : names) {
            boolean active = module.isGroupFilterOn() && name.equalsIgnoreCase(module.currentGroup());

            groupTable.add(theme.label(name));
            groupTable.add(theme.label("（" + store.groupSize(name) + " 首）"));
            groupTable.add(theme.label(active ? "← 正在只播这个组" : "")).expandCellX();

            WButton only = groupTable.add(theme.button(active ? "取消过滤" : "只播这个组")).widget();
            if (active) {
                only.action = () -> module.setGroupFilter("", false);
            } else {
                only.action = () -> module.setGroupFilter(name, true);
            }

            groupTable.row();
        }
    }

    // ================================================================ 添加歌曲

    /**
     * 把"查找"的结果铺成控件：输入什么词、匹配到哪几首、点「加入」把哪首加进列表。
     *
     * <p><b>2026-10-01 改（用户实测原话「搜索改异步啊喂卡死了」）</b>：这里原来是同步的——
     * {@code module.refreshSongs()}（列目录）+ {@code store.findAvailable()}（逐个解析 NBS 元数据）
     * 就发生在这一帧里，一屏几十个文件读下来游戏整个卡住。现在这个方法<b>一行 IO 都没有</b>：
     * 它只读 {@link MusicBot#searchMatches()}（后台已经算好的不可变列表）和状态标记，
     * 真正的读盘/解析在 {@code MusicBotStore.scan} 里、由后台线程跑（见 {@code MusicBotSearch}）。
     *
     * <p>四种画法：没输入 → 提示；正在查 → 「搜索中…」；查失败 → 失败原因；查完 → 结果列表。
     * 匹配语义（精确优先、去扩展名再比一次）与原来完全一致，只是搬到了后台。
     */
    private void rebuildMatches() {
        if (matchesTable == null) return;
        matchesTable.clear();

        String query = addBox == null ? "" : addBox.get().trim();
        if (query.isEmpty()) {
            hint("输入歌名的一部分（回车或点「查找」），匹配到的歌会列在下面。");
            return;
        }

        if (module.isSearching()) {
            hint("搜索中…（在后台扫目录 + 读 NBS 元数据，界面不会卡）");
            return;
        }

        String error = module.searchError();
        if (error != null) {
            hint("搜索失败：" + error + "　用 .musicbot songs 看目录里有什么。");
            return;
        }

        List<SongInfo> found = module.searchMatches();
        if (found.isEmpty()) {
            hint("目录里没有匹配「" + query + "」的歌（已扫到 " + module.store().availableCount()
                + " 首）。用 .musicbot songs 看目录里有什么。");
            return;
        }

        hint("匹配 " + found.size() + " 首，点「加入」把它加进列表末尾"
            + (module.isGroupFilterOn() ? "（会顺手打进组「" + module.currentGroup() + "」）" : "") + "：");

        int shown = Math.min(found.size(), MAX_MATCHES);
        for (int i = 0; i < shown; i++) {
            SongInfo song = found.get(i);
            matchesTable.add(theme.label(shortName(song.displayName()))).expandCellX();
            matchesTable.add(theme.label(song.lastTick() > 0 ? song.lastTick() + "t" : ""));

            // 传相对路径而不是显示名：Op.AddSong 内部还是走模糊匹配，而相对路径（含扩展名）
            // 在 findAvailable 里是"精确命中"，不会因为重名把另一首加进来
            String relative = song.relative();
            WButton add = matchesTable.add(theme.button("加入")).widget();
            add.action = () -> module.guiAddSong(relative);

            matchesTable.row();
        }

        if (shown < found.size()) {
            matchesTable.add(theme.label("…还有 " + (found.size() - shown) + " 首，把歌名写得更具体一点。"))
                .expandCellX();
            matchesTable.row();
        }
    }

    /** 歌名最多显示多少**列**（不是字符数）。见 {@link #shortName}。 */
    private static final int NAME_DISPLAY_COLS = 22;

    /**
     * 只影响显示的截断。
     *
     * <p><b>2026-10-01 修（用户实测截图：「修复文字超出边框」）。</b>原来按**字符数**截断，
     * 但这一格是 {@code expandCellX()}（吃掉剩余宽度），名字比格子宽就直接压到「组」和按钮上 ——
     * 截图里 {@code [BnA]OST47]CoffeeCats[exactl1} 正压在组号上，
     * {@code CBsenrenBanka]KoiiKoiiFuen} 也一样。
     *
     * <p>为什么只能靠截断：彗星的 {@code Cell} <b>没有 maxWidth</b>（javap 确认，只有
     * {@code minWidth}），所以没法给那一格设上限宽度 —— 只能把字符串先截短到"一定放得下"。
     *
     * <p>按**显示列数**算：CJK/全角占 2 列，其余占 1 列。实测该宽度下约 22 列放得下，
     * 而原来的 48 <b>字符</b>在纯 ASCII 名字上就是一整行放不下（48 列）。
     */
    private static String shortName(String name) {
        if (name == null) return "?";
        int cols = 0;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); ) {
            final int cp = name.codePointAt(i);
            final int w = isWideCodePoint(cp) ? 2 : 1;
            if (cols + w > NAME_DISPLAY_COLS) {
                return sb.append('…').toString();
            }
            sb.appendCodePoint(cp);
            cols += w;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** 全角 / CJK：这些码位在 Minecraft 的默认字体里占两列。 */
    private static boolean isWideCodePoint(final int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
            || (cp >= 0x2E80 && cp <= 0xA4CF)
            || (cp >= 0xAC00 && cp <= 0xD7A3)
            || (cp >= 0xF900 && cp <= 0xFAFF)
            || (cp >= 0xFE30 && cp <= 0xFE6F)
            || (cp >= 0xFF00 && cp <= 0xFF60)
            || (cp >= 0xFFE0 && cp <= 0xFFE6);
    }

    // ================================================================
    // 本类用到的彗星 API 全部先用 javap 从 libs/meteor-client-1.21.11-He-20260725.jar 核实过
    // （remapped 成 yarn 名的那份：.gradle/loom-cache/remapped_mods/.../db7a2de464.jar）
    //
    //   WindowScreen(GuiTheme, String)                  // 标题栏/窗口由它自己建
    //   WindowScreen.add(WWidget) -> Cell<WWidget>       // 转发给 window.add -> window.view.add
    //   Cell.minWidth(double) / expandX() / pad(double) / expandCellX() / widget()
    //   GuiTheme.label(String) / button(String) / textBox(String text, String placeholder)
    //   GuiTheme.horizontalList() / table() / view() / horizontalSeparator(String)
    //   WPressable.action : Runnable                     // onMouseReleased 里 onPressed(button) 之后 run()
    //   WTextBox.action : Runnable / get() / set(String) / setFocused(boolean)
    //   WView.maxHeight / hasScrollBar / scrollOnlyWhenMouseOver : 都是 public 字段
    //   WTable.add(WWidget) -> Cell / row() / clear()
    //   WidgetScreen.renderCustom(DrawContext,int,int,float) 在画树之前调 onRenderBefore(DrawContext,float)
    //   WidgetScreen.close() 只把 closing 置位（真正的关闭在 renderCustom 里），所以能放在按钮回调里
    //   Utils.screenToOpen : public static Screen，由 Utils.onTick(TickEvent.Post) 在 currentScreen==null 时开屏
    //
    // 2026-10-01（改异步搜索）又核实了这几条：
    //   WidgetScreen.removed() 里调 onClosed()  —— protected，可覆写；界面关闭时用它丢待办请求
    //   WButton.set(String)    重算 textWidth + invalidate —— 所以只在文案真的变了时才调
    //   MeteorExecutor.executor : public static ExecutorService（newCachedThreadPool + setDaemon(true)，
    //                          线程名 "Meteor-Executor-N"）——后台慢活走它，不自己 new Thread
    //   ChatUtils.info/warning/error → sendMsg(...) → MinecraftClient.execute(Runnable)
    //                          ——所以后台线程里万一触发 Notebot.warning 也只是把消息转交主线程
    //
    // 另外两条判定依据（决定了这里的 UI 行为，都是从字节码里读出来的）：
    //   Notebot.getStatus()  "Playing song. x/y" / "Ready to play." / "No song loaded." / "Module disabled."
    //   Notebot.pause()      isPlaying = !isPlaying —— 是"切换"，不是"只暂停"
    // ================================================================
}
