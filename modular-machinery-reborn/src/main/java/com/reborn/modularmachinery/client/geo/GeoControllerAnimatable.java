package com.reborn.modularmachinery.client.geo;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.GeoModelSpec;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineRegistry;
import com.reborn.modularmachinery.api.event.ControllerModelAnimationEvent;
import com.reborn.modularmachinery.script.ClientModelRegistry;
import net.minecraftforge.common.MinecraftForge;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;

/**
 * 一个控制器方块实体的 GeckoLib 动画对象。
 *
 * <p>GeckoLib 的 {@link GeoAnimatable} 不是方块实体本身：它要求一个稳定的实例 id 与一个动画缓存，
 * 而方块实体每个存档都会被重新创建。这里用渲染器持有的 {@code WeakHashMap} 把方块实体映射到一个
 * 包装对象，包装对象仅弱引用控制器；实例 id 用控制器坐标的 {@code asLong()} 覆盖默认的 {@code hashCode()}，这样同一台机器
 * 在重渲染之间不会因为对象身份变化而重置动画。
 *
 * <p>模型与动画来自 {@link MachineDefinition#geoModel()}，不是硬编码在客户端类里——数据包换一份定义，
 * 模型和两个动画名就跟着换。
 */
public final class GeoControllerAnimatable implements GeoAnimatable {

    private final WeakReference<MachineControllerBlockEntity> controller;
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private RawAnimation lastAnimation;
    private List<ControllerModelAnimationEvent.AnimationStep> lastSteps = List.of();
    private ClientModelRegistry.Selection selection;
    private boolean resetAnimation;
    private double transitionLength = 5;
    private boolean reportedAnimationError;

    public GeoControllerAnimatable(MachineControllerBlockEntity controller) {
        this.controller = new WeakReference<>(controller);
    }

    public MachineControllerBlockEntity controller() {
        return Objects.requireNonNull(this.controller.get(), "Controller no longer exists");
    }

    /** 当前定义的自定义模型；没有定义或没有 {@code geo-model} 时为 {@code null}。 */
    @Nullable
    public GeoModelSpec spec() {
        if (selection != null) return selection.spec();
        return MachineRegistry.clientById(controller().machineId())
                .map(MachineDefinition::geoModel)
                .orElse(null);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, this::animationState));
    }

    /** Select once per render, so model, texture, animation file and callback share one decision. */
    public void select(ClientModelRegistry.Selection selection) {
        if (!Objects.equals(this.selection, selection)) {
            resetAnimation = true; lastAnimation = null; lastSteps = List.of();
        }
        this.selection = selection;
    }

    public PlayState animationState(AnimationState<GeoControllerAnimatable> state) {
        GeoModelSpec spec = spec();
        if (spec == null || spec.animation() == null || !controller().isFormed()) return PlayState.STOP;
        String name = controller().isWorking() && spec.workingAnimation() != null
                ? spec.workingAnimation() : spec.idleAnimation();
        AnimationController<GeoControllerAnimatable> animationController = state.getController();
        var current = animationController.getCurrentAnimation();
        int currentState = switch (animationController.getAnimationState()) {
            case RUNNING -> 0; case TRANSITIONING -> 1; default -> 2;
        };
        String modelName = selection == null || selection.name().isEmpty() ? spec.model().toString() : selection.name();
        ControllerModelAnimationEvent event = new ControllerModelAnimationEvent(controller(),
                new ControllerModelAnimationEvent.Snapshot(modelName,
                        current == null ? null : current.animation().name(), state.getAnimationTick(), currentState,
                        state.getLimbSwing(), state.getLimbSwingAmount(), state.getPartialTick(), state.isMoving(),
                        transitionLength, animationController.getAnimationSpeed()), name);
        try { MinecraftForge.EVENT_BUS.post(event); }
        catch (RuntimeException | LinkageError error) {
            if (!reportedAnimationError) {
                reportedAnimationError = true;
                com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.warn("Client animation callback failed for {}; using default animation", controller().machineId(), error);
            }
            event.reset();
        }
        transitionLength = event.getTransitionLengthTicks();
        animationController.setTransitionLength((int) transitionLength);
        animationController.setAnimationSpeed(event.getAnimationSpeed());
        if (event.getPlayState() == 1 || event.getAnimations().isEmpty()) return PlayState.STOP;
        List<ControllerModelAnimationEvent.AnimationStep> steps = event.getAnimations();
        if (resetAnimation) { animationController.forceAnimationReset(); resetAnimation = false; }
        if (lastAnimation == null || !lastSteps.equals(steps)) {
            lastSteps = steps; lastAnimation = RawAnimation.begin();
            for (var step : steps) lastAnimation = step.loop() ? lastAnimation.thenLoop(step.name()) : lastAnimation.thenPlay(step.name());
        }
        return state.setAndContinue(lastAnimation);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    @Override
    public double getTick(Object object) {
        MachineControllerBlockEntity controller = this.controller.get();
        return controller == null || controller.getLevel() == null ? 0 : controller.getLevel().getGameTime();
    }
}
