package program.typesystem;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.EnumSet.of;
import static id.Mdf.*;
import static program.typesystem.SubTyping.isSubType;

import id.Mdf;

/** LUB (Least Upper Bound):
/ * The smallest element e such that x≤e for all elements x in the set.
/ * GLB (Greatest Lower Bound):
/ * The largest element e such that e≤x for all elements x in the set.

 least upper bound lub(imm,mut)= read
 greatest lower bound glb(imm,mut)= iso

 iso    imm mut    read
 read is greatest, read is upper, read is supertype
 iso is least iso is lower, iso is subtype

 */
public class MdfLubGlb {
  private static final EnumSet<Mdf> allMdf = EnumSet.copyOf(
    EnumSet.allOf(Mdf.class).stream()
      .filter(Mdf::isSyntaxMdf)
      .collect(Collectors.toSet()));
  private static final Map<Set<Mdf>, Mdf> lubMap = new HashMap<>();
  private static final Map<Set<Mdf>, Mdf> glbMap = new HashMap<>();
  public static final Set<Set<Mdf>> domain(){
    return Collections.unmodifiableSet(lubMap.keySet());
  }
  public static Mdf lub(Collection<Mdf> options) {
    var o= EnumSet.copyOf(options);
    return lubMap.get(o);
  }
  public static Mdf glb(Collection<Mdf> options) {
    var o= EnumSet.copyOf(options);
    return glbMap.get(o); 
  }
  /// `ub` is above every option, so every option is a subtype of it.
  static boolean isUb(EnumSet<Mdf> options,Mdf ub){
    return options.stream().allMatch(x -> isSubType(x,ub));
  }
  /// `lb` is below every option, so it is a subtype of every option.
  static boolean isLb(EnumSet<Mdf> options,Mdf lb){
    return options.stream().allMatch(x -> isSubType(lb,x));
  }
  /// The least upper bound is an upper bound that is below every other upper bound.
  static boolean isLub(EnumSet<Mdf> options,Mdf lub){
    var isUb= isUb(options,lub);
    var isLowest= allMdf.stream()
      .filter(mdf -> isUb(options,mdf))
      .allMatch(ub -> isSubType(lub, ub));
    return isUb && isLowest;
  }
  /// The greatest lower bound is a lower bound that is above every other lower bound.
  static boolean isGlb(EnumSet<Mdf> options,Mdf glb){
    var isLb= isLb(options,glb);
    var isGreatest= allMdf.stream()
      .filter(mdf -> isLb(options,mdf))
      .allMatch(lb -> isSubType(lb, glb));
    return isLb && isGreatest;
  }
  static void init(EnumSet<Mdf> options,Mdf glb,Mdf lub){
    var novel1= lubMap.put(options,lub);
    var novel2= glbMap.put(options, glb);
    assert novel1==null && novel2==null;//both new entries
    assert isLub(options,lub)
      :"not lub: "+lub+" "+options;
    assert isGlb(options,glb)
      :"not glb: "+glb+" "+options;
    var otherLub= allMdf.stream()
      .filter(mdf->mdf!=lub).filter(mdf->isLub(options,mdf)).toList();
    assert otherLub.isEmpty()
      :"not unique Lub: "+otherLub;    
    var otherGlb= allMdf.stream()
      .filter(mdf->mdf!=glb).filter(mdf->isGlb(options,mdf)).toList();
    assert otherGlb.isEmpty()
      :"not unique glb: "+otherGlb;
  }

  // RCs | GLB | LUB
  static {
    init(of(iso),      iso,     iso);
    init(of(imm),      imm,     imm);
    init(of(mut),      mut,     mut);
    init(of(mutH), mutH, mutH);
    init(of(read),     read,    read);
    init(of(readH), readH, readH);

    init(of(iso,imm),        iso, imm);
    init(of(iso,mut),        iso, mut);
    init(of(iso, mutH),       iso, mutH);
    init(of(iso, read),      iso, read);
    init(of(iso, readH),  iso, readH);
    init(of(imm, mut),       iso, read);//is it read?//test confirms
    init(of(imm, mutH),      iso, readH); //accidentally wrote read
    init(of(imm, read),      imm, read);
    init(of(imm, readH),  imm, readH);
    init(of(mut, mutH),      mut, mutH);
    init(of(mut, read),      mut, read);
    init(of(mut, readH),  mut, readH);
    init(of(mutH, read),     mut, readH);
    init(of(mutH, readH), mutH, readH);//accidentally wrote mut
    init(of(read, readH), read, readH);

    init(of(iso, imm, mut),        iso,  read);
    init(of(iso, imm, mutH),       iso, readH);
    init(of(iso, imm, read),       iso,  read);
    init(of(iso, imm, readH),   iso, readH);
    init(of(iso, mut, mutH),       iso, mutH);
    init(of(iso, mut, read),       iso,  read);
    init(of(iso, mut, readH),   iso, readH);
    init(of(iso, mutH, read),      iso, readH);//accidentally wrote read
    init(of(iso, mutH, readH),  iso, readH);
    init(of(iso, read, readH),  iso, readH);
    init(of(imm, mut, mutH),       iso, readH);//accidentally wrote read
    init(of(imm, mut, read),       iso,  read);
    init(of(imm, mut, readH),   iso, readH);
    init(of(imm, mutH, read),      iso, readH);
    init(of(imm, mutH, readH),  iso, readH);
    init(of(imm, read, readH),  imm, readH);
    init(of(mut, mutH, read),      mut, readH);//accidentally wrote read
    init(of(mut, mutH, readH),  mut, readH);
    init(of(mut, read, readH),  mut, readH);
    init(of(mutH, read, readH), mut, readH);//accidentally wrote mutH instead of mut

    init(of(iso, imm, mut, mutH),       iso, readH);
    init(of(iso, imm, mut, read),       iso, read);
    init(of(iso, imm, mut, readH),   iso, readH);
    init(of(iso, imm, mutH, read),      iso, readH);
    init(of(iso, imm, mutH, readH),  iso, readH);
    init(of(iso, imm, read, readH),  iso, readH);
    init(of(iso, mut, mutH, read),      iso, readH);
    init(of(iso, mut, mutH, readH),  iso, readH);
    init(of(iso, mut, read, readH),  iso, readH);
    init(of(iso, mutH, read, readH), iso, readH);
    init(of(imm, mut, mutH, read),      iso, readH);
    init(of(imm, mut, mutH, readH),  iso, readH);
    init(of(imm, mut, read, readH),  iso, readH);
    init(of(imm, mutH, read, readH), iso, readH);
    init(of(mut, mutH, read, readH), mut, readH);

    init(of(iso, imm, mut, mutH, read),      iso, readH);
    init(of(iso, imm, mut, mutH, readH),  iso, readH);
    init(of(iso, imm, mut, read, readH),  iso, readH);
    init(of(iso, imm, mutH, read, readH), iso, readH);
    init(of(iso, mut, mutH, read, readH), iso, readH);
    init(of(imm, mut, mutH, read, readH), iso, readH);

    init(of(iso, imm, mut, mutH, read, readH), iso, readH);
    
    assert lubMap.size()==63;
    assert glbMap.size()==63;
  }
  public static void main(String[] arg) {
    System.out.println("hello world");
    //Java hates us,
    //if the main class has static initialization it dies silently?
    assert false;
  }
  public static class MMain{
    public static void main(String[] arg) {
      System.out.println("hello world");
      System.out.println(MdfLubGlb.lub(EnumSet.of(mut)));
      //6 wrong in the table + 1 doubt resolved
    }    
  }
}