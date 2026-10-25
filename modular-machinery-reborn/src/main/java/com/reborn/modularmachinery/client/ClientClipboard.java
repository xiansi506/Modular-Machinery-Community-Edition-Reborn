package com.reborn.modularmachinery.client;

/**
 * F2（0.86.0）：服务端让客户端把一段文本放进系统剪贴板，旧版 {@code PktCopyToClipboard} 的客户端半边。
 *
 * <p>照旧版走 AWT 的 {@code Toolkit.getSystemClipboard()}，而不是 Minecraft 自己的 GLFW 剪贴板：
 * 旧版就是这么写的，且 AWT 这条路在窗口失焦时仍然可用。比旧版多一层 catch——无头环境（某些服务端
 * 工具链跑客户端类）会抛 {@code HeadlessException}，吞掉它是因为聊天里已经有同一行文本，剪贴板只是
 * 便利，失败不该把玩家踢出会话。
 */
public final class ClientClipboard {

    public static void copy(String text) {
        if (!java.awt.Desktop.isDesktopSupported()) {
            return;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (Throwable ignored) {
            // 聊天里已有同一行文本，这里是尽力而为。
        }
    }

    private ClientClipboard() {
    }
}
