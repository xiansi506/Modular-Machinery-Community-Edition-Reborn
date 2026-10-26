package mmverify;
import com.google.gson.*;
import com.reborn.modularmachinery.magic.*;
import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.*;
import com.reborn.modularmachinery.energy.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import io.netty.buffer.Unpooled;
import java.util.*;
public final class MagicCoreCheck {
 static int passed,failed;
 interface Check {void run()throws Exception;}
 static void ok(boolean value){if(!value)throw new AssertionError();}
 static void test(String name,Check check){try{check.run();passed++;System.out.println("PASS "+name);}catch(Throwable t){failed++;System.out.println("FAIL "+name);t.printStackTrace(System.out);}}
 static final class Store implements MagicPort.Access {
  final Object key;double value,limit,min;int writes;boolean refuse;
  Store(Object key,double value,double limit){this.key=key;this.value=value;this.limit=limit;}
  public Object identity(){return key;}public double amount(){return value;}public double minimum(){return min;}public double maximum(){return limit;}
  public double move(double delta,boolean sim){if(refuse&&!sim)return 0;double n=delta>0?Math.min(delta,limit-value):-Math.min(-delta,value-min);if(!sim){value+=n;writes++;}return n;}
  public void restore(Object before){value=(Double)before;}
 }
 static MagicPort port(MagicFamily family,boolean input,MagicPort.Access access){return port(family,input,Map.of("",access));}
 static MagicPort port(MagicFamily family,boolean input,Map<String,MagicPort.Access> access){return new MagicPort(){public MagicFamily family(){return family;}public boolean input(){return input;}public Access access(String variant){return access.get(variant);}};}
 static HatchCollection ports(MagicPort...p){var b=HatchCollection.builder();for(var port:p)b.addMagicPort(port,null);return b.build();}
 static MagicRequirement req(MagicFamily f,IOType io,double n,String variant,double min,double max){return new MagicRequirement(io,f,n,variant,min,max,false);}
 static MagicRequirement mana(IOType io,double n){return req(MagicFamily.MANA,io,n,"",0,Integer.MAX_VALUE);}
 static MachineRecipe recipe(MachineRequirement...r){return new MachineRecipe(new ResourceLocation("test:magic"),"test:machine","Magic",20,List.of(r),1,false);}
 static class TestLevel extends WorldHidingCheck.TestLevel {
  long time;int updates;
  TestLevel(){super();}
  @Override public long getGameTime(){return time;}
  @Override public boolean hasChunkAt(BlockPos pos){return true;}
  @Override public void sendBlockUpdated(BlockPos pos,net.minecraft.world.level.block.state.BlockState a,net.minecraft.world.level.block.state.BlockState b,int flags){updates++;}
 }
 static class TestHatch extends MagicHatchBlockEntity {
  int dirty;
  TestHatch(){super(BlockPos.ZERO,Blocks.IRON_BLOCK.defaultBlockState());}
  @Override public void changed(){dirty++;}
 }
 static TestLevel level()throws Exception{var l=(TestLevel)WorldHidingCheck.allocate(TestLevel.class);l.entities=new HashMap<>();WorldHidingCheck.field(l,net.minecraft.world.level.Level.class,"dimension",net.minecraft.world.level.Level.OVERWORLD);return l;}
 static TestHatch hatch(MagicFamily f,boolean input)throws Exception{
  var h=(TestHatch)WorldHidingCheck.allocate(TestHatch.class);
  WorldHidingCheck.field(h,MagicHatchBlockEntity.class,"family",f);WorldHidingCheck.field(h,MagicHatchBlockEntity.class,"input",input);WorldHidingCheck.field(h,MagicHatchBlockEntity.class,"manaCapacity",100000);
  WorldHidingCheck.field(h,MagicHatchBlockEntity.class,"orbs",new ItemStackHandler(f==MagicFamily.LIFEESSENCE?1:0));WorldHidingCheck.field(h,MagicHatchBlockEntity.class,"words",new int[24]);
  WorldHidingCheck.field(h,BlockEntity.class,"worldPosition",BlockPos.ZERO);WorldHidingCheck.field(h,BlockEntity.class,"blockState",Blocks.IRON_BLOCK.defaultBlockState());WorldHidingCheck.field(h,BlockEntity.class,"level",level());return h;
 }
 public static void main(String[]args)throws Exception{
  SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
  for(var mod:List.of("botania","naturesaura","bloodmagic"))new MagicModule(mod).register(new com.reborn.modularmachinery.compat.CompatRegistry());
  var bus=com.reborn.modularmachinery.api.event.MachineEvents.class.getDeclaredField("bus");bus.setAccessible(true);bus.set(null,net.minecraftforge.eventbus.api.BusBuilder.builder().build());
  test("mana input pays exact units",()->{var s=new Store("a",700,1000);ok(mana(IOType.INPUT,125).apply(ports(port(MagicFamily.MANA,true,s)),RandomSource.create())&&s.value==575);});
  test("simulation leaves real and planned stores unchanged",()->{var s=new Store("a",100,1000);var p=ports(port(MagicFamily.MANA,true,s));ok(mana(IOType.INPUT,90).canSatisfy(p)&&s.value==100&&s.writes==0);});
  test("insufficient pooled input never half-pays",()->{var a=new Store("a",30,100);var b=new Store("b",40,100);ok(!mana(IOType.INPUT,80).apply(ports(port(MagicFamily.MANA,true,a),port(MagicFamily.MANA,true,b)),null)&&a.value==30&&b.value==40);});
  test("output capacity is enforced",()->{var s=new Store("a",95,100);ok(!mana(IOType.OUTPUT,6).apply(ports(port(MagicFamily.MANA,false,s)),null)&&s.value==95);ok(mana(IOType.OUTPUT,5).apply(ports(port(MagicFamily.MANA,false,s)),null)&&s.value==100);});
  test("input and output directions remain separate",()->{var s=new Store("a",100,1000);ok(!mana(IOType.INPUT,5).canSatisfy(ports(port(MagicFamily.MANA,false,s))));});
  test("same environment appears once even behind two hatches",()->{var s=new Store("chunk",10,100);ok(!mana(IOType.INPUT,15).canSatisfy(ports(port(MagicFamily.MANA,true,s),port(MagicFamily.MANA,true,s))));});
  test("shared transaction prevents cross-tag double payment",()->{var s=new Store("chunk",10,100);var b=HatchCollection.builder().addMagicPort(port(MagicFamily.MANA,true,s),"a").addMagicPort(port(MagicFamily.MANA,true,s),"b").build();var tx=new ResourceTransaction();var v=b.transactionView(tx);ok(mana(IOType.INPUT,6).reserve(v.withTag("a"),RecipeModifiers.EMPTY));ok(!mana(IOType.INPUT,6).reserve(v.withTag("b"),RecipeModifiers.EMPTY)&&s.value==10);});
  test("tag filtering preserves transactional identity",()->{var a=new Store("a",10,100);var b=new Store("b",20,100);var p=HatchCollection.builder().addMagicPort(port(MagicFamily.MANA,true,a),"a").addMagicPort(port(MagicFamily.MANA,true,b),"b").build();ok(p.withTag("a").magicPorts().size()==1&&p.withTag("missing").isEmpty());ok(p.transactionView(new ResourceTransaction()).withTag("a").withGroup(3).isTransactionView());});
  test("magic ports participate in hatch count and emptiness",()->{var p=ports(port(MagicFamily.MANA,true,new Store("a",1,100)));ok(!p.isEmpty()&&p.hatchCount()==1&&p.withGroup(-1).magicPorts().size()==1);});
  test("balanced input and output can reuse one shared channel",()->{var s=new Store("same",100,100);var p=ports(port(MagicFamily.MANA,true,s),port(MagicFamily.MANA,false,s));var tx=new ResourceTransaction();var v=p.transactionView(tx);ok(mana(IOType.INPUT,100).reserve(v,RecipeModifiers.EMPTY)&&mana(IOType.OUTPUT,100).reserve(v,RecipeModifiers.EMPTY)&&tx.commit()&&s.value==100);});
  test("late failure rolls back magic and long FE together",()->{var s=new Store("a",100,100);var energy=new LongEnergyStorage(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE,()->{});energy.setStored(9_000_000_000L);var tx=new ResourceTransaction();tx.energy(energy).extractEnergy(500,false);ok(mana(IOType.INPUT,50).reserve(ports(port(MagicFamily.MANA,true,s)).transactionView(tx),RecipeModifiers.EMPTY));tx.record(()->false);ok(!tx.commit()&&s.value==100&&energy.getEnergyStoredL()==9_000_000_000L);});
  test("execute refusal rolls all earlier magic payments back",()->{var a=new Store("a",20,100);var b=new Store("b",20,100);b.refuse=true;ok(!mana(IOType.INPUT,30).apply(ports(port(MagicFamily.MANA,true,a),port(MagicFamily.MANA,true,b)),null)&&a.value==20&&b.value==20);});
  test("external changes invalidate the plan without overwriting them",()->{var s=new Store("a",100,100);var tx=new ResourceTransaction();ok(mana(IOType.INPUT,40).reserve(ports(port(MagicFamily.MANA,true,s)).transactionView(tx),RecipeModifiers.EMPTY));s.value=90;ok(!tx.commit()&&s.value==90);});
  test("post-input minimum is respected",()->{var s=new Store("a",10,100);ok(!req(MagicFamily.WILL,IOType.INPUT,6,"default",5,100).canSatisfy(ports(port(MagicFamily.WILL,true,Map.of("default",s)))));});
  test("Will outputs cannot cross maximum",()->{var s=new Store("a",99,1000);ok(!req(MagicFamily.WILL,IOType.OUTPUT,2,"default",0,100).apply(ports(port(MagicFamily.WILL,false,Map.of("default",s))),null)&&s.value==99);});
  test("different Will types never share a balance",()->{var a=new Store("chunk",10,100);var b=new Store("chunk",20,100);var p=ports(port(MagicFamily.WILL,true,Map.of("default",a,"corrosive",b)));var tx=new ResourceTransaction();var v=p.transactionView(tx);ok(req(MagicFamily.WILL,IOType.INPUT,7,"default",0,100).reserve(v,RecipeModifiers.EMPTY)&&req(MagicFamily.WILL,IOType.INPUT,15,"corrosive",0,100).reserve(v,RecipeModifiers.EMPTY)&&tx.commit()&&a.value==3&&b.value==5);});
  test("fractional Will can split across ports without rounding loss",()->{var a=new Store("a",.1,100);var b=new Store("b",.1,100);var c=new Store("c",.1,100);ok(req(MagicFamily.WILL,IOType.INPUT,.3,"default",0,100).apply(ports(port(MagicFamily.WILL,true,Map.of("default",a)),port(MagicFamily.WILL,true,Map.of("default",b)),port(MagicFamily.WILL,true,Map.of("default",c))),null));ok(a.value+b.value+c.value<1e-15);});
  test("empty stores do not satisfy tiny nonzero Will requests",()->{var s=new Store("a",0,100);ok(!req(MagicFamily.WILL,IOType.INPUT,1e-12,"default",0,100).canSatisfy(ports(port(MagicFamily.WILL,true,Map.of("default",s)))));});
  test("Aura consumes exactly the declared raw amount",()->{var s=new Store("chunk",1000000,Integer.MAX_VALUE);s.min=Integer.MIN_VALUE;ok(req(MagicFamily.AURA,IOType.INPUT,100,"naturesaura:overworld",Integer.MIN_VALUE,Integer.MAX_VALUE).apply(ports(port(MagicFamily.AURA,true,Map.of("naturesaura:overworld",s))),null)&&s.value==999900);});
  test("negative Aura remains supported with explicit bounds",()->{var s=new Store("chunk",-50,Integer.MAX_VALUE);s.min=Integer.MIN_VALUE;ok(req(MagicFamily.AURA,IOType.INPUT,25,"naturesaura:overworld",-100,Integer.MAX_VALUE).apply(ports(port(MagicFamily.AURA,true,Map.of("naturesaura:overworld",s))),null)&&s.value==-75);});
  test("wrong Aura type fails closed",()->{var s=new Store("a",100,100);ok(!req(MagicFamily.AURA,IOType.INPUT,1,"naturesaura:nether",0,100).canSatisfy(ports(port(MagicFamily.AURA,true,Map.of("naturesaura:overworld",s)))));});
  test("zero amount can check presence and minimum",()->{var s=new Store("a",5,100);ok(req(MagicFamily.WILL,IOType.INPUT,0,"default",5,100).canSatisfy(ports(port(MagicFamily.WILL,true,Map.of("default",s)))));ok(!req(MagicFamily.WILL,IOType.INPUT,0,"default",6,100).canSatisfy(ports(port(MagicFamily.WILL,true,Map.of("default",s)))));});
  test("parallel limits do not overflow at int ceiling",()->{var s=new Store("a",Integer.MAX_VALUE,Integer.MAX_VALUE);ok(mana(IOType.INPUT,3).parallelLimit(ports(port(MagicFamily.MANA,true,s)),RecipeModifiers.EMPTY,Integer.MAX_VALUE)==Integer.MAX_VALUE/3);});
  test("parallel-unaffected requirements stay at one copy",()->{var r=mana(IOType.INPUT,10);r.setParallelizeUnaffected(true);r.setParallelism(8);ok(r.parallelism()==1&&r.canSatisfy(ports(port(MagicFamily.MANA,true,new Store("a",10,100)))));});
  test("typed modifiers affect only the chosen magic family",()->{var mod=RecipeModifier.parse(JsonParser.parseString("{\"target\":\"mana\",\"io\":\"input\",\"operation\":1,\"multiplier\":2}"),"test");var mods=RecipeModifiers.of(List.of(mod));var s=new Store("a",15,100);ok(!mana(IOType.INPUT,10).canSatisfy(ports(port(MagicFamily.MANA,true,s)),mods));ok(req(MagicFamily.LIFEESSENCE,IOType.INPUT,10,"",0,100).canSatisfy(ports(port(MagicFamily.LIFEESSENCE,true,s)),mods));});
  test("missing magic ports cannot satisfy a positive requirement",()->ok(!mana(IOType.INPUT,1).canSatisfy(HatchCollection.EMPTY)));
  test("recipe start reservation sees all requirements together",()->{var s=new Store("same",10,100);ok(!recipe(mana(IOType.INPUT,6),mana(IOType.INPUT,6)).canStart(ports(port(MagicFamily.MANA,true,s)))&&s.value==10);});
  test("per-tick magic obeys duration compensation",()->{var r=new MagicRequirement(IOType.INPUT,MagicFamily.MANA,10,"",0,1000,true);r.setDurationMultiplier(2);var s=new Store("a",25,100);ok(r.tick(ports(port(MagicFamily.MANA,true,s)),null)&&s.value==5);});
  for(MagicFamily f:MagicFamily.values())test("real requirement type packet round trip "+f,()->{String variant=f==MagicFamily.WILL?"corrosive":f==MagicFamily.AURA?"naturesaura:overworld":"";var r=new MagicRequirement(IOType.OUTPUT,f,f==MagicFamily.WILL?.5:123,variant,0,1000,true);var b=new FriendlyByteBuf(Unpooled.buffer());r.type().write(b,r);var restored=(MagicRequirement)r.type().readBody(b,IOType.OUTPUT);ok(restored.family==f&&restored.amount==r.amount&&restored.variant.equals(variant)&&restored.perTick());b.release();});
  for(String value:List.of("-1","0.5","\"12\"","null","true"))test("invalid integer magic JSON amount "+value,()->{try{MagicRequirements.type(MagicFamily.MANA).read(JsonParser.parseString("{\"amount\":"+value+"}").getAsJsonObject(),IOType.INPUT,"test");throw new AssertionError();}catch(JsonParseException expected){}});
  for(double n:new double[]{-1000000,0,.125,Integer.MAX_VALUE,9000000000d,1e-12})test("24-word signed-short menu transport "+n,()->{var h=hatch(MagicFamily.WILL,true);WorldHidingCheck.field(h,BlockEntity.class,"level",null);long bits=Double.doubleToRawLongBits(n);for(int i=0;i<4;i++)h.menuWord(i,(short)(bits>>>(i*16)));ok(Double.doubleToRawLongBits(h.value(0))==bits);});
  test("native mana load clamps to capacity and replaces",()->{var h=hatch(MagicFamily.MANA,true);var tag=new CompoundTag();tag.putInt("stored",Integer.MAX_VALUE);h.load(tag);h.load(tag);ok(h.stored()==100000);});
  test("LP cache survives removal of the orb and repeat loads",()->{var h=hatch(MagicFamily.LIFEESSENCE,true);var tag=new CompoundTag();tag.putInt("stored",100000);h.load(tag);h.load(tag);ok(h.stored()==100000&&h.orbs().getSlots()==1);});
  test("zero current is never approximately a nonzero movement",()->ok(!MagicPort.same(0,1e-12)&&MagicPort.same(.1+.2,.3)));
  test("unrepresentable tiny Will payment cannot become free",()->{var s=new Store("huge",1e18,Double.MAX_VALUE);ok(!req(MagicFamily.WILL,IOType.INPUT,.1,"default",0,Double.MAX_VALUE).apply(ports(port(MagicFamily.WILL,true,Map.of("default",s))),null)&&s.value==1e18);});
  test("integer Aura bounds reject fractional thresholds",()->{try{req(MagicFamily.AURA,IOType.INPUT,1,"naturesaura:overworld",.5,100);throw new AssertionError();}catch(IllegalArgumentException expected){}});
  test("script helpers generate the four native requirement kinds",()->{var js=new com.reborn.modularmachinery.kubejs.MachineRecipeJS().manaInput(10,true).lifeEssenceOutput(20).auraInput("naturesaura:overworld",30,0).willOutput("corrosive",.5,100);var field=js.getClass().getDeclaredField("builder");field.setAccessible(true);var json=((MachineRecipeJsonBuilder)field.get(js)).build("test:machine");ok(json.getAsJsonArray("requirements").size()==4);for(var element:json.getAsJsonArray("requirements")){var j=element.getAsJsonObject();var type=RequirementTypes.byName(j.get("type").getAsString());ok(type!=null);type.read(j,IOType.valueOf(j.get("io-type").getAsString().toUpperCase(Locale.ROOT)),"script");}});
  test("optional magic loot follows registered mod families",()->{ok(MagicLootTables.generate(m->false).isEmpty());ok(MagicLootTables.generate(m->m.equals("botania")).size()==2);ok(MagicLootTables.generate(m->m.equals("bloodmagic")).size()==4);var all=MagicLootTables.generate(m->true);ok(all.size()==8);for(var e:all.entrySet()){ok(e.getKey().getPath().startsWith("loot_tables/blocks/"));var j=JsonParser.parseString(new String(e.getValue(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();ok(j.get("type").getAsString().equals("minecraft:block"));ok(j.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("conditions").size()==1);}});
  test("backend lying about a transfer cannot create a free payment",()->{var a=new MagicPort.Access(){double value=20;public Object identity(){return this;}public double amount(){return value;}public double minimum(){return 0;}public double maximum(){return 100;}public double move(double d,boolean sim){return d;}public void restore(Object before){value=(Double)before;}};ok(!mana(IOType.INPUT,5).apply(ports(port(MagicFamily.MANA,true,a)),null)&&a.amount()==20);});
  test("overlapping environment aliases fail atomically",()->{double[] value={10};java.util.function.Function<String,MagicPort.Access> access=key->new MagicPort.Access(){public Object identity(){return key;}public double amount(){return value[0];}public double minimum(){return 0;}public double maximum(){return 100;}public double move(double d,boolean sim){double moved=d>0?Math.min(d,100-value[0]):-Math.min(-d,value[0]);if(!sim)value[0]+=moved;return moved;}public void restore(Object before){value[0]=(Double)before;}};ok(!mana(IOType.INPUT,15).apply(ports(port(MagicFamily.MANA,true,access.apply("a")),port(MagicFamily.MANA,true,access.apply("b"))),null)&&value[0]==10);});
  System.out.println("RESULT "+passed+" passed / "+failed+" failed");if(failed>0)System.exit(1);
 }
}
