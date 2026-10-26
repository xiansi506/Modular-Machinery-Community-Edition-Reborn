package com.reborn.modularmachinery.client.geo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineRegistry;
import com.reborn.modularmachinery.machine.GeoModelSpec;
import com.reborn.modularmachinery.script.ClientModelRegistry;
import com.reborn.modularmachinery.client.WorldComponentHider;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import com.mojang.math.Axis;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import com.reborn.modularmachinery.client.MachineBloom;
import software.bernie.geckolib.renderer.GeoObjectRenderer;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.LinkedHashMap;

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
     * Animatable 仅弱引用控制器，因此稳定保留动画缓存不会反向拖住 WeakHashMap 的键。
     * 控制器仍存在时不因普通 GC 随机丢失动画；脚本／资源重载主动清理缓存。
     */
    private final Map<MachineControllerBlockEntity, GeoControllerAnimatable> animatables
            = new WeakHashMap<>();
    private long generation = -1;
    private boolean captureBloom;
    private final Map<GeoModelSpec, Boolean> failedModels = new LinkedHashMap<>(16, .75F, true);

    public MachineControllerGeoRenderer() {
        super(new MachineControllerGeoModel());
    }

    @Override
    public void render(MachineControllerBlockEntity controller, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        MachineDefinition definition = MachineRegistry.clientById(controller.machineId()).orElse(null);
        if (definition == null) {
            WorldComponentHider.modelRendered(controller, false);
            return;
        }
        ClientModelRegistry.Selection selection = ClientModelRegistry.resolve(controller, definition.geoModel());
        if (generation != ClientModelRegistry.generation()) {
            animatables.clear(); failedModels.clear(); generation = ClientModelRegistry.generation();
        }
        if (selection.spec() == null) {
            WorldComponentHider.modelRendered(controller, false);
            return;
        }
        GeoControllerAnimatable animatable = this.animatables.computeIfAbsent(controller, GeoControllerAnimatable::new);
        boolean previousCapture = captureBloom;
        boolean rendered;
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        captureBloom = controller.getLevel() != null && controller.getLevel() == minecraft.level
                && bufferSource == minecraft.renderBuffers().bufferSource();
        try {
            rendered = renderSelection(animatable, selection, poseStack, bufferSource, packedLight);
            if (!rendered && definition.geoModel() != null && !definition.geoModel().equals(selection.spec()))
                rendered = renderSelection(animatable, new ClientModelRegistry.Selection("", definition.geoModel()), poseStack, bufferSource, packedLight);
        } finally { captureBloom = previousCapture; }
        WorldComponentHider.modelRendered(controller, rendered);
    }

    private boolean renderSelection(GeoControllerAnimatable animatable, ClientModelRegistry.Selection selection,
                                    PoseStack callerPose, MultiBufferSource buffers, int light) {
        if (failedModels.containsKey(selection.spec())) return false;
        animatable.select(selection);
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(callerPose.last().pose());
        poseStack.last().normal().set(callerPose.last().normal());
        try {
            super.render(poseStack, animatable, buffers, null, null, light);
            return true;
        } catch (RuntimeException | LinkageError error) {
            failedModels.put(selection.spec(), true);
            if (failedModels.size() > 64) failedModels.remove(failedModels.keySet().iterator().next());
            com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.warn(
                    "Could not render client model {}; using definition model when available", selection.spec().model(), error);
            return false;
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
    public void renderRecursively(PoseStack poseStack, GeoControllerAnimatable animatable, GeoBone bone,
                                  RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                                  boolean isReRender, float partialTick, int packedLight, int packedOverlay,
                                  float red, float green, float blue, float alpha) {
        BoneRenderStyle style = styleOf(bone);
        if (style.emissive() || style.transparent()) {
            renderType = MachineModelRenderTypes.get(getTextureLocation(animatable), style);
            buffer = bufferSource.getBuffer(renderType);
        }
        // Reacquire ordinary buffers too: a previous sibling can have ended the shared builder.
        else buffer = bufferSource.getBuffer(renderType);
        if (style.emissive()) {
            packedLight = LightTexture.FULL_BRIGHT;
            if (captureBloom && !isReRender) {
                VertexConsumer mask = MachineBloom.capture(getTextureLocation(animatable));
                // Only this bone's cubes receive this dual consumer. Child recursion reacquires its own.
                if (mask != null) buffer = VertexMultiConsumer.create(buffer, mask);
            }
        }
        super.renderRecursively(poseStack, animatable, bone, renderType, bufferSource, buffer,
                isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    public static BoneRenderStyle styleOf(GeoBone bone) {
        BoneRenderStyle style = BoneRenderStyle.NORMAL;
        for (GeoBone current = bone; current != null; current = current.getParent()) style = style.child(current.getName());
        return style;
    }

    @Override
    public long getInstanceId(GeoControllerAnimatable animatable) {
        return animatable.controller().getBlockPos().asLong();
    }
}
