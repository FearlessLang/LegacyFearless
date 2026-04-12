package rt;

import base.*;
import base.flows.FlowOp_1;
import base.flows.Flow_1;
import base.iter.Iter_1;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public interface ListK extends base.List_0 {
  ListK $self = new ListK(){};

  @Override default List_1 $hash$imm(Object e1_m$) {
      var res = new ArrayList<>(1);
      res.add(e1_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$) {
      var res = new ArrayList<>(2);
      res.add(e1_m$);
      res.add(e2_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$) {
      var res = new ArrayList<>(3);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$) {
      var res = new ArrayList<>(4);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$) {
      var res = new ArrayList<>(5);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$) {
      var res = new ArrayList<>(6);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$) {
      var res = new ArrayList<>(7);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$) {
      var res = new ArrayList<>(8);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      res.add(e8_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$) {
      var res = new ArrayList<>(9);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      res.add(e8_m$);
      res.add(e9_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$) {
      var res = new ArrayList<>(10);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      res.add(e8_m$);
      res.add(e9_m$);
      res.add(e10_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$) {
      var res = new ArrayList<>(11);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      res.add(e8_m$);
      res.add(e9_m$);
      res.add(e10_m$);
      res.add(e11_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$) {
      var res = new ArrayList<>(12);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      res.add(e8_m$);
      res.add(e9_m$);
      res.add(e10_m$);
      res.add(e11_m$);
      res.add(e12_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$, Object e13_m$) {
      var res = new ArrayList<>(13);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      res.add(e8_m$);
      res.add(e9_m$);
      res.add(e10_m$);
      res.add(e11_m$);
      res.add(e12_m$);
      res.add(e13_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$, Object e13_m$, Object e14_m$) {
      var res = new ArrayList<>(14);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      res.add(e8_m$);
      res.add(e9_m$);
      res.add(e10_m$);
      res.add(e11_m$);
      res.add(e12_m$);
      res.add(e13_m$);
      res.add(e14_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$, Object e13_m$, Object e14_m$, Object e15_m$) {
      var res = new ArrayList<>(15);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      res.add(e8_m$);
      res.add(e9_m$);
      res.add(e10_m$);
      res.add(e11_m$);
      res.add(e12_m$);
      res.add(e13_m$);
      res.add(e14_m$);
      res.add(e15_m$);
      return new ListImpl<>(res);
    }

    @Override default List_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$, Object e13_m$, Object e14_m$, Object e15_m$, Object e16_m$) {
      var res = new ArrayList<>(16);
      res.add(e1_m$);
      res.add(e2_m$);
      res.add(e3_m$);
      res.add(e4_m$);
      res.add(e5_m$);
      res.add(e6_m$);
      res.add(e7_m$);
      res.add(e8_m$);
      res.add(e9_m$);
      res.add(e10_m$);
      res.add(e11_m$);
      res.add(e12_m$);
      res.add(e13_m$);
      res.add(e14_m$);
      res.add(e15_m$);
      res.add(e16_m$);
      return new ListImpl<>(res);
    }

  @Override default List_1 $hash$imm() {
    return new ListImpl<>(new ArrayList<>());
  }

  @Override default List_1 fromLList$imm(LList_1 list_m$) {
    var res = new ArrayList<>(list_m$.size$read().intValue());
    list_m$.iter$mut().for$mut(e->{
      res.add(e);
      return Void_0.$self;
    });
    return new ListImpl<>(res);
  }

  @Override default List_1 consumeUList$imm(base.UList_1 list_m$) {
    // The iso guarantee means nothing else aliases list_m$, so we can reuse its storage.
    @SuppressWarnings("unchecked")
    var impl = (UListK.UListImpl<Object>) list_m$;
    return new ListImpl<>(impl.inner());
  }

  record ListImpl<E>(java.util.List<E> inner) implements base.List_1 {
    @Override public FlowOp_1 _flowimm$imm(long start_m$, long end_m$) {
      return List_1._flowimm$imm$fun(start_m$, end_m$, this);
    }

    @Override public FlowOp_1 _flowmut$mut(long start_m$, long end_m$) {
      return List_1._flowmut$mut$fun(start_m$, end_m$, this);
    }

    @Override public Object get$imm(long i_m$) {
      return inner.get((int) i_m$);
    }

    @Override public Object get$read(long i_m$) {
      return inner.get((int) i_m$);
    }

    @Override public Object get$mut(long i_m$) {
      return inner.get((int) i_m$);
    }

    @Override public Opt_1 tryGet$imm(long i_m$) {
      if (i_m$ >= inner.size()) { return Opt_1.$self; }
      return Opts_0.$self.$hash$imm(inner.get((int) i_m$));
    }

    @Override public Opt_1 tryGet$read(long i_m$) {
      if (i_m$ >= inner.size()) { return Opt_1.$self; }
      return Opts_0.$self.$hash$imm(inner.get((int) i_m$));
    }

    @Override public Opt_1 tryGet$mut(long i_m$) {
      if (i_m$ >= inner.size()) { return Opt_1.$self; }
      return Opts_0.$self.$hash$imm(inner.get((int) i_m$));
    }

    @Override public Iter_1 iter$imm() {
      return List_1.iter$imm$fun(this);
    }

    @Override public Iter_1 iter$read() {
      return List_1.iter$read$fun(this);
    }

    @Override public Iter_1 iter$mut() {
      return List_1.iter$mut$fun(this);
    }

    @Override public Flow_1 flow$imm() {
      return List_1.flow$imm$fun(this);
    }

    @Override public Flow_1 flow$read() {
      return List_1.flow$read$fun(this);
    }

    @Override public Flow_1 flow$mut() {
      return List_1.flow$mut$fun(this);
    }

    @Override public Bool_0 isEmpty$read() {
      return inner.isEmpty() ? True_0.$self : False_0.$self;
    }

    @Override public base.ListView_1 subList$read(long from, long to) {
      return List_1.subList$read$fun(from, to, this);
    }
    @Override public base.List_1 as$read(base.MF_2 f) {
      return List_1.as$read$fun(f, this);
    }

    @Override public Long size$read() {
      return (long) inner.size();
    }

    @Override public FlowOp_1 _flowread$read(long start_m$, long end_m$) {
      return List_1._flowread$read$fun(start_m$, end_m$, this);
    }

    @Override public base.UList_1 uList$imm() {
      return new UListK.UListImpl<>(new ArrayList<>(this.inner));
    }
    @Override public base.UList_1 uList$read() {
      return new UListK.UListImpl<>(new ArrayList<>(this.inner));
    }
    @Override public base.UList_1 uList$mut() {
      return new UListK.UListImpl<>(new ArrayList<>(this.inner));
    }
  }

  record ByteBufferListImpl(ByteBuffer inner) implements base.List_1 {
    @Override public FlowOp_1 _flowimm$imm(long start_m$, long end_m$) {
      return List_1._flowimm$imm$fun(start_m$, end_m$, this);
    }

    @Override public FlowOp_1 _flowmut$mut(long start_m$, long end_m$) {
      throw new RuntimeException("Unreachable code");
    }

    @Override public Object get$imm(long i_m$) {
      return inner.get((int) i_m$);
    }

    @Override public Object get$read(long i_m$) {
      return inner.get((int) i_m$);
    }

    @Override public Object get$mut(long i_m$) {
      throw new RuntimeException("Unreachable code");
    }

    @Override public Opt_1 tryGet$imm(long i_m$) {
      if (i_m$ >= inner.capacity()) { return Opt_1.$self; }
      return Opts_0.$self.$hash$imm(inner.get((int) i_m$));
    }

    @Override public Opt_1 tryGet$read(long i_m$) {
      if (i_m$ >= inner.capacity()) { return Opt_1.$self; }
      return Opts_0.$self.$hash$imm(inner.get((int) i_m$));
    }

    @Override public Opt_1 tryGet$mut(long i_m$) {
      throw new RuntimeException("Unreachable code");
    }

    @Override public Iter_1 iter$imm() {
      return List_1.iter$imm$fun(this);
    }

    @Override public Iter_1 iter$read() {
      return List_1.iter$read$fun(this);
    }

    @Override public Iter_1 iter$mut() {
      throw new RuntimeException("Unreachable code");
    }

    @Override public Flow_1 flow$imm() {
      return List_1.flow$imm$fun(this);
    }

    @Override public Flow_1 flow$read() {
      return List_1.flow$read$fun(this);
    }

    @Override public Flow_1 flow$mut() {
      throw new RuntimeException("Unreachable code");
    }

    @Override public Bool_0 isEmpty$read() {
      return inner.capacity() == 0 ? True_0.$self : False_0.$self;
    }

    @Override public base.ListView_1 subList$read(long from, long to) {
      return List_1.subList$read$fun(from, to, this);
    }

    @Override public base.List_1 as$read(base.MF_2 f) {
      return List_1.as$read$fun(f, this);
    }

    @Override public Long size$read() {
      return (long) inner.capacity();
    }

    @Override public FlowOp_1 _flowread$read(long start_m$, long end_m$) {
      return List_1._flowread$read$fun(start_m$, end_m$, this);
    }

    @Override public base.UList_1 uList$imm() {
      var list = new ArrayList<Byte>(inner.capacity());
      for (int i = 0; i < inner.capacity(); i++) { list.add(inner.get(i)); }
      return new UListK.UListImpl<>(list);
    }
    @Override public base.UList_1 uList$read() {
      var list = new ArrayList<Byte>(inner.capacity());
      for (int i = 0; i < inner.capacity(); i++) { list.add(inner.get(i)); }
      return new UListK.UListImpl<>(list);
    }
    @Override public base.UList_1 uList$mut() {
      throw new RuntimeException("Unreachable code");
    }
  }
}
