package mmverify;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A12（勘测 §4 第 125 行）：旧版 {@code general.enable-full-data-sync}。
 *
 * <p>旧版默认关闭；开启后配方开始、结束（以及 {@code isNotWorking} 的非工作 tick）走
 * {@code markForUpdateSync()}，把整份方块实体 NBT 发给客户端。本版此前既没有这个键，也没有在开工/完成时
 * 主动发包：{@code getUpdateTag()} 虽然已经是整份保存状态，但没有任何调用点。
 *
 * <p>这一节钉两半：
 * <ul>
 *   <li><b>配置</b>：访问器存在、离线回退 false、渲染出的 spec 里确有
 *       {@code general.enable-full-data-sync=false}；</li>
 *   <li><b>接线</b>：控制器有一个只服务端执行的 helper，读开关后发 {@code sendBlockUpdated}；
 *       tick 的开工/完成分支和工厂线程的 started/finished 回调真的调用了它。</li>
 * </ul>
 *
 * <p>只做「开始/结束」两处，不照搬旧版 {@code isNotWorking} 的每个非工作 tick：那会在机器空转时每 tick
 * 广播整份 NBT。这个取舍写进 {@code docs/已知限制.md} §二，不在这里假装等价。
 */
final class FullDataSyncCheck {

    private FullDataSyncCheck() {
    }

    static void fullDataSync() {
        section("A12 全量数据同步开关（0.68.0）");
        configSurface();
        wiring();
    }

    /** 配置面：访问器默认 false，渲染出的 spec 也有这个键。 */
    private static void configSurface() {
        try {
            Method accessor = Class.forName("com.reborn.modularmachinery.config.ModConfig")
                    .getMethod("enableFullDataSync");
            report("ModConfig exposes a static boolean enableFullDataSync()",
                    Modifier.isStatic(accessor.getModifiers()) && accessor.getReturnType() == boolean.class);
            report("...and it falls back to the original's default false before the config is loaded",
                    !(Boolean) accessor.invoke(null));
        } catch (Throwable missing) {
            report("ModConfig.enableFullDataSync() is missing: " + missing, false);
        }

        Map<String, String> rendered = ParallelCraftCheck.renderConfigSpec();
        if (rendered.isEmpty()) {
            report("the config spec could not be rendered", false);
            return;
        }
        report("the rendered spec contains general.enable-full-data-sync",
                rendered.containsKey("general.enable-full-data-sync"));
        check("...with the original's default false", "false", rendered.get("general.enable-full-data-sync"));
    }

    /** 接线：helper 只在服务端发整份更新；四个开始/结束点都调用。 */
    private static void wiring() {
        Path sourceFile = Path.of(
                "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/block/"
                        + "MachineControllerBlockEntity.java");
        try {
            String entity = withoutComments(Files.readString(sourceFile));

            String helper = methodBody(entity, "private void syncFullDataIfEnabled()");
            report("the controller has a syncFullDataIfEnabled() helper", !helper.isEmpty());
            report("...which reads ModConfig.enableFullDataSync()",
                    helper.contains("ModConfig.enableFullDataSync()"));
            report("...and never sends from the client side", helper.contains("isClientSide"));
            report("...and sends the block entity's full update tag",
                    helper.contains("sendBlockUpdated") && helper.contains("Block.UPDATE_ALL"));

            String tickServer = methodBody(entity, "private static void tickServer(");
            String tick = methodBody(entity, "public static void tick(");
            check("the single craft calls it once on start and once on finish", 2L,
                    count(tickServer, "syncFullDataIfEnabled()") + count(tick, "syncFullDataIfEnabled()"));

            String factoryStarted = methodBody(entity, "public void onThreadStarted(");
            String factoryFinished = methodBody(entity, "public void onThreadFinished(");
            report("the factory's recipe start calls it",
                    factoryStarted.contains("syncFullDataIfEnabled()"));
            report("the factory's recipe finish calls it",
                    factoryFinished.contains("syncFullDataIfEnabled()"));
        } catch (Throwable unreadable) {
            report("reading the controller source failed: " + unreadable, false);
        }
    }

    /** Count non-overlapping occurrences; the source is ASCII around the needle. */
    private static int count(String text, String needle) {
        int seen = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            seen++;
            from += needle.length();
        }
        return seen;
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

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
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
