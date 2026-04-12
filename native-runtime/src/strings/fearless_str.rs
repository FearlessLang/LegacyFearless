use jni::Env;
use std::slice;
use std::str::Utf8Error;

use jni::objects::JByteBuffer;

#[repr(transparent)]
pub struct FearlessStr<'array_local> {
    slice: &'array_local [u8],
}
impl<'array_local> FearlessStr<'array_local> {
    pub fn new<'local, 'array>(env: &mut Env<'local>, utf8_str: &'array JByteBuffer<'array_local>) -> FearlessStr<'array_local> {
        // Safety: Lifetimes bind the data here. Making this critical would be safe too,
        // but we don't need to do that unless this is becoming a perf issue (making it critical
        // has stalling risks on other threads because it locks the allocator)
        let buf_ptr = env.get_direct_buffer_address(utf8_str).unwrap();
        let buf_len = env.get_direct_buffer_capacity(utf8_str).unwrap();
        let slice = unsafe { slice::from_raw_parts(buf_ptr, buf_len) };
        Self { slice }
    }
    pub fn validate(&self) -> Option<Utf8Error> {
        let raw_str = self.as_bytes();
        std::str::from_utf8(raw_str).err()
    }
    /// # Safety
    /// You have called `self.validate()` on this string, and it returned `None`.
    pub unsafe fn as_str(&self) -> &str {
        let raw_str = self.as_bytes();
        std::str::from_utf8_unchecked(raw_str)
    }
    pub fn as_bytes(&self) -> &[u8] {
        self.slice
    }
}
