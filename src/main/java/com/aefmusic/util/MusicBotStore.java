package com.aefmusic.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.notebot.decoder.SongDecoders;
import meteordevelopment.meteorclient.utils.notebot.song.Song;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * 「MusicBot」的数据层：可用歌曲索引 + 有序播放列表 + 分组，以及它们的 JSON 落盘。
 *
 * <h3>为什么单独一个类</h3>
 * 播放这件事已经由彗星自带的 Notebot 模块负责（{@code Notebot.loadSong/play/stop} 都是 public，
 * 见 {@code Notebot.java}）。本模块只做"选曲"——所以把"选什么"的状态和"怎么放"的执行分开：
 * 状态全在这里，模块只负责在恰当的时机把 {@link #resolve(String)} 出来的文件交给 Notebot。
 *
 * <h3>落盘位置</h3>
 * {@code <gameDir>/musicbot/musicbot.json}。用 FabricLoader 的游戏目录而不是 {@code MeteorClient.FOLDER}，
 * 因为后者在极早期可能还没初始化；扫歌曲目录时才用 {@code MeteorClient.FOLDER}（那时必然已就绪）。
 *
 * <h3>关于"歌曲没了"</h3>
 * 列表里存的是相对 {@code <gameDir>/meteor-client/notebot/} 的相对路径。文件被删掉后条目**保留**
 * 并在解析时标 {@code missing}，而不是自动剔除——用户换个整合包/改个歌名只是暂时对不上，
 * 自动删掉等于替他决定，找不回来。要清理由用户自己 {@code .musicbot remove}。
 *
 * <h3>线程约定（2026-10-01 加，改之前先读这段）</h3>
 * 这个类的所有可变字段（{@link #playlist} / {@link #groups} / {@link #available} /
 * {@link #displayNames} / {@link #metaCache} / 随机袋子 / 过滤器）**只被 Minecraft 客户端线程读写**：
 * 它既跑 {@code TickEvent.Post}（Op 队列的执行点），也跑界面渲染（{@code onRenderBefore}），
 * 是同一条线程，所以这些字段之间不存在并发访问。
 *
 * <p>慢活（列目录 / 读整个 NBS 文件）如果压在客户端线程上就是掉帧甚至卡死，所以异步路径这么分工：
 * <ol>
 * <li>主线程用 {@link #snapshotForScan()} 做一份<b>只读快照</b>（浅拷贝，不读盘）；</li>
 * <li>后台线程跑 {@link #scan(ScanInput, String)}——它<b>是 static 的、拿不到 this</b>，
 * 只碰快照和局部变量，产出不可变的 {@link ScanResult}；</li>
 * <li>主线程用 {@link #applyScan(ScanResult)} 把结果并回来，这是异步路径上唯一改字段的地方。</li>
 * </ol>
 * 快照里那两个 map 的值（{@code String[]}）一旦放进缓存就不再被改写，所以后台只读它们也是安全的。
 * 编排代码在 {@link MusicBotSearch}。
 */
public final class MusicBotStore {

    /** 存储与列表的第 1 版结构；将来改字段时靠它做迁移判断。 */
    public static final int SCHEMA = 1;

    /** 歌曲目录名（相对 {@code MeteorClient.FOLDER}）——与 Notebot.playRandomSong 用的是同一个目录。 */
    private static final String NOTEBOT_DIR_NAME = "notebot";

    /** 本模块自己的数据目录名（相对 FabricLoader 游戏目录）。 */
    private static final String DATA_DIR_NAME = "musicbot";

    private static final String FILE_NAME = "musicbot.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 一条播放列表条目。
     *
     * @param relative 相对 notebot 目录的路径。用相对路径而不是绝对路径，
     *                 否则换个启动器/换台机器（游戏目录变了）整份列表就全废了。
     * @param group    所属分组名；{@code null} = 未分组。
     */
    public record SongEntry(String relative, String group) {
        public SongEntry withGroup(String newGroup) {
            return new SongEntry(relative, newGroup);
        }
    }

    /**
     * 歌曲目录里的一首歌。
     *
     * @param displayName 显示名（默认用去扩展名的文件名；能解出 NBS 元数据时用标题）
     * @param missing     列表条目指向的文件已不存在
     * @param group       所属分组（扫描出来的候选歌曲没有分组，这个字段给"播放列表里那一份"用）
     */
    public record SongInfo(File file, String relative, String displayName, String title, String author,
                           int lastTick, int noteKinds, boolean missing, String group) {
    }

    /** 歌曲目录扫描失败（目录不存在 / 读不了）。调用方应该把 message 告诉用户。 */
    public static final class ScanException extends Exception {
        public ScanException(String message) {
            super(message);
        }
    }

    private final Random random = new Random();

    /** 播放列表，有序。{@code null} = 还没 load 过。 */
    private List<SongEntry> playlist;
    /** 分组名 → 相对路径集合。用 TreeMap 只为让"列出分组"的输出顺序稳定（好读、好对比）。 */
    private final Map<String, Set<String>> groups = new TreeMap<>();

    /** 歌曲目录扫描结果：相对路径（小写） → 文件。小写是为了在 Windows/Linux 上匹配行为一致。 */
    private final Map<String, File> available = new LinkedHashMap<>();
    /** 显示名缓存：相对路径（小写） → 名字。每次 refresh 清空。 */
    private final Map<String, String> displayNames = new LinkedHashMap<>();
    /** 元数据缓存：相对路径（小写） → {标题, 作者, 末tick, 音高种类数}；解析失败用 null 占位，避免反复重试。 */
    private final Map<String, String[]> metaCache = new LinkedHashMap<>();

    // ---------------------------------------------------------------- 路径

    /**
     * 数据目录覆盖（**只给独立验证用**，游戏内永远是 null）。
     *
     * <p>为什么留这么个口子：{@link #dataDir()} 正常情况下要经 FabricLoader，那个东西
     * 不起 Fabric 就拿不到，于是"存盘 → 读回 → 坏文件恢复"这条路径永远没法在游戏外验证，
     * 只能靠"我看了一遍应该没问题"。让它可以被临时指到一个临时目录，就能真的跑一遍。
     * 生产代码里没有任何一处 set 它。
     */
    private static Path dataDirOverride;

    /** 仅供独立验证设置数据目录；传 null 恢复默认（游戏目录）。 */
    public static void setDataDirForTesting(Path dir) {
        dataDirOverride = dir;
    }

    /** 数据目录 {@code <gameDir>/musicbot/}；拿不到游戏目录时返回 null。 */
    public static Path dataDir() {
        if (dataDirOverride != null) return dataDirOverride;
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(DATA_DIR_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 落盘文件 {@code <gameDir>/musicbot/musicbot.json}。 */
    public static Path dataFile() {
        Path dir = dataDir();
        return dir == null ? null : dir.resolve(FILE_NAME);
    }

    /**
     * 彗星放歌的目录 {@code <gameDir>/meteor-client/notebot/}——与 Notebot 自己的
     * {@code playRandomSong()} 挑的是同一个目录，所以"Notebot 能放的"和"列表能加的"必然一致。
     *
     * <p><b>为什么不是一句 {@code new File(MeteorClient.FOLDER, "notebot")}</b>：
     * {@code MeteorClient.FOLDER} 是静态 final，它在类初始化时就去问 FabricLoader 要游戏目录；
     * 在没有 Fabric 的 JVM 里（独立跑逻辑验证）那一步会抛 {@code ExceptionInInitializerError}——
     * 是 {@link Error}，不是 Exception。这个 Error 会顺着
     * {@code displayNameOf → indexOfInPlaylist → 命令} 一路冒到聊天栏，把"列个歌单"这种
     * 纯读取操作整个搞崩。所以这里降级：先试 Meteor 的目录，拿不到就按 Fabric 的游戏目录拼一个
     * （两者本来就相等，只是取值路径不同），都拿不到返回 null 让调用方去提示。
     */
    public static File notebotDir() {
        try {
            File folder = MeteorClient.FOLDER;
            if (folder != null) return new File(folder, NOTEBOT_DIR_NAME);
        } catch (Throwable ignored) {
            // 见上面注释：这里可能是 Error，必须连 Error 一起吞
        }
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("meteor-client").resolve(NOTEBOT_DIR_NAME).toFile();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 歌曲目录的可读绝对路径；拿不到时给一句人能看懂的话。 */
    public static String notebotDirText() {
        File dir = notebotDir();
        return dir == null ? "<拿不到游戏目录>" : dir.getAbsolutePath();
    }

    // ---------------------------------------------------------------- 扫描

    /**
     * 重新扫描 {@code notebot/} 目录。
     *
     * <p>只认 {@link SongDecoders#hasDecoder(File)} 说它认识的扩展名（NBS/TXT），
     * 这样"扫出来的"和"Notebot 放得出来的"必然是同一个集合，不会出现列表里有歌、
     * 点播放却报"格式不对"的情况。
     */
    public void refresh() throws ScanException {
        File dir = notebotDir();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, File> index = listSongs(dir, names);

        available.clear();
        available.putAll(index);
        displayNames.clear();
        displayNames.putAll(names);
        metaCache.clear();
        log("扫描 " + dir.getAbsolutePath() + " → " + available.size() + " 首");
    }

    /**
     * 列目录 → 相对路径 → 文件 / 显示名。**一个文件都不解析**（只做 {@code isFile()} + 扩展名判断）。
     *
     * <p>抽出来是因为同步的 {@link #refresh()} 和后台的 {@link #scan(ScanInput, String)} 必须是
     * 同一份"扫到什么算一首"的判定；各写一遍迟早会分叉。
     *
     * @param dir      歌曲目录；null / 不存在 / 读不了都抛 {@link ScanException}
     * @param namesOut 显示名出口（{@code putIfAbsent}，不覆盖调用方已有的名字）
     * @return 相对路径（小写）→ 文件；顺序 = 目录里的顺序
     */
    private static Map<String, File> listSongs(File dir, Map<String, String> namesOut) throws ScanException {
        if (dir == null) {
            throw new ScanException("拿不到游戏目录，无法定位歌曲目录（meteor-client/notebot）。");
        }
        if (!dir.isDirectory()) {
            throw new ScanException("找不到歌曲目录：" + dir.getAbsolutePath()
                + "（把 .nbs 文件放进去，或在 Notebot 里打开一次歌曲列表让它自己建）");
        }
        File[] files = dir.listFiles();
        if (files == null) {
            throw new ScanException("歌曲目录读不了：" + dir.getAbsolutePath());
        }

        Map<String, File> index = new LinkedHashMap<>();
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            if (!SongDecoders.hasDecoder(f)) continue;

            String rel = toRelative(f, dir);
            if (rel == null) continue;
            String key = norm(rel);
            // 同一首歌只留一份（Windows 大小写不敏感，两个 key 会撞）
            index.putIfAbsent(key, f);
            namesOut.putIfAbsent(key, stripExtension(f.getName()));
        }
        return index;
    }

    /** 已扫到的歌曲数。 */
    public int availableCount() {
        return available.size();
    }

    /** 已扫到的歌曲，按显示名排序（给"列出全部可用歌曲"用）。 */
    public List<SongInfo> availableSongs() {
        List<SongInfo> list = new ArrayList<>();
        for (Map.Entry<String, File> e : available.entrySet()) {
            list.add(info(e.getKey(), e.getValue(), null));
        }
        list.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        return list;
    }

    /** 按显示名/文件名模糊找可用歌曲；找不到返回空列表。 */
    public List<SongInfo> findAvailable(String query) {
        String q = norm(query);
        if (q.isEmpty()) return List.of();

        List<SongInfo> exact = new ArrayList<>();
        List<SongInfo> partial = new ArrayList<>();
        // 去掉扩展名再比一次：用户很自然会写 "Song3.nbs"（他看到的文件名就是这样），
        // 而显示名和 base 都是不带扩展名的，不多比这一轮就永远匹配不上。
        String qBase = norm(stripExtension(query));
        for (Map.Entry<String, File> e : available.entrySet()) {
            String name = norm(displayNames.getOrDefault(e.getKey(), e.getValue().getName()));
            String base = norm(stripExtension(e.getValue().getName()));
            if (exactMatch(name, base, q, qBase)) {
                exact.add(info(e.getKey(), e.getValue(), null));
            } else if (partialMatch(name, base, q, qBase)) {
                partial.add(info(e.getKey(), e.getValue(), null));
            }
        }
        return exact.isEmpty() ? partial : exact;
    }

    /**
     * 精确命中判定（显示名/文件名全等，带不带扩展名都算）。
     *
     * <p><b>为什么抽成静态谓词</b>：后台线程的 {@link #scan(ScanInput, String)} 必须和这里
     * **逐字同义**——复制一份判定迟早会分叉（改了一边忘了另一边，界面找得到、命令找不到）。
     * 两边共用这一对方法，语义只有一处。
     */
    private static boolean exactMatch(String name, String base, String q, String qBase) {
        return name.equals(q) || base.equals(q) || base.equals(qBase) || name.equals(qBase);
    }

    /** 模糊命中判定（与 {@link #exactMatch} 配套使用）。 */
    private static boolean partialMatch(String name, String base, String q, String qBase) {
        return name.contains(q) || base.contains(q) || (!qBase.isEmpty() && base.contains(qBase));
    }

    // ---------------------------------------------------------------- 异步扫描 / 搜索
    //
    // 为什么有这一段：列目录 + 解析 NBS 元数据是纯 IO/CPU 的慢活，原来是在客户端线程上同步跑的
    // （界面点「查找」、模块 onActivate）——一整排文件解析下来就是掉帧甚至卡死。
    // 现在拆成三步：主线程做快照 → 后台线程扫+解析（static，碰不到 this）→ 主线程合并结果。

    /**
     * 后台扫描/搜索的<b>输入快照</b>：主线程构造，之后只读。
     *
     * @param dir          歌曲目录（主线程用 {@link #notebotDir()} 解析好；null = 拿不到游戏目录）
     * @param displayNames 显示名缓存副本（浅拷贝）
     * @param meta         元数据缓存副本（浅拷贝；值 {@code String[]} 放进缓存后不再改写，故后台只读安全）
     */
    public record ScanInput(File dir, Map<String, String> displayNames, Map<String, String[]> meta) {
    }

    /**
     * 后台扫描/搜索的<b>输出结果</b>：不可变，后台线程构造，主线程用 {@link #applyScan} 合并。
     *
     * @param query       这次请求的查询串（空串 = 只扫目录）
     * @param error       失败原因；null = 成功
     * @param index       这次扫到的目录内容（相对路径小写 → 文件）
     * @param displayNames 这次算出来的显示名（缓存副本 + 新扫到的）
     * @param meta        这次新解析出来的元数据（只含需要解析且缓存里没有的；null 值 = 解析失败占位）
     * @param matches     匹配结果（{@code query} 为空时是空列表）
     */
    public record ScanResult(String query, String error, Map<String, File> index,
                             Map<String, String> displayNames, Map<String, String[]> meta,
                             List<SongInfo> matches) {
        public boolean ok() {
            return error == null;
        }
    }

    /**
     * 主线程：给后台任务准备一份只读快照。**不读盘、不解析**，只是把缓存浅拷贝一份，
     * 这样后台线程永远不需要碰这个对象的字段。
     */
    public ScanInput snapshotForScan() {
        return new ScanInput(notebotDir(), new LinkedHashMap<>(displayNames), new LinkedHashMap<>(metaCache));
    }

    /**
     * 后台线程：扫目录（{@link #listSongs}）+ 匹配（与 {@link #findAvailable} 同一对谓词）
     * + 给命中的歌解析元数据（与 {@link #meta(String)} 同一套规则）。
     *
     * <p><b>这个方法必须是 static</b>：后台线程一行 store 字段都不许碰。它只读 {@code input}
     * （主线程构造的不可变快照）和自己的局部变量，返回一个全新的不可变结果。
     * 唯一的"非纯"部分是 {@link SongDecoders#parse(File)}（读盘，另外它内部会读 Notebot 的设置，
     * 极端情况下会 {@code Notebot.warning} —— 那是 {@code MinecraftClient.execute} 转交主线程的，
     * 见类注释与报告）和 {@link #log(String)}（slf4j，线程安全）。
     */
    public static ScanResult scan(ScanInput input, String query) {
        File dir = input == null ? null : input.dir();
        Map<String, String> names = input == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(input.displayNames());

        Map<String, File> index;
        try {
            index = listSongs(dir, names);
        } catch (ScanException e) {
            return failure(query, e.getMessage());
        } catch (Throwable t) {
            return failure(query, "扫描歌曲目录出错：" + t);
        }
        log("扫描(后台) " + (dir == null ? "?" : dir.getAbsolutePath()) + " → " + index.size() + " 首");

        String q = norm(query);
        if (q.isEmpty()) {
            // 只扫描（模块 onActivate 走这条）：没有查询串，不需要解析任何文件
            return success(query, index, names, new LinkedHashMap<>(), List.of());
        }

        String qBase = norm(stripExtension(query));
        Map<String, String[]> meta = new LinkedHashMap<>();
        List<SongInfo> exact = new ArrayList<>();
        List<SongInfo> partial = new ArrayList<>();
        for (Map.Entry<String, File> e : index.entrySet()) {
            String key = e.getKey();
            File f = e.getValue();
            String name = norm(names.getOrDefault(key, f.getName()));
            String base = norm(stripExtension(f.getName()));
            if (exactMatch(name, base, q, qBase)) {
                exact.add(assemble(key, f, names.get(key), cachedOrParse(key, f, input.meta(), meta), null));
            } else if (partialMatch(name, base, q, qBase)) {
                partial.add(assemble(key, f, names.get(key), cachedOrParse(key, f, input.meta(), meta), null));
            }
        }
        return success(query, index, names, meta, exact.isEmpty() ? partial : exact);
    }

    /**
     * 主线程：把后台结果并进 store。**异步路径上唯一改字段的地方**，只允许在客户端线程调用
     * （模块的 {@code TickEvent.Post} 或界面渲染帧——两者是同一条线程）。
     *
     * <p>失败的扫描不动任何已有索引：和原来 {@link #refresh()} 抛异常时字段没被清掉的行为一致
     * （目录暂时读不了不该把已经扫到的歌单也清空）。
     */
    public void applyScan(ScanResult result) {
        if (result == null || !result.ok()) return;

        available.clear();
        available.putAll(result.index());
        displayNames.putAll(result.displayNames());
        metaCache.putAll(result.meta());
    }

    private static ScanResult success(String query, Map<String, File> index, Map<String, String> names,
                                      Map<String, String[]> meta, List<SongInfo> matches) {
        return new ScanResult(query, null,
            Collections.unmodifiableMap(new LinkedHashMap<>(index)),
            Collections.unmodifiableMap(new LinkedHashMap<>(names)),
            Collections.unmodifiableMap(new LinkedHashMap<>(meta)),
            Collections.unmodifiableList(new ArrayList<>(matches)));
    }

    /**
     * 造一个"失败"结果。
     *
     * <p><b>为什么是 public</b>：{@link MusicBotSearch#start} 里那个后台 lambda 必须保证
     * "无论如何都要往信箱里放一个结果"——它是单飞闸门唯一的开门钥匙。所以 scan 抛出去的任何东西
     * 都要能在 lambda 里就地转成一个失败结果，而那个 lambda 在另一个包里，拿不到这个私有工厂。
     */
    public static ScanResult failure(String query, String message) {
        return new ScanResult(query, message, Map.of(), Map.of(), Map.of(), List.of());
    }

    /**
     * 后台线程：取一条元数据——先查快照缓存（解析失败也缓存了 null 占位，避免反复重试坏文件），
     * 没有再读盘解析，并把结果记进 {@code out} 一起带回主线程。
     */
    private static String[] cachedOrParse(String keyLower, File f, Map<String, String[]> snapshot,
                                          Map<String, String[]> out) {
        if (snapshot != null && snapshot.containsKey(keyLower)) return snapshot.get(keyLower);
        if (out.containsKey(keyLower)) return out.get(keyLower);

        String[] meta = null;
        if (f != null && f.isFile()) {
            try {
                Song song = SongDecoders.parse(f);
                if (song != null) {
                    meta = new String[]{
                        song.getTitle(),
                        song.getAuthor(),
                        String.valueOf(song.getLastTick()),
                        String.valueOf(song.getRequirements() == null ? 0 : song.getRequirements().size())
                    };
                }
            } catch (Throwable t) {
                // 坏文件不抛出去：一首歌解析失败不该让整次搜索失败
                log("元数据解析失败 " + f.getName() + " : " + t);
            }
        }
        out.put(keyLower, meta);
        return meta;
    }

    /**
     * 按显示名/文件名从**播放列表**里找条目下标；找不到返回 -1。
     *
     * <p>三轮匹配，精确优先：显示名全等 → 文件名（去扩展名）全等 → 三者任一包含。
     * 带不带扩展名都能找到（用户抄的是文件名，而列表里显示的是去扩展名的名字）。
     */
    public int indexOfInPlaylist(String query) {
        List<SongEntry> pl = songs();
        String q = norm(query);
        if (q.isEmpty()) return -1;
        String qBase = norm(stripExtension(query));

        for (int i = 0; i < pl.size(); i++) {
            if (norm(displayNameOf(pl.get(i).relative())).equals(q)) return i;
        }
        for (int i = 0; i < pl.size(); i++) {
            if (norm(stripExtension(fileName(pl.get(i).relative()))).equals(qBase)) return i;
        }
        for (int i = 0; i < pl.size(); i++) {
            String display = norm(displayNameOf(pl.get(i).relative()));
            String base = norm(stripExtension(fileName(pl.get(i).relative())));
            if (display.contains(q) || base.contains(q) || base.contains(qBase)) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- 列表增删改

    /** 当前播放列表（只读视图）。load 之前返回空列表而不是 null，省掉调用方的判空。 */
    public List<SongEntry> songs() {
        return playlist == null ? List.of() : Collections.unmodifiableList(playlist);
    }

    public int size() {
        return playlist == null ? 0 : playlist.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * 追加一首歌。按相对路径去重——同一首歌加两次没有意义，而且会让"上一首/下一首"变得诡异。
     *
     * @return 真正加进去了才返回 true
     */
    public boolean add(String relative, String group) {
        ensureLoaded();
        String rel = normalizeRelative(relative);
        if (rel == null) return false;

        for (SongEntry e : playlist) {
            if (norm(e.relative()).equals(norm(rel))) return false;
        }
        String g = cleanGroup(group);
        playlist.add(new SongEntry(rel, g));
        if (g != null) groups.computeIfAbsent(g, k -> new LinkedHashSet<>()).add(rel);
        return true;
    }

    /** 按下标移除。 */
    public SongEntry removeAt(int index) {
        ensureLoaded();
        if (index < 0 || index >= playlist.size()) return null;
        SongEntry removed = playlist.remove(index);
        // 只从"它自己那个组"里摘，不要顺手把别的组也清了——用户可以手动把一首歌挂进多个组
        Set<String> g = groupOf(removed.group());
        if (g != null) {
            g.remove(removed.relative());
        }
        return removed;
    }

    /** 按名字移除；返回被移除的条目，找不到返回 null。 */
    public SongEntry removeByName(String query) {
        int i = indexOfInPlaylist(query);
        return i < 0 ? null : removeAt(i);
    }

    /**
     * 上/下移。
     *
     * <p><b>语义</b>：{@code delta > 0} = 往列表后面挪（下移），{@code delta < 0} = 往前挪（上移）。
     * {@code move(1, 1)} 在 {@code [A,B,C]} 上得到 {@code [A,C,B]}——B 和它后面那首交换，
     * 而不是"插到 C 后面变成第三位"。
     *
     * <p><b>坑（实测踩过）</b>：先 {@code remove(index)} 再 {@code add(index + delta)} 是错的。
     * remove 之后目标位置左边塌了一格，于是下移一格会变成下移两格；
     * 必须把目标位置按方向补偿 1（下移时 {@code -1}），或者干脆交换。
     *
     * @return 真的动了才返回 true（已经在头/尾就返回 false，让调用方给出"到头了"的反馈）
     */
    public boolean move(int index, int delta) {
        ensureLoaded();
        int n = playlist.size();
        if (index < 0 || index >= n || delta == 0) return false;

        int target = index + delta;
        if (target < 0 || target >= n) return false;

        SongEntry e = playlist.remove(index);
        // 往下移：remove 已经把它后面的元素整体左移了一格，插入点要减 1
        int insertAt = delta > 0 ? target - 1 : target;
        insertAt = Math.max(0, Math.min(insertAt, playlist.size()));
        playlist.add(insertAt, e);
        return true;
    }

    /** 清空整份列表（分组里的条目也随之失效，所以分组一起清）。 */
    public void clearPlaylist() {
        ensureLoaded();
        playlist.clear();
        groups.clear();
    }

    /** 给某条重新打组；{@code group == null} 表示取消分组。 */
    public boolean setGroup(String query, String group) {
        ensureLoaded();
        int i = indexOfInPlaylist(query);
        if (i < 0) return false;

        SongEntry old = playlist.get(i);
        String g = cleanGroup(group);
        playlist.set(i, old.withGroup(g));

        Set<String> oldSet = groupOf(old.group());
        if (oldSet != null) oldSet.remove(old.relative());
        if (g != null) groups.computeIfAbsent(g, k -> new LinkedHashSet<>()).add(old.relative());
        return true;
    }

    /**
     * 查某个组名的成员集合；<b>组名为空时返回 null，绝不拿 null 去查 {@link TreeMap}</b>。
     *
     * <p>这是踩过的坑：{@code groups} 是 TreeMap（为了"列出组"的顺序稳定），
     * 而 {@code TreeMap.get(null)} 会抛 NPE 而不是返回 null（HashMap 才返回 null）。
     * 未分组的歌 group 就是 null，于是"给一首没分组的歌打组"必定崩。
     */
    private Set<String> groupOf(String group) {
        String key = groupKey(group);
        return key == null ? null : groups.get(key);
    }

    // ---------------------------------------------------------------- 分组

    /** 已有分组名（字典序，稳定输出）。 */
    public List<String> groupNames() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : groups.entrySet()) {
            if (!e.getValue().isEmpty()) out.add(e.getKey());
        }
        return out;
    }

    /** 某个组里的条目（保持播放列表里的相对顺序）。组不存在时返回空列表。 */
    public List<SongEntry> groupEntries(String group) {
        Set<String> members = groupOf(group);
        if (members == null || members.isEmpty()) return List.of();

        List<SongEntry> out = new ArrayList<>();
        for (SongEntry e : songs()) {
            if (members.contains(e.relative())) out.add(e);
        }
        return out;
    }

    public int groupSize(String group) {
        Set<String> members = groupOf(group);
        return members == null ? 0 : members.size();
    }

    /** 整个组改名。目标组已存在时是"合并"。返回移动了几首。 */
    public int renameGroup(String from, String to) {
        ensureLoaded();
        String fromKey = groupKey(from);
        String toClean = cleanGroup(to);
        if (fromKey == null || toClean == null) return 0;

        Set<String> members = groups.remove(fromKey);
        if (members == null) return 0;

        Set<String> target = groups.computeIfAbsent(toClean, k -> new LinkedHashSet<>());
        target.addAll(members);

        int moved = 0;
        for (int i = 0; i < playlist.size(); i++) {
            SongEntry e = playlist.get(i);
            if (fromKey.equals(groupKey(e.group()))) {
                playlist.set(i, e.withGroup(toClean));
                moved++;
            }
        }
        return moved;
    }

    /** 解散一个组。组里的歌**保留在列表里**，只是变成未分组——整组删除请用 {@link #removeGroup}。 */
    public int dissolveGroup(String group) {
        ensureLoaded();
        String key = groupKey(group);
        if (key == null) return 0;
        Set<String> members = groups.remove(key);
        if (members == null) return 0;

        int cleared = 0;
        for (int i = 0; i < playlist.size(); i++) {
            SongEntry e = playlist.get(i);
            if (key.equals(groupKey(e.group()))) {
                playlist.set(i, e.withGroup(null));
                cleared++;
            }
        }
        return cleared;
    }

    /** 整组从列表里删掉（连同歌曲条目）。 */
    public int removeGroup(String group) {
        ensureLoaded();
        String key = groupKey(group);
        if (key == null) return 0;
        Set<String> members = groups.remove(key);
        if (members == null) return 0;

        int before = playlist.size();
        playlist.removeIf(e -> key.equals(groupKey(e.group())));
        return before - playlist.size();
    }

    // ---------------------------------------------------------------- 解析 / 播放顺序

    /**
     * 把一条列表条目解析成可播放的文件。
     *
     * <p>三级回退：① 相对路径直接命中 → ② 相对路径当文件名在扫描结果里找 → ③ 按显示名在扫描结果里找。
     * 之所以要回退，是因为相对路径是按扫描当时的目录结构写下来的，用户把 .nbs 挪进子目录后
     * ②③ 还能把它找回来。
     *
     * @return 存在的文件；找不到返回 null
     */
    public File resolve(String nameOrRelative) {
        SongEntry direct = entryByRelative(nameOrRelative);
        if (direct != null) return resolveEntry(direct);
        return resolveEntry(new SongEntry(nameOrRelative, null));
    }

    /** 解析列表里第 index 条。 */
    public File resolveAt(int index) {
        if (index < 0 || index >= size()) return null;
        return resolveEntry(playlist.get(index));
    }

    private File resolveEntry(SongEntry entry) {
        String rel = normalizeRelative(entry.relative());
        if (rel == null) return null;

        File base = notebotDir();
        if (base != null) {
            File f = new File(base, rel);
            if (f.isFile()) return f;
        }

        String key = norm(rel);
        File byRel = available.get(key);
        if (byRel != null && byRel.isFile()) return byRel;

        String baseName = norm(stripExtension(fileName(rel)));
        for (Map.Entry<String, File> e : available.entrySet()) {
            if (norm(stripExtension(e.getValue().getName())).equals(baseName)) return e.getValue();
        }
        return null;
    }

    /**
     * 挑下一首要放的下标。**不改变任何状态**——真正的推进由
     * {@link #commitShuffleDraw(int)} 在播放真的开始之后记账（否则加载失败会吃掉一轮里的名额）。
     *
     * <p>候选集合由 {@link #setFilter} 决定（「只播这个组」就是靠它实现的），
     * 所以"随机不重复"是<b>在过滤后的候选集里</b>不重复，而不是在整个列表里不重复。
     *
     * @param current  当前下标；-1 表示还没开始（此时"下一首"= 候选集里的第一首）
     * @param mode     sequential / shuffle / loop
     * @param resetCycle 仅 shuffle 有意义：一轮放完后是重开一轮还是停下
     * @return 下一首下标；返回 -1 = 不该再放了（候选集空，或一轮放完且用户选了停下）
     */
    public int pickNext(int current, PlayMode mode, boolean resetCycle) {
        ensureLoaded();
        List<Integer> cand = candidates();
        if (cand.isEmpty()) return -1;

        if (mode == PlayMode.SHUFFLE) {
            int n = cand.size();
            if (n == 1) return cand.get(0);

            // 袋子是按"候选集里的第几个"存的，所以候选集一变就得重洗，否则会指到别组的歌上
            if (!filterKey.equals(bagFilterKey)) {
                shuffleBag.clear();
                shuffleDone = false;
                refillBag();
            }
            if (shuffleBag.isEmpty()) {
                if (shuffleDone && !resetCycle) return -1;
                refillBag();
            }
            int pos = shuffleBag.get(shuffleBag.size() - 1);
            if (bagIndex(pos) == current && n > 1) {
                // 别让同一首连播两次（袋子里最后那张正好是当前这首）
                pos = shuffleBag.get(0);
            }
            return bagIndex(pos);
        }

        if (mode == PlayMode.LOOP) {
            if (current < 0) return cand.get(0);
            int pos = cand.indexOf(current);
            // 当前这首不在候选集里（刚改了过滤条件）→ 从候选集头开始
            if (pos < 0) return cand.get(0);
            return cand.get((pos + 1) % cand.size());
        }

        // sequential：放完一轮就停
        if (current < 0) return cand.get(0);
        int pos = cand.indexOf(current);
        if (pos < 0) return cand.get(0);
        return pos + 1 < cand.size() ? cand.get(pos + 1) : -1;
    }

    /** 播放真的开始了，把 {@link #pickNext} 挑中的下标从袋子里划掉。 */
    public void commitShuffleDraw(int index) {
        if (shuffleBag.isEmpty()) return;
        int pos = bagPos(index);
        if (pos < 0) return;
        shuffleBag.remove(pos);
        if (shuffleBag.isEmpty()) shuffleDone = true;
    }

    /**
     * 上一首。顺序/循环模式下就是下标 -1；随机模式下从"已抽出的历史"里回退一步，
     * 不然"上一首"在随机模式里会跳到一首毫无关系的新歌上，那不是用户按这个键的意思。
     *
     * @return 下标；-1 = 没有上一首
     */
    public int pickPrevious(int current, PlayMode mode) {
        ensureLoaded();
        List<Integer> cand = candidates();
        if (cand.isEmpty()) return -1;

        if (mode == PlayMode.SHUFFLE) {
            if (history.size() >= 2) {
                history.remove(history.size() - 1);           // 丢掉"当前"
                return history.get(history.size() - 1);       // 回到上一首
            }
            return -1;
        }
        int pos = cand.indexOf(current);
        if (pos <= 0) {
            return mode == PlayMode.LOOP ? cand.get(cand.size() - 1) : -1;
        }
        return cand.get(pos - 1);
    }

    /** 记录一次"真的播了"，供上一首回退使用。 */
    public void recordPlayed(int index) {
        if (index < 0) return;
        if (!history.isEmpty() && history.get(history.size() - 1) == index) return;
        history.add(index);
        while (history.size() > 64) history.remove(0);
    }

    /** 清空随机抽取状态（用户手动选曲、或重新开播时调用，免得袋子里留着上一轮的残留）。 */
    public void resetShuffleState() {
        shuffleBag.clear();
        shuffleDone = false;
        history.clear();
        bagFilterKey = null;
    }

    /** 把随机袋子按当前候选集重填一遍（加/删歌、换组之后都要调，否则位置会指错歌）。 */
    public void reshuffle() {
        shuffleBag.clear();
        shuffleDone = false;
        refillBag();
    }

    /**
     * 设定"下一首的候选集合"。
     *
     * @param allowed 允许参与挑选的下标；传 {@code null} = 不限（整个列表）
     * @param key     过滤条件的标识串（比如组名）。它变了就说明候选集变了，随机袋子要重洗
     */
    public void setFilter(List<Integer> allowed, String key) {
        ensureLoaded();
        filter = allowed == null ? null : new ArrayList<>(allowed);
        filterKey = key == null ? "" : key;
    }

    public void clearFilter() {
        setFilter(null, "");
    }

    /** 当前候选集合（升序下标）。 */
    private List<Integer> candidates() {
        if (filter != null) return filter;
        List<Integer> all = new ArrayList<>(playlist.size());
        for (int i = 0; i < playlist.size(); i++) all.add(i);
        return all;
    }

    /** 随机袋子里第 pos 张牌指向的真实下标。 */
    private int bagIndex(int pos) {
        List<Integer> cand = candidates();
        if (cand.isEmpty()) return -1;
        int clamped = Math.max(0, Math.min(pos, cand.size() - 1));
        return cand.get(clamped);
    }

    /** 真实下标在袋子里的位置；不在袋子里返回 -1。 */
    private int bagPos(int index) {
        for (int i = 0; i < shuffleBag.size(); i++) {
            if (bagIndex(shuffleBag.get(i)) == index) return i;
        }
        return -1;
    }

    private void refillBag() {
        shuffleBag.clear();
        int n = candidates().size();
        for (int i = 0; i < n; i++) shuffleBag.add(i);
        Collections.shuffle(shuffleBag, random);
        shuffleDone = false;
        bagFilterKey = filterKey;
    }

    /** 袋子装的是"候选集里的第几个"（不是真实下标），这样过滤条件换了也能靠 bagFilterKey 发现并重洗。 */
    private final List<Integer> shuffleBag = new ArrayList<>();
    private final List<Integer> history = new ArrayList<>();
    /** 是否已经完整放过一轮（只在"一轮不重复"的语义里有意义）。 */
    private boolean shuffleDone;
    /** 候选下标集合；null = 整个列表。 */
    private List<Integer> filter;
    private String filterKey = "";
    /** 上次装袋时用的过滤标识；和当前 filterKey 不一致就重洗。 */
    private String bagFilterKey = "";

    // ---------------------------------------------------------------- 元数据

    /** 列表里第 index 条的显示名；解析不出文件时给"文件名（缺失）"。 */
    public String displayNameAt(int index) {
        if (index < 0 || index >= size()) return "?";
        return displayNameOf(playlist.get(index).relative());
    }

    /**
     * 界面专用的"浅"显示名：只查缓存，**绝不读盘解析 NBS**。
     *
     * <p>为什么要多这一个口子：{@link #displayNameOf} 在缓存未命中时会调
     * {@link SongDecoders#parse(File)}（把整个文件读一遍）。渲染帧里只要有一条这样的路径，
     * 一屏几十行就是几十次整文件解析——那正是本次要修的卡死。
     * 而"扫描改成异步"意味着开屏后的头几帧缓存必然是冷的，所以界面这一侧必须有一条不解析的路。
     *
     * <p>规则与 {@link #displayNameOf} 完全一致（缓存显示名 → 元数据标题 → 去扩展名的文件名），
     * 只是不做解析、也不写缓存；扫描结果一到，两者的输出逐字相同。
     */
    public String displayNameShallow(int index) {
        if (index < 0 || index >= size()) return "?";
        String relative = playlist.get(index).relative();
        String key = norm(normalizeRelative(relative));
        if (key == null) return String.valueOf(relative);

        String cached = displayNames.get(key);
        if (cached != null) return cached;

        String[] meta = metaCache.get(key);
        if (meta != null && meta[0] != null && !meta[0].isBlank()) return meta[0];

        return stripExtension(fileName(relative));
    }

    /** 列表里第 index 条对应的文件（可能为 null = 缺失）。 */
    public File fileAt(int index) {
        return index < 0 || index >= size() ? null : resolveAt(index);
    }

    public SongEntry entryAt(int index) {
        return index < 0 || index >= size() ? null : playlist.get(index);
    }

    private SongEntry entryByRelative(String relative) {
        String key = norm(normalizeRelative(relative));
        if (key == null) return null;
        for (SongEntry e : songs()) {
            if (norm(e.relative()).equals(key)) return e;
        }
        return null;
    }

    /**
     * 显示名。**解析 NBS 元数据是要读整个文件的**，所以结果进 {@link #metaCache}；
     * 解析失败也缓存一个空占位，避免每次列列表都把坏文件重读一遍。
     */
    private String displayNameOf(String relative) {
        String key = norm(normalizeRelative(relative));
        if (key == null) return String.valueOf(relative);

        String cached = displayNames.get(key);
        if (cached != null) return cached;

        String[] meta = meta(key);
        String name;
        if (meta == null) {
            name = stripExtension(fileName(relative)) + "（缺文件）";
        } else if (meta[0] != null && !meta[0].isBlank()) {
            name = meta[0];
        } else {
            name = stripExtension(fileName(relative));
        }
        displayNames.put(key, name);
        return name;
    }

    /** {标题, 作者, 末tick, 音高种类数}；解析不了返回 null。 */
    public String[] meta(String keyLower) {
        if (metaCache.containsKey(keyLower)) return metaCache.get(keyLower);

        File f = available.get(keyLower);
        if (f == null) {
            File base = notebotDir();
            if (base != null) {
                File direct = new File(base, keyLower);
                if (direct.isFile()) f = direct;
            }
        }
        String[] meta = null;
        if (f != null && f.isFile()) {
            try {
                Song song = SongDecoders.parse(f);
                if (song != null) {
                    meta = new String[]{
                        song.getTitle(),
                        song.getAuthor(),
                        String.valueOf(song.getLastTick()),
                        String.valueOf(song.getRequirements() == null ? 0 : song.getRequirements().size())
                    };
                }
            } catch (Throwable t) {
                // 坏文件不抛出去：一首歌解析失败不该让整个列表命令失败
                log("元数据解析失败 " + f.getName() + " : " + t);
            }
        }
        metaCache.put(keyLower, meta);
        return meta;
    }

    private SongInfo info(String keyLower, File file, String group) {
        String display = displayNames.get(keyLower);
        boolean missing = !file.isFile();
        String[] meta = missing ? null : meta(keyLower);
        if (!missing && display == null) {
            display = meta != null && meta[0] != null && !meta[0].isBlank() ? meta[0] : stripExtension(file.getName());
            displayNames.put(keyLower, display);
        }
        return assemble(keyLower, file, display, meta, group);
    }

    /**
     * 组装一条 {@link SongInfo}。**纯函数**：不写缓存、不读盘。
     *
     * <p>同步路径（{@link #info}）与后台路径（{@link #scan}）共用它，保证同一个文件在两条路径上
     * 得到**逐字相同**的显示名/标题/时长——否则"界面上看到的"和"命令加进来的"会对不上。
     * （缓存写入留在各自的调用点上：后台那份要带回主线程才生效。）
     */
    private static SongInfo assemble(String keyLower, File file, String cachedDisplay, String[] meta, String group) {
        String title = meta == null ? null : meta[0];
        String author = meta == null ? null : meta[1];
        int lastTick = -1;
        int noteKinds = -1;
        if (meta != null) {
            try {
                lastTick = Integer.parseInt(meta[2]);
                noteKinds = Integer.parseInt(meta[3]);
            } catch (NumberFormatException ignored) {
            }
        }
        boolean missing = !file.isFile();

        String display = cachedDisplay;
        if (display == null && !missing) {
            display = title != null && !title.isBlank() ? title : stripExtension(file.getName());
        }
        if (display == null) display = stripExtension(fileName(keyLower));
        return new SongInfo(file, file.getName(), display, title, author, lastTick, noteKinds, missing, group);
    }

    // ---------------------------------------------------------------- 落盘

    /** 是否 load 过（load 过的空列表和"还没 load"是两种状态）。 */
    public boolean isLoaded() {
        return playlist != null;
    }

    private void ensureLoaded() {
        if (playlist == null) {
            playlist = new ArrayList<>();
        }
    }

    /**
     * 读盘。文件不存在 → 空列表（不算错误）；文件坏了 → 改名 {@code .bad} 留档再从空列表开始。
     *
     * <p>坏文件**不删**而是改名：用户手写过这个 JSON 的可能性很大，直接删掉就是丢数据。
     */
    public void load() {
        ensureLoaded();
        // 整段包 Throwable：取游戏目录要经 FabricLoader，写日志要经 MeteorClient.LOG，
        // 任何一个环节在当前环境里不可用（早期启动 / 无 Fabric / 独立跑逻辑验证）
        // 都不该把"载入列表"一起拖死——最坏是拿不到旧数据，而不是抛出去让模块构造失败。
        try {
            Path p = dataFile();
            if (p == null || !Files.isRegularFile(p)) {
                log("列表文件不存在，按空列表启动：" + p);
                return;
            }
            String text = Files.readString(p, StandardCharsets.UTF_8);
            JsonElement el = JsonParser.parseString(text);
            if (el == null || !el.isJsonObject()) {
                throw new IOException("根节点不是 JSON 对象");
            }
            readJson(el.getAsJsonObject());
            log("已载入列表 " + playlist.size() + " 首 / " + groupNames().size() + " 组");
        } catch (Throwable t) {
            log("列表文件损坏（" + t + "），改名 .bad 留档后按空列表启动");
            try {
                Path p = dataFile();
                if (p != null && Files.isRegularFile(p)) {
                    Files.move(p, p.resolveSibling(p.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Throwable ignored) {
            }
            playlist = new ArrayList<>();
            groups.clear();
        }
    }

    /**
     * 写一行调试日志（前缀 MUSICBOT），但**日志本身的任何失败都不许传出去**。
     *
     * <p>为什么不用裸的 {@code MeteorClient.LOG}：{@code MeteorClient} 的静态初始化会去碰 FabricLoader，
     * 在没有 Fabric 的环境里那是一个 {@link Error}（ExceptionInInitializerError / NoClassDefFoundError）
     * 而不是 Exception。日志写不出来是小事，因为写不出来而让"列歌单"这种只读操作炸掉是大事。
     *
     * <p>落点是 Meteor 原生日志（{@code logs/latest.log}）：迁移前这里写的是 3cHelper+ 自己的调试日志
     * 文件，独立插件不该再往那个文件里写。调用时机与内容一字未动，只换了出口。
     */
    private static void log(String msg) {
        try {
            MeteorClient.LOG.info("MUSICBOT {}", msg);
        } catch (Throwable ignored) {
        }
    }

    private void readJson(JsonObject root) {
        playlist = new ArrayList<>();
        groups.clear();

        JsonElement schema = root.get("schema");
        if (schema != null && schema.isJsonPrimitive()) {
            try {
                int v = schema.getAsInt();
                if (v > SCHEMA) {
                    // 只警告不拒绝：未来的字段我们读不懂但能保住已知的那部分，比直接罢工好
                    log("列表文件 schema=" + v + " 比本版本(" + SCHEMA + ") 新，按已知字段读取");
                }
            } catch (Throwable ignored) {
            }
        }

        JsonElement pl = root.get("playlist");
        if (pl != null && pl.isJsonArray()) {
            for (JsonElement item : pl.getAsJsonArray()) {
                if (item == null || !item.isJsonObject()) continue;
                JsonObject o = item.getAsJsonObject();
                String rel = asString(o.get("song"));
                if (rel == null || rel.isBlank()) continue;
                String g = cleanGroup(asString(o.get("group")));
                playlist.add(new SongEntry(rel, g));
            }
        }

        JsonElement gs = root.get("groups");
        if (gs != null && gs.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : gs.getAsJsonObject().entrySet()) {
                String name = cleanGroup(e.getKey());
                if (name == null) continue;
                JsonElement arr = e.getValue();
                if (arr == null || !arr.isJsonArray()) continue;
                Set<String> set = groups.computeIfAbsent(name, k -> new LinkedHashSet<>());
                for (JsonElement s : arr.getAsJsonArray()) {
                    String rel = asString(s);
                    if (rel != null && !rel.isBlank()) set.add(rel);
                }
            }
        }

        // 自愈：groups 段缺失/被手改坏时，按每条自己的 group 字段把分组补回来，
        // 否则"打了组但 .musicbot group play 说组里没歌"会让人摸不着头脑。
        Map<String, Set<String>> rebuilt = new LinkedHashMap<>();
        for (SongEntry e : playlist) {
            if (e.group() == null) continue;
            rebuilt.computeIfAbsent(e.group(), k -> new LinkedHashSet<>()).add(e.relative());
        }
        for (Map.Entry<String, Set<String>> e : rebuilt.entrySet()) {
            Set<String> set = groups.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>());
            set.addAll(e.getValue());
        }
    }

    /** 原子落盘：先写 .tmp 再 move，中途崩了不会留下半个 JSON。 */
    public boolean save() {
        Path p = dataFile();
        if (p == null) {
            log("保存失败：拿不到游戏目录");
            return false;
        }
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(toJson()), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Files.deleteIfExists(tmp);
                throw e;
            }
            return true;
        } catch (Throwable t) {
            log("保存失败：" + t);
            return false;
        }
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", SCHEMA);
        root.addProperty("savedAt", timestamp());

        JsonArray arr = new JsonArray();
        for (SongEntry e : songs()) {
            JsonObject o = new JsonObject();
            o.addProperty("song", e.relative());
            if (e.group() != null) o.addProperty("group", e.group());
            arr.add(o);
        }
        root.add("playlist", arr);

        JsonObject gs = new JsonObject();
        for (Map.Entry<String, Set<String>> e : groups.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            JsonArray a = new JsonArray();
            for (String s : e.getValue()) a.add(s);
            gs.add(e.getKey(), a);
        }
        root.add("groups", gs);
        return root;
    }

    /** 落盘时间戳的格式（yyyy-MM-dd HH:mm:ss）——与迁移前逐字一致，老文件和新文件长得一样。 */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 当前时间（yyyy-MM-dd HH:mm:ss）；{@code savedAt} 字段用的就是它。 */
    private static String timestamp() {
        return LocalDateTime.now().format(TS);
    }

    // ---------------------------------------------------------------- 小工具

    private static String asString(JsonElement el) {
        if (el == null || !el.isJsonPrimitive()) return null;
        try {
            return el.getAsString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 播放顺序。 */
    public enum PlayMode {
        /** 顺序：按列表顺序放完一轮就停。 */
        SEQUENTIAL,
        /** 随机：一轮内不重复。 */
        SHUFFLE,
        /** 循环：按当前顺序一直放。 */
        LOOP
    }

    /** 去扩展名（只去最后一段，{@code a.b.nbs} → {@code a.b}）。 */
    private static String stripExtension(String name) {
        if (name == null) return "?";
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** 从路径里取文件名（同时兼容 Windows 的 {@code \} 与 POSIX 的 {@code /}）。 */
    private static String fileName(String relative) {
        if (relative == null) return "?";
        int slash = Math.max(relative.lastIndexOf('/'), relative.lastIndexOf('\\'));
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    /**
     * 相对 notebot 目录的路径；不在目录内返回 null（防止手改 JSON 写到目录外去）。
     *
     * <p>基准目录由调用方给：后台扫描用快照里已经解析好的那个目录，免得后台线程再去问一次
     * {@link #notebotDir()}。
     */
    private static String toRelative(File f, File baseFile) {
        try {
            if (baseFile == null) return f.getName();
            Path base = baseFile.toPath().toAbsolutePath().normalize();
            Path abs = f.toPath().toAbsolutePath().normalize();
            if (!abs.startsWith(base)) return null;
            return base.relativize(abs).toString();
        } catch (Throwable t) {
            return f.getName();
        }
    }

    /** 去掉开头的斜杠、统一分隔符；空串返回 null。 */
    private static String normalizeRelative(String rel) {
        if (rel == null) return null;
        String s = rel.trim().replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        return s.isEmpty() ? null : s;
    }

    /** 大小写无关的比较键。用 Locale.ROOT，避免土耳其语环境下 I/i 的经典坑。 */
    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /** 分组名的比较键；{@code null}/空串都归为"未分组"。 */
    private static String groupKey(String group) {
        return cleanGroup(group) == null ? null : group.trim();
    }

    private static String cleanGroup(String group) {
        if (group == null) return null;
        String s = group.trim();
        return s.isEmpty() ? null : s;
    }
}
