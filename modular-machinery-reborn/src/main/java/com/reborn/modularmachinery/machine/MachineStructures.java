package com.reborn.modularmachinery.machine;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;

/**
 * 运行时结构的公共组装入口（0.104.0，C6 投影仪）。
 *
 * <p>之前「基座 + 动态段 + 朝向旋转」只存在于 {@code DiagnoseCommand.runtimeStructure} 的私有实现里，
 * 投影仪的世界虚影需要同一份组装逻辑——复制会漂移（诊断说一套、虚影画一套），所以提取到这里，
 * 诊断命令与虚影都委托本类。虚影还需要 modifier 候选（JEI 面板预览
 * {@code StructurePreviews.previewRenderPattern} 的 modifier 段），故提供两档：
 *
 * <ul>
 *   <li>{@link #runtimeStructure}：基座 + 动态段（按控制器定格的节数），旋转后——诊断语义，
 *       「实际生效」的结构。</li>
 *   <li>{@link #ghostStructure}：在组装<strong>未旋转</strong>时先合并 modifier 候选再整体旋转——
 *       虚影语义，「还差什么」的引导结构。modifier 候选必须在朝向 0 的坐标系里合并（offset 与
 *       pattern 一样按朝向 0 写），这与 {@code previewRenderPattern} 和诊断的两条规则一脉相承：
 *       先在朝向 0 下合并所有段，最后统一旋转。</li>
 * </ul>
 */
public final class MachineStructures {

    private MachineStructures() {
    }

    /** 诊断语义：机器在当前朝向下<strong>实际生效</strong>的结构（基座 + 动态段，已旋转）。 */
    public static MachinePattern runtimeStructure(MachineControllerBlockEntity controller,
                                                  MachineDefinition machine) {
        return assembleUnrotated(controller, machine).rotated(controller.rotationSteps());
    }

    /** 虚影语义：实际生效的结构再并入 modifier 候选——回答「这个位置还允许放什么」。 */
    public static MachinePattern ghostStructure(MachineControllerBlockEntity controller,
                                                MachineDefinition machine) {
        MachinePattern assembled = assembleUnrotated(controller, machine);
        for (MachineModifier modifier : machine.modifiers()) {
            assembled = assembled.withMerged(MachinePattern.builder()
                    .add(modifier.offset(), modifier.accepted()).build());
        }
        for (MachineMultiBlockModifier modifier : machine.multiBlockModifiers()) {
            assembled = assembled.withMerged(modifier.pattern());
        }
        return assembled.rotated(controller.rotationSteps());
    }

    /**
     * 基座 + 各动态段在<strong>朝向 0</strong>下合并，不旋转。
     *
     * <p>与 {@code DiagnoseCommand.runtimeStructure} 同源的两条规则（见其 javadoc）：
     * 从定义的基座出发而不是注册表里预旋转的那一份；动态段自己的坐标按朝向 0 写，
     * 所以先在朝向 0 合并、最后再整体旋转。
     */
    private static MachinePattern assembleUnrotated(MachineControllerBlockEntity controller,
                                                    MachineDefinition machine) {
        MachinePattern assembled = machine.pattern();
        for (var dynamic : machine.dynamicPatterns()) {
            int size = dynamic.minSize();
            for (var status : controller.dynamicPatterns()) {
                if (status.patternName().equals(dynamic.name())) {
                    size = status.size();
                }
            }
            // 朝向 0 下合并，因为段自己的坐标就是按朝向 0 写的。
            assembled = assembled.withMerged(dynamic.expand(size, 0));
        }
        return assembled;
    }
}
