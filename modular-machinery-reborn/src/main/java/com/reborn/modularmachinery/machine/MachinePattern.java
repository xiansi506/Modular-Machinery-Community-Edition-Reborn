package com.reborn.modularmachinery.machine;

import com.google.gson.JsonParseException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The static, data-driven structure of a machine definition.
 *
 * <p>Coordinates are relative to the controller, which sits at {@code (0,0,0)} and is not part of the pattern
 * — matching the original, where {@code DynamicMachine} added {@code parts} and then removed the origin. The
 * pattern may span any shape and any number of Y levels; the bounding box is derived from the coordinates,
 * also as in {@code BlockArray.updateSize}.
 *
 * <p>Each position holds a list of acceptable blocks (the original's {@code elements} array).
 */
public final class MachinePattern {

    private final Map<BlockPos, List<BlockMatcher>> positions;

    /**
     * 打了 <strong>selector-tag</strong> 的位置 → 标签名，没有标签的位置不在里面。
     *
     * <p>这是旧版 {@code TaggedPositionBlockArray} 的对应层：旧版把「哪个位置属于哪个部件」这件事记在结构数组里，
     * 配方就能通过 {@code selector-tag} 精确指定「原料送到哪儿」。M1 当时有意没移植它——那时没有消费者；
     * 波 3 的 {@code dynamic-patterns} 就是那个消费者（动态结构会生成 {@code input_line_0} 这样的标签）。
     *
     * <p>只有一个<strong>空映射</strong>作为「没有标签」的表示，没有 {@code null}：绝大多数结构根本没有标签，
     * 让它们共享同一个不可变空映射比到处判空干净。
     */
    private final Map<BlockPos, String> tags;

    private final BlockPos min;
    private final BlockPos max;
    private final BlockPos size;

    private MachinePattern(Map<BlockPos, List<BlockMatcher>> positions, Map<BlockPos, String> tags,
                          BlockPos min, BlockPos max, BlockPos size) {
        this.positions = Collections.unmodifiableMap(positions);
        this.tags = Collections.unmodifiableMap(tags);
        this.min = min;
        this.max = max;
        this.size = size;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<BlockPos, List<BlockMatcher>> positions() {
        return this.positions;
    }

    /**
     * {@code selector-tag} 在某位置上的标签名，没有则为 {@code null}。
     *
     * <p>调用者通常是结构扫描：它按位置遍历，把标签一并交给 {@link HatchCollection}，
     * 于是配方侧就能按标签筛选端口。
     */
    @javax.annotation.Nullable
    public String tag(BlockPos pos) {
        return this.tags.get(pos);
    }

    /** 完整的位置 → 标签映射，供诊断与断言使用。没有标签的结构答一个空映射。 */
    public Map<BlockPos, String> tags() {
        return this.tags;
    }

    /** 这个结构里有没有任何打了标签的位置。 */
    public boolean hasTags() {
        return !this.tags.isEmpty();
    }

    /**
     * 把另一个结构并进来，返回合并后的新结构。
     *
     * <p>用途是波 3b：基座（定义里的 {@code parts}）与量出来的动态段各自是一个 {@code MachinePattern}，
     * 而<strong>端口收集、建造预览、结构诊断都只认一个</strong>结构。与其让每一处都学会「基座 + N 个动态段」，
     * 不如在匹配那一刻就合成一个——调用者拿到的永远是一个完整结构，不知道它是由几部分拼的。
     *
     * <p>两个结构在同一位置都有内容时，接受方块<strong>取并集</strong>：任一方的方块都可以放在那里。
     * 这与 {@link Builder#add} 已有的合并语义一致（同位置多次 add 会把候选方块加起来），
     * 而动态段与基座在同一个格子上要求不同方块是定义本身存在歧义，取并集至少能让它成型而不是随机失败。
     */
    public MachinePattern withMerged(MachinePattern other) {
        Builder builder = builder();
        for (Map.Entry<BlockPos, List<BlockMatcher>> entry : this.positions.entrySet()) {
            builder.add(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<BlockPos, List<BlockMatcher>> entry : other.positions.entrySet()) {
            builder.add(entry.getKey(), entry.getValue());
        }
        // 标签在位置上不重叠（同一位置只属于一个部件），所以两边的标签直接合并。
        // 动态段自己的标签（input_line_0 这种）正是这样进入有效结构的。
        for (Map.Entry<BlockPos, String> entry : this.tags.entrySet()) {
            builder.tag(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<BlockPos, String> entry : other.tags.entrySet()) {
            builder.tag(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    public int partCount() {
        return this.positions.size();
    }

    public BlockPos min() {
        return this.min;
    }

    public BlockPos max() {
        return this.max;
    }

    public BlockPos size() {
        return this.size;
    }

    /** {@code true} when every pattern position holds one of its accepted blocks. */
    public boolean matches(Level level, BlockPos controller) {
        for (Map.Entry<BlockPos, List<BlockMatcher>> entry : this.positions.entrySet()) {
            BlockPos at = controller.offset(entry.getKey());
            if (!level.isLoaded(at)) {
                return false;
            }
            if (!anyMatches(level.getBlockState(at), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyMatches(BlockState state, List<BlockMatcher> accepted) {
        for (BlockMatcher matcher : accepted) {
            if (matcher.matches(state)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把整个结构绕 Y 轴逆时针旋转四分之一圈，对应旧版 {@code MiscUtils.rotateYCCW(pos)} = {@code (z, y, -x)}。
     *
     * <p><strong>标签必须跟着一起转。</strong>旋转是「同一台机器换个朝向成型」，标签认的是<strong>部件</strong>而不是
     * 世界坐标；如果只转位置不转标签，旋转之后 {@code input_line_0} 就会指到另一个部件上，
     * 配方送料的位置会静默地错掉——这类错误在世界里极难看出来，所以它由复现器断言钉住。
     */
    public MachinePattern rotateYCounterClockwise() {
        Builder builder = builder();
        for (Map.Entry<BlockPos, List<BlockMatcher>> entry : this.positions.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockPos rotated = new BlockPos(pos.getZ(), pos.getY(), -pos.getX());
            builder.add(rotated, entry.getValue());
            String tag = this.tags.get(pos);
            if (tag != null) {
                builder.tag(rotated, tag);
            }
        }
        return builder.build();
    }

    public static final class Builder {

        private final Map<BlockPos, List<BlockMatcher>> positions = new LinkedHashMap<>();
        private final Map<BlockPos, String> tags = new LinkedHashMap<>();
        private BlockPos min = new BlockPos(0, 0, 0);
        private BlockPos max = new BlockPos(0, 0, 0);
        private boolean empty = true;

        /** Adds accepted blocks at a relative position, merging with anything already there. */
        public Builder add(BlockPos pos, List<BlockMatcher> accepted) {
            if (accepted.isEmpty()) {
                throw new JsonParseException("Pattern position " + pos + " has an empty element list");
            }
            positions.computeIfAbsent(pos, key -> new ArrayList<>()).addAll(accepted);
            grow(pos);
            return this;
        }

        /**
         * 给一个位置打上 {@code selector-tag}。
         *
         * <p>空或 {@code null} 的标签被当作「没有标签」处理，而不是记下一个空字符串：旧版的
         * {@code ComponentSelectorTag} 构造器同样把 {@code null}/空串归一成「没有标签」
         * （{@code ComponentSelectorTag.java:26-29}），所以一个作者写了 {@code "selector-tag": ""} 的部件，
         * 行为应当与完全没写这个字段一致。
         *
         * <p>同一位置被写多次标签时<strong>后者胜出</strong>——这个位置只能属于一个部件，
         * 而 {@code parts} 里重复写同一坐标本来就是定义错误，这里不为它发明合并规则。
         */
        public Builder tag(BlockPos pos, @javax.annotation.Nullable String tag) {
            if (tag == null || tag.isEmpty()) {
                return this;
            }
            tags.put(pos, tag);
            return this;
        }

        private void grow(BlockPos pos) {
            if (empty) {
                min = pos;
                max = pos;
                empty = false;
                return;
            }
            min = new BlockPos(Math.min(min.getX(), pos.getX()),
                    Math.min(min.getY(), pos.getY()), Math.min(min.getZ(), pos.getZ()));
            max = new BlockPos(Math.max(max.getX(), pos.getX()),
                    Math.max(max.getY(), pos.getY()), Math.max(max.getZ(), pos.getZ()));
        }

        public boolean isEmpty() {
            return positions.isEmpty();
        }

        public MachinePattern build() {
            BlockPos size = empty
                    ? new BlockPos(0, 0, 0)
                    : new BlockPos(max.getX() - min.getX() + 1, max.getY() - min.getY() + 1,
                            max.getZ() - min.getZ() + 1);
            return new MachinePattern(new LinkedHashMap<>(positions), new LinkedHashMap<>(tags), min, max, size);
        }
    }
}
