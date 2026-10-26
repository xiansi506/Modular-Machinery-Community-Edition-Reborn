package mmverify;
import com.reborn.modularmachinery.magic.*;
import com.reborn.modularmachinery.compat.magic.*;
import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.LazyOptional;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import vazkii.botania.api.BotaniaAPI;
import vazkii.botania.api.BotaniaForgeCapabilities;
import vazkii.botania.api.internal.ManaNetwork;
import vazkii.botania.api.mana.*;
import vazkii.botania.api.mana.spark.SparkAttachable;
import de.ellpeck.naturesaura.api.NaturesAuraAPI;
import de.ellpeck.naturesaura.api.aura.chunk.IAuraChunk;
import de.ellpeck.naturesaura.api.aura.type.IAuraType;
import wayoftime.bloodmagic.common.item.*;
import wayoftime.bloodmagic.core.data.*;
import wayoftime.bloodmagic.util.helper.NetworkHelper;
import wayoftime.bloodmagic.demonaura.*;
import wayoftime.bloodmagic.will.DemonWillHolder;
import wayoftime.bloodmagic.api.compat.EnumDemonWillType;
public final class MagicNativeCheck {
 static int passed,failed;interface Check{void run()throws Exception;}
 static void ok(boolean b){if(!b)throw new AssertionError();}
 static void test(String n,Check c){try{c.run();passed++;System.out.println("PASS "+n);}catch(Throwable t){failed++;System.out.println("FAIL "+n);t.printStackTrace(System.out);}}
 static Object replaceStatic(Class<?> type,String name,Object value)throws Exception{
  var field=type.getDeclaredField(name);field.setAccessible(true);Object before=field.get(null);
  var f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var u=(sun.misc.Unsafe)f.get(null);u.putObjectVolatile(u.staticFieldBase(field),u.staticFieldOffset(field),value);return before;
 }
 static Object object(Object p,Method m,Object[]a){return switch(m.getName()){case "equals"->p==a[0];case "hashCode"->System.identityHashCode(p);case "toString"->"offline real API fixture";default->null;};}
 static void backend(MagicCoreCheck.TestHatch h,MagicBackend b)throws Exception{WorldHidingCheck.field(h,MagicHatchBlockEntity.class,"backend",b);}
 static MagicRequirement req(MagicFamily f,IOType io,double n,String v,double min,double max){return new MagicRequirement(io,f,n,v,min,max,false);}
 static HatchCollection ports(MagicBackend...b){var p=HatchCollection.builder();for(var q:b)p.addMagicPort(q,null);return p.build();}
 static class OrbItem extends Item implements IBloodOrb,IBindable {
  OrbItem(){super(new Item.Properties().stacksTo(1));}
  public BloodOrb getOrb(ItemStack stack){return new BloodOrb(new ResourceLocation("test:orb"),1,5000,10);}
 }
 static class AuraLevel extends MagicCoreCheck.TestLevel {
  LevelChunk chunk;
  AuraLevel(){super();}
  @Override public LevelChunk getChunk(int x,int z){return chunk;}
 }
 static class AuraChunkFixture extends LevelChunk {
  IAuraChunk aura;
  AuraChunkFixture(){super(null,null);}
  @Override public <T> LazyOptional<T> getCapability(Capability<T> cap,Direction side){return cap==NaturesAuraAPI.CAP_AURA_CHUNK?LazyOptional.of(()->aura).cast():LazyOptional.empty();}
 }
 static final class AuraState implements InvocationHandler {
  Map<BlockPos,Integer> spots=new HashMap<>();int dirty;
  IAuraType type=(IAuraType)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IAuraType.class},(p,m,a)->switch(m.getName()){case "getName"->new ResourceLocation("naturesaura:overworld");case "getColor"->0x55aa55;case "getPriority"->1;case "isPresentInLevel","isSimilar"->true;default->object(p,m,a);});
  int value(BlockPos pos,int radius){return 1000000+spots.entrySet().stream().filter(e->e.getKey().distSqr(pos)<=radius*radius).mapToInt(Map.Entry::getValue).sum();}
  CompoundTag save(){var tag=new CompoundTag();var list=new ListTag();for(var e:spots.entrySet()){var a=new CompoundTag();a.putLong("p",e.getKey().asLong());a.putInt("n",e.getValue());list.add(a);}tag.put("spots",list);return tag;}
  void load(CompoundTag tag){spots.clear();for(var a:tag.getList("spots",10)){var n=(CompoundTag)a;spots.put(BlockPos.of(n.getLong("p")),n.getInt("n"));}}
  public Object invoke(Object p,Method m,Object[]a){return switch(m.getName()){
   case "getType"->type;case "serializeNBT"->save();case "deserializeNBT"->{load((CompoundTag)a[0]);yield null;}case "markDirty"->{dirty++;yield null;}
   case "getDrainSpot"->spots.getOrDefault((BlockPos)a[0],0);
   case "storeAura","drainAura"->{int n=(int)a[1];boolean sim=a.length==4&&(boolean)a[3];if(!sim){spots.merge((BlockPos)a[0],m.getName().equals("storeAura")?n:-n,Integer::sum);dirty++;}yield n;}
   default->object(p,m,a);
  };}
 }
 static Object cancel(Class<?> type)throws Exception {Object old=MinecraftForge.EVENT_BUS;var bus=(net.minecraftforge.eventbus.api.IEventBus)Proxy.newProxyInstance(MagicNativeCheck.class.getClassLoader(),new Class[]{net.minecraftforge.eventbus.api.IEventBus.class},(p,m,a)->m.getName().equals("post")&&type.isInstance(a[0])?true:m.invoke(old,a));replaceStatic(MinecraftForge.class,"EVENT_BUS",bus);return old;}
 static void will(DemonWillHolder holder, EnumDemonWillType type, double value){holder.drainWill(type,Double.MAX_VALUE);holder.addWill(type,value);}
 public static void main(String[]args)throws Exception{
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  // Prevent full mod bootstrap through ServiceLoader; keep the genuine API interfaces and capability classes.
  Thread.currentThread().setContextClassLoader(new ClassLoader(MagicNativeCheck.class.getClassLoader()){@Override public Enumeration<java.net.URL> getResources(String n)throws java.io.IOException{return n.equals("META-INF/services/vazkii.botania.api.BotaniaAPI")?Collections.emptyEnumeration():super.getResources(n);}});
  Set<Object> pools=Collections.newSetFromMap(new IdentityHashMap<>());int[] events={0,0};
  ManaNetwork network=(ManaNetwork)Proxy.newProxyInstance(MagicNativeCheck.class.getClassLoader(),new Class[]{ManaNetwork.class},(p,m,a)->{
   if(m.getName().equals("fireManaNetworkEvent")){ok(a[0] instanceof ManaPool);if(a[2]==ManaNetworkAction.ADD){pools.add(a[0]);events[0]++;}else{pools.remove(a[0]);events[1]++;}return null;}return object(p,m,a);
  });
  BotaniaAPI api=(BotaniaAPI)Proxy.newProxyInstance(MagicNativeCheck.class.getClassLoader(),new Class[]{BotaniaAPI.class},(p,m,a)->m.getName().equals("getManaNetworkInstance")?network:object(p,m,a));
  Object oldApi=replaceStatic(BotaniaAPI.class,"INSTANCE",api);
  test("genuine Botania receiver accepts input bursts with capacity clamp",()->{var h=MagicCoreCheck.hatch(MagicFamily.MANA,true);var b=new BotaniaBackend(h);backend(h,b);var r=(ManaReceiver)b.capability(BotaniaForgeCapabilities.MANA_RECEIVER).orElse(null);ok(r.canReceiveManaFromBursts());r.receiveMana(200000);ok(h.stored()==100000&&r.isFull()&&r.getManaReceiverLevel()==h.getLevel()&&r.getManaReceiverPos().equals(h.getBlockPos()));});
  test("input implements signed receiver API without exposing a ManaPool",()->{var h=MagicCoreCheck.hatch(MagicFamily.MANA,true);h.stored(500);var b=new BotaniaBackend(h);var r=(ManaReceiver)b.capability(BotaniaForgeCapabilities.MANA_RECEIVER).orElse(null);r.receiveMana(-50);ok(h.stored()==450&&!(r instanceof ManaPool));});
  test("output ManaPool accepts signed direct transfers while rejecting bursts",()->{var h=MagicCoreCheck.hatch(MagicFamily.MANA,false);h.stored(500);var b=new BotaniaBackend(h);var r=(ManaPool)b.capability(BotaniaForgeCapabilities.MANA_RECEIVER).orElse(null);ok(r.isOutputtingPower()&&!r.canReceiveManaFromBursts()&&r.getMaxMana()==100000);r.receiveMana(100);r.receiveMana(-50);ok(h.stored()==550&&r.getColor().equals(Optional.of(DyeColor.WHITE)));});
  test("output pool joins and leaves the API network exactly once",()->{events[0]=events[1]=0;pools.clear();var h=MagicCoreCheck.hatch(MagicFamily.MANA,false);var b=new BotaniaBackend(h);b.load();b.load();b.tick();ok(pools.size()==1&&events[0]==1);b.unload();b.unload();ok(pools.isEmpty()&&events[1]==1);});
  test("Botania capability invalidation and revival preserve storage",()->{var h=MagicCoreCheck.hatch(MagicFamily.MANA,false);h.stored(42);var b=new BotaniaBackend(h);var old=b.capability(BotaniaForgeCapabilities.MANA_RECEIVER);int[] calls={0};old.addListener(v->calls[0]++);b.invalidate();b.invalidate();ok(!old.isPresent()&&calls[0]==1);b.revive();var fresh=b.capability(BotaniaForgeCapabilities.MANA_RECEIVER);ok(old!=fresh&&((ManaPool)fresh.orElse(null)).getCurrentMana()==42);b.unload();});
  test("genuine spark capability enforces incoming direction",()->{var a=MagicCoreCheck.hatch(MagicFamily.MANA,true);a.stored(100);var b=new BotaniaBackend(a);var spark=(SparkAttachable)b.capability(BotaniaForgeCapabilities.SPARK_ATTACHABLE).orElse(null);ok(spark.canAttachSpark(ItemStack.EMPTY)&&spark.getAvailableSpaceForMana()==99900&&!spark.areIncomingTranfersDone());var c=new BotaniaBackend(MagicCoreCheck.hatch(MagicFamily.MANA,false));var output=(SparkAttachable)c.capability(BotaniaForgeCapabilities.SPARK_ATTACHABLE).orElse(null);ok(output.getAvailableSpaceForMana()==0&&output.areIncomingTranfersDone());});
  test("Botania burst simulation on client never changes mana",()->{var h=MagicCoreCheck.hatch(MagicFamily.MANA,true);WorldHidingCheck.field(h.getLevel(),net.minecraft.world.level.Level.class,"isClientSide",true);var b=new BotaniaBackend(h);((ManaReceiver)b.capability(BotaniaForgeCapabilities.MANA_RECEIVER).orElse(null)).receiveMana(500);ok(h.stored()==0);});
  replaceStatic(BotaniaAPI.class,"INSTANCE",oldApi);
  var saved=new BMWorldSavedData();WorldHidingCheck.field(null,NetworkHelper.class,"dataHandler",saved);UUID owner=UUID.randomUUID();var soul=saved.getNetwork(owner);soul.setCurrentEssence(5000);
  WorldHidingCheck.field(net.minecraft.core.registries.BuiltInRegistries.ITEM,Class.forName("net.minecraftforge.registries.NamespacedWrapper"),"frozen",false);
  ((net.minecraftforge.registries.ForgeRegistry<Item>)net.minecraftforge.registries.ForgeRegistries.ITEMS).unfreeze();
  Item orbItem=new OrbItem();net.minecraftforge.registries.ForgeRegistries.ITEMS.register(new ResourceLocation("test:bound_orb"),orbItem);ItemStack bound=new ItemStack(orbItem);bound.getOrCreateTag().put("binding",new Binding(owner,"Offline owner").serializeNBT());
  test("genuine Blood Magic 3 binding resolves orb capacity",()->{var h=MagicCoreCheck.hatch(MagicFamily.LIFEESSENCE,true);h.orbs().setStackInSlot(0,bound.copy());var b=new BloodMagicBackend(h);ok(b.acceptsOrb(bound)&&b.boundOrb()&&b.orbCapacity()==5000);});
  test("LP input withdraws at most one tenth of orb capacity",()->{soul.setCurrentEssence(5000);var h=MagicCoreCheck.hatch(MagicFamily.LIFEESSENCE,true);h.orbs().setStackInSlot(0,bound.copy());var b=new BloodMagicBackend(h);backend(h,b);b.tick();b.tick();ok(h.stored()==500&&soul.getCurrentEssence()==4500);ok(req(MagicFamily.LIFEESSENCE,IOType.INPUT,125,"",0,Integer.MAX_VALUE).apply(ports(b),null)&&h.stored()==375);b.tick();ok(h.stored()==500&&soul.getCurrentEssence()==4375);});
  test("unbound orb never grants access to a cached LP buffer",()->{var h=MagicCoreCheck.hatch(MagicFamily.LIFEESSENCE,true);h.stored(100);h.orbs().setStackInSlot(0,new ItemStack(orbItem));var b=new BloodMagicBackend(h);ok(!b.boundOrb()&&!req(MagicFamily.LIFEESSENCE,IOType.INPUT,1,"",0,Integer.MAX_VALUE).canSatisfy(ports(b))&&h.stored()==100);});
  test("malformed binding UUID is rejected without ticking crash",()->{var h=MagicCoreCheck.hatch(MagicFamily.LIFEESSENCE,true);var stack=new ItemStack(orbItem);stack.getOrCreateTag().put("binding",new CompoundTag());h.orbs().setStackInSlot(0,stack);var b=new BloodMagicBackend(h);b.tick();ok(!b.boundOrb()&&h.stored()==0);});
  test("LP output retains the part the real SoulNetwork cannot take",()->{soul.setCurrentEssence(4900);var h=MagicCoreCheck.hatch(MagicFamily.LIFEESSENCE,false);h.orbs().setStackInSlot(0,bound.copy());h.stored(500);var b=new BloodMagicBackend(h);b.tick();ok(h.stored()==400&&soul.getCurrentEssence()==5000);});
  test("canceled real Blood Magic syphon does not create LP",()->{soul.setCurrentEssence(5000);var h=MagicCoreCheck.hatch(MagicFamily.LIFEESSENCE,true);h.orbs().setStackInSlot(0,bound.copy());var b=new BloodMagicBackend(h);Object originalBus=cancel(wayoftime.bloodmagic.event.SoulNetworkEvent.Syphon.class);try{b.tick();ok(h.stored()==0&&soul.getCurrentEssence()==5000);}finally{replaceStatic(MinecraftForge.class,"EVENT_BUS",originalBus);}});
  test("canceled real Blood Magic fill retains cached output",()->{soul.setCurrentEssence(0);var h=MagicCoreCheck.hatch(MagicFamily.LIFEESSENCE,false);h.orbs().setStackInSlot(0,bound.copy());h.stored(100);var b=new BloodMagicBackend(h);Object originalBus=cancel(wayoftime.bloodmagic.event.SoulNetworkEvent.Fill.class);try{b.tick();ok(h.stored()==100&&soul.getCurrentEssence()==0);}finally{replaceStatic(MinecraftForge.class,"EVENT_BUS",originalBus);}});
  test("orb swap invalidates an LP plan",()->{var h=MagicCoreCheck.hatch(MagicFamily.LIFEESSENCE,true);h.orbs().setStackInSlot(0,bound.copy());h.stored(100);var b=new BloodMagicBackend(h);var tx=new ResourceTransaction();ok(req(MagicFamily.LIFEESSENCE,IOType.INPUT,50,"",0,Integer.MAX_VALUE).reserve(ports(b).transactionView(tx),RecipeModifiers.EMPTY));h.orbs().setStackInSlot(0,ItemStack.EMPTY);ok(!tx.commit()&&h.stored()==100);});
  var level=MagicCoreCheck.level();WorldDemonWillHandler.addWillWorld(level.dimension().location());var willChunk=new WillChunk(new PosXY(0,0));var holder=new DemonWillHolder();willChunk.setCurrentWill(holder);WorldDemonWillHandler.getWillWorld(level.dimension().location()).getWillChunks().put(new PosXY(0,0),willChunk);
  test("real Blood Magic world Will input uses fractional quantities",()->{will(holder,EnumDemonWillType.DEFAULT,50.5);var h=MagicCoreCheck.hatch(MagicFamily.WILL,true);WorldHidingCheck.field(h,net.minecraft.world.level.block.entity.BlockEntity.class,"level",level);var b=new BloodMagicBackend(h);ok(req(MagicFamily.WILL,IOType.INPUT,20.25,"default",10,100).apply(ports(b),null)&&holder.getWill(EnumDemonWillType.DEFAULT)==30.25);});
  test("real Will output obeys the post-output maximum",()->{will(holder,EnumDemonWillType.CORROSIVE,99.5);var h=MagicCoreCheck.hatch(MagicFamily.WILL,false);WorldHidingCheck.field(h,net.minecraft.world.level.block.entity.BlockEntity.class,"level",level);var b=new BloodMagicBackend(h);ok(!req(MagicFamily.WILL,IOType.OUTPUT,1,"corrosive",0,100).apply(ports(b),null));ok(req(MagicFamily.WILL,IOType.OUTPUT,.5,"corrosive",0,100).apply(ports(b),null)&&holder.getWill(EnumDemonWillType.CORROSIVE)==100);});
  test("two real Will hatches in one chunk do not double count",()->{will(holder,EnumDemonWillType.DEFAULT,10);var a=MagicCoreCheck.hatch(MagicFamily.WILL,true);var c=MagicCoreCheck.hatch(MagicFamily.WILL,true);WorldHidingCheck.field(a,net.minecraft.world.level.block.entity.BlockEntity.class,"level",level);WorldHidingCheck.field(c,net.minecraft.world.level.block.entity.BlockEntity.class,"level",level);ok(!req(MagicFamily.WILL,IOType.INPUT,15,"default",0,100).canSatisfy(ports(new BloodMagicBackend(a),new BloodMagicBackend(c))));});
  test("real Will rollback restores the paid chunk amount",()->{will(holder,EnumDemonWillType.DEFAULT,10);var h=MagicCoreCheck.hatch(MagicFamily.WILL,true);WorldHidingCheck.field(h,net.minecraft.world.level.block.entity.BlockEntity.class,"level",level);var tx=new ResourceTransaction();ok(req(MagicFamily.WILL,IOType.INPUT,7,"default",0,100).reserve(ports(new BloodMagicBackend(h)).transactionView(tx),RecipeModifiers.EMPTY));tx.record(()->false);ok(!tx.commit()&&holder.getWill(EnumDemonWillType.DEFAULT)==10);});
  AuraState state=new AuraState();IAuraChunk aura=(IAuraChunk)Proxy.newProxyInstance(MagicNativeCheck.class.getClassLoader(),new Class[]{IAuraChunk.class},state);var chunk=(AuraChunkFixture)WorldHidingCheck.allocate(AuraChunkFixture.class);chunk.aura=aura;var world=(AuraLevel)WorldHidingCheck.allocate(AuraLevel.class);world.entities=new HashMap<>();world.chunk=chunk;WorldHidingCheck.field(world,net.minecraft.world.level.Level.class,"dimension",net.minecraft.world.level.Level.OVERWORLD);
  var hooks=(NaturesAuraAPI.IInternalHooks)Proxy.newProxyInstance(MagicNativeCheck.class.getClassLoader(),new Class[]{NaturesAuraAPI.IInternalHooks.class},(p,m,a)->m.getName().equals("getAuraInArea")?state.value((BlockPos)a[1],(int)a[2]):object(p,m,a));Object oldHooks=replaceStatic(NaturesAuraAPI.class,"INSTANCE",hooks);
  test("genuine Nature's Aura capability and raw-unit API are used",()->{state.spots.clear();var h=MagicCoreCheck.hatch(MagicFamily.AURA,true);WorldHidingCheck.field(h,net.minecraft.world.level.block.entity.BlockEntity.class,"level",world);var b=new AuraBackend(h);ok(b.variants()[0].equals("naturesaura:overworld"));ok(req(MagicFamily.AURA,IOType.INPUT,100,"naturesaura:overworld",0,Integer.MAX_VALUE).apply(ports(b),null)&&state.value(BlockPos.ZERO,1)==999900);});
  test("Aura snapshot rollback restores exact drain-spot positions",()->{state.spots.clear();state.spots.put(new BlockPos(3,0,0),-250);var snapshot=state.save();var h=MagicCoreCheck.hatch(MagicFamily.AURA,true);WorldHidingCheck.field(h,net.minecraft.world.level.block.entity.BlockEntity.class,"level",world);var tx=new ResourceTransaction();ok(req(MagicFamily.AURA,IOType.INPUT,100,"naturesaura:overworld",0,Integer.MAX_VALUE).reserve(ports(new AuraBackend(h)).transactionView(tx),RecipeModifiers.EMPTY));tx.record(()->false);ok(!tx.commit()&&snapshot.equals(state.save()));});
  test("Aura output crosses zero without the convenience overload truncation",()->{state.spots.clear();state.spots.put(BlockPos.ZERO,-100);var h=MagicCoreCheck.hatch(MagicFamily.AURA,false);WorldHidingCheck.field(h,net.minecraft.world.level.block.entity.BlockEntity.class,"level",world);var b=new AuraBackend(h);ok(req(MagicFamily.AURA,IOType.OUTPUT,250,"naturesaura:overworld",Integer.MIN_VALUE,Integer.MAX_VALUE).apply(ports(b),null)&&state.spots.get(BlockPos.ZERO)==150);});
  test("unknown Aura type leaves the real capability unchanged",()->{state.spots.clear();var h=MagicCoreCheck.hatch(MagicFamily.AURA,true);WorldHidingCheck.field(h,net.minecraft.world.level.block.entity.BlockEntity.class,"level",world);ok(!req(MagicFamily.AURA,IOType.INPUT,1,"naturesaura:nether",0,Integer.MAX_VALUE).canSatisfy(ports(new AuraBackend(h)))&&state.spots.isEmpty());});
  replaceStatic(NaturesAuraAPI.class,"INSTANCE",oldHooks);
  System.out.println("RESULT "+passed+" passed / "+failed+" failed");if(failed>0)System.exit(1);
 }
}
