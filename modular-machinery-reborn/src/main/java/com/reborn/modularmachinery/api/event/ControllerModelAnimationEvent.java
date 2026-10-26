package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Client animation decisions expressed without an optional animation-library type. */
public final class ControllerModelAnimationEvent extends MachineEvent {
    public record AnimationStep(String name, boolean loop) {
        public AnimationStep { if (name == null || name.isBlank()) throw new IllegalArgumentException("Animation name must not be blank"); }
    }
    public record Snapshot(String modelName, @Nullable String currentAnimationName, double animationTick,
                           int animationState, float limbSwing, float limbSwingAmount, float partialTick,
                           boolean moving, double transitionLengthTicks, double animationSpeed) {}
    private final Snapshot snapshot;
    @Nullable private final String defaultAnimation;
    private final List<AnimationStep> animations = new ArrayList<>();
    private int playState;
    private double transitionLengthTicks;
    private double animationSpeed;
    public ControllerModelAnimationEvent() { snapshot = null; defaultAnimation = null; }
    public ControllerModelAnimationEvent(MachineControllerBlockEntity controller, Snapshot snapshot,
                                         @Nullable String defaultAnimation) {
        super(controller, controller.machineId()); this.snapshot = snapshot; this.defaultAnimation = defaultAnimation;
        reset();
    }
    public Snapshot snapshot() { return snapshot; }
    public String getCurrentModelName() { return snapshot.modelName(); }
    @Nullable public String getCurrentAnimationName() { return snapshot.currentAnimationName(); }
    public double getAnimationTick() { return snapshot.animationTick(); }
    public int getAnimationState() { return snapshot.animationState(); }
    public float getLimbSwing() { return snapshot.limbSwing(); }
    public float getLimbSwingAmount() { return snapshot.limbSwingAmount(); }
    public float getPartialTick() { return snapshot.partialTick(); }
    public boolean isMoving() { return snapshot.moving(); }
    public List<AnimationStep> getAnimations() { return List.copyOf(animations); }
    public void addAnimation(String name) { addAnimation(name, false); }
    public void addAnimation(String name, boolean loop) { animations.add(new AnimationStep(name, loop)); }
    public void setAnimation(String name) { setAnimation(name, false); }
    public void setAnimation(String name, boolean loop) { AnimationStep step = new AnimationStep(name, loop); animations.clear(); animations.add(step); }
    public void clearAnimations() { animations.clear(); }
    public int getPlayState() { return playState; }
    public void setPlayState(int state) { if (state != 0 && state != 1) throw new IllegalArgumentException("playState must be 0 (continue) or 1 (stop)"); playState = state; }
    public double getTransitionLengthTicks() { return transitionLengthTicks; }
    public void setTransitionLengthTicks(double ticks) {
        if (!Double.isFinite(ticks) || ticks < 0 || ticks > Integer.MAX_VALUE) throw new IllegalArgumentException("transitionLengthTicks must be a finite nonnegative int-range value");
        transitionLengthTicks = Math.round(ticks);
    }
    public double getAnimationSpeed() { return animationSpeed; }
    public void setAnimationSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < 0) throw new IllegalArgumentException("animationSpeed must be finite and nonnegative");
        animationSpeed = speed;
    }
    public void reset() {
        animations.clear(); if (defaultAnimation != null) addAnimation(defaultAnimation, true);
        playState = 0; transitionLengthTicks = snapshot.transitionLengthTicks(); animationSpeed = snapshot.animationSpeed();
    }
}
