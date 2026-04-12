package rt;

import base.*;
import base.flows.FlowOp_1;
import base.flows.Flow_1;
import base.iter.Iter_1;

import java.util.ArrayList;

public interface UListK extends base.UList_0 {
  UListK $self = new UListK(){};

  static base.UList_1 asShallowClone(base.UList_1 list, base.MF_2 f) {
    return switch (list) {
      case UListImpl<?> impl -> new UListImpl<>(new ArrayList<>(impl.inner));
      default -> list.as$read(f);
    };
  }

  @Override default UList_1 $hash$imm(Object e1_m$) {
    var res = new ArrayList<>(1);
    res.add(e1_m$);
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$) {
    var res = new ArrayList<>(2);
    res.add(e1_m$);
    res.add(e2_m$);
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$) {
    var res = new ArrayList<>(3);
    res.add(e1_m$);
    res.add(e2_m$);
    res.add(e3_m$);
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$) {
    var res = new ArrayList<>(4);
    res.add(e1_m$);
    res.add(e2_m$);
    res.add(e3_m$);
    res.add(e4_m$);
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$) {
    var res = new ArrayList<>(5);
    res.add(e1_m$);
    res.add(e2_m$);
    res.add(e3_m$);
    res.add(e4_m$);
    res.add(e5_m$);
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$) {
    var res = new ArrayList<>(6);
    res.add(e1_m$);
    res.add(e2_m$);
    res.add(e3_m$);
    res.add(e4_m$);
    res.add(e5_m$);
    res.add(e6_m$);
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$) {
    var res = new ArrayList<>(7);
    res.add(e1_m$);
    res.add(e2_m$);
    res.add(e3_m$);
    res.add(e4_m$);
    res.add(e5_m$);
    res.add(e6_m$);
    res.add(e7_m$);
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$) {
    var res = new ArrayList<>(8);
    res.add(e1_m$);
    res.add(e2_m$);
    res.add(e3_m$);
    res.add(e4_m$);
    res.add(e5_m$);
    res.add(e6_m$);
    res.add(e7_m$);
    res.add(e8_m$);
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$) {
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
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$) {
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
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$) {
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
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$) {
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
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$, Object e13_m$) {
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
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$, Object e13_m$, Object e14_m$) {
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
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$, Object e13_m$, Object e14_m$, Object e15_m$) {
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
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm(Object e1_m$, Object e2_m$, Object e3_m$, Object e4_m$, Object e5_m$, Object e6_m$, Object e7_m$, Object e8_m$, Object e9_m$, Object e10_m$, Object e11_m$, Object e12_m$, Object e13_m$, Object e14_m$, Object e15_m$, Object e16_m$) {
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
    return new UListImpl<>(res);
  }

  @Override default UList_1 $hash$imm() {
    return new UListImpl<>(new ArrayList<>());
  }

  @Override default UList_1 fromLList$imm(LList_1 list_m$) {
    var res = new ArrayList<>(list_m$.size$read().intValue());
    list_m$.iter$mut().for$mut(e->{
      res.add(e);
      return Void_0.$self;
    });
    return new UListImpl<>(res);
  }

  @Override default UList_1 withCapacity$imm(long n) {
    if (n > Integer.MAX_VALUE) {
      rt.Error.throwFearlessError(base.Infos_0.$self.msg$imm(
        rt.Str.fromJavaStr("Lists may not have a capacity greater than "+Integer.MAX_VALUE)
      ));
    }
    return new UListImpl<>(new ArrayList<>((int) n));
  }

  record UListImpl<E>(java.util.List<E> inner) implements base.UList_1 {
    @Override public FlowOp_1 _flowimm$imm(long start_m$, long end_m$) {
      return UList_1._flowimm$imm$fun(start_m$, end_m$, this);
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

    @Override public Void_0 addAll$mut(UList_1 other_m$) {
      @SuppressWarnings("unchecked") // validated by the Fearless type system
      var other = (UListImpl<E>) other_m$;
      inner.addAll(other.inner);
      return Void_0.$self;
    }

    @Override public base.Opt_1 takeFirst$mut() {
      if (inner.isEmpty()) {
        return base.Opt_1.$self;
      }
      return base.Opts_0.$self.$hash$imm(inner.removeFirst());
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
      return UList_1.iter$imm$fun(this);
    }

    @Override public Iter_1 iter$read() {
      return UList_1.iter$read$fun(this);
    }

    @Override public Iter_1 iter$mut() {
      return UList_1.iter$mut$fun(this);
    }

    @Override public Flow_1 flow$imm() {
      return UList_1.flow$imm$fun(this);
    }

    @Override public Flow_1 flow$read() {
      return UList_1.flow$read$fun(this);
    }

    @Override public Flow_1 flow$mut() {
      return UList_1.flow$mut$fun(this);
    }

    @Override public Bool_0 isEmpty$read() {
      return inner.isEmpty() ? True_0.$self : False_0.$self;
    }

    @Override public Void_0 clear$mut() {
      inner.clear();
      return Void_0.$self;
    }

    @Override public base.ListView_1 subList$read(long from, long to) {
      // Share the underlying ArrayList via a read-only ListImpl wrapper.
      var wrapped = new ListK.ListImpl<>(this.inner);
      return base.List_1.subList$read$fun(from, to, wrapped);
    }

    @Override public base.UList_1 as$read(base.MF_2 f) {
      return UList_1.as$read$fun(f, this);
    }

    @Override public Long size$read() {
      return (long) inner.size();
    }

    @Override public FlowOp_1 _flowread$read(long start_m$, long end_m$) {
      return UList_1._flowread$read$fun(start_m$, end_m$, this);
    }

    @Override public Void_0 add$mut(Object e_m$) {
      @SuppressWarnings("unchecked") // validated by the Fearless type system
      E e = (E) e_m$;
      inner.add(e);
      return Void_0.$self;
    }

    @Override public UListImpl<E> $plus$mut(Object e_m$) {
      @SuppressWarnings("unchecked") // validated by the Fearless type system
      E e = (E) e_m$;
      inner.add(e);
      return this;
    }

    @Override public base.List_1 list$imm() {
      // Free retag: share the underlying ArrayList.
      return new ListK.ListImpl<>(this.inner);
    }
    @Override public base.List_1 list$read() {
      return new ListK.ListImpl<>(new ArrayList<>(this.inner));
    }
    @Override public base.List_1 list$mut() {
      return new ListK.ListImpl<>(new ArrayList<>(this.inner));
    }
  }
}
