package utils;

import program.TypeSystemFeatures;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

public interface Base {
  static ast.Program ignoreBase(ast.Program p) {
    return new ast.Program(
      new TypeSystemFeatures(),
      p.ds().entrySet().stream()
        .filter(kv->!kv.getKey().name().startsWith("base."))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
      Map.of()
    );
  }

  static String load(String file) {
    return ResolveResource.getAndReadAsset("/base/"+file);
  }
  static String read(Path path) {
    return IoErr.of(()->Files.readString(path, StandardCharsets.UTF_8));
  }

  static String[] readAll(String prefix) {
    return IoErr.of(()->{
      var root = ResolveResource.asset(prefix);
      try(var fs = Files.walk(root)) {
        return fs
          .filter(Files::isRegularFile)
          .map(Base::read)
          .toArray(String[]::new);
      }
    });
  }
  String[] baseLib = readAll("/base");
  String[] immBaseLib = readAll("/immBase");

  String minimalBase = """
    package base
    Main:{ #(s: mut System): Void }
    Sealed:{}
    Void:{}
    
    System:Sealed{}
    """;

  String mutBaseAliases = """
    package test
    alias base.iter.Iter as Iter,
    
    alias base.IsoPod as IsoPod,
    
    alias base.caps.Write as Write,
    alias base.caps.Read as Read,
    alias base.caps.FileHandleMode as FileHandleMode,
    alias base.caps.Create as Create,
    
    alias base.caps.UnrestrictedIO as UnrestrictedIO,
    alias base.caps.IO as IO,
    alias base.caps.Env as Env,
    alias base.caps.FEnv as FEnv,
    alias base.caps.FRandomSeed as FRandomSeed,
    alias base.caps.RandomSeed as RandomSeed,
    alias base.caps.Clock as Clock,
    alias base.caps.CapTrys as CapTrys,
    alias base.caps.CapTry as CapTry,
    
    alias base.caps.System as System,
    
    alias base.Res as Res,
    alias base.Action as Action,
    
    alias base.LinkedLens as LinkedLens,
    alias base.LinkedHashMap as LinkedHashMap,
    alias base.Map as Map,
    alias base.Maps as Maps,
    alias base.EmptyMap as EmptyMap,
    alias base.Lens as Lens,
    
    alias base.Extensible as Extensible,
    alias base.Extension as Extension,
    
    alias base.Either as Either,
    
    alias base.Block as Block,
    alias base.ControlFlow as ControlFlow,
    
    alias base.Str as Str,
    alias base.StrMap as StrMap,
    alias base.Stringable as Stringable,
    
    alias base.Var as Var,
    alias base.Vars as Vars,
    alias base.Count as Count,
    
    alias base.Opts as Opts,
    alias base.Opt as Opt,
    alias base.OptMap as OptMap,
    
    alias base.Slots as Slots,
    alias base.Slot as Slot,
    
    alias base.Int as Int,
    alias base.Float as Float,
    alias base.Nat as Nat,
    
    alias base.LList as LList,
    alias base.List as List,
    alias base.UList as UList,
    alias base.ListViews as ListViews,
    alias base.Collection as Collection,
    
    alias base.As as As,
    alias base.Box as Box,
    alias base.Sealed as Sealed,
    alias base.Magic as Magic,
    alias base.F as F,
    alias base.MF as MF,
    alias base.Consumer as Consumer,
    alias base.Let as Let,
    alias base.Void as Void,
    alias base.HasIdentity as HasIdentity,
    
    alias base.Ice as Ice,
    alias base.Freezer as Freezer,
    alias base.ToImm as ToImm,
    
    alias base.Abort as Abort,
    alias base.Main as Main,
    
    alias base.Error as Error,
    alias base.Try as Try,
    alias base.Info as Info,
    alias base.Infos as Infos,
    
    alias base.Bool as Bool,
    alias base.True as True,
    alias base.False as False,
    
    alias base.Assert as Assert,
    
    alias base.flows.Flow as Flow,
    alias base.flows.ActorRes as ActorRes,
    
    alias base.Ordering as Ordering,
    alias base.FOrdering as FOrdering,
    alias base.Debug as Debug,
    
    alias base.Actions as Actions,
    alias base.ToHash as ToHash,
    """;
}
