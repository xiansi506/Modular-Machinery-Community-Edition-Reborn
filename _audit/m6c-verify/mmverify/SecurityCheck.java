package mmverify;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * A11（勘测 §4 第 127 行）：旧版 {@code general.enable-security-system} 与控制器 owner。
 *
 * <p>旧版在放置时<strong>总是</strong>把 owner 写进方块实体（{@code ItemBlockController.java:35-63}），并把
 * UUID 字符串写进掉落物（{@code BlockController.java:145-164}）。{@code enable-security-system} 只加两道门
 * （{@code EventHandler.java:104-159}）：非 owner 不能破坏控制器，非 owner 不能放置带别人 owner 标签的控制器
 * 物品。它<strong>没有</strong>限制打开界面或运行机器，尽管配置注释那样承诺。本版保持同样的两道门。
 *
 * <p>测试分四节：配置面、owner 编解码、两道门的真值表、生产代码的接线。所有生产类都用反射读取，因此在旧
 * 源码上能编译并报告 FAIL，而不是编译失败——这是本项目「红跑必须是 FAIL 而不是崩溃」的要求。
 */
final class SecurityCheck {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SecurityCheck() {
    }

    static void security() {
        section("A11 owner 校验（0.69.0）");
        configSurface();
        ownerCodec();
        decisions();
        wiring();
    }

    /** 配置面：访问器默认 false，渲染出的 spec 也有这个键。 */
    private static void configSurface() {
        try {
            Method accessor = Class.forName("com.reborn.modularmachinery.config.ModConfig")
                    .getMethod("enableSecuritySystem");
            report("ModConfig exposes a static boolean enableSecuritySystem()",
                    Modifier.isStatic(accessor.getModifiers()) && accessor.getReturnType() == boolean.class);
            report("...and it falls back to the original's default false before the config is loaded",
                    !(Boolean) accessor.invoke(null));
        } catch (Throwable missing) {
            report("ModConfig.enableSecuritySystem() is missing: " + missing, false);
        }

        Map<String, String> rendered = ParallelCraftCheck.renderConfigSpec();
        if (rendered.isEmpty()) {
            report("the config spec could not be rendered", false);
            return;
        }
        report("the rendered spec contains general.enable-security-system",
                rendered.containsKey("general.enable-security-system"));
        check("...with the original's default false", "false", rendered.get("general.enable-security-system"));
    }

    /** owner 的 NBT 编解码：写读往返、null 删除、非法字符串读成 null、物品标签同路。 */
    private static void ownerCodec() {
        Class<?> security;
        try {
            security = Class.forName("com.reborn.modularmachinery.block.ControllerSecurity");
        } catch (Throwable missing) {
            report("ControllerSecurity is missing: " + missing, false);
            return;
        }
        try {
            Method put = security.getMethod("putOwner", CompoundTag.class, UUID.class);
            Method read = security.getMethod("ownerOf", CompoundTag.class);
            Method hasTag = security.getMethod("hasOwnerTag", ItemStack.class);
            Method readStack = security.getMethod("ownerOf", ItemStack.class);

            CompoundTag tag = new CompoundTag();
            put.invoke(null, tag, ALICE);
            report("the owner round-trips through the original's \"owner\" string key",
                    ALICE.equals(read.invoke(null, tag)));
            put.invoke(null, tag, (Object) null);
            report("...and writing null removes the key instead of writing \"null\"",
                    read.invoke(null, tag) == null);

            tag.putString("owner", "not-a-uuid");
            report("a malformed owner string reads as null (the original's catch left the field unset)",
                    read.invoke(null, tag) == null);

            ItemStack stack = new ItemStack(Items.STONE);
            report("a fresh item carries no owner tag", !(Boolean) hasTag.invoke(null, stack));
            stack.getOrCreateTag().putString("owner", ALICE.toString());
            report("...an item tag with an owner is recognised", (Boolean) hasTag.invoke(null, stack));
            report("...and reads back the same uuid", ALICE.equals(readStack.invoke(null, stack)));
        } catch (Throwable broken) {
            report("driving the owner codec failed: " + broken, false);
        }
    }

