package com.reborn.modularmachinery.machine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 机器定义里**根字段**的解析，逐条对应旧版的 {@code DynamicMachinePreDeserializer}。
 *
 * <h2>为什么要单独一个类</h2>
 *
 * <p>旧版把这些「读一个根字段、错了就抛一条明确的话」的方法集中在一个 pre-deserializer 里，本版照做，
 * 理由有两条：
 *
 * <ol>
 *   <li><b>错误文案属于接口的一部分。</b>包作者写错一个字段时看到的那句话，就是他与这个模组之间的
 *       唯一反馈。集中在一处才可能保证它与旧版逐条对应（{@link #color} 的两条就是旧版原文的意思）。</li>
 *   <li><b>可测。</b>这些方法是纯函数（{@code JsonObject} → 值），所以离线复现器可以直接驱动它们，
 *       不必建一台机器、也不必进游戏。波 6 的其它项都做不到这一点。</li>
 * </ol>
 */
public final class MachineRootFields {

    private MachineRootFields() {
    }

    /**
     * 定义里的 {@code color}：**十六进制字符串**，例如 {@code "FF00FF"}。
     *
     * <p>与旧版 {@code DynamicMachinePreDeserializer#getColor} 的对应关系，以及一处**有意差异**，
     * 都写在 {@link MachineDefinition#parseColor} 上——这里只负责把它接到解析链里。
     *
     * @param root  定义的根对象
     * @param where 出错信息里用来指认是哪个文件（作者据此找到那一行）
     * @return 颜色值；没有 {@code color} 字段时返回 {@link MachineDefinition#DEFAULT_COLOR}
     * @throws IllegalArgumentException 字段存在但不是「恰好六位十六进制」（含旧版的两条错误情形）
     */
    public static int color(JsonObject root, String where) {
        JsonElement element = root.get("color");
        if (element == null) {
            // 没有这个字段就是「不染色」，与旧版 definedColor 从默认值起步等价。
            return MachineDefinition.DEFAULT_COLOR;
        }
        return MachineDefinition.parseColor(root, where);
    }
}
