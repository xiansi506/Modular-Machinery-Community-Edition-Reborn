package com.reborn.modularmachinery.client.geo;

import com.reborn.modularmachinery.machine.GeoModelSpec;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

import java.util.Objects;

/**
 * 把 {@link GeoModelSpec} 的五个资源交给 GeckoLib 的模型。
 *
 * <p>这里只做转发：哪个机器用哪个模型是数据层的事（{@link GeoControllerAnimatable#spec()}），
 * 客户端不再按机器名维护一张表。旧版的 {@code MachineControllerModel} 把三个 location 固定成字段，
 * 是因为它由 {@code GeoMachineModel.registerGeoMachineModel} 为每个模型各建一个实例；本版的定义可以
 * 共享同一个 renderer，所以按 animatable 取当前定义。
 */
public final class MachineControllerGeoModel extends GeoModel<GeoControllerAnimatable> {

    @Override
    public ResourceLocation getModelResource(GeoControllerAnimatable animatable) {
        return spec(animatable).model();
    }

    @Override
    public ResourceLocation getTextureResource(GeoControllerAnimatable animatable) {
        return spec(animatable).texture();
    }

    @Override
    public ResourceLocation getAnimationResource(GeoControllerAnimatable animatable) {
        GeoModelSpec spec = spec(animatable);
        return Objects.requireNonNull(spec.animation(),
                () -> "geo-model.animation is not set for " + animatable.controller().machineId());
    }

    private static GeoModelSpec spec(GeoControllerAnimatable animatable) {
        return Objects.requireNonNull(animatable.spec(),
                () -> "no geo-model for " + animatable.controller().machineId());
    }
}
