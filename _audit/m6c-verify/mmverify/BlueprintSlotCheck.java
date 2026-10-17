package mmverify;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 勘测 X9：<b>控制器蓝图槽可以堆叠</b>。
 *
 * <h2>缺陷</h2>
 *
 * <p>旧版在控制器构造器里显式写了 {@code inventory.setStackLimit(1, BLUEPRINT_SLOT)}
 * （{@code TileMultiblockMachineController.java:168}；从存档重建之后 {@code :1385} 再设一次）。
 * 本项目搬这个槽位时只搬了「槽位是哪一个」，漏了上限，于是 {@link ItemStackHandler} 的默认值 64 生效，
 * 而蓝图物品本身允许叠到 16——一个槽里能塞下一叠蓝图。
 *
 * <h2>这里钉的是哪一层</h2>
 *
 * <p>上限写在方块实体自己的 {@code getSlotLimit} 上，所以它同时决定了三件事：GUI 拖动时单个格子的钳制
 * （{@link SlotItemHandler#getMaxStackSize()} 就是读这个数）、任何 {@code IItemHandler} 的
 * {@code insertItem} 能放进去几个、以及快速移动（Shift+双击）一次能搬几个。本节把三层都驱动一遍，
 * 因为只钉住其中一层的话，「修了 GUI 但自动化还能塞一叠」和「修了插入但 GUI 还能拖一叠」都照样绿。
 *
 * <h2>为什么用普通物品当样本</h2>
 *
 * <p>蓝图物品要经注册表才能拿到实例，离线环境没有注册表；而<b>槽位上限与物品种类无关</b>——
 * 这一点由第一条对照断言证明（未设上限的同形状处理器是 64）。
 */
final class BlueprintSlotCheck {

    private BlueprintSlotCheck() {
    }

    static void blueprintSlotLimit() {
        ParallelCraftCheck.section("X9：蓝图槽一格只放一张（0.63.0）");

        // 0) 机制对照：这是缺陷的来源——ItemStackHandler(1) 的默认上限是 64，不是 1。
        //    没有这一条，「上限是 1」的断言看不出它到底修了什么。
        ParallelCraftCheck.check("对照：未设上限的单槽 ItemStackHandler 默认上限（缺陷来源）", 64,
                new ItemStackHandler(1).getSlotLimit(0));

        int[] contentChanges = {0};
        ItemStackHandler handler;
        try {
            // 反射调用，不是直接调用：0.62.9 的构建里没有这个工厂，直接写会**编译不过**——
            // 那样红先行就只能停在「构建失败」，而构建失败对「槽位上限是几」什么都没说。
            // 这与本复现器既定的 reflection-first 约定一致（IngredientArrayCheck 等段同理）。
            handler = (ItemStackHandler) MachineControllerBlockEntity.class
                    .getMethod("newBlueprintHandler", Runnable.class)
                    .invoke(null, (Runnable) () -> contentChanges[0]++);
        } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException
                 | LinkageError missing) {
            ParallelCraftCheck.report("控制器没有设置过蓝图槽上限（0.62.9 及以前只有 new ItemStackHandler(1)，"
                    + "默认上限 64；本节要的就是那个上限是 1 的处理器）：" + missing, false);
            return;
        }

        ParallelCraftCheck.check("蓝图槽的槽位数", 1, handler.getSlots());
        ParallelCraftCheck.check("蓝图槽的堆叠上限", 1, handler.getSlotLimit(0));

        // 1) 插入层：一叠 5 个只能进去 1 个，其余退回。
        ItemStack leftover = handler.insertItem(0, new ItemStack(Items.PAPER, 5), false);
        ParallelCraftCheck.check("插入 5 个时被退回的个数", 4, leftover.getCount());
        ParallelCraftCheck.check("...槽里只留下 1 个", 1, handler.getStackInSlot(0).getCount());
        ParallelCraftCheck.check("...内容变更回调被调用（接线到 BlockEntity#setChanged，否则读档不会变脏）",
                1, contentChanges[0]);

        // 2) 槽已满：再插 1 个必须全部退回。
        ItemStack second = handler.insertItem(0, new ItemStack(Items.PAPER, 1), false);
        ParallelCraftCheck.check("槽已满时再插入 1 个：全部退回", 1, second.getCount());
        ParallelCraftCheck.check("...槽里仍然是 1 个", 1, handler.getStackInSlot(0).getCount());

        // 3) GUI 层：SlotItemHandler 的钳制数取自同一个 getSlotLimit。
        try {
            SlotItemHandler slot = new SlotItemHandler(handler, 0, 0, 0);
            ParallelCraftCheck.check("GUI 槽位的最大堆叠（玩家拖动时按它钳制）", 1, slot.getMaxStackSize());
            ParallelCraftCheck.check("...对 64 可叠的物品也不超过 1", 1,
                    slot.getMaxStackSize(new ItemStack(Items.PAPER, 64)));
        } catch (Throwable unavailable) {
            ParallelCraftCheck.report("构造 GUI 槽位失败（这一层没有拿到证据）: " + unavailable, false);
        }

        // 4) 取走之后上限仍然有效（上限不是「一次性」的）。
        ItemStack taken = handler.extractItem(0, 1, false);
        ParallelCraftCheck.check("取走 1 个", 1, taken.getCount());
        ParallelCraftCheck.report("...槽位空了，再插一叠 3 个仍然只进 1 个",
                handler.insertItem(0, new ItemStack(Items.PAPER, 3), false).getCount() == 2
                        && handler.getStackInSlot(0).getCount() == 1);
    }
}
