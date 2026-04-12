use std::io;
use std::io::Write;

use jni::{Env, EnvUnowned, jni_str};
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JByteBuffer, JClass};
use jni::strings::JNIString;
use jni::sys::{jint, jintArray};
use unicode_segmentation::UnicodeSegmentation;
use unicode_normalization::UnicodeNormalization;

pub use fearless_str::FearlessStr;

mod fearless_str;
mod conversions;

#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_validateStringOrThrow<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, utf8_str: JByteBuffer<'local>) {
    env.with_env(|env| -> jni::errors::Result<()> {
        if let Some(err) = FearlessStr::new(env, &utf8_str).validate() {
            env.throw_new(jni_str!("rt/NativeRuntime$StringEncodingError"), JNIString::from(format!("{}", err)))?;
        }
        Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}

/// # Safety
/// You have invoked `NativeRuntime.validateStringOrThrow()` before calling this method.
#[no_mangle]
pub unsafe extern "system" fn Java_rt_NativeRuntime_indexString<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, utf8_str: JByteBuffer<'local>) -> jintArray {
    env.with_env(|env| -> jni::errors::Result<_> {
        let graphemes = {
            let f_str = FearlessStr::new(env, &utf8_str);
            let str = f_str.as_str();

            // Java ByteBuffers and byte[] must be indexable by a signed integer to be created,
            // so it is impossible to ever overflow when casting the usize to an i32
            str.grapheme_indices(true)
                .map(|(idx, _grapheme)| idx as jint)
                .collect::<Vec<_>>()
        };

        let res = env.new_int_array(graphemes.len())?;
        res.set_region(env, 0, &graphemes)?;
        Ok(res.into_raw())
    }).resolve::<ThrowRuntimeExAndDefault>()
}

/// Normalise a string using NFC.
/// # Safety
/// You have invoked `NativeRuntime.validateStringOrThrow()` before calling this method.
#[no_mangle]
pub unsafe extern "system" fn Java_rt_NativeRuntime_normaliseString<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, utf8_str: JByteBuffer<'local>) -> JByteArray<'local> {
    env.with_env(|env| -> jni::errors::Result<_> {
        let normalised: Vec<u8> = {
            let f_str = FearlessStr::new(env, &utf8_str);
            let str = f_str.as_str();
            let nfc = str.nfc().collect::<String>();
            nfc.into_bytes()
        };

        Ok(env.byte_array_from_slice(&normalised)?)
    }).resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_print<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, utf8_str: JByteBuffer<'local>) {
    env.with_env(|env| -> jni::errors::Result<()> {
        print(env, &utf8_str, false, io::stdout().lock());
        Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_println<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, utf8_str: JByteBuffer<'local>) {
    env.with_env(|env| -> jni::errors::Result<()> {
        print(env, &utf8_str, true, io::stdout().lock());
        Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_printErr<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, utf8_str: JByteBuffer<'local>) {
    env.with_env(|env| -> jni::errors::Result<()> {
        print(env, &utf8_str, false, io::stderr().lock());
        Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}
#[no_mangle]
pub extern "system" fn Java_rt_NativeRuntime_printlnErr<'local>(mut env: EnvUnowned<'local>, _class: JClass<'local>, utf8_str: JByteBuffer<'local>) {
    env.with_env(|env| -> jni::errors::Result<()> {
        print(env, &utf8_str, true, io::stderr().lock());
        Ok(())
    }).resolve::<ThrowRuntimeExAndDefault>()
}

fn print<'local, B: Write>(env: &mut Env<'local>, utf8_str: &JByteBuffer<'local>, append_newline: bool, mut buffer: B) {
    let f_str = FearlessStr::new(env, utf8_str);
    let str = f_str.as_bytes();
    buffer.write_all(str).unwrap();
    if append_newline { buffer.write_all(b"\n").unwrap(); }
    buffer.flush().unwrap();
}
