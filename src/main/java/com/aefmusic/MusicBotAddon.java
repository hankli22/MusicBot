package com.aefmusic;

import com.aefmusic.modules.MusicBot;
import com.aefmusic.util.MusicBotCommand;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * MusicBot 的 addon 入口。
 *
 * <h3>这个插件做什么</h3>
 * 只做一件事：给彗星（Meteor Client）自带的 {@code Notebot} 模块加「播放列表 / 随机不重复 / 分组」。
 * 播放本身<b>完全复用 Notebot</b>（{@code loadSong(File)} / {@code play()} / {@code pause()} /
 * {@code stop()} / {@code getStatus()} 都是 public，零反射），本插件只决定"下一首放哪首、什么时候换"。
 * 设计依据与已知取舍见 {@link com.aefmusic.modules.MusicBot} 的类注释。
 *
 * <h3>为什么结构这么小</h3>
 * 它是从 3cHelper+ 那个大 addon 里拆出来的独立插件：只保留一个模块 + 一个命令，
 * 不引用大 addon 的任何类，也不需要 mixin（{@code fabric.mod.json} 里没有 mixins 段）。
 * 注册点只有 {@link #onInitialize()} 一处。
 */
public class MusicBotAddon extends MeteorAddon {

    /** 本插件的模块分类（GUI 左侧那一栏）。 */
    public static final Category CATEGORY = new Category("MusicBot");

    @Override
    public void onInitialize() {
        // 音乐列表：给 Notebot 加播放列表/随机/分组。默认**关**——它会接管 Notebot 的播放状态，
        // 这类"改变别的模块行为"的东西必须由用户主动开。
        Modules.get().add(new MusicBot());
        // 操作面（别名 .mb）：列表/分组是变长的，GUI 里放不下，只能走命令。
        Commands.add(new MusicBotCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.aefmusic";
    }
}
