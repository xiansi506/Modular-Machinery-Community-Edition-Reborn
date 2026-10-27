package com.reborn.modularmachinery.compat.ae2;

import com.reborn.modularmachinery.block.*;
import com.reborn.modularmachinery.machine.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A shared entrance; resolving it never loads a chunk or copies stored materials. */
public final class MePatternMirrorBlockEntity extends BlockEntity implements MachineInputProvider {
    private BlockPos providerPos;
    private String dimension="";
    private boolean unloaded;
    public MePatternMirrorBlockEntity(BlockPos pos,BlockState state){super(ModBlocks.ME_PATTERN_MIRROR_ENTITY.get(),pos,state);}
    public MePatternProviderBlockEntity provider(){
        if(unloaded||isRemoved()||level==null||!level.hasChunkAt(worldPosition)||providerPos==null||!dimension.equals(level.dimension().location().toString())||!level.hasChunkAt(providerPos))return null;
        return level.getBlockEntity(providerPos) instanceof MePatternProviderBlockEntity provider&&!provider.isRemoved()?provider:null;
    }
    private void refreshHosts(){if(level!=null&&!level.isClientSide)MachineControllerBlockEntity.inputProviderChanged(level,worldPosition);}
    @Override public void onLoad(){super.onLoad();unloaded=false;refreshHosts();}
    @Override public void setRemoved(){super.setRemoved();refreshHosts();}
    @Override public void onChunkUnloaded(){unloaded=true;super.onChunkUnloaded();refreshHosts();}
    public boolean bind(CompoundTag tag){
        if(level==null||!tag.contains("position")||!level.dimension().location().toString().equals(tag.getString("dimension")))return false;
        providerPos=BlockPos.of(tag.getLong("position"));dimension=tag.getString("dimension");setChanged();
        refreshHosts();return true;
    }
    public String linkDescription(){return providerPos==null?"—":dimension+" "+providerPos.toShortString();}
    public Object inputIdentity(){var provider=provider();return provider==null?this:provider;}
    public int declaredInputGroup(){var provider=provider();return provider==null?-1:provider.declaredInputGroup();}
    @Override public java.util.Collection<BlockPos> inputDependencies(){return providerPos!=null&&level!=null&&dimension.equals(level.dimension().location().toString())?java.util.List.of(providerPos):java.util.List.of();}
    public void appendInputs(HatchCollection.Builder builder,String tag,GroupAllocator groups){var provider=provider();if(provider!=null)provider.appendInputs(builder,tag,groups);}
    @Override protected void saveAdditional(CompoundTag tag){super.saveAdditional(tag);if(providerPos!=null){tag.putLong("position",providerPos.asLong());tag.putString("dimension",dimension);}}
    @Override public void load(CompoundTag tag){super.load(tag);providerPos=tag.contains("position")?BlockPos.of(tag.getLong("position")):null;dimension=tag.getString("dimension");}
}
