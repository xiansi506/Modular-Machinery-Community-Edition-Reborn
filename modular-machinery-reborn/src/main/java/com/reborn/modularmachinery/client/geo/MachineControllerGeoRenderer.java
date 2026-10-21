package com.reborn.modularmachinery.client.geo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineRegistry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoObjectRenderer;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 控制器的 GeckoLib 渲染器。
 *
 * <p>渲染位置照旧版 {@code MachineControllerRenderer.java:179-185}：先把模型抬到方块中心
 * （{@code translate(0.5, 0.01, 0.5)}），再按控制器成型时的朝向绕 Y 轴转
 * {@code rotationSteps * 90} 度。旧版用 {@code rotateBlockMatrix}，四个方向分别是
 * north 0 / west 90 / south 180 / east 270，与这里一致。
 *
 * <p>本类继承 {@link GeoObjectRenderer} 而不是持有一个委托字段，是为了让 GeckoLib 的
 * preRender/actuallyRender 链照常工作；方块实体的 {@code render} 只是入口，真正的模型绘制走
 * {@code super.render}。
 */
public final class MachineControllerGeoRenderer extends GeoObjectRenderer<GeoControllerAnimatable>
        implements BlockEntityRenderer<MachineControllerBlockEntity> {

    /**
     * 键与值都是弱引用：值里的 animatable 持有控制器，如果只让键弱引用、值强引用，
     * 键就永远回收不掉（WeakHashMap 的经典泄漏）。值被回收后下一次渲染会重建一个，
     * 代价只是那台机器的动画从第一帧开始，不会拖住方块实体。
     */
    private final Map<MachineControllerBlockEntity, WeakReference<GeoControllerAnimatable>> animatables
            = new WeakHashMap<>();

    public MachineControllerGeoRenderer() {
        super(new MachineControllerGeoModel());
    }

    @Override
    public void render(MachineControllerBlockEntity controller, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        MachineDefinition definition = MachineRegistry.byId(controller.machineId()).orElse(null);
        if (definition == null || definition.geoModel() == null) {
            return;
        }
        GeoControllerAnimatable animatable = this.animatables.computeIfAbsent(controller,
                key -> new WeakReference<>(new GeoControllerAnimatable(key))).get();
        if (animatable == null) {
            animatable = new GeoControllerAnimatable(controller);
            this.animatables.put(controller, new WeakReference<>(animatable));
        }
        poseStack.pushPose();
        try {
            super.render(poseStack, animatable, bufferSource, null, null, packedLight);
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public void preRender(PoseStack poseStack, GeoControllerAnimatable animatable, BakedGeoModel model,
                          MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender,
                          float partialTick, int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        this.objectRenderTranslations = new Matrix4f(poseStack.last().pose());
        scaleModelForRender(this.scaleWidth, this.scaleHeight, poseStack, animatable, model, isReRender,
                partialTick, packedLight, packedOverlay);
        poseStack.translate(0.5f, 0.01f, 0.5f);
        poseStack.mulPose(Axis.YP.rotationDegrees(animatable.controller().rotationSteps() * 90.0f));
    }

    @Override
    public long getInstanceId(GeoControllerAnimatable animatable) {
        return animatable.controller().getBlockPos().asLong();
    }
}
