package com.reborn.modularmachinery.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一段<strong>沿某个面重复</strong>的结构：装配线的「一节」。
 *
 * <h2>它解决什么问题</h2>
 *
 * <p>普通机器的结构是固定的。装配线不是：它是一条<strong>可以任意加长</strong>的线，同一个世界里 5 节、
 * 另一个世界里 15 节都是合法的同一台机器。所以结构定义里要有一段「重复单元」，让结构长度成为变量。
 *
 * <p>本类对应旧版的 {@code github.kasuminova.mmce.common.util.DynamicPattern}（395 行）。
 *
 * <h2>三个键在两个层级上同名，这是最容易读错的地方</h2>
 *
 * <p>机器定义的根上有 {@code parts}（固定基座，含控制器），而 {@code dynamic-patterns} 里的每一项也有
 * {@code parts}（重复单元）。同一个键名、两种含义。{@code parts-end} 是第三段：只出现<strong>一次</strong>的收尾单元。
 *
 * <pre>{@code
 * 控制器 + 基座（根 parts）        ← 头，固定
 *   ↓ 沿 faces 方向重复
 * [重复段]（本类的 parts）× N      ← 中间，N 在 [minSize, maxSize] 之间
 *   ↓
 * [收尾段]（parts-end，可选）× 1   ← 尾
 * }</pre>
 *
 * <h2>匹配算法要照抄的两个细节</h2>
 *
 * <p>见 {@link #matchesSize}：太长的结构是<strong>不匹配</strong>而不是截断接受；以及回退一格之后还要再验一次
 * 收尾段。第二条的理由旧版写在注释里（{@code Prevent overlapping structures}），而它防的是「两段结构互相
 * 吃掉对方的格子」——在世界里表现为机器偶尔不成型，极难查。
 */
public final class DynamicPattern {

    private final String name;
    private final Set<Direction> faces;

    /** 最少重复几节才算成型。旧版默认 0，且它自己的读写器不给下限检查。 */
    private final int minSize;

    /** 最多重复几节。超过就<strong>不匹配</strong>，不是截断。 */
    private final int maxSize;

    /** 重复单元：沿 {@link #faces} 重复的那一节。 */
    private final MachinePattern part;

    /** 收尾单元，只出现一次；没有就是 {@code null}。 */
    @Nullable
    private final MachinePattern endPart;

    /** 第一节的起点偏移（相对控制器）。 */
    private final BlockPos startOffset;

    /** 每一节之间的步进偏移。 */
    private final BlockPos stepOffset;

    public DynamicPattern(String name, Set<Direction> faces, int minSize, int maxSize,
                          MachinePattern part, @Nullable MachinePattern endPart,
                          BlockPos startOffset, BlockPos stepOffset) {
        this.name = name;
        this.faces = Set.copyOf(faces);
        this.minSize = minSize;
        this.maxSize = maxSize;
        this.part = part;
        this.endPart = endPart;
        this.startOffset = startOffset;
        this.stepOffset = stepOffset;
    }

    public String name() {
        return this.name;
    }

    /** 定义里声明的那几个延伸方向（控制器朝北时的字面值）。 */
    public Set<Direction> faces() {
        return this.faces;
    }

    public int minSize() {
        return this.minSize;
    }

    public int maxSize() {
        return this.maxSize;
    }

    public MachinePattern part() {
        return this.part;
    }

    @Nullable
    public MachinePattern endPart() {
        return this.endPart;
    }

    public BlockPos startOffset() {
        return this.startOffset;
    }

    public BlockPos stepOffset() {
        return this.stepOffset;
    }

    /** 这个模式的收尾段是否存在。 */
    public boolean hasEnd() {
        return this.endPart != null;
    }

    /**
     * 量出这个模式在世界里当前重复了几节，并给出实际匹配上的方向；节数不足 {@link #minSize} 时算不匹配。
     *
     * <p>{@code minSize} 在旧版是<strong>解析了却不使用</strong>的：它被读进来、被存下来、也被写进 NBT，
     * 但匹配路径上没有任何一处读它（{@code matchesSize} 的返回值只与 {@code maxSize} 比较）。
     * 本版把 {@code >= minSize} 接上，理由是本项目对「作者写下的字段」的一贯做法：
     * 一个看起来在限制、实际不限制的字段正是本项目反复付代价的那种失败模式。
     * 这是一处<strong>有意偏离</strong>旧版，它只会让定义更严格，不会让既有的合法结构失效——
     * 除非作者写的 {@code minSize} 本来就没被遵守过。
     *
     * @param level         世界
     * @param controller    控制器位置（所有偏移都相对它）
     * @param rotationSteps 控制器朝向相对 NORTH 的逆时针四分之一圈数（北 0、西 1、南 2、东 3）
     */
    public MatchResult match(Level level, BlockPos controller, int rotationSteps) {
        Set<Direction> actual = trueFacing(this.faces, rotationSteps);
        BlockPos start = rotate(this.startOffset, rotationSteps);
        BlockPos step = rotate(this.stepOffset, rotationSteps);

        // 多个方向都能匹配时取**最长**的那个，这是旧版的行为
        // （`DynamicPattern.matches` 末尾的 max(comparingInt(value))）。取最长而不是取第一个，
        // 是因为一台机器的长度是它自己的事实，不该取决于我们遍历方向的顺序。
        MatchResult best = MatchResult.NO_MATCH;
        for (Direction face : actual) {
            int size = matchesSize(pos -> this.part.matches(level, pos),
                    pos -> this.endPart != null && this.endPart.matches(level, pos),
                    controller, start, step);
            if (size >= this.minSize && size > best.size()) {
                best = new MatchResult(size, face);
            }
        }
        return best;
    }

    /**
     * {@link #match} 的核心，抽成「问一个位置成不成立」的谓词形式。
     *
     * <p><strong>为什么要有这个重载：</strong>数长度这段算术是这个特性里最值得测、也最容易写错的部分
     * （上限判定、收尾段的两处检查、回退一格防重叠），而它的自然形态需要一个 {@code Level}——
     * 本项目的离线复现器没有世界，于是这段逻辑会变成「测不到的代码」。
     * 把「世界」缩小成两个 {@code BlockPos -> boolean} 之后，复现器可以用一个位置集合造出任意布局，
     * 把每一种边界都验一遍。
     *
     * <p>公开的 {@link #match} 只是把真实的 {@link MachinePattern} 匹配包成这两个谓词，本身不含算术。
     *
     * @param partAt    某个位置是否是合法的重复段
     * @param endPartAt 某个位置是否是合法的收尾段（没有收尾段时恒为 false）
     * @return 节数（{@code >= 0}），或者 {@code -1} 表示不匹配
     */
    /**
     * 量长度：从起点开始一节一节地数，直到不匹配、或者数到收尾段。
     *
     * <p>这是旧版 {@code DynamicPattern#matchesSize} 的对应实现，而下面几处<strong>必须照抄</strong>：
     *
     * <ol>
     *   <li>节数超过 {@code maxSize} 时返回 {@code -1}。<strong>过长的结构是不匹配，不是被截断接受</strong>——
     *       如果截断接受，玩家可以把线无限接长而机器仍然成型，长度上限就形同虚设。</li>
     *   <li>遇到不匹配时，先看<strong>当前这一格</strong>是不是收尾段；是的话节数就是已经数过的那些。</li>
     *   <li>当前这一格不是收尾段时，<strong>回退一格再验一次</strong>。旧版给这一步写的理由是
     *       {@code Prevent overlapping structures}：两个模式相邻时，一方的收尾段可能正好落在另一方重复段的位置上，
     *       不验这一下会让两段结构互相吃掉格子——在世界里表现为「机器偶尔不成型」，极难查。</li>
     *   <li>收尾段也顶上第一节的位置时（回退到起点、收尾段在那里匹配），结果是 <b>0 节</b>且<strong>成立</strong>：
     *       一个只有收尾段的动态结构是合法的。这一点与「一节都不匹配」要分开看，后者才是不成立。</li>
     * </ol>
     *
     * @param partAt    某个位置是否是合法的重复段
     * @param endPartAt 某个位置是否是合法的收尾段（没有收尾段时恒为 false）
     * @return 节数（{@code >= 0} 且被收尾段证实过），或者 {@code -1} 表示不匹配
     *
     * <p><strong>为什么是 {@code public} 而不是包私有：</strong>本项目的离线复现器在另一个包里，
     * 而这段算术<strong>必须</strong>被它覆盖到。它没有副作用、不碰世界、只读自己的两个字段与 {@code maxSize}，
     * 所以公开它不会给任何人留下破坏不变量的机会——换来的是一段否则永远测不到的算法。
     */
    public int matchesSize(java.util.function.Predicate<BlockPos> partAt,
                    java.util.function.Predicate<BlockPos> endPartAt,
                    BlockPos controller, BlockPos start, BlockPos step) {
        BlockPos offset = controller.offset(start);
        int count = 0;

        while (true) {
            if (partAt.test(offset)) {
                count++;
                if (count > this.maxSize) {
                    // 太长：不匹配，而不是截断接受。
                    return -1;
                }
                offset = offset.offset(step);
                continue;
            }

            // 这一格不是重复段。它要么是收尾段本身，要么是收尾段之后的那一格。
            if (this.endPart != null) {
                if (endPartAt.test(offset)) {
                    return count;
                }
                // 回退一格再验一次——旧版注释里写的 Prevent overlapping structures。
                BlockPos back = offset.subtract(step);
                if (endPartAt.test(back)) {
                    return count - 1;
                }
                return -1;
            }
            // 没有收尾段：必须至少数到一节，否则这个模式没成型。
            return count == 0 ? -1 : count;
        }
    }

    /**
     * 把「重复 {@code size} 节」展开成一个完整结构，供端口收集与建造预览使用。
     *
     * <p>这是旧版 {@code DynamicPattern#addPatternToBlockArray} 的对应实现。它产出的标签名就是作者在配方里
     * 引用的名字，格式逐字沿用旧版：
     *
     * <ul>
     *   <li>第 {@code i} 节的部件：{@code <selector-tag>_<模式名>_<i>}，节号从 0 开始</li>
     *   <li>收尾段的部件：{@code <selector-tag>_<模式名>_end}（没有节号——一个模式只有一个尾）</li>
     * </ul>
     *
     * <p><strong>没有 {@code selector-tag} 的部件不会得到标签</strong>，这也与旧版一致：它遍历的是
     * {@code getTaggedPositions()}，而只有打了标签的位置才在那个映射里。于是「结构里的普通方块」
     * 对配方而言仍然是无标签的，只有作者显式命名过的部件才能被配方指认。
     *
     * @param size          重复几节（{@code match} 量出来的数）
     * @param rotationSteps 控制器朝向相对 NORTH 的逆时针四分之一圈数；标签与偏移都按它旋转
     */
    public MachinePattern expand(int size, int rotationSteps) {
        MachinePattern.Builder builder = MachinePattern.builder();
        BlockPos offset = rotate(this.startOffset, rotationSteps);
        BlockPos step = rotate(this.stepOffset, rotationSteps);

        for (int index = 0; index < size; index++) {
            if (index > 0) {
                offset = offset.offset(step);
            }
            addSection(builder, this.part, offset, rotationSteps, index, false);
        }

        if (this.endPart != null) {
            addSection(builder, this.endPart, offset.offset(step), rotationSteps, 0, true);
        }
        return builder.build();
    }

    private void addSection(MachinePattern.Builder builder, MachinePattern section, BlockPos offset,
                            int rotationSteps, int index, boolean end) {
        for (Map.Entry<BlockPos, List<BlockMatcher>> entry : section.positions().entrySet()) {
            BlockPos pos = rotate(entry.getKey(), rotationSteps).offset(offset);
            builder.add(pos, entry.getValue());
            String selectorTag = section.tag(entry.getKey());
            if (selectorTag != null) {
                // 这个部件在定义里被作者命名过，于是它在**这一段**里得到一个带段号的标签。
                builder.tag(pos, end ? endTag(selectorTag, this.name)
                        : segmentTag(selectorTag, this.name, index));
            }
        }
    }

    /**
     * 把定义里的朝向换算成世界里实际要验的朝向，对应旧版 {@code DynamicPattern#getTrueFacing}。
     *
     * <p><strong>上、下不参与旋转</strong>，这是旧版明确的行为（它在那两个值上直接返回原值）。
     * 理由很自然：绕 Y 轴旋转不影响「上」和「下」这两个方向本身。
     */
    public static Set<Direction> trueFacing(Set<Direction> declared, int rotationSteps) {
        Set<Direction> out = new LinkedHashSet<>();
        for (Direction face : declared) {
            if (face == Direction.UP || face == Direction.DOWN) {
                out.add(face);
                continue;
            }
            Direction rotated = face;
            for (int i = 0; i < rotationSteps; i++) {
                rotated = rotated.getCounterClockWise();
            }
            out.add(rotated);
        }
        return out;
    }

    /**
     * 把一个偏移按朝向旋转，对应旧版 {@code DynamicPattern#rotatePosTo}（它从 NORTH 起逐次逆时针转）。
     *
     * <p>用的是与 {@link MachinePattern#rotateYCounterClockwise} 同一个变换 {@code (x, y, z) -> (z, y, -x)}——
     * 两处必须是同一个，否则结构会成型而它的动态部分错位。
     */
    public static BlockPos rotate(BlockPos pos, int rotationSteps) {
        BlockPos rotated = pos;
        for (int i = 0; i < rotationSteps; i++) {
            rotated = new BlockPos(rotated.getZ(), rotated.getY(), -rotated.getX());
        }
        return rotated;
    }

    /**
     * 一次匹配的结果。
     *
     * @param size  数到的节数；{@code 0} 表示不匹配
     * @param face  实际匹配上的那个面；不匹配时为 {@code null}
     */
    public record MatchResult(int size, @Nullable Direction face) {

        public static final MatchResult NO_MATCH = new MatchResult(0, null);

        public boolean isMatched() {
            return this.size > 0 && this.face != null;
        }
    }

    /**
     * 一个模式在某台已成型机器上的<strong>定格</strong>：哪一面、几节。
     *
     * <p>为什么必须定格并存进 NBT：动态结构的长度在世界上是变化的，而配方对某一段的绑定
     * （{@code selector-tag} 里的段号）必须在成型那一刻确定下来。如果每一 tick 重新量一次，
     * 玩家加长一节就会让配方指向别的段，正在跑的合成会静默换料。旧版把这三个值存进 NBT 也是这个原因
     * （{@code TileMultiblockMachineController.java:1435/1490}）。
     */
    public record Status(String patternName, int size, Direction face) {
    }

    /**
     * 第 {@code index} 节的标签名，对应旧版 {@code DynamicPattern#addPatternToBlockArray} 里的格式串
     * {@code "%s_%s_%d"}（{@code 标签_模式名_段号}）。
     *
     * <p><strong>这个格式是硬约定</strong>：作者写的配方里就是靠它指认「第几段」的，改一个字符就与旧版不兼容，
     * 也会让作者既有的配方失效。段号从 0 开始，与旧版的循环变量一致。
     */
    public static String segmentTag(String selectorTag, String patternName, int index) {
        return selectorTag + "_" + patternName + "_" + index;
    }

    /**
     * 收尾段的标签名，对应旧版的 {@code "%s_%s_end"}。
     *
     * <p>收尾段不带段号：一个模式只有一个尾，加段号只会让作者多写一个没意义的数字。
     */
    public static String endTag(String selectorTag, String patternName) {
        return selectorTag + "_" + patternName + "_end";
    }
}
