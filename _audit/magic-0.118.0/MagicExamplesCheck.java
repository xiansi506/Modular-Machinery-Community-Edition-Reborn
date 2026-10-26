package mmverify;
import com.google.gson.*;
import com.reborn.modularmachinery.magic.*;
import com.reborn.modularmachinery.machine.*;
import com.reborn.modularmachinery.recipe.*;
import java.nio.file.*;
import java.util.*;
public final class MagicExamplesCheck {
 public static void main(String[] args)throws Exception{
  net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
  for(var mod:List.of("botania","naturesaura","bloodmagic"))new MagicModule(mod).register(new com.reborn.modularmachinery.compat.CompatRegistry());
  int checks=0;
  for(String mod:List.of("botania","naturesaura","bloodmagic")){
   var base=Path.of("examples/magic-resources/"+mod+"-datapack/data/magic_demo");
   var machine=MachineSchema.read(JsonParser.parseString(Files.readString(base.resolve("machinery/"+mod+".json"))),mod,Map.of());
   if(!machine.id().toString().equals("magic_demo:"+mod))throw new AssertionError("Wrong machine id "+machine.id());
   System.out.println("PASS machine definition "+mod);checks++;
   var json=JsonParser.parseString(Files.readString(base.resolve("recipes/"+mod+"_stone.json"))).getAsJsonObject();
   if(!json.get("machine").getAsString().equals(machine.id().toString())||json.get("recipeTime").getAsInt()!=100)throw new AssertionError();
   int magic=0;for(var element:json.getAsJsonArray("requirements")){var r=MachineRequirementParser.parse(element.getAsJsonObject(),new net.minecraft.resources.ResourceLocation("magic_demo",mod));if(r instanceof MagicRequirement)magic++;}
   if(magic!=(mod.equals("bloodmagic")?2:1))throw new AssertionError();
   System.out.println("PASS complete recipe body "+mod);checks++;
  }
  System.out.println("RESULT "+checks+" passed / 0 failed");
 }
}
