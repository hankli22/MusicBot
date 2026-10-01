package com.aefmusic.util;

import com.aefmusic.util.MusicBotStore.ScanInput;
import com.aefmusic.util.MusicBotStore.ScanResult;
import com.aefmusic.util.MusicBotStore.SongInfo;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「扫目录 + 找歌」的异步执行器：慢活全在后台线程，结果回主线程再合并。
 *
 * <h3>它解决什么</h3>
 * 界面上点「查找」、或刚开模块触发扫描，原来是<b>在客户端线程上同步</b>跑的
 * （{@code store.refresh()} 列目录 + {@code store.findAvailable()} 逐个解析 NBS 元数据）。
 * 一次匹配可能要读几十个文件，压在渲染帧里就是掉帧甚至整局卡死——用户实测原话「搜索改异步啊喂卡死了」。
 *
 * <h3>并发模型：谁会访问哪个字段（这一段是重点，别当口号看）</h3>
 * <ul>
 * <li><b>只在客户端线程读写</b>（Minecraft 主线程；{@code TickEvent.Post} 与界面
 * {@code onRenderBefore} 跑的是同一条线程，所以这些字段之间没有并发）：
 * {@link #state}、{@link #query}、{@link #matches}、{@link #error}、{@link #version}、
 * {@link #queuedQuery}、{@link #lastAppliedQuery}、{@link #lastAppliedError}、{@link #lastStartMillis}。</li>
 * <li><b>主线程 → 后台线程</b>：一个 {@link ScanInput}（主线程在 {@code snapshotForScan()} 里做的
 * 只读浅拷贝）+ 查询串。两者通过 {@code ExecutorService.execute} 提交，提交/执行之间有
 * happens-before 保证；后台线程<b>只读</b>它们，绝不回头读 store 的字段。</li>
 * <li><b>后台线程 → 主线程</b>：{@link #incoming}（{@link AtomicReference}）是唯一的跨线程可变引用。
 * 后台线程只做一次 {@code set}（放进去的结果全字段不可变、内部集合不可变），
 * 主线程用 {@code getAndSet(null)} 取走——取走之后没有任何线程再引用它。</li>
 * </ul>
 * 也就是说：{@code MusicBotStore} 的可变状态永远只被客户端线程改，后台线程连它的引用都拿不到
 * （{@code MusicBotStore.scan} 是 static 方法）。
 *
 * <h3>单飞与"最后一次点击胜出"</h3>
 * 同一时刻最多只有一个任务在跑（{@link #state} == {@code RUNNING} 时不再提交新任务）。
 * 搜索进行中再点一次<b>既不新建任务、也不取消正在跑的那个</b>（它只读快照，跑完照样要把索引并回去），
 * 而是把查询串记进 {@link #queuedQuery}，等当前任务回来时立刻补跑最后一次请求。
 * 效果就是"重复点击 = 最后一次胜出"，既不堆任务，界面上也不会闪一次旧查询的结果
 * （有排队请求时中间那次结果不发布，界面一直显示"搜索中…"）。
 *
 * <h3>2026-10-06 加：单飞闸门自己的活锁兜底（"一次卡住 → 之后所有搜索都被丢弃"）</h3>
 * 单飞闸门的前提是"后台任务<b>一定会</b>把结果放进信箱"。可这个前提有两个洞，任何一个成立，
 * {@link #state} 就永久停在 {@code RUNNING}，而 {@link #request} 在 RUNNING 时只记
 * {@link #queuedQuery} 不提交——于是<b>之后每一次点「查找」都被静默吃掉</b>，界面永远"搜索中…"，
 * 只能重启游戏。两个洞是：
 * <ol>
 * <li><b>提交前就抛</b>：{@code snapshotForScan()} 之后到 {@code MeteorExecutor.execute} 之间
 * 任何一句抛（RejectedExecutionException / OOM / 快照构造失败），原代码没有 catch，
 * 异常顺着 {@code MusicBotScreen.onRenderBefore} 上抛，而信箱永远是空的。</li>
 * <li><b>后台任务永久阻塞</b>：{@code listSongs} 的 {@code listFiles()}、{@code SongDecoders.parse}
 * 都是磁盘 IO。长时间运行后目录变成断开的网络盘 / 休眠的机械盘 / 卡住的 SMB 挂载时，
 * 后台线程可以永远不返回。原代码没有任何超时。</li>
 * </ol>
 * 兜底由三件东西组成（都只在客户端线程上跑，不引入新的并发）：
 * <ul>
 * <li>{@link #generation}：每次"放弃一次在途任务"就 +1；后台结果带的是提交时的代次，
 * 代次对不上直接丢弃——被放弃的僵尸任务<b>晚到的结果不会覆盖新任务的结果</b>。</li>
 * <li>{@link #watchdogMillis} 看门狗：{@code RUNNING} 超过这个时长还没有结果，就把它当成卡死，
 * 放弃并解卡（落回可再次搜索的状态；卡死期间用户点过的那次请求由 {@link #poll} 补跑）。</li>
 * <li>{@code start()} 里的 try/catch：提交阶段抛异常 = 直接 {@code fail()}，不发任务也不留 RUNNING。</li>
 * </ul>
 * 三者的共同点：<b>任何一条路径离开 RUNNING 都会写一行 MUSICBOT 日志</b>说明"我是从哪个状态被救回来的"，
 * 这样下次用户说"又卡了"，日志自己就把现场说清了。
 *
 * <h3>这套兜底是验证过的，不是"看了一遍觉得对"</h3>
 * 用一份不起游戏的独立验证（桩掉 {@code MeteorClient}/{@code MeteorExecutor}，见交付说明里的
 * 验证脚本）跑过四个场景：后台永不返回→解卡后新搜索真的被提交；卡死期间又点一次→补跑最后一次点击；
 * 后台真的跑到 {@code scan} 且目录不存在→是失败结果而不是异常/永久 RUNNING；正常搜索不被误杀。
 * <b>第一版就是被这套验证抓出错的</b>：当时看门狗自己调 {@code start(null, next)} 补跑，
 * 而 {@code start} 拿到 null store 立刻判失败——补跑等于没补。所以补跑放在 {@link #poll} 里
 * （那里才有 store 引用）。
 *
 * <h3>线程从哪来</h3>
 * 用的是 Meteor 自己的 {@link MeteorExecutor}（唯一的现成后台执行器，{@code javap} 核实：
 * {@code newCachedThreadPool} + {@code setDaemon(true)} + 线程名 {@code Meteor-Executor-N}），
 * 不自己 new Thread、也不会泄漏线程。它不是单线程池，所以"同一时刻只有一个搜索"这件事由本类的
 * 单飞闸门保证，而不是靠池的大小。
 */
public final class MusicBotSearch {

    /** 界面能看到的四种状态（{@link #IDLE} = 还没查过）。 */
    public enum State {
        IDLE,
        RUNNING,
        DONE,
        FAILED
    }

    /**
     * 后台结果信箱：后台线程 {@code set}，客户端线程 {@code getAndSet(null)}。
     *
     * <p>用 {@link AtomicReference} 而不是普通字段：它同时提供了"跨线程可见性"和"只有一个消费者能
     * 取到同一个结果"这两件事，不需要任何锁。信箱里最多只会有一个结果（单飞保证）——除了
     * "看门狗放弃了一个卡住的任务"这一种情况：那个僵尸任务晚到的结果会和当前任务的结果抢信箱。
     * 所以信箱里存的是 {@link Stamped}（结果 + 提交时的代次），{@link #poll} 按代次认领。
     */
    private final AtomicReference<Stamped> incoming = new AtomicReference<>();

    // ---- 以下字段只在客户端线程读写（见类注释）----

    /** 当前状态。 */
    private State state = State.IDLE;
    /** 当前（或最近一次）请求的查询串；空串 = 只扫目录。 */
    private String query = "";
    /** 最近一次成功搜索的结果（不可变列表）；只给界面读。 */
    private List<SongInfo> matches = List.of();
    /** 最近一次失败原因；只在 {@link State#FAILED} 时有意义。 */
    private String error;
    /** 搜索进行中又提交的查询（最后一次胜出）；没有排队时为 null。 */
    private String queuedQuery;
    /** 上一个已合并结果对应的查询串（"" = 只扫目录）。 */
    private String lastAppliedQuery = "";
    /** 上一个已合并结果的失败原因；null = 成功。 */
    private String lastAppliedError;
    /** 状态版本号：每次"界面上能看到的东西"变了就 +1，界面据此决定要不要重建结果表。 */
    private int version;

    /**
     * 当前在途任务的代次。每次提交 +1；看门狗放弃一个卡住的任务时也 +1
     * （这样那个僵尸任务晚到的结果会因为箱子上的号对不上而被丢掉）。
     */
    private int generation;

    /** 最近一次提交后台任务的时刻（{@code System.currentTimeMillis()}）；看门狗用它算已经等了多久。 */
    private long lastStartMillis;

    /** 看门狗阈值（毫秒）。正常一次"扫目录 + 解析命中项"是毫秒级，卡到 20 秒按"卡死"处理。 */
    private static volatile long watchdogMillis = 20_000L;

    /**
     * 后台结果 + 它属于哪一代。
     *
     * @param generation 提交这个任务时 {@link #generation} 的值
     */
    private record Stamped(int generation, ScanResult result) {
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public String query() {
        return query;
    }

    /** 失败原因；没失败返回 null。 */
    public String error() {
        return state == State.FAILED ? error : null;
    }

    public List<SongInfo> matches() {
        return matches;
    }

    public int version() {
        return version;
    }

    /** 上一个已合并结果对应的查询串（紧跟在 {@link #poll} 返回 true 之后读才有意义）。 */
    public String lastAppliedQuery() {
        return lastAppliedQuery;
    }

    /** 上一个已合并结果的失败原因；null = 成功。 */
    public String lastAppliedError() {
        return lastAppliedError;
    }

    /**
     * 当前这次搜索已经跑了多少毫秒；不在 {@link State#RUNNING} 时返回 0。
     *
     * <p>给界面/命令的"自检"用：{@code search=RUNNING(23000ms)} 这种显示，一眼就能看出
     * "是卡死了还是在正常跑"（看门狗阈值 {@link #watchdogMillis}）。
     */
    public long runningMillis() {
        if (state != State.RUNNING) return 0L;
        long start = lastStartMillis;
        if (start <= 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - start);
    }

    /** 看门狗阈值（毫秒）——给自检输出用，免得界面自己写死一个数字。 */
    public static long watchdogMillis() {
        return watchdogMillis;
    }

    /**
     * 改看门狗阈值（毫秒）。<b>只给独立验证用</b>：真跑一次 20 秒的卡死没法写进自动化检查。
     * 生产代码里没有任何一处调它（和 {@code MusicBotStore.setDataDirForTesting} 同一个约定）。
     */
    public static void setWatchdogMillisForTesting(long millis) {
        watchdogMillis = millis;
    }

    /**
     * 提交一次"扫目录 +（{@code query} 非空时）找歌"。**立即返回**：列目录、读文件、解析元数据
     * 全在后台线程。
     *
     * @param query 空串 / null = 只扫目录（模块 onActivate 用；顺手把索引和缓存预热）
     */
    public void request(MusicBotStore store, String query) {
        String q = query == null ? "" : query.trim();
        if (state == State.RUNNING) {
            queuedQuery = q;    // 最后一次点击胜出，不取消正在跑的那个（见类注释）
            return;
        }
        start(store, q);
    }

    /**
     * 客户端线程：把已完成的后台结果合并进 store，并给单飞闸门做一次体检。
     *
     * <p>不管有没有结果，每次调用都会先跑 {@link #watchdog()}：单飞闸门卡住时"没有任何结果到达"，
     * 只在有结果时才检查的看门狗等于没有。<b>这一步是 O(1) 的两次比较，可以每帧调。</b>
     *
     * @return true = 这次真的合并了一个结果（调用方据此决定要不要给用户反馈）
     */
    public boolean poll(MusicBotStore store) {
        watchdog();

        Stamped stamped = incoming.getAndSet(null);
        if (stamped != null && stamped.result() != null) {
            if (stamped.generation() != generation) {
                // 僵尸任务晚到的结果：它属于已经被放弃的那一代，合并进去只会覆盖新任务的结果。
                log("丢弃过期搜索结果（结果代次 " + stamped.generation() + "，当前代次 " + generation
                    + "，query=\"" + stamped.result().query() + "\"）");
            } else {
                ScanResult result = stamped.result();

                // 唯一的 store 写入点：只在客户端线程上跑
                if (store != null) store.applyScan(result);
                lastAppliedQuery = result.query() == null ? "" : result.query();
                lastAppliedError = result.error();

                if (queuedQuery != null) {
                    // 还有一次"最后一次点击"等着跑：直接接着跑，界面保持"搜索中…"，不发布中间那次结果
                    String next = queuedQuery;
                    queuedQuery = null;
                    start(store, next);
                    version++;
                    return true;
                }

                query = lastAppliedQuery;
                matches = result.matches();
                error = result.error();
                state = result.ok() ? State.DONE : State.FAILED;
                lastStartMillis = 0L;
                version++;
                return true;
            }
        }

        // 看门狗刚放弃了一次卡死的搜索、并且用户在卡死期间还点过「查找」：在这里补跑。
        //
        // 为什么不在 watchdog() 里直接补跑：看门狗是在 poll 开头跑的，那时候**还没有 store 引用**
        // （poll 的 store 参数），而 start() 需要一个 store 才能做快照。第一版就是在那里调了
        // start(null, ...)，结果补跑立刻被 start 里的"拿不到 MusicBotStore"判失败 —— 独立验证
        // 直接把这个错误抓出来了（scenario2）。所以把补跑放在这里，用真正的 store。
        if (state == State.FAILED && queuedQuery != null) {
            String next = queuedQuery;
            queuedQuery = null;
            version++;                    // 让界面重画：失败的提示不该继续挂着
            start(store, next);
            return true;
        }
        return false;
    }

    /**
     * 自检/解卡：{@link State#RUNNING} 超过 {@link #watchdogMillis} 还没有结果 = 单飞闸门卡死。
     *
     * <p>处理方式：把 {@link #generation} +1 判死在途任务（它晚到的结果会被 {@link #poll} 丢掉），
     * 然后落到 {@link State#FAILED} 并给出人能看懂的原因。若用户在卡死期间又点过「查找」
     * （{@link #queuedQuery} 非空），{@link #poll} 会在同一次调用里用真正的 store 把它补跑掉——
     * 补跑**不能**放在这里，因为看门狗没有 store 引用（这一点是独立验证抓出来的，见 poll 的注释）。
     *
     * <p>每次解卡都会写一行 MUSICBOT 日志——"我从哪个状态被救回来的"必须留在日志里。
     */
    private void watchdog() {
        if (state != State.RUNNING) return;
        long start = lastStartMillis;
        if (start <= 0L) return;
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed <= watchdogMillis) return;

        generation++;                 // 判死：那个僵尸任务的结果从此对不上号
        String stuckQuery = query;
        if (queuedQuery != null) {
            log("搜索看门狗：query=\"" + stuckQuery + "\" 已 RUNNING " + elapsed + "ms（阈值 "
                + watchdogMillis + "ms）判为卡死并放弃；排队中的 query=\"" + queuedQuery + "\" 立刻补跑");
        } else {
            log("搜索看门狗：query=\"" + stuckQuery + "\" 已 RUNNING " + elapsed + "ms（阈值 "
                + watchdogMillis + "ms）判为卡死并放弃，已回到可再次搜索的状态");
        }
        fail("上一次搜索卡住了（超过 " + (watchdogMillis / 1000) + " 秒没有结果，后台线程大概被磁盘 IO 卡住了），"
            + "已放弃那一次，可以重新点「查找」。");
    }

    private void start(MusicBotStore store, String query) {
        this.query = query;
        this.matches = List.of();
        this.error = null;
        this.state = State.RUNNING;
        this.generation++;
        this.lastStartMillis = System.currentTimeMillis();
        this.version++;

        if (store == null) {
            fail("内部错误：拿不到 MusicBotStore。");
            return;
        }
        if (MeteorExecutor.executor == null) {
            // javap 核实：MeteorClient.onInitializeClient 里 AddonManager.init()（= 各 addon 的
            // onInitialize）先跑，ReflectInit.init(PreInit.class)（= MeteorExecutor.init()）后跑，
            // 所以正常玩到这一步必然已就绪。真为 null 也不许抛：给一句人能看懂的话。
            fail("后台线程池还没就绪（MeteorExecutor 未初始化）。");
            return;
        }

        final int gen = generation;
        try {
            ScanInput input = store.snapshotForScan();   // 主线程浅拷贝，之后只读
            final String q = query;                      // 只把不可变值带进 lambda，别让后台读 this.query
            // 后台任务自己包 try/catch：MusicBotStore.scan 内部虽然到处都是 catch，但
            // "把结果放进信箱"这一句必须在任何情况下都执行到——它是单飞闸门唯一的开门钥匙。
            // 漏了这一句（Error / 线程池拒绝 / scan 里将来新加的抛点）就等于永久 RUNNING。
            MeteorExecutor.execute(() -> {
                ScanResult result;
                try {
                    result = MusicBotStore.scan(input, q);
                } catch (Throwable t) {
                    result = MusicBotStore.failure(q, "后台扫描任务异常：" + t);
                    log("后台扫描任务抛出 " + t + "（query=\"" + q + "\"），已按失败结果投递");
                }
                incoming.set(new Stamped(gen, result));
            });
        } catch (Throwable t) {
            // 提交阶段就抛（快照构造失败 / 线程池拒绝 / OOM）：绝不能把 state 留在 RUNNING
            fail("提交后台搜索任务失败：" + t);
            log("提交后台搜索任务失败（query=\"" + query + "\"）：" + t);
        }
    }

    private void fail(String message) {
        this.state = State.FAILED;
        this.error = message;
        this.matches = List.of();
        this.lastStartMillis = 0L;
        this.version++;
    }

    /**
     * 写一行调试日志（前缀 MUSICBOT）。
     *
     * <p>和 {@code MusicBotStore.log} 同一个理由包 Throwable：{@code MeteorClient} 的静态初始化
     * 在没有 Fabric 的环境里是 {@link Error}，日志写不出来是小事，因为写不出来而把搜索搞崩是大事。
     */
    private static void log(String msg) {
        try {
            MeteorClient.LOG.info("MUSICBOT {}", msg);
        } catch (Throwable ignored) {
        }
    }
}
