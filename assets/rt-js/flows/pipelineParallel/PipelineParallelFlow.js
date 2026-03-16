// rt/flows/pipelineParallel/PipelineParallelFlow.js
import { FearlessError } from "../../FearlessError.js";
import { base$$Void_0 } from "../../../base/Void_0.js";

/* -------------------- Messages -------------------- */
export const Message = {
  Error: class {
    constructor(info) { this.info = info; }
  },
  Stop: class Stop {
    static INSTANCE = new Stop();
  }
};

/* ---------------- Deterministic Error ---------------- */
export class DeterministicFearlessError extends FearlessError {
  constructor(info) { super(info); }
}

/* ---------------- WrappedSink ---------------- */
export class WrappedSink {
  constructor(original) {
    this.original = original;
    this.subject = new Subject(original);
  }

  stopDown$mut$0() {
    // console.log("[WS.stopDown] submit STOP → join");
    try {
      // Enqueue Stop synchronously and wait for this subject to drain.
      this.subject.submit(Message.Stop.INSTANCE);
      // console.log("[Subject.join] waiting");
      this.subject.join();
    } catch (err) {
      if (err instanceof DeterministicFearlessError) throw err;
      if (err instanceof FearlessError) throw err;
      const msg =
        err instanceof Error && /stack overflow/i.test(err.message)
          ? "Stack overflowed"
          : err.message;
      throw new Error(msg, { cause: err });
    }
    return base$$Void_0.$self;
  }

  $hash$mut$1(x$) {
    this.subject.submit(x$);
    return base$$Void_0.$self;
  }

  pushError$mut$1(info$) {
    this.subject.submit(new Message.Error(info$));
    return base$$Void_0.$self;
  }

  softClose() { this.subject.softClosed = true; }
}

/* ------------- Factory singleton ------------- */
export class WrappedSinkK {
  static $self = new WrappedSinkK();
  $hash$imm$1(original) { return new WrappedSink(original); }
}

/* ---------------- Subject ---------------- */
class BlockingQueue {
  constructor(capacity = 512) {
    this.capacity = capacity;
    this.buf = [];
    this.waitTakers = [];
    this.waitSpace = [];
  }

  offer(item) {
    // console.log("[BQ.offer]", item);
    if (this.buf.length < this.capacity) {
      if (this.waitTakers.length > 0) {
        // console.log("[BQ.offer] fulfilling taker");
        const take = this.waitTakers.shift();
        take(item);
      } else {
        this.buf.push(item);
      }
      return true;
    }
    return false;
  }

  async take() {
    if (this.buf.length > 0) return this.buf.shift();
    return new Promise(r => this.waitTakers.push(r));
  }

  poll() { return this.buf.length > 0 ? this.buf.shift() : null; }

  signalSpace() {
    if (this.waitSpace.length > 0) {
      const w = this.waitSpace.shift();
      w();
    }
  }
}

export class Subject {

  constructor(downstream) {
    this.downstream = downstream;
    this.buffer = new BlockingQueue(512);
    this.exception = null;
    this.softClosed = false;
    this.limitStopped = false; // ← NEW flag
    this._workerPromise = this._runLoop();
  }

  submit(msg) {
    if (this.softClosed && msg !== Message.Stop.INSTANCE) return;
    const ok = this.buffer.offer(msg);
    if (!ok) throw new Error("Subject buffer full");
  }

  async _runLoop() {
    try {
      while (true) {
        let msg = this.buffer.poll();
        if (msg == null) msg = await this.buffer.take();

        if (msg === Message.Stop.INSTANCE) {
          this.downstream.stopDown$mut$0(); // join flushes here
          break;
        }

        if (msg instanceof Message.Error) {
          this._processError(msg);
          continue;
        }

        this._processDataMsg(msg);
      }
    } catch (e) {
      this.exception = e;
    }
  }

  async join() {
    await this._workerPromise;
    if (this.exception) throw this.exception;
    return base$$Void_0.$self;
  }

  _processError(msg) {
    if (this.softClosed) return;
    this.softClosed = true;
    try {
      this.downstream.pushError$mut$1(msg.info);
    } catch (err) {
      if (err instanceof FearlessError)
        throw new DeterministicFearlessError(err.info);
      throw err;
    }
  }


  _processDataMsg(data) {
    console.log("[JOIN STAGE DATA]", data);
    if (this.softClosed) return;

    const d = this.downstream;
    try {
      d.$hash$mut$1(data);

      // detect limit operator and trigger downstream stop once
      if (!this.limitStopped && d.runner_m$ && d.remaining_m$) {
        const r = Number(d.remaining_m$?.get$mut$0?.() ?? 0);
        if (r <= 1) {
          this.limitStopped = true;
          console.log("[Subject] limit exhausted → stopping downstream ONCE");
          // Proactively tell the *downstream of the limit* to stop,
          // but keep THIS Subject running so it can still receive Stop.
          d.downstream_m$?.stopDown$mut$0?.();
          // DO NOT set softClosed here
        }
      }
    } catch (err) {
      this.softClosed = true;
      // existing error handling ...
    }
  }
}

/* ---------------- Export container ---------------- */
export const PipelineParallelFlow = {
  WrappedSinkK,
  WrappedSink,
  Message,
  Subject,
  DeterministicFearlessError
};
