package rt;

import base.*;

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
		utf8.get(dst);
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

	@Override default Str str$read() {
		return this;
	}
	@Override default base.Bool_0 $equals$equals$imm(Str other$) {
		return this.utf8().equals(other$.utf8()) ? True_0.$self : False_0.$self;
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
	@Override default base.Bool_0 isEmpty$imm() {
		return this.utf8().remaining() == 0 ? True_0.$self : False_0.$self;
	}
}
