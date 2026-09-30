package com.aefmusic.util;

import com.aefmusic.util.MusicBotStore.ScanInput;
import com.aefmusic.util.MusicBotStore.ScanResult;
import com.aefmusic.util.MusicBotStore.SongInfo;
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
 * {@link #queuedQuery}、{@link #lastAppliedQuery}、{@link #lastAppliedError}。</li>
 * <li><b>主线程 → 后台线程</b>：一个 {@link ScanInput}（主线程在 {@code snapshotForScan()} 里做的
 * 只读浅拷贝）+ 查询串。两者通过 {@code ExecutorService.execute} 提交，提交/执行之间有
 * happens-before 保证；后台线程<b>只读</b>它们，绝不回头读 store 的字段。</li>
 * <li><b>后台线程 → 主线程</b>：{@link #incoming}（{@link AtomicReference}）是唯一的跨线程可变引用。
 * 后台线程只做一次 {@code set}（放进去的 {@link ScanResult} 全字段不可变、内部集合不可变），
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
     * 取到同一个结果"这两件事，不需要任何锁。信箱里最多只会有一个结果（单飞保证）。
     */
    private final AtomicReference<ScanResult> incoming = new AtomicReference<>();

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
     * 客户端线程：把已完成的后台结果合并进 store。
     *
     * @return true = 这次真的合并了一个结果（调用方据此决定要不要给用户反馈）
     */
    public boolean poll(MusicBotStore store) {
        ScanResult result = incoming.getAndSet(null);
        if (result == null) return false;

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
        version++;
        return true;
    }

    private void start(MusicBotStore store, String query) {
        this.query = query;
        this.matches = List.of();
        this.error = null;
        this.state = State.RUNNING;
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

        ScanInput input = store.snapshotForScan();   // 主线程浅拷贝，之后只读
        final String q = query;                      // 只把不可变值带进 lambda，别让后台读 this.query
        MeteorExecutor.execute(() -> incoming.set(MusicBotStore.scan(input, q)));
    }

    private void fail(String message) {
        this.state = State.FAILED;
        this.error = message;
        this.matches = List.of();
        this.version++;
    }
}
