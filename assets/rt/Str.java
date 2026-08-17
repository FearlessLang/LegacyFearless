package rt;

import base.*;
import base.flows.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public interface Str extends base.Str_0 {
	ByteBuffer utf8();
	int[] graphemes();

	static ByteBuffer wrap(byte[] array) {
		return ByteBuffer
			.allocateDirect(array.length)
			.put(array)
			.position(0);
	}
	static String toJavaStr(ByteBuffer utf8) {
		var dst = new byte[utf8.remaining()];
		utf8.duplicate().get(dst);
		return new String(dst, StandardCharsets.UTF_8);
	}

	Str EMPTY = fromTrustedUtf8(ByteBuffer.allocateDirect(0));
	static Str fromJavaStr(String str) {
		var utf8 = str.getBytes(StandardCharsets.UTF_8);
		return fromTrustedUtf8(wrap(utf8));
	}
	static Str fromUtf8(ByteBuffer utf8) {
		NativeRuntime.validateStringOrThrow(utf8);
		return fromTrustedUtf8(utf8);
	}
	static Str fromTrustedUtf8(ByteBuffer utf8) {
		return new Str(){
			private int[] GRAPHEMES = null;
			@Override public ByteBuffer utf8() { return utf8; }
			@Override public int[] graphemes() {
				if (GRAPHEMES == null) { GRAPHEMES = NativeRuntime.indexString(utf8); }
				return GRAPHEMES;
			}
		};
	}

	@Override default List_1 utf8$imm() {
		return new ListK.ByteBufferListImpl(this.utf8());
	}

	@Override default Str str$read() {
		return this;
	}
	@Override default base.Bool_0 $equals$equals$imm(Str other$) {
		return this.utf8().equals(other$.utf8()) ? True_0.$self : False_0.$self;
	}
	@Override default base.Bool_0 $exclamation$equals$imm(Str other$) {
		return this.utf8().equals(other$.utf8()) ? False_0.$self : True_0.$self;
	}
	@Override default base.Bool_0 startsWith$imm(Str other$) {
		var needle = other$.utf8();
		if (this.utf8().remaining() < needle.remaining()) { return False_0.$self; }
		var haystack = this.utf8().slice(0, needle.remaining());
		return haystack.equals(needle) ? True_0.$self : False_0.$self;
	}
	@Override default Str $plus$imm(base.Stringable_0 other$) {
		var a = this.utf8();
		var b = other$.str$read().utf8();
		var res = ByteBuffer.allocateDirect(a.remaining() + b.remaining());
		res.put(a.duplicate());
		res.put(b.duplicate());
		res.position(0);
		return fromTrustedUtf8(res);
	}
	@Override default Str $plus$mut(base.Stringable_0 other$) { throw new java.lang.Error("Unreachable code"); }
	@Override default base.Void_0 append$mut(base.Stringable_0 other$) { throw new java.lang.Error("Unreachable code"); }
	@Override default base.Void_0 clear$mut() { throw new java.lang.Error("Unreachable code"); }
	@Override default Long size$imm() {
		var utf8 = this.utf8();
		long count = 0;
		for (int i = utf8.position(), end = utf8.limit(); i < end; ++i) {
			if ((utf8.get(i) & 0xC0) != 0x80) { ++count; }
		}
		return count;
	}
	@Override default base.Void_0 assertEq$imm(Str other$) {
		return _StrHelpers_0.$self.assertEq$imm(this, other$);
	}
	@Override default base.Void_0 assertEq$imm(Str other$, Str message$) {
		return _StrHelpers_0.$self.assertEq$imm(this, other$, message$);
	}
	@Override default base.Bool_0 isEmpty$read() {
		return this.utf8().remaining() == 0 ? True_0.$self : False_0.$self;
	}
	@Override default Str join$imm(Flow_1 flow_m$) {
		return (Str) flow_m$.fold$mut(()->new MutStr(EMPTY), (_acc, _str) -> {
			var acc = (MutStr) _acc;
			var str = (Str) _str;
			return acc.isEmpty$read() == True_0.$self ? acc.$plus$mut(str) : acc.$plus$mut(this).$plus$mut(str);
		});
	}

	/** Byte offset of the start of codepoint index {@code cp}, scanning forward from the buffer position.
	 *  {@code cp} equal to the codepoint count returns the buffer's byte length. */
	default int codepointByteOffset(long cp) {
		var utf8 = this.utf8();
		int len = utf8.remaining();
		long seen = 0;
		for (int i = 0; i < len; ++i) {
			if ((utf8.get(utf8.position() + i) & 0xC0) != 0x80) {
				if (seen == cp) { return i; }
				++seen;
			}
		}
		return len;
	}

	@Override default Str substring$imm(long start_m$, long end_m$) {
		if (start_m$ > end_m$) {
			throw new FearlessError(base.Infos_0.$self.msg$imm(fromJavaStr("Start index must be less than end index")));
		}
		if (start_m$ < 0) {
			throw new FearlessError(base.Infos_0.$self.msg$imm(fromJavaStr("Start index must be greater than or equal to 0")));
		}
		if (end_m$ > this.size$imm()) {
			throw new FearlessError(base.Infos_0.$self.msg$imm(fromJavaStr("End index must be less than the size of the string")));
		}
		return new SubStr(this, (int) start_m$, (int) end_m$);
	}

	@Override default Str charAt$imm(long index_m$) {
		return substring$imm(index_m$, index_m$ + 1);
	}

	@Override default Str normalise$imm() {
		var res = NativeRuntime.normaliseString(this.utf8());
		return fromTrustedUtf8(wrap(res));
	}

	/** A flow of the codepoints of this string, each one a single codepoint {@code Str}.
	 *  The flow captures the UTF-8 buffer once, at the moment of the call, and reads
	 *  only that buffer afterwards. The size is a hint: the framework does not ask a
	 *  split half for its size. */
	@Override default Flow_1 codepoints$imm() {
		var size = size$imm();
		var utf8 = this.utf8().slice();
		return Flow_0.$self.fromOp$imm(this._codepoints$imm(utf8, 0, utf8.remaining()), size);
	}
	/** A codepoint flow over the byte window {@code [start, end_)} of {@code utf8}.
	 *  {@code utf8} must have position 0, so that all offsets are absolute, and the
	 *  window bounds must be codepoint boundaries. A split makes a narrower window
	 *  over the same buffer, so every operation is O(1) for each element. */
	default FlowOp_1 _codepoints$imm(ByteBuffer utf8, int start, int end_) {
		return new FlowOp_1() {
			int cur = start;
			int end = end_;
			/** The first codepoint boundary after byte {@code i}, or {@code end}.
			 *  A continuation byte has the top two bits {@code 10}, the same test that
			 *  {@code size$imm} uses, so this flow emits exactly {@code size$imm} elements. */
			private int boundaryAfter(int i) {
				int j = i + 1;
				while (j < this.end && (utf8.get(j) & 0xC0) == 0x80) { ++j; }
				return j;
			}
			/** Emit the codepoint at the cursor and move the cursor past it. */
			private Str next() {
				int from = this.cur;
				int to = boundaryAfter(from);
				this.cur = to;
				return fromTrustedUtf8(utf8.slice(from, to - from));
			}
			/** A codepoint boundary strictly inside the remaining window, or -1.
			 *  This is conservative: a short window with two codepoints can still give
			 *  -1, for example a 1-byte codepoint before a 4-byte one, where the byte
			 *  midpoint moves forward to {@code end}. A refused split loses only an
			 *  optimisation. {@code canSplit$read} and {@code split$mut} both use this
			 *  helper, so the two always agree. */
			private int midBoundary() {
				int mid = this.cur + (this.end - this.cur) / 2;
				while (mid < this.end && (utf8.get(mid) & 0xC0) == 0x80) { ++mid; }
				return mid <= this.cur || mid >= this.end ? -1 : mid;
			}
			@Override public Bool_0 isFinite$mut() {
				return True_0.$self;
			}
			@Override public Void_0 step$mut(_Sink_1 sink_m$) {
				if (this.cur >= this.end) {
					sink_m$.stopDown$mut();
					return Void_0.$self;
				}
				sink_m$.$hash$mut(next());
				return Void_0.$self;
			}
			/** Move the cursor to the end of the window, which makes {@code isRunning}
			 *  false. The window end is a byte offset, so this needs no scan. */
			@Override public Void_0 stopUp$mut() {
				this.cur = this.end;
				return Void_0.$self;
			}
			@Override public Bool_0 isRunning$mut() {
				return this.cur >= this.end ? False_0.$self : True_0.$self;
			}
			@Override public Void_0 for$mut(_Sink_1 downstream_m$) {
				while (this.cur < this.end) {
					downstream_m$.$hash$mut(next());
				}
				downstream_m$.stopDown$mut();
				return Void_0.$self;
			}
			@Override public Opt_1 split$mut() {
				int mid = midBoundary();
				if (mid < 0) { return Opt_1.$self; }
				int oldEnd = this.end;
				this.end = mid;
				return Opts_0.$self.$hash$imm(_codepoints$imm(utf8, mid, oldEnd));
			}
			@Override public Bool_0 canSplit$read() {
				return midBoundary() >= 0 ? True_0.$self : False_0.$self;
			}
		};
	}

	@Override default Flow_1 graphemes$imm() {
		var utf8 = this.utf8();
		var starts = this.graphemes();
		long count = starts.length;
		return Flow_0.$self.fromOp$imm(this._graphemes$imm(utf8, starts, 0, count), count);
	}
	default FlowOp_1 _graphemes$imm(ByteBuffer utf8, int[] starts, long start, long end_) {
		return new FlowOp_1() {
			long cur = start;
			long end = end_;
			private Str graphemeAt(long i) {
				int idx = (int) i;
				int from = starts[idx];
				int to = idx + 1 < starts.length ? starts[idx + 1] : utf8.remaining();
				return fromTrustedUtf8(utf8.slice(from, to - from));
			}
			@Override public Bool_0 isFinite$mut() {
				return True_0.$self;
			}
			@Override public Void_0 step$mut(_Sink_1 sink_m$) {
				if (this.cur >= this.end) {
					sink_m$.stopDown$mut();
					return Void_0.$self;
				}
				sink_m$.$hash$mut(graphemeAt(this.cur++));
				return Void_0.$self;
			}
			@Override public Void_0 stopUp$mut() {
				this.cur = starts.length;
				return Void_0.$self;
			}
			@Override public Bool_0 isRunning$mut() {
				return this.cur >= this.end ? False_0.$self : True_0.$self;
			}
			@Override public Void_0 for$mut(_Sink_1 downstream_m$) {
				for (; this.cur < end; ++this.cur) {
					downstream_m$.$hash$mut(graphemeAt(this.cur));
				}
				downstream_m$.stopDown$mut();
				return Void_0.$self;
			}
			@Override public Opt_1 split$mut() {
				var size = this.end - this.cur;
				if (size <= 1) { return Opt_1.$self; }
				var mid = this.cur + (size / 2);
				var end_ = this.end;
				this.end = mid;
				return Opts_0.$self.$hash$imm(_graphemes$imm(utf8, starts, mid, end_));
			}
			@Override public Bool_0 canSplit$read() {
				return this.end - this.cur > 1 ? True_0.$self : False_0.$self;
			}
		};
	}

	@Override default base.Hasher_0 hash$read(base.Hasher_0 hasher) {
		hasher.str$mut(this);
		return hasher;
	}

	@Override default Fallible float$imm() {
		return m -> {
			try {
				var res = Double.parseDouble(toJavaStr(this.utf8()));
				return m.ok$mut(res);
			} catch (NumberFormatException e) {
				return m.info$mut(base.Infos_0.$self.msg$imm(fromJavaStr(e.getMessage())));
			}
		};
	}

	final class SubStr implements Str {
		private int[] GRAPHEMES;
		private final ByteBuffer UTF8;
		private final long size;
		public SubStr(Str all, int start, int end) {
			var utf8 = all.utf8();
			assert utf8.position() == 0 : "SubStr position must be 0";
			var index = all.codepointByteOffset(start);
			var endIdx = all.codepointByteOffset(end);
			var byteSize = endIdx - index;
			assert byteSize >= 0 : "SubStr byteSize must be >= 0, was "+byteSize;
			this.UTF8 = utf8.slice(index, byteSize);
			this.size = end - start;
		}
		@Override public ByteBuffer utf8() {
			return UTF8;
		}
		@Override public int[] graphemes() {
			if (this.size == 1) { GRAPHEMES = new int[]{0}; }
			if (GRAPHEMES == null) { GRAPHEMES = NativeRuntime.indexString(UTF8); }
			return GRAPHEMES;
		}
		@Override public Long size$imm() {
			return this.size;
		}
		@Override public Bool_0 isEmpty$read() {
			return size == 0 ? True_0.$self : False_0.$self;
		}
	}
}
