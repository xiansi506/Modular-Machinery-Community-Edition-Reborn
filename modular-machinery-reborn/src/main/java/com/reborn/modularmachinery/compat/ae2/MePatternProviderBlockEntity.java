package com.reborn.modularmachinery.compat.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.*;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.*;
import appeng.api.stacks.*;
import appeng.api.util.AECableType;
import com.reborn.modularmachinery.block.*;
import com.reborn.modularmachinery.machine.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** A crafting provider whose persistent buffers are machine inputs, including through linked mirrors. */
public final class MePatternProviderBlockEntity extends BlockEntity
        implements ICraftingProvider, IActionHost, IInWorldGridNodeHost, MachineInputProvider {
    public static final int PATTERNS=36;
    // Ordinals 0..2 are saved by releases up to 0.108.0; never reorder them.
    public enum WorkMode { DEFAULT, CRAFTING_LOCK_MODE, ENHANCED_BLOCKING_MODE, BLOCKING_MODE, ISOLATION_INPUT }
    private final IManagedGridNode mainNode;
    private final List<ItemStack> patterns=new ArrayList<>(Collections.nCopies(PATTERNS,ItemStack.EMPTY));
    private final ProviderState storage=new ProviderState(this::setChanged,this::refreshHosts,this::available);
    private static final Set<MePatternProviderBlockEntity> ACTIVE_LOCKS=ConcurrentHashMap.newKeySet();
    private static boolean listenerRegistered;
    private BlockPos legacyLockHost;
    private WorkMode clientMode=WorkMode.DEFAULT;
    private CompoundTag clientSummary=new CompoundTag();
    private net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler> patternCapability = net.minecraftforge.common.util.LazyOptional.of(PatternInventory::new);
    private final class PatternInventory implements net.minecraftforge.items.IItemHandler {
        public int getSlots(){return PATTERNS;}
        public ItemStack getStackInSlot(int slot){return getPatternAt(slot);}
        public int getSlotLimit(int slot){return 1;}
        public boolean isItemValid(int slot,ItemStack stack){return slot>=0&&slot<PATTERNS&&appeng.crafting.pattern.AEPatternDecoder.INSTANCE.isEncodedPattern(stack);}
        public ItemStack insertItem(int slot,ItemStack stack,boolean simulate){
            if(!isItemValid(slot,stack)||!getPatternAt(slot).isEmpty())return stack;
            if(!simulate)setPattern(slot,stack.copyWithCount(1));return stack.getCount()==1?ItemStack.EMPTY:stack.copyWithCount(stack.getCount()-1);
        }
        public ItemStack extractItem(int slot,int amount,boolean simulate){var stack=getPatternAt(slot);if(amount<=0||stack.isEmpty())return ItemStack.EMPTY;if(!simulate)setPattern(slot,ItemStack.EMPTY);return stack;}
    }
    @Override public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(net.minecraftforge.common.capabilities.Capability<T> capability,Direction side){return capability==net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER?patternCapability.cast():super.getCapability(capability,side);}
    @Override public void invalidateCaps(){super.invalidateCaps();patternCapability.invalidate();}
    @Override public void reviveCaps(){super.reviveCaps();patternCapability=net.minecraftforge.common.util.LazyOptional.of(PatternInventory::new);}
    public MePatternProviderBlockEntity(BlockPos pos,BlockState state){
        super(ModBlocks.ME_PATTERN_PROVIDER_ENTITY.get(),pos,state);
        mainNode=GridHelper.createManagedNode(this,new IGridNodeListener<MePatternProviderBlockEntity>(){
            @Override public void onSaveChanges(MePatternProviderBlockEntity owner,IGridNode node){owner.setChanged();}
            @Override public void onStateChanged(MePatternProviderBlockEntity owner,IGridNode node,IGridNodeListener.State reason){ICraftingProvider.requestUpdate(owner.mainNode);}
            @Override public void onGridChanged(MePatternProviderBlockEntity owner,IGridNode node){ICraftingProvider.requestUpdate(owner.mainNode);}
        }).addService(ICraftingProvider.class,this).setFlags(GridFlags.REQUIRE_CHANNEL).setIdlePowerUsage(1)
            .setInWorldNode(true).setTagName("ae_node").setExposedOnSides(EnumSet.allOf(Direction.class)).setVisualRepresentation(state.getBlock());
    }
    private boolean available(){return level!=null&&!level.isClientSide&&!isRemoved()&&level.hasChunkAt(worldPosition)&&level.getBlockEntity(worldPosition)==this;}
    @Override public void onLoad(){super.onLoad();if(level!=null&&!level.isClientSide){mainNode.create(level,worldPosition);ICraftingProvider.requestUpdate(mainNode);registerListeners();if(storage.locked())ACTIVE_LOCKS.add(this);refreshHosts();}}
    @Override public void setRemoved(){ACTIVE_LOCKS.remove(this);mainNode.destroy();super.setRemoved();}
    @Override public void onChunkUnloaded(){ACTIVE_LOCKS.remove(this);mainNode.destroy();super.onChunkUnloaded();}
    @Override public IGridNode getGridNode(Direction side){return mainNode.getNode();}
    @Override public IGridNode getActionableNode(){return mainNode.getNode();}
    @Override public AECableType getCableConnectionType(Direction side){return AECableType.SMART;}
    public ProviderState storage(){return storage;}
    public void setNetworkOwner(net.minecraft.world.entity.player.Player player){mainNode.setOwningPlayer(player);}
    public void serverTick(){
        if(!available())return;
        if(storage.pending()&&level.getGameTime()%5==0)storage.tickReturn((key,amount)->{
            var grid=mainNode.getGrid();if(grid==null||!mainNode.isActive()||!mainNode.isOnline())return 0;
            return appeng.api.storage.StorageHelper.poweredInsert(grid.getEnergyService(),grid.getStorageService().getInventory(),key,amount,IActionSource.ofMachine(this),appeng.api.config.Actionable.MODULATE);
        });
        if(storage.locked())ACTIVE_LOCKS.add(this);else ACTIVE_LOCKS.remove(this);
    }
    @Override public List<IPatternDetails> getAvailablePatterns(){
        List<IPatternDetails> result=new ArrayList<>();if(level==null||level.isClientSide||!mainNode.isActive())return result;
        for(ItemStack stack:patterns){if(stack.isEmpty())continue;var detail=appeng.crafting.pattern.AEPatternDecoder.INSTANCE.decodePattern(stack,level,false);if(detail instanceof appeng.crafting.pattern.AEProcessingPattern)result.add(detail);}
        return result;
    }
    @Override public boolean isBusy(){return storage.busy();}
    @Override public boolean pushPattern(IPatternDetails detail,KeyCounter[] inputs){
        if(!available()||!mainNode.isActive()||!(detail instanceof appeng.crafting.pattern.AEProcessingPattern))return false;
        // Never accept an unadvertised pattern. Duplicate definitions select the first physical slot.
        for(int i=0;i<PATTERNS;i++)if(detail.getDefinition().equals(AEItemKey.of(patterns.get(i)))){
            boolean accepted=storage.accept(i,detail.getDefinition(),inputs);if(accepted&&storage.locked())ACTIVE_LOCKS.add(this);return accepted;
        }
        return false;
    }
    public void setWorkMode(WorkMode mode){storage.setMode(mode);legacyLockHost=null;if(!storage.locked())ACTIVE_LOCKS.remove(this);}
    public WorkMode workMode(){return level!=null&&level.isClientSide?clientMode:storage.mode();}
    public void setClientWorkModeMirror(int ordinal){if(level!=null&&level.isClientSide)clientMode=ordinal>=0&&ordinal<WorkMode.values().length?WorkMode.values()[ordinal]:WorkMode.DEFAULT;}
    public CompoundTag summary(){return summary(0);}
    public CompoundTag summary(int page){
        if(level!=null&&level.isClientSide)return clientSummary.copy();
        CompoundTag tag=storage.summary(page);tag.putBoolean("online",mainNode.isActive()&&mainNode.isOnline());return tag;
    }
    public void setClientSummary(CompoundTag tag){if(level!=null&&level.isClientSide)clientSummary=tag.copy();}
    public void setClientPatternsMirror(List<ItemStack> values){if(level==null||!level.isClientSide)return;readPatterns(values);}
    private void readPatterns(List<ItemStack> values){for(int i=0;i<PATTERNS;i++)patterns.set(i,i<values.size()?values.get(i).copy():ItemStack.EMPTY);}
    public ItemStack getPatternAt(int slot){return slot>=0&&slot<PATTERNS?patterns.get(slot).copy():ItemStack.EMPTY;}
    public List<ItemStack> patternsView(){return patterns.stream().map(ItemStack::copy).toList();}
    public void setPattern(int slot,ItemStack stack){
        if(slot<0||slot>=PATTERNS)throw new IndexOutOfBoundsException(slot);
        ItemStack next=stack==null?ItemStack.EMPTY:stack.copy();
        if(!ItemStack.matches(patterns.get(slot),next))storage.patternChanged(slot);
        patterns.set(slot,next);setChanged();if(level!=null&&!level.isClientSide){level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);ICraftingProvider.requestUpdate(mainNode);}
    }
    @Override public int declaredInputGroup(){return storage.group();}
    @Override public Object inputIdentity(){return this;}
    @Override public void appendInputs(HatchCollection.Builder builder,String tag,GroupAllocator groups){storage.appendInputs(builder,tag,groups);}
    private void refreshHosts(){
        if(level==null||level.isClientSide)return;
        for(var host:MachineControllerBlockEntity.loadedControllers())if(host.getLevel()==level)host.refreshInputPorts();
    }
    private static void registerListeners(){
        if(listenerRegistered)return;listenerRegistered=true;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((com.reborn.modularmachinery.api.event.RecipeFinishEvent e)->unlockByHost(e.controller()));
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((com.reborn.modularmachinery.api.event.FactoryRecipeFinishEvent e)->unlockByHost(e.controller()));
    }
    private static void unlockByHost(MachineControllerBlockEntity controller){
        for(var provider:ACTIVE_LOCKS)if(provider.level==controller.getLevel()&&(controller.usesInputProvider(provider)||provider.legacyLockHost!=null&&provider.legacyLockHost.equals(controller.getBlockPos()))){
            provider.storage.unlock();provider.legacyLockHost=null;ACTIVE_LOCKS.remove(provider);
        }
    }
    @Override protected void saveAdditional(CompoundTag tag){super.saveAdditional(tag);saveContents(tag);mainNode.saveToNBT(tag);}
    private void saveContents(CompoundTag tag){
        ListTag list=new ListTag();for(var pattern:patterns)list.add(pattern.save(new CompoundTag()));tag.put("patterns",list);
        tag.putInt("work_mode",storage.mode().ordinal());tag.put("provider_storage",storage.save());
        if(legacyLockHost!=null&&storage.locked())tag.putLong("lock_host",legacyLockHost.asLong());
    }
    public CompoundTag dropContents(){CompoundTag tag=new CompoundTag();saveContents(tag);tag.remove("lock_host");return tag;}
    @Override public void load(CompoundTag tag){
        super.load(tag);ListTag list=tag.getList("patterns",Tag.TAG_COMPOUND);List<ItemStack> values=new ArrayList<>();for(int i=0;i<Math.min(PATTERNS,list.size());i++)values.add(ItemStack.of(list.getCompound(i)));readPatterns(values);
        int ordinal=tag.getInt("work_mode");clientMode=ordinal>=0&&ordinal<WorkMode.values().length?WorkMode.values()[ordinal]:WorkMode.DEFAULT;
        clientSummary=tag.getCompound("summary").copy();
        if(level!=null&&level.isClientSide)return;
        storage.load(tag.getCompound("provider_storage"),tag.getInt("work_mode"),tag.getBoolean("lock_active"));
        legacyLockHost=tag.contains("lock_host")?BlockPos.of(tag.getLong("lock_host")):null;
        mainNode.loadFromNBT(tag);
    }
    @Override public CompoundTag getUpdateTag(){
        // Never put unbounded storage in a chunk or menu packet.
        CompoundTag tag=new CompoundTag();ListTag list=new ListTag();for(var pattern:patterns)list.add(pattern.save(new CompoundTag()));tag.put("patterns",list);tag.putInt("work_mode",storage.mode().ordinal());tag.put("summary",summary());return tag;
    }
    @Override public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket(){return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);}
    @Override public void onDataPacket(net.minecraft.network.Connection connection,net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet){if(packet.getTag()!=null)load(packet.getTag());}
}
