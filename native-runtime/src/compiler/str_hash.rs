use crate::strings::FearlessStr;
use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteBuffer, JClass};
use jni::sys::jlong;

#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_hashString<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, utf8_str: JByteBuffer<'local>) -> jlong {
	env.with_env(|env| -> jni::errors::Result<_> {
		let str = FearlessStr::new(env, &utf8_str);
		Ok(seahash::hash(str.as_bytes()) as i64)
	}).resolve::<ThrowRuntimeExAndDefault>()
}