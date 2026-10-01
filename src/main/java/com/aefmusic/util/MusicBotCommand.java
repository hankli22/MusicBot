package com.aefmusic.util;

import com.aefmusic.gui.MusicBotScreen;
import com.aefmusic.modules.MusicBot;
import com.aefmusic.util.MusicBotStore.PlayMode;
import com.aefmusic.util.MusicBotStore.SongEntry;
import com.aefmusic.util.MusicBotStore.SongInfo;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

import java.io.File;
import java.util.List;

/**
 * {@code .musicbot}（别名 {@code .mb}）——「MusicBot」的唯一方便操作面。
 *
 * <h3>为什么必须有个命令</h3>
 * 彗星的 Settings 只能放固定数量的控件，而播放列表是<b>变长</b>的：加歌、删歌、上移下移、
 * 打组、按组播放——这些在 GUI 里没法做成固定的几个框。所以 GUI 那边只留"模式/组过滤/立即执行"
 * 这类<b>定长</b>的东西，所有跟"第几首"有关的事都走命令。
 *
 * <h3>索引口径</h3>
 * 命令里出现的序号**一律是 1 基**，和 {@code .musicbot list} 打出来的编号一致。
 * 混用 0 基/1 基是这类工具最容易让人骂人的地方，所以这里只有一个口径。
 *
 * <h3>歌名为什么要引号</h3>
 * 用 {@code string()} 而不是 {@code greedyString()}：{@code group set <歌> <组>} 需要两个参数，
 * greedy 会把后面全吃掉。所以歌名带空格时请写引号：{@code .musicbot add "My Song"}。
 */
public class MusicBotCommand extends Command {

    /** 一次 list 最多打多少行——聊天栏不是日志文件，刷屏了反而看不见重点。 */
    private static final int MAX_LINES = 40;

    public MusicBotCommand() {
        super("musicbot", "MusicBot：播放列表/随机/分组的增删改查（歌由彗星 Notebot 播放）。", "mb");
    }

