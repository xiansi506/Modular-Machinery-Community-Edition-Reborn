package com.reborn.modularmachinery.client.geo;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.GeoModelSpec;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineRegistry;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;

/**
 * 一个控制器方块实体的 GeckoLib 动画对象。
 *
 * <p>GeckoLib 的 {@link GeoAnimatable} 不是方块实体本身：它要求一个稳定的实例 id 与一个动画缓存，
 * 而方块实体每个存档都会被重新创建。这里用渲染器持有的 {@code WeakHashMap} 把方块实体映射到一个
 * 包装对象；实例 id 用控制器坐标的 {@code asLong()} 覆盖默认的 {@code hashCode()}，这样同一台机器
 * 在重渲染之间不会因为对象身份变化而重置动画。
 *
 * <p>模型与动画来自 {@link MachineDefinition#geoModel()}，不是硬编码在客户端类里——数据包换一份定义，
 * 模型和两个动画名就跟着换。
 */
public final class GeoControllerAnimatable implements GeoAnimatable {

    private final MachineControllerBlockEntity controller;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private String lastAnimationName;
    private RawAnimation lastAnimation;

    public GeoControllerAnimatable(MachineControllerBlockEntity controller) {
        this.controller = controller;
    }

    public MachineControllerBlockEntity controller() {
        return this.controller;
    }

    /** 当前定义的自定义模型；没有定义或没有 {@code geo-model} 时为 {@code null}。 */
    @Nullable
    public GeoModelSpec spec() {
        return MachineRegistry.clientById(this.controller.machineId())
                .map(MachineDefinition::geoModel)
                .orElse(null);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, state -> {
            GeoModelSpec spec = spec();
            if (spec == null) {
                return PlayState.STOP;
            }
            String name = this.controller.isWorking() && spec.workingAnimation() != null
                    ? spec.workingAnimation()
                    : spec.idleAnimation();
            if (name == null) {
                return PlayState.STOP;
            }
            if (!name.equals(this.lastAnimationName)) {
                this.lastAnimationName = name;
                this.lastAnimation = RawAnimation.begin().thenLoop(name);
            }
            return state.setAndContinue(this.lastAnimation);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public double getTick(Object object) {
        return this.controller.getLevel() == null ? 0 : this.controller.getLevel().getGameTime();
    }
}