    /** 两道门的真值表；纯函数，不需要世界或玩家实体。 */
    private static void decisions() {
        Class<?> security;
        try {
            security = Class.forName("com.reborn.modularmachinery.block.ControllerSecurity");
        } catch (Throwable missing) {
            report("ControllerSecurity is missing: " + missing, false);
            return;
        }
        try {
            Method canBreak = security.getMethod("canBreak", boolean.class, boolean.class,
                    UUID.class, UUID.class);
            Method canPlace = security.getMethod("canPlace", boolean.class, boolean.class,
                    UUID.class, UUID.class);

            report("break: security off allows a non-owner",
                    (Boolean) canBreak.invoke(null, false, false, ALICE, BOB));
            report("break: security on allows a controller with no recorded owner",
                    (Boolean) canBreak.invoke(null, true, false, null, BOB));
            report("break: security on allows the owner",
                    (Boolean) canBreak.invoke(null, true, false, ALICE, ALICE));
            report("break: security on allows creative players",
                    (Boolean) canBreak.invoke(null, true, true, ALICE, BOB));
            report("break: security on denies a non-owner in survival",
                    !(Boolean) canBreak.invoke(null, true, false, ALICE, BOB));

            report("place: security off allows an item owned by someone else",
                    (Boolean) canPlace.invoke(null, false, false, ALICE, BOB));
            report("place: security on allows an item with no owner tag",
                    (Boolean) canPlace.invoke(null, true, false, null, BOB));
            report("place: security on allows the owner to place it",
                    (Boolean) canPlace.invoke(null, true, false, ALICE, ALICE));
            report("place: security on allows creative placement of someone else's item",
                    (Boolean) canPlace.invoke(null, true, true, ALICE, BOB));
            report("place: security on denies a non-owner in survival",
                    !(Boolean) canPlace.invoke(null, true, false, ALICE, BOB));
        } catch (Throwable broken) {
            report("driving the security decisions failed: " + broken, false);
        }
    }

    /** 生产接线：方块实体持久化、物品放置、掉落、破坏监听与事件注册。 */
    private static void wiring() {
        try {
            String entity = withoutComments(Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/block/"
                            + "MachineControllerBlockEntity.java")));
            String save = methodBody(entity, "protected void saveAdditional(");
            String load = methodBody(entity, "public void load(");
            report("the controller saves its owner under the original's key",
                    save.contains("ControllerSecurity.putOwner(tag, this.owner)"));
            report("...and reads it back, treating a malformed value as no owner",
                    load.contains("ControllerSecurity.ownerOf(tag)"));
            report("...and exposes getOwner()/setOwner(UUID)",
                    entity.contains("public UUID getOwner()") && entity.contains("public void setOwner("));

            String item = withoutComments(Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/item/"
                            + "MachineControllerItem.java")));
            String place = methodBody(item, "public InteractionResult place(");
            report("the controller item consults canPlace before placing",
                    place.contains("ControllerSecurity.canPlace("));
            report("...then defers to the vanilla placement",
                    place.contains("super.place(context)"));
            report("...records the item's owner, or the placer when the item has no owner tag",
                    place.contains("setOwner(taggedOwner)") && place.contains("setOwner(player.getUUID())"));

            String block = withoutComments(Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/block/"
                            + "MachineControllerBlock.java")));
            String drops = methodBody(block, "public List<ItemStack> getDrops(");
            report("the controller's drop carries its owner",
                    drops.contains("ControllerSecurity.putOwner("));
            report("...read from the loot context's block entity",
                    drops.contains("LootContextParams.BLOCK_ENTITY"));

            String securitySource = withoutComments(Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/block/"
                            + "ControllerSecurity.java")));
            String breakHandler = methodBody(securitySource, "public static void onBlockBreak(");
            report("the break handler narrows the level to ServerLevel",
                    breakHandler.contains("instanceof ServerLevel"));
            report("...and cancels the event for a non-owner",
                    breakHandler.contains("canBreak(") && breakHandler.contains("setCanceled(true)"));

            String mod = withoutComments(Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                            + "ModularMachineryReborn.java")));
            report("the mod registers the break handler on Forge's event bus",
                    mod.contains("ControllerSecurity::onBlockBreak"));
        } catch (Throwable unreadable) {
            report("reading the A11 production sources failed: " + unreadable, false);
        }
    }

    /** 取一个方法的方法体（从签名到配对的右花括号）。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
                opened = true;
            } else if (c == '}') {
                depth--;
                if (opened && depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.substring(start);
    }

    /** 去掉注释但保留字符串字面量；理由同 ComparatorCheck 的同名函数。 */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char s = source.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (s == '"') {
                        break;
                    }
                }
                continue;
            }
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char s = source.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (s == '\'') {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static void check(String what, String expected, String actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }
}
