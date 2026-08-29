package magic;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import astFull.T;
import failure.CompileError;
import failure.FailOr;
import files.Pos;
import id.Id;

public enum LiteralKind {
  UStr("base.uStrLit",Magic.Str,"base._StrInstance"),
  SStr("base.sStrLit",Magic.Str,"base._StrInstance"),//TODO: add MagicSStr and instance
  Int("base.intLit",Magic.Int,"base._IntInstance"),
  Nat("base.natLit",Magic.Nat,"base._NatInstance"),
  Float("base.floatLit",Magic.Float,"base._FloatInstance");
  String pkgName;
  Id.DecId magicKind;
  String instance;
  LiteralKind(String pkgName, Id.DecId magicKind, String instance){
    this.pkgName= pkgName; this.magicKind= magicKind; this.instance= instance;}
  public static boolean isLiteral(String name){
    return match(name).isPresent();
  }
  public static Optional<LiteralKind> match(String name){
    for(var v:LiteralKind.values()){
      if(name.startsWith(v.pkgName+".")){ return Optional.of(v); }
    }
    return Optional.empty();
  }
  public Id.DecId toDecId(){ return new Id.DecId(instance, 0); }
  public Id.DecId magicKind(){ return magicKind; }
  public Optional<Supplier<CompileError>> validate(String fullName){
    String simpleName=fullName.substring(pkgName.length()+1);
    return switch(this) {
      case UStr -> new FearlessStringHandler(FearlessStringHandler.StringKind.Unicode)
        .toJavaString(simpleName)
        .asOpt();
      default -> FailOr.ok().asOpt();
    //  case Nat://TODO: add kind specific validation (too big number for example)
    //Note: Nick will fill up here
    };
  }
  public static Optional<String> toFullName(String name){
    return classify(name).map(k->k.pkgName+"."+name);
  }
  public static Optional<Id.IT<T>> toFullIt(String name,List<T> ts){
    return toFullName(name).map(s->new Id.IT<>(s,ts));
  }    
  public static Optional<LiteralKind> classify(String name){
    if (name.matches("[+-][\\d_]*\\d+$")){ return Optional.of(Int); }
    if (name.matches("[+-]?[\\d_]*\\d+\\.[\\d_]*\\d+$")){ return Optional.of(Float); }
    if (name.matches("[\\d_]*\\d+$")){ return Optional.of(Nat); }
    if (Magic.strValidation(name,'`')){ return Optional.of(SStr); }
    if (Magic.strValidation(name,'"')){ return Optional.of(UStr); }
    return Optional.empty();
  }
  public static Optional<Id.IT<astFull.T>> nameToType(String name){
    return match(name)
      .map(k->k.magicKind)
      .map(mk->new Id.IT<astFull.T>(mk, List.of()));
  }
}