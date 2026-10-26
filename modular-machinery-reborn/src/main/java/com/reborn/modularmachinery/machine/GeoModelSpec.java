package com.reborn.modularmachinery.machine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Set;

/**
 * 机器定义里的 {@code geo-model}：一台带自定义 GeckoLib 模型的控制器要用的五个资源。
 *
 * <p>旧版没有这个根字段。它的入口是 CraftTweaker 的 {@code GeoMachineModel.registerGeoMachineModel}
 * （{@code _mmce-src/.../common/integration/crafttweaker/model/GeoMachineModel.java:17-42}），
 * 由脚本把模型、贴图、动画文件与两个动画名登记进 {@code DynamicMachineModelRegistry}，再按机器名取回。
 * 本版没有 CraftTweaker 入口，所以把同一组值放进数据驱动定义里——这也是本版处理旧版脚本专属能力的一贯做法。
 *
 * <h2>命名空间规则</h2>
 *
 * <p>旧版 {@code GeoMachineModel} 有一条容易漏掉的行为：三个 {@link ResourceLocation} 如果命名空间是
 * {@code minecraft}（包括**根本没写命名空间**、由 {@code ResourceLocation} 补成 {@code minecraft} 的情形），
 * 就会被改写成旧模组的命名空间 {@code modularmachinery}。照抄这条规则，因为旧版的模型/贴图路径就是按
 * 那个命名空间放的；改了会让已有资源包找不到文件。
 *
 * <p>注意这里写的是**旧版的** {@code modularmachinery}，不是本模组现在的 mod id。两者不是一回事。
 */
public record GeoModelSpec(ResourceLocation model,
                           ResourceLocation texture,
                           @Nullable ResourceLocation animation,
                           @Nullable String idleAnimation,
                           @Nullable String workingAnimation) {

    /** 旧版 GeoMachineModel 的命名空间：资源路径缺省或写成 minecraft 时用它。 */
    public static final String ORIGINAL_NAMESPACE = "modularmachinery";

    private static final Set<String> KEYS = Set.of("model", "texture", "animation", "idle", "working");

    public GeoModelSpec {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(texture, "texture");
    }

    /**
     * 解析 {@code geo-model}。没有这个根字段时返回 {@code null}——「这台机器没有自定义模型」是正常状态，
     * 不是错误，也不是一个五个字段全空的 {@code GeoModelSpec}。
     *
     * @throws JsonParseException 字段存在但内容不合法；消息里点名具体键，便于包作者定位
     */
    @Nullable
    public static GeoModelSpec parse(JsonObject root, String where) {
        JsonElement element = root.get("geo-model");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            throw new JsonParseException(where + ": 'geo-model' must be an object with at least 'model' and "
                    + "'texture', but " + describe(element) + " was given");
        }
        JsonObject json = element.getAsJsonObject();
        for (String key : json.keySet()) {
            if (!KEYS.contains(key)) {
                throw new JsonParseException(where + ": 'geo-model' has an unknown key '" + key + "'. "
                        + "Known keys are: " + String.join(", ", KEYS.stream().sorted().toList()));
            }
        }

        ResourceLocation model = resource(json, "model", where);
        ResourceLocation texture = resource(json, "texture", where);
        ResourceLocation animation = optionalResource(json, "animation", where);
        String idle = optionalText(json, "idle", where);
        String working = optionalText(json, "working", where);
        if (idle != null && idle.isBlank()) {
            throw new JsonParseException(where + ": 'geo-model.idle' must not be blank");
        }
        if (working != null && working.isBlank()) {
            throw new JsonParseException(where + ": 'geo-model.working' must not be blank");
        }
        if ((idle != null || working != null) && animation == null) {
            throw new JsonParseException(where + ": 'geo-model.idle' and 'geo-model.working' name animations "
                    + "inside 'geo-model.animation', so that file is required when either name is present");
        }
        return new GeoModelSpec(model, texture, animation, idle, working);
    }

    private static ResourceLocation resource(JsonObject json, String key, String where) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            throw new JsonParseException(where + ": 'geo-model." + key + "' is required");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(where + ": 'geo-model." + key + "' must be a string, but "
                    + describe(element) + " was given");
        }
        String text = element.getAsString();
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null) {
            throw new JsonParseException(where + ": 'geo-model." + key + "' is not a valid resource location: \""
                    + text + "\"");
        }
        if ("minecraft".equals(id.getNamespace())) {
            return new ResourceLocation(ORIGINAL_NAMESPACE, id.getPath());
        }
        return id;
    }

    @Nullable
    private static ResourceLocation optionalResource(JsonObject json, String key, String where) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(where + ": 'geo-model." + key + "' must be a string, but "
                    + describe(element) + " was given");
        }
        String text = element.getAsString();
        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null) {
            throw new JsonParseException(where + ": 'geo-model." + key + "' is not a valid resource location: \""
                    + text + "\"");
        }
        if ("minecraft".equals(id.getNamespace())) {
            return new ResourceLocation(ORIGINAL_NAMESPACE, id.getPath());
        }
        return id;
    }

    @Nullable
    private static String optionalText(JsonObject json, String key, String where) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(where + ": 'geo-model." + key + "' must be a string, but "
                    + describe(element) + " was given");
        }
        return element.getAsString();
    }

    private static String describe(JsonElement element) {
        return element.isJsonPrimitive() ? element.getAsJsonPrimitive().toString() : element.getClass().getSimpleName();
    }
}
