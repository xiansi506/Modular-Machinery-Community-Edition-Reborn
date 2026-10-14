package com.reborn.modularmachinery.recipe;

import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 一种需求的种类，也就是旧版的 {@code RequirementType}
 * （{@code hellfirepvp.modularmachinery.common.crafting.requirement.type.RequirementType}）。
 *
 * <p><b>为什么需要它。</b>在旧版里这是一项 Forge 注册表内容（{@code RegistriesMM.REQUIREMENT_TYPE_REGISTRY}），
 * 兼容模块（{@code kport.modularmagic} 的 10 种、Mekanism 的气体）就是往那张表里加自己的一行。
 * 本版到 0.32.0 为止没有这张表：五种需求是 {@code ModRecipeSerializers} 里的五个
 * {@code private static final String} 加三处 {@code switch}/{@code instanceof}——
 * **一个兼容模块要加需求，必须改主模组的源码**，所以波 5.2 先把这张表建起来。
 *
 * <p><b>旧版的一项职责为什么不在这里。</b>旧版的 {@code RequirementType} 只管
 * {@code createRequirement(IOType, JsonObject)}（{@code RequirementType.java:27}）。本版把**线上格式**也交给它，
 * 这不是风格选择：本版的 {@code writeRequirement} 是靠一条 {@code instanceof} 链把需求**反推**成种类字符串的
 * （{@code ModRecipeSerializers} 改动前 {@code :212-264}），也就是说「是什么类型」这件事原本由**主模组的一串
 * if**决定。那么一个注册进来的兼容需求能解析、能进配方，**一发包就会抛
 * {@code IllegalStateException("Unsupported requirement …")}**——「单机能跑、联机不行」。
 * 把读写交给类型自己，这条链就不存在了。
 *
 * <p><b>命名空间。</b>{@link #id()} 永远是全限定的，内置五种用
 * {@code modular_machinery_reborn:<path>}。配方文件里写什么见 {@link RequirementTypes#byName}：查找会先按
 * **本模组的命名空间**试一次（于是 {@code item} / {@code modularmachinery:item} /
 * {@code modular_machinery_reborn:item} 三者等价——这是本版既有行为），失败后再按**你注册时写的那个命名空间**
 * 原样试一次。所以模块按自己的 modid 注册（{@code ae2:gas}）是可以被解析到的。
 *
 * <p><b>工厂方法。</b>用 {@link #of} 而不是让实现类写构造器：{@link RequirementTypes#register} 拒绝
 * 空的或重复的 id，工厂方法让「一个 id 对应一个实例」在注册表之外也成立。
 */
public interface RequirementType {

    /**
     * 这种需求的注册键，全限定。
     *
     * <p>它在两处被当作身份使用：配方 JSON 的 {@code type} 字段（去掉命名空间后），以及**网络包里的种类字符串**。
     * 所以同一个 id 必须永远指同一件事，注册之后不可变——{@link RequirementTypes#register} 用「重复即报错」守住这一点。
     */
    ResourceLocation id();

    /**
     * 读一条这种需求。
     *
     * <p>{@code json} 是 {@code requirements} 数组里的**整个**对象，包含 {@code type} 与 {@code io-type}——
     * 与旧版的 {@code createRequirement} 收到的东西相同。{@code ioType} 已经由
     * {@link MachineRequirementParser} 读好并校验过，这样每个种类不必自己再解析一次同一个字段。
     *
     * <p>{@code where} 是报错消息里指认「哪条配方里的哪条需求」用的，照全项目惯例直接拼进消息。
     *
     * <p>实现**不负责** {@code selector-tag}：它对每种需求含义完全相同，
     * 由 {@link MachineRequirementParser#parse} 在调用本方法之后统一挂上（旧版也把它放在
     * {@code ComponentRequirement} 上而不是各类型里）。
     */
    MachineRequirement read(JsonObject json, IOType ioType, Object where);

    /**
     * 把一条需求的正文写进包。
     *
     * <p><b>种类字符串不由这里写。</b>{@code readRequirement} 已经先写了
     * {@code requirement.type().id().toString()}，正文紧跟其后。让每个种类自己再写一遍种类，
     * 就是「同一个值有两处写入点」，而这两处迟早会分叉。
     *
     * <p>{@code requirement} 一定是本类型解析出来的对象（{@link #read} 的产物，或是它经过 {@code selector-tag}
     * 设置之后的那一个）。实现可以直接强转，不需要再判断类型。
     */
    void write(FriendlyByteBuf buf, MachineRequirement requirement);

    /**
     * 读回一条需求的正文。
     *
     * <p>字段顺序必须与 {@link #write} 一一对应；{@code ioType} 已经由
     * {@code readRequirement} 读好，{@code selector-tag} 由它在正文之后读。
     */
    MachineRequirement readBody(FriendlyByteBuf buf, IOType ioType);

    /**
     * 这种需求在配方修改器里算哪一类，旧版 {@code RecipeModifier} 的 {@code target}。
     *
     * <p>旧版是拿注册表里那行 {@code RequirementType} 本身当 target 的
     * （{@code RecipeModifier.java:239}、{@code RecipeModifierBuilder.java:70}），所以「哪一种需求」
     * 与「修改器打给谁」在旧版是同一件事。本版 {@link RecipeModifier.Target} 目前是**闭枚举**
     * （{@code ITEM}/{@code FLUID}/{@code ENERGY}/{@code DURATION}/{@code ALL}），这一处差异已登记为待改：
     * 兼容模块的第六种需求（气体、灵气……）没有合适的枚举项，只能落到 {@code ALL} 或某一项上。
     * **映射到具体项而语义不符时不会报错**——这正是要改掉它的理由。
     */
    RecipeModifier.Target modifierTarget();

    /**
     * 这种需求依赖哪个模组，旧版的 {@code requiresModid()}（{@code RequirementType.java:29-32}），不需要时为
     * {@code null}。
     *
     * <p>它的用途是**兜底**：旧版在注册表冻结之后调
     * {@code IntegrationTypeHelper.filterModIdRequirementTypes}（{@code IntegrationTypeHelper.java:45-58}），
     * 把 {@code requiresModid()} 非空、而那个模组没装的类型**从注册表里摘掉**。本版照做，
     * 见 {@link RequirementTypes#removeUnavailable}.
     */
    @Nullable
    default String requiresModid() {
        return null;
    }

    /**
     * 一种需求的工厂：id 加一个「本模组的五种在哪个模组里」的判定。
     *
     * <p>写成接口里的静态方法，是为了让内置类型和兼容模块用**同一条**构造路径——如果内置类型另有一套写法，
     * 那么兼容模块拿到的就是一条「内置类型没走过」的路，而没走过的路不会有人替你验证。
     */
    static RequirementType of(ResourceLocation id, Handler handler) {
        return of(id, handler, null);
    }

    /**
     * {@link #of(ResourceLocation, Handler)} 加上「这种需求依赖哪个模组」。
     *
     * <p>存在的理由很直白：兼容模块的需求几乎总是依赖它那个模组（旧版每一项都写着
     * {@code requiresModid() = "mekanism"} 这类），所以「注册一个依赖某模组的种类」必须是一句话，
     * 而不是让每个模块各自匿名重写 {@link #requiresModid()}。
     *
     * @param requiresModid 依赖的 modid，或 {@code null}
     */
    static RequirementType of(ResourceLocation id, Handler handler, @Nullable String requiresModid) {
        return new RequirementType() {
            @Override
            public ResourceLocation id() {
                return id;
            }

            @Override
            public MachineRequirement read(JsonObject json, IOType ioType, Object where) {
                return handler.read(json, ioType, where);
            }

            @Override
            public void write(FriendlyByteBuf buf, MachineRequirement requirement) {
                handler.write(buf, requirement);
            }

            @Override
            public MachineRequirement readBody(FriendlyByteBuf buf, IOType ioType) {
                return handler.readBody(buf, ioType);
            }

            @Override
            public RecipeModifier.Target modifierTarget() {
                return handler.modifierTarget();
            }

            @Override
            public String requiresModid() {
                return requiresModid;
            }
        };
    }

    /**
     * {@link #of} 需要的四件事。
     *
     * <p>{@code requiresModid} 不在这里，而在接口本身：所有内置类型都不依赖别的模组，把它们都写成
     * {@code { return null; }} 只是噪声。
     */
    interface Handler {

        MachineRequirement read(JsonObject json, IOType ioType, Object where);

        void write(FriendlyByteBuf buf, MachineRequirement requirement);

        MachineRequirement readBody(FriendlyByteBuf buf, IOType ioType);

        RecipeModifier.Target modifierTarget();
    }
}