    /** 「MusicBot」模块实例；没注册时返回 null。 */
    private static MusicBot module() {
        try {
            return Modules.get().get(MusicBot.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 拿到模块，拿不到就报错并返回 true（"已处理失败"）。 */
    private boolean missing() {
        if (module() != null) return false;
        error("找不到「MusicBot」模块（可能没注册成功）。");
        return true;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {

        // ---------------------------------------------------------- 看
        builder.executes(ctx -> statusAndList());

        builder.then(literal("list").executes(ctx -> list(false)));
        builder.then(literal("listall").executes(ctx -> list(true)));

        builder.then(literal("songs").executes(ctx -> songs()));
        builder.then(literal("refresh").executes(ctx -> {
            if (missing()) return SINGLE_SUCCESS;
            info("%s", module().refreshSongs());
            return SINGLE_SUCCESS;
        }));
        builder.then(literal("status").executes(ctx -> status()));
        builder.then(literal("save").executes(ctx -> {
            if (missing()) return SINGLE_SUCCESS;
            info(module().saveNow() ? "已保存到 " + MusicBotStore.dataFile() : "保存失败，看调试日志。");
            return SINGLE_SUCCESS;
        }));

        // 图形界面。和模块里的「界面热键」等价，只是关着模块时也能用。
        // 真正开屏交给 MusicBotScreen.open()（经 Utils.screenToOpen 延迟到主线程、且没有界面开着时）。
        builder.then(literal("gui").executes(ctx -> {
            MusicBotScreen.open();
            info("已打开 MusicBot 界面（没弹出来的话，先把当前界面关掉）。");
            return SINGLE_SUCCESS;
        }));

        // ---------------------------------------------------------- 列表增删
        builder.then(literal("add")
            .then(argument("歌名", StringArgumentType.string()).executes(ctx -> {
                if (missing()) return SINGLE_SUCCESS;
                return addSong(ctx.getArgument("歌名", String.class));
            })));

        builder.then(literal("remove")
            .then(argument("歌名或序号", StringArgumentType.string()).executes(ctx -> {
                if (missing()) return SINGLE_SUCCESS;
                return removeSong(ctx.getArgument("歌名或序号", String.class));
            })));

        // 上/下移。
        // 为什么方向用 up/down 这两个词而不是 +1/-1：Brigadier 的 integer() 默认用
        // CommandReader 读整数，**不接受前导负号**，".musicbot move 3 -1" 会在解析阶段就失败，
        // 而不是进到我们的代码里。与其让用户对着报错猜，不如给两个明确的词。
        builder.then(literal("move")
            .then(argument("序号", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    if (missing()) return SINGLE_SUCCESS;
                    return move(ctx.getArgument("序号", Integer.class), 1);
                })
                .then(literal("up").executes(ctx -> {
                    if (missing()) return SINGLE_SUCCESS;
                    return move(ctx.getArgument("序号", Integer.class), -1);
                }))
                .then(literal("down").executes(ctx -> {
                    if (missing()) return SINGLE_SUCCESS;
                    return move(ctx.getArgument("序号", Integer.class), 1);
                }))
                .then(argument("位移", IntegerArgumentType.integer(0))
                    .executes(ctx -> {
                        if (missing()) return SINGLE_SUCCESS;
                        return move(ctx.getArgument("序号", Integer.class),
                            ctx.getArgument("位移", Integer.class));
                    }))));

        builder.then(literal("clear").executes(ctx -> {
            if (missing()) return SINGLE_SUCCESS;
            MusicBot m = module();
            int n = m.store().size();
            m.store().clearPlaylist();
            m.stopPlaying(false);
            m.saveNow();
            info("已清空 %d 首（含分组）。", n);
            return SINGLE_SUCCESS;
        }));

        // ---------------------------------------------------------- 播放控制
        builder.then(literal("play")
            .executes(ctx -> {
                if (missing()) return SINGLE_SUCCESS;
                MusicBot m = module();
                if (!m.isActive()) {
                    // 命令要能"一键开播"：不然用户得先去 GUI 开模块再回来敲命令
                    enableAndLoad(m);
                }
                m.playFromList();
                return SINGLE_SUCCESS;
            })
            .then(argument("序号或歌名", StringArgumentType.string()).executes(ctx -> {
                if (missing()) return SINGLE_SUCCESS;
                MusicBot m = module();
                if (!m.isActive()) enableAndLoad(m);
                return playOne(m, ctx.getArgument("序号或歌名", String.class));
            })));

        builder.then(literal("next").executes(ctx -> {
            if (missing()) return SINGLE_SUCCESS;
            module().next();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("prev").executes(ctx -> {
            if (missing()) return SINGLE_SUCCESS;
            module().previous();
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("stop").executes(ctx -> {
            if (missing()) return SINGLE_SUCCESS;
            module().stopPlaying(false);
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("mode")
            .then(literal("seq").executes(ctx -> setMode(PlayMode.SEQUENTIAL)))
            .then(literal("shuffle").executes(ctx -> setMode(PlayMode.SHUFFLE)))
            .then(literal("loop").executes(ctx -> setMode(PlayMode.LOOP))));

        // ---------------------------------------------------------- 分组
        builder.then(literal("group")
            .executes(ctx -> groups())
            .then(literal("list").executes(ctx -> groups()))
            .then(literal("show")
                .then(argument("组名", StringArgumentType.string()).executes(ctx -> showGroup(ctx.getArgument("组名", String.class)))))
            .then(literal("set")
                .then(argument("歌名", StringArgumentType.string())
                    .then(argument("组名", StringArgumentType.string()).executes(ctx -> setGroup(
                        ctx.getArgument("歌名", String.class),
                        ctx.getArgument("组名", String.class))))))
            .then(literal("add")
                .then(argument("组名", StringArgumentType.string())
                    .then(argument("匹配", StringArgumentType.string()).executes(ctx -> groupBulk(
                        ctx.getArgument("组名", String.class),
                        ctx.getArgument("匹配", String.class), false)))))
            .then(literal("from")
                .then(argument("组名", StringArgumentType.string())
                    .then(argument("匹配", StringArgumentType.string()).executes(ctx -> groupBulk(
                        ctx.getArgument("组名", String.class),
                        ctx.getArgument("匹配", String.class), true)))))
            .then(literal("play")
                .then(argument("组名", StringArgumentType.string()).executes(ctx -> playGroup(ctx.getArgument("组名", String.class)))))
            .then(literal("remove")
                .then(argument("组名", StringArgumentType.string()).executes(ctx -> removeGroup(ctx.getArgument("组名", String.class)))))
            .then(literal("rename")
                .then(argument("旧组名", StringArgumentType.string())
                    .then(argument("新组名", StringArgumentType.string()).executes(ctx -> renameGroup(
                        ctx.getArgument("旧组名", String.class),
                        ctx.getArgument("新组名", String.class))))))
            .then(literal("dissolve")
                .then(argument("组名", StringArgumentType.string()).executes(ctx -> dissolveGroup(ctx.getArgument("组名", String.class)))))
            .then(literal("off").executes(ctx -> {
                if (missing()) return SINGLE_SUCCESS;
                module().setGroupFilter("", false);
                info("组过滤已关，整个列表参与播放。");
                return SINGLE_SUCCESS;
            })));
    }

    // ================================================================= 实现

    /** 开模块并做一次目录扫描（命令第一次开播时走这里）。 */
    private void enableAndLoad(MusicBot m) {
        m.enable();
        info("%s", m.refreshSongs());
    }

    private int statusAndList() {
        status();
        return list(false);
    }

    private int status() {
        if (missing()) return SINGLE_SUCCESS;
        MusicBot m = module();
        MusicBotStore store = m.store();

        info("—— MusicBot ——");
        info("模块：%s ｜ 列表 %d 首 ｜ 组 %d 个 ｜ %s",
            m.isActive() ? "开" : "关", store.size(), store.groupNames().size(), m.describeFilter());
        info("模式：%s ｜ 目录：%s（可用 %d 首）",
            modeName(m.mode()), MusicBotStore.notebotDirText(), store.availableCount());

        String cur = m.currentDescription();
        if (cur == null) {
            info("当前：没在放。");
        } else {
            info("当前：#%d %s%s", m.currentNumber(), cur, m.isWaitingForStart() ? "（正在加载…）" : "");
        }

        // 自检段（2026-10-06 加）：专门为"长时间运行后卡死"准备的出口。
        // 用户说"又卡了"时，把下面两行截图/复制出来就能定位，不用再来回猜。
        info("自检：%s", m.diagnose());
        info("自检：Notebot 原始状态串：%s", m.notebotStatus());
        return SINGLE_SUCCESS;
    }

    private int list(boolean showAll) {
        if (missing()) return SINGLE_SUCCESS;
        MusicBotStore store = module().store();
        if (store.isEmpty()) {
            info("播放列表是空的。用 .musicbot add \"歌名\" 加歌；.musicbot songs 看目录里有什么。");
            return SINGLE_SUCCESS;
        }

        int n = store.size();
        int shown = showAll ? n : Math.min(n, MAX_LINES);
        info("—— 播放列表（%d 首%s）——", n, shown < n ? "，只显示前 " + shown : "");
        for (int i = 0; i < shown; i++) {
            SongEntry e = store.entryAt(i);
            File f = store.resolveAt(i);
            info("%d. %s%s%s", i + 1, store.displayNameAt(i),
                e != null && e.group() != null ? "  [组:" + e.group() + "]" : "",
                f == null ? "  ⚠文件缺失" : "");
        }
        if (shown < n) {
            info("…还有 %d 首，用 .musicbot listall 全看。", n - shown);
        }
        return SINGLE_SUCCESS;
    }

    private int songs() {
        if (missing()) return SINGLE_SUCCESS;
        MusicBot m = module();
        // 没扫过就先扫一次：用户第一条命令常常就是 .musicbot songs
        if (m.store().availableCount() == 0) {
            String r = m.refreshSongs();
            if (r.startsWith("扫描失败")) {
                error("%s", r);
                return SINGLE_SUCCESS;
            }
        }
        List<SongInfo> all = m.store().availableSongs();
        if (all.isEmpty()) {
            info("歌曲目录里没有 Notebot 认识的歌。把 .nbs / .txt 放进：%s",
                MusicBotStore.notebotDirText());
            return SINGLE_SUCCESS;
        }
        int shown = Math.min(all.size(), MAX_LINES);
        info("—— 可用歌曲（%d 首%s）——", all.size(), shown < all.size() ? "，只显示前 " + shown : "");
        for (int i = 0; i < shown; i++) {
            SongInfo s = all.get(i);
            info("%d. %s%s", i + 1, s.displayName(),
                s.lastTick() > 0 ? "  (" + s.lastTick() + "t)" : "");
        }
        if (shown < all.size()) info("…还有 %d 首。", all.size() - shown);
        return SINGLE_SUCCESS;
    }

    private int addSong(String query) {
        MusicBot m = module();
        MusicBotStore store = m.store();
        if (store.availableCount() == 0) {
            String r = m.refreshSongs();
            if (r.startsWith("扫描失败")) {
                error("%s", r);
                return SINGLE_SUCCESS;
            }
        }

        List<SongInfo> found = store.findAvailable(query);
        if (found.isEmpty()) {
            error("目录里没有匹配「%s」的歌。用 .musicbot songs 看有哪些。", query);
            return SINGLE_SUCCESS;
        }
        if (found.size() > 1) {
            warning("有 %d 首匹配「%s」，说得更具体一点（或用完整文件名）：", found.size(), query);
            for (int i = 0; i < Math.min(8, found.size()); i++) info("  · %s", found.get(i).displayName());
            return SINGLE_SUCCESS;
        }

        SongInfo s = found.get(0);
        String group = m.isGroupFilterOn() ? m.currentGroup() : null;
        if (!store.add(s.relative(), group)) {
            warning("「%s」已经在列表里了（第 %d 位）。", s.displayName(), store.indexOfInPlaylist(s.displayName()) + 1);
            return SINGLE_SUCCESS;
        }
        m.saveNow();
        info("已加入第 %d 位：%s%s", store.size(), s.displayName(),
            group == null ? "" : "  [组:" + group + "]");
        return SINGLE_SUCCESS;
    }

    private int removeSong(String arg) {
        MusicBot m = module();
        MusicBotStore store = m.store();

        SongEntry removed = null;
        // 先按序号理解（用户看到的就是序号），不成再当歌名
        Integer idx = parseIndex(arg, store.size());
        if (idx != null) {
            removed = store.removeAt(idx);
        } else {
            removed = store.removeByName(arg);
        }
        if (removed == null) {
            error("列表里没有「%s」（序号超范围，或歌名匹配不上）。", arg);
            return SINGLE_SUCCESS;
        }
        m.saveNow();
        info("已移除：%s（还剩 %d 首）", removed.relative(), store.size());
        return SINGLE_SUCCESS;
    }

    private int move(int oneBased, int delta) {
        MusicBot m = module();
        MusicBotStore store = m.store();
        int idx = oneBased - 1;
        if (idx < 0 || idx >= store.size()) {
            error("序号 %d 超范围（列表 %d 首）。", oneBased, store.size());
            return SINGLE_SUCCESS;
        }
        String name = store.displayNameAt(idx);
        if (!store.move(idx, delta)) {
            warning("「%s」已经在%s了，没动。", name, delta > 0 ? "最后面" : "最前面");
            return SINGLE_SUCCESS;
        }
        m.saveNow();
        // delta > 0 = 往列表后面挪 = 下移（与 store.move 的语义、Setting 的说明保持一致）
        info("已把「%s」%s到第 %d 位。", name, delta > 0 ? "下移" : "上移", idx + delta + 1);
        return SINGLE_SUCCESS;
    }

    private int playOne(MusicBot m, String arg) {
        MusicBotStore store = m.store();
        Integer idx = parseIndex(arg, store.size());
        if (idx == null) {
            int found = store.indexOfInPlaylist(arg);
            if (found < 0) {
                error("列表里找不到「%s」。", arg);
                return SINGLE_SUCCESS;
            }
            idx = found;
        }
        m.playIndex(idx, true);
        return SINGLE_SUCCESS;
    }

    private int setMode(PlayMode mode) {
        if (missing()) return SINGLE_SUCCESS;
        module().setMode(mode);
        info("播放模式：%s。", modeName(mode));
        return SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------- 分组

    private int groups() {
        if (missing()) return SINGLE_SUCCESS;
        MusicBotStore store = module().store();
        List<String> names = store.groupNames();
        if (names.isEmpty()) {
            info("还没有任何分组。用 .musicbot group from \"组名\" \"匹配\" 整批加歌并打组。");
            return SINGLE_SUCCESS;
        }
        info("—— 分组（%d 个）——", names.size());
        for (String g : names) {
            info("· %s（%d 首）%s", g, store.groupSize(g),
                g.equalsIgnoreCase(module().currentGroup()) && module().isGroupFilterOn() ? "  ← 正在只播这个组" : "");
        }
        return SINGLE_SUCCESS;
    }

    private int showGroup(String group) {
        if (missing()) return SINGLE_SUCCESS;
        MusicBotStore store = module().store();
        List<SongEntry> entries = store.groupEntries(group);
        if (entries.isEmpty()) {
            error("组「%s」不存在或是空的。.musicbot group list 看有哪些组。", group);
            return SINGLE_SUCCESS;
        }
        info("—— 组「%s」（%d 首）——", group, entries.size());
        for (int i = 0; i < Math.min(entries.size(), MAX_LINES); i++) {
            SongEntry e = entries.get(i);
            File f = store.resolve(e.relative());
            info("%d. %s%s", i + 1, displayOf(store, e.relative()), f == null ? "  ⚠文件缺失" : "");
        }
        return SINGLE_SUCCESS;
    }

    private int setGroup(String song, String group) {
        MusicBot m = module();
        MusicBotStore store = m.store();

        Integer idx = parseIndex(song, store.size());
        String target = "none".equalsIgnoreCase(group) || "无".equals(group) ? null : group;
        boolean ok;
        if (idx != null) {
            SongEntry e = store.entryAt(idx);
            ok = e != null && store.setGroup(e.relative(), target);
        } else {
            ok = store.setGroup(song, target);
        }
        if (!ok) {
            error("没找到匹配「%s」的歌。", song);
            return SINGLE_SUCCESS;
        }
        m.saveNow();
        info("已把「%s」的组设为：%s", song, target == null ? "（无分组）" : target);
        return SINGLE_SUCCESS;
    }

    /**
     * 整批打组。
     *
     * @param alsoAdd true = 先从歌曲目录里把匹配的歌加进列表（{@code group from}）；
     *                false = 只在**已在列表里**的歌里打组（{@code group add}）
     */
    private int groupBulk(String group, String query, boolean alsoAdd) {
        MusicBot m = module();
        MusicBotStore store = m.store();

        int hit = 0;
        if (alsoAdd) {
            if (store.availableCount() == 0) m.refreshSongs();
            for (SongInfo s : store.findAvailable(query)) {
                if (store.add(s.relative(), group)) hit++;
                else if (store.setGroup(s.relative(), group)) hit++;
            }
        } else {
            // 从后往前改，避免边遍历边改下标
            for (int i = store.size() - 1; i >= 0; i--) {
                SongEntry e = store.entryAt(i);
                if (e == null) continue;
                if (displayOf(store, e.relative()).toLowerCase().contains(query.toLowerCase())
                    || e.relative().toLowerCase().contains(query.toLowerCase())) {
                    if (store.setGroup(e.relative(), group)) hit++;
                }
            }
        }
        if (hit == 0) {
            warning("没有匹配「%s」的歌被打进组「%s」。%s", query, group,
                alsoAdd ? "用 .musicbot songs 看目录里有什么。" : "用 .musicbot group from 从目录里整批加。");
            return SINGLE_SUCCESS;
        }
        m.saveNow();
        info("已把 %d 首打进组「%s」（该组现在 %d 首）。", hit, group, store.groupSize(group));
        return SINGLE_SUCCESS;
    }

    private int playGroup(String group) {
        MusicBot m = module();
        MusicBotStore store = m.store();
        if (store.groupSize(group) == 0) {
            error("组「%s」不存在或是空的。.musicbot group list 看有哪些组。", group);
            return SINGLE_SUCCESS;
        }
        m.setGroupFilter(group, true);
        if (!m.isActive()) enableAndLoad(m);
        info("只播组「%s」（%d 首）。", group, store.groupSize(group));
        m.playFromList();
        return SINGLE_SUCCESS;
    }

    private int removeGroup(String group) {
        MusicBot m = module();
        int n = m.store().removeGroup(group);
        if (n <= 0) {
            error("组「%s」不存在或是空的。", group);
            return SINGLE_SUCCESS;
        }
        m.saveNow();
        info("已删除组「%s」及其 %d 首歌。", group, n);
        return SINGLE_SUCCESS;
    }

    private int dissolveGroup(String group) {
        MusicBot m = module();
        int n = m.store().dissolveGroup(group);
        if (n <= 0) {
            error("组「%s」不存在或是空的。", group);
            return SINGLE_SUCCESS;
        }
        m.saveNow();
        info("已解散组「%s」，%d 首歌留在列表里（变成未分组）。", group, n);
        return SINGLE_SUCCESS;
    }

    private int renameGroup(String from, String to) {
        MusicBot m = module();
        int n = m.store().renameGroup(from, to);
        if (n <= 0) {
            error("组「%s」不存在或是空的。", from);
            return SINGLE_SUCCESS;
        }
        m.saveNow();
        info("已把组「%s」改名为「%s」（%d 首）。", from, to, n);
        return SINGLE_SUCCESS;
    }

    // ---------------------------------------------------------- 小工具

    /** 显示名（走 store 的缓存，不会为了打印一行去重读 NBS）。 */
    private static String displayOf(MusicBotStore store, String relative) {
        int idx = store.indexOfInPlaylist(relative);
        return idx >= 0 ? store.displayNameAt(idx) : relative;
    }

    /** "3" → 下标 2。不是纯数字或者超范围都返回 null（交给调用方按歌名再试一次）。 */
    private static Integer parseIndex(String arg, int size) {
        if (arg == null) return null;
        String s = arg.trim();
        if (s.isEmpty() || s.length() > 6) return null;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return null;
        }
        try {
            int oneBased = Integer.parseInt(s);
            if (oneBased < 1 || oneBased > size) return null;
            return oneBased - 1;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String modeName(PlayMode mode) {
        if (mode == null) return "顺序";
        return switch (mode) {
            case SEQUENTIAL -> "顺序（放完一轮停）";
            case SHUFFLE -> "随机（一轮内不重复）";
            case LOOP -> "循环";
        };
    }
}
