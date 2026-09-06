package program.typesystem;

import static id.Mdf.mut;
import static org.junit.jupiter.api.Assertions.*;
import static program.typesystem.SubTyping.isSubType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
//import static program.typesystem.RunTypeSystem.ok;
//import static program.typesystem.RunTypeSystem.fail;

import id.Mdf;

class TestLubGlb {

  @Test void test() {
    MdfLubGlb.lub(EnumSet.of(mut));
    //MdfLubGlb is selftesting.
  }
  /// The least upper bound is the most general of the modifiers that every option
  /// is a subtype of.
  @Test void testHandMadeLub(){
    for(var mdfs: MdfLubGlb.domain()){
      assertEquals(MdfLubGlb.lub(mdfs),mostGeneral(mdfs),
        "For mdfs= "+mdfs);
    }
  }
  /// The greatest lower bound is the most specific of the modifiers that are a
  /// subtype of every option.
  @Test void testHandMadeGlb(){
    for(var mdfs: MdfLubGlb.domain()){
      assertEquals(MdfLubGlb.glb(mdfs),mostSpecific(mdfs),
        "For mdfs= "+mdfs);
    }
  }
  Mdf mostSpecific(Set<Mdf> options){
    return mostSpecGen(options,this::mostSpecWin,true);
  }
  Mdf mostGeneral(Set<Mdf> options){
    return mostSpecGen(options,this::mostGenWin,false);
  }
  boolean mostSpecWin(Mdf mi, Mdf mj){ return isSubType(mi, mj); }
  boolean mostGenWin(Mdf mi, Mdf mj){ return isSubType(mj, mi); }
  Mdf mostSpecGen(Set<Mdf> options, BiPredicate<Mdf,Mdf> test, boolean max){
    assert !options.isEmpty();
    List<Mdf> res= new ArrayList<>(Stream.of(Mdf.values())
      .filter(Mdf::isSyntaxMdf)
      .filter(mi->options.stream()
        .allMatch(mj->test.test(mi,mj)))
      .toList());
    assert !res.isEmpty();
    if(max){ 
      return res.stream().max(Comparator.comparingInt(
        EMethTypeSystem.recvPriority::indexOf
        )).get();
    }//could avoid this duplication, but it is now just test code
    return res.stream().min(Comparator.comparingInt(
        EMethTypeSystem.recvPriority::indexOf
        )).get();    
  }
}
