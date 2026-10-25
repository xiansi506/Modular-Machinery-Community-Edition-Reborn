package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * C2 小件（0.91.0）：一个智能接口类型的值被成功更新之后发出——旧版
 * {@code SmartInterfaceUpdateEvent}（{@code TileSmartInterface:34}）的对应物。
 *
 * <p><b>与旧版的差异</b>：旧版智能接口是**独立方块**（{@code TileSmartInterface}），事件携带接口方块
 * 的坐标与 {@code SmartInterfaceData}；本版智能接口是机器定义声明的类型、值存在控制器上
 * （D16），界面也开在控制器上——所以事件携带 {@link #type()} 与 {@link #newValue()}，位置即控制器
 * 自己（{@link #controller()}）。更新点：菜单同步包（{@code SmartInterfaceUpdatePacket}）等服务端
 * 写入路径最终都过 {@code setSmartInterfaceValue}，事件只在那里发，且只在**成功写入**后发
 * （类型未声明时写入被拒，不发）。
 */
public final class SmartInterfaceUpdateEvent extends MachineEvent {

    private final String type;
    private final float newValue;

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public SmartInterfaceUpdateEvent() {
        this.type = null;
        this.newValue = 0F;
    }

    public SmartInterfaceUpdateEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                                     String type, float newValue) {
        super(controller, machineId);
        this.type = type;
        this.newValue = newValue;
    }

    /** 被更新的接口类型名（机器定义 {@code smart-interfaces} 里声明的那一个）。 */
    public String type() {
        return this.type;
    }

    /** 更新后的值。 */
    public float newValue() {
        return this.newValue;
    }
}
