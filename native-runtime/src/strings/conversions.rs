use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass};
use jni::sys::{jbyte, jdouble, jlong};

#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_floatToStr<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, n: jdouble) -> JByteArray<'local> {
    env.with_env(|env| -> jni::errors::Result<_> {
        let str = n.to_string();
        Ok(env.byte_array_from_slice(str.as_bytes())?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_intToStr<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, n: jlong) -> JByteArray<'local> {
    env.with_env(|env| -> jni::errors::Result<_> {
        let str = n.to_string();
        Ok(env.byte_array_from_slice(str.as_bytes())?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_natToStr<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, n: jlong) -> JByteArray<'local> {
    env.with_env(|env| -> jni::errors::Result<_> {
        let str = (n as u64).to_string();
        Ok(env.byte_array_from_slice(str.as_bytes())?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_byteToStr<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, n: jbyte) -> JByteArray<'local> {
    env.with_env(|env| -> jni::errors::Result<_> {
        let str = (n as u8).to_string();
        Ok(env.byte_array_from_slice(str.as_bytes())?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}
