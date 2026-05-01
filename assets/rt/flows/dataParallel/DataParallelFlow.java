package rt.flows.dataParallel;

import base.*;
import base.flows.*;
import rt.flows.dataParallel.dynamicSplit.DynamicSplitFlow;
import rt.flows.dataParallel.eod.EODWorker;
import rt.flows.dataParallel.heartbeat.HeartbeatFlowWorker;
import rt.flows.pipelineParallel.ConvertFromDataParallel;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DataParallelFlow implements Flow_1 {
  private final FlowOp_1 source_m$;
  private final Opt_1 size_m$;
  private final DataParallelFlowK $this;
  private final long size;

  public DataParallelFlow(FlowOp_1 source_m$, Opt_1 size_m$, DataParallelFlowK $this) {
    this.source_m$ = source_m$;
    this.size_m$ = size_m$;
    this.$this = $this;
    this.size = (Long) size_m$.match$mut(new OptMatch_2() {
      @Override public Object some$mut(Object x_m$) {
        return x_m$;
      }
      @Override public Object empty$mut() {
        return -1L;
      }
    });
  }

  public Flow_1 self$mut() {
    return Flow_1.self$mut$fun(this);
  }

  public Flow_1 self$read() {
    return Flow_1.self$read$fun(this);
  }

  public Flow_1 self$imm() {
    return Flow_1.self$imm$fun(this);
  }

  public Object let$mut(F_2 x_m$, Continuation_3 cont_m$) {
    return Flow_1.let$mut$fun(x_m$, cont_m$, this);
  }

  public Flow_1 actorMut$mut(Object state_m$, ActorImplMut_3 f_m$) {
    return ConvertFromDataParallel.of(this, size_m$).actorMut$mut(state_m$, f_m$);
  }

  public Flow_1 actor$mut(Object state_m$, ActorImpl_3 f_m$) {
    return ConvertFromDataParallel.of(this, size_m$).actor$mut(state_m$, f_m$);
  }

  public Flow_1 limit$mut(long n_m$) {
    return ConvertFromDataParallel.of(this, size_m$).limit$mut(n_m$);
  }

  public Flow_1 with$mut(Flow_1 other_m$) {
    return $this.fromOp$imm(_With_0.$self.$hash$imm(s->s, source_m$, other_m$.unwrapOp$mut(null)), Opt_1.$self);
  }

  public Opt_1 first$mut() {
    return _SeqFlow_0.$self.fromOp$imm(new DataParallelSource(), size_m$).first$mut();
  }
  public Opt_1 last$mut() {
    return _TerminalOps_1.last$mut$fun(this);
  }

  public List_1 list$mut() {
    return _TerminalOps_1.list$mut$fun(this);
  }

  public Bool_0 all$mut(F_2 predicate_m$) {
    var res = this
      .filter$mut(x -> ((base.Bool_0) predicate_m$.$hash$read(x)).not$imm())
      .first$mut();
    return res.isEmpty$read();
//    return _TerminalOps_1.all$mut$fun(predicate_m$, this);
  }
  public Bool_0 none$mut(F_2 predicate_m$) {
    return _TerminalOps_1.none$mut$fun(predicate_m$, this);
  }

  public Flow_1 filter$mut(F_2 p_m$) {
    return $this.fromOp$imm(_Filter_0.$self.$hash$imm(s->s, source_m$, p_m$), Opt_1.$self);
  }

  public Flow_1 flatMap$mut(F_2 f_m$) {
    return $this.fromOp$imm(_FlatMap_0.$self.$hash$imm(s->s, source_m$, f_m$), Opt_1.$self);
  }

  public Opt_1 findMap$mut(F_2 f_m$) {
//    return _SeqFlow_0.$self.fromOp$imm(source_m$, size_m$).findMap$mut(f_m$);
    return this
      .map$mut(f_m$)
      .filter$mut(res -> ((Opt_1) res).isSome$read())
      .first$mut()
      .flatMap$mut(new OptFlatMap_2() {
        @Override public Opt_1 some$mut(Object x_m$) {return OptFlatMap_2.some$mut$fun(x_m$, this);}
        @Override public Opt_1 empty$mut() {return OptFlatMap_2.empty$mut$fun(this);}
        @Override public Opt_1 $hash$mut(Object t_m$) {
          return (Opt_1) t_m$;
        }
      });
//    return _SeqFlow_0.$self.fromOp$imm(new DataParallelSource(), size_m$).findMap$mut(f_m$);
  }
  public Opt_1 unorderedFindMap$mut(F_2 f_m$) {
    return this.find$mut(f_m$);
  }

  public Opt_1 split$mut() {
    return Opt_1.$self;
  }

  public FlowOp_1 unwrapOp$mut(_UnwrapFlowToken_0 fear55$_m$) {
    return source_m$;
  }

  public Object fold$mut(MF_1 acc_m$, F_3 f_m$) {
    return _SeqFlow_0.$self.fromOp$imm(new DataParallelSource(), size_m$).fold$mut(acc_m$, f_m$);
  }

  public Action_1 only$mut() {
    return _SeqFlow_0.$self.fromOp$imm(new DataParallelSource(), size_m$).only$mut();
  }
  public Object get$mut() {
    return _SeqFlow_0.$self.fromOp$imm(new DataParallelSource(), size_m$).get$mut();
  }
  public Opt_1 opt$mut() {
    return _SeqFlow_0.$self.fromOp$imm(new DataParallelSource(), size_m$).opt$mut();
  }

  public Flow_1 map$mut(F_2 f_m$) {
    return $this.fromOp$imm(_Map_0.$self.$hash$imm(s->s, source_m$, f_m$), this.size_m$);
  }
  public Flow_1 map$mut(ToIso_1 c, F_3 f_m$) {
    return ConvertFromDataParallel.of(this, size_m$).map$mut(c, f_m$);
  }

  public Flow_1 mapFilter$mut(F_2 f_m$) {
    return _NonTerminalOps_1.mapFilter$mut$fun(f_m$, this);
  }

  public Flow_1 assumeFinite$mut() {
    return $this.fromOp$imm(_AssumeFinite_1.$self.$hash$read(source_m$), this.size_m$);
  }

  public Flow_1 peek$mut(F_2 f_m$) {
    return _NonTerminalOps_1.peek$mut$fun(f_m$, this);
  }
  public Flow_1 peek$mut(ToIso_1 c, F_3 f_m$) {
    return ConvertFromDataParallel.of(this, size_m$).peek$mut(c, f_m$);
  }

  public Bool_0 any$mut(F_2 predicate_m$) {
    var res = this
      .filter$mut(predicate_m$)
      .first$mut();
    return res.isSome$read();
//    return _TerminalOps_1.any$mut$fun(predicate_m$, this);
  }

  public Opt_1 find$mut(F_2 predicate_m$) {
    return _TerminalOps_1.find$mut$fun(predicate_m$, this);
  }
  public Opt_1 first$mut(F_2 predicate_m$) {
    return _SeqFlow_0.$self.fromOp$imm(new DataParallelSource(), size_m$).first$mut(predicate_m$);
  }

  public Opt_1 max$mut(F_3 compare_m$) {
    return _TerminalOps_1.max$mut$fun(compare_m$, this);
  }

  public Long count$mut() {
    return this.size >= 0 ? this.size : (Long) fold$mut(()->0L, (acc, _) -> ((long) acc) + 1);
  }
  public Opt_1 size$read() {
    return this.size_m$;
  }

  public Flow_1 scan$mut(Object acc_m$, F_3 f_m$) {
    return _NonTerminalOps_1.scan$mut$fun(acc_m$, f_m$, this);
  }

  public Object $hash$imm(Extension_2 ext_m$) {
    return Extensible_1.$hash$imm$fun(ext_m$, this);
  }

  public Object $hash$read(Extension_2 ext_m$) {
    return Extensible_1.$hash$read$fun(ext_m$, this);
  }

  public Object $hash$mut(Extension_2 ext_m$) {
    return Extensible_1.$hash$mut$fun(ext_m$, this);
  }

  public Object join$mut(base.flows.Joinable_1 f) {
    return Flow_1.join$mut$fun(f, this);
  }

  public Void_0 for$mut(F_2 f_m$) {
    return _TerminalOps_1.for$mut$fun(f_m$, this);
  }
  public Void_0 forEffect$mut(MF_2 f_m$) {
    return _TerminalOps_1.forEffect$mut$fun(f_m$, this);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    var that = (DataParallelFlow) obj;
    return Objects.equals(this.source_m$, that.source_m$) &&
      Objects.equals(this.size_m$, that.size_m$) &&
      Objects.equals(this.$this, that.$this);
  }

  @Override
  public int hashCode() {
    return Objects.hash(source_m$, size_m$, $this);
  }

  @Override
  public String toString() {
    return "DataParallelFlow[" +
      "source_m$=" + source_m$ + ", " +
      "size_m$=" + size_m$ + ", " +
      "$this=" + $this + ']';
  }

  public DataParallelSource getDataParallelSource() {
    return new DataParallelSource();
  }

  public class DataParallelSource implements FlowOp_1 {
    private final AtomicBoolean isDPRunning = new AtomicBoolean(true);
    @Override public Bool_0 isFinite$mut() {
      return source_m$.isFinite$mut();
    }

    @Override public Void_0 step$mut(_Sink_1 sink_m$) {
      return source_m$.step$mut(sink_m$);
    }

    @Override public Void_0 stopUp$mut() {
      isDPRunning.set(false);
      return source_m$.stopUp$mut();
    }

    @Override public Bool_0 isRunning$mut() {
      return source_m$.isRunning$mut();
    }

    @Override public Void_0 for$mut(_Sink_1 downstream_m$) {
      EODWorker.for_(source_m$, downstream_m$, (int) size, isDPRunning);
//      DynamicSplitFlow.for_(source_m$, downstream_m$, isDPRunning);
//      HeartbeatFlowWorker.for_(source_m$, downstream_m$, (int) size);
//      ForkJoinWorker.for_(source_m$, downstream_m$, isDPRunning);
//      UnrestrictedWorker.for_(source_m$, downstream_m$, (int)size, isDPRunning);
//      nestLevel.incrementAndGet();
//      if (stats == null) {
//        stats = size >= 0 ? new Stats(size) : new Stats();
//      }
//      new ForRemaining(source_m$, downstream_m$).forRemaining();
//      if (nestLevel.decrementAndGet() == 0) {
////        stats = null;
//      }
      return Void_0.$self;
    }

    @Override public Opt_1 split$mut() {
      return source_m$.split$mut();
    }

    @Override public Bool_0 canSplit$read() {
      return source_m$.canSplit$read();
    }
  }
}
