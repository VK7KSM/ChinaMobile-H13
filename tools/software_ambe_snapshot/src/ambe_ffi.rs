//! AMBE Encoder/Decoder FFI Bindings
//!
//! This module provides Rust bindings to the native AMBE encoder and decoder libraries.

#![allow(dead_code)]

use std::ffi::c_void;
use std::os::raw::{c_float, c_int};
use std::sync::Mutex;

/// Global mutex to serialize all AMBE FFI calls.
///
/// The underlying C/C++ libraries (imbe_vocoder, mbelib) use global/static state
/// that is not thread-safe. Concurrent encoder and decoder calls from different
/// threads (e.g. receive task decoding while transmit encodes) cause segfaults.
/// This mutex ensures all FFI calls are serialized.
static AMBE_LOCK: Mutex<()> = Mutex::new(());

// FFI declarations - Encoder
extern "C" {
    fn ambe_encoder_create() -> *mut c_void;
    fn ambe_encoder_destroy(encoder: *mut c_void);
    fn ambe_encode_dmr(encoder: *mut c_void, samples: *const i16, ambe_out: *mut u8) -> c_int;
    fn ambe_set_gain(encoder: *mut c_void, gain: c_float);
}

// FFI declarations - Decoder
extern "C" {
    fn ambe_decoder_create() -> *mut c_void;
    fn ambe_decoder_destroy(decoder: *mut c_void);
    fn ambe_decode_dmr(decoder: *mut c_void, ambe_in: *const u8, samples_out: *mut i16) -> c_int;
    fn ambe_set_decoder_gain(decoder: *mut c_void, gain: c_float);
}

/// AMBE Encoder for DMR
///
/// This wraps the native MBEEncoder library which uses the IMBE vocoder
/// to encode PCM audio to AMBE+2 format used by DMR.
pub struct AmbeEncoder {
    handle: *mut c_void,
}

// SAFETY: AmbeEncoder is Send because the underlying C++ object
// doesn't use thread-local storage and each encoder is independent.
unsafe impl Send for AmbeEncoder {}

impl AmbeEncoder {
    /// Create a new AMBE encoder configured for DMR mode
    pub fn new() -> Option<Self> {
        let _lock = AMBE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let handle = unsafe { ambe_encoder_create() };
        if handle.is_null() {
            None
        } else {
            Some(Self { handle })
        }
    }

    /// Encode 160 PCM samples (20ms at 8kHz) to 9 bytes of AMBE data
    ///
    /// # Arguments
    /// * `samples` - 160 signed 16-bit PCM samples at 8kHz
    ///
    /// # Returns
    /// 9 bytes of AMBE encoded data, or None on error
    pub fn encode(&self, samples: &[i16]) -> Option<[u8; 9]> {
        if samples.len() < 160 {
            return None;
        }

        let _lock = AMBE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        // The C++ MBEEncoder::encode() uses the output buffer as a 72-byte
        // scratch space (one bit per byte for intermediate encode_49bit step),
        // then writes the final 9-byte result via memcpy. We must provide
        // at least 72 bytes to avoid stack buffer overflow.
        let mut work_buf = [0u8; 72];
        let result = unsafe {
            ambe_encode_dmr(self.handle, samples.as_ptr(), work_buf.as_mut_ptr())
        };

        if result == 0 {
            let mut ambe_out = [0u8; 9];
            ambe_out.copy_from_slice(&work_buf[..9]);
            Some(ambe_out)
        } else {
            None
        }
    }

    /// Set the gain adjustment for encoding
    ///
    /// Default is 2.5 for DMR. Higher values increase output volume.
    pub fn set_gain(&self, gain: f32) {
        let _lock = AMBE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        unsafe { ambe_set_gain(self.handle, gain) };
    }
}

impl Drop for AmbeEncoder {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            let _lock = AMBE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
            unsafe { ambe_encoder_destroy(self.handle) };
        }
    }
}

impl Default for AmbeEncoder {
    fn default() -> Self {
        Self::new().expect("Failed to create AMBE encoder")
    }
}

/// AMBE Decoder for DMR
///
/// This wraps the mbelib decoder to decode AMBE+2 format used by DMR
/// back to PCM audio.
pub struct AmbeDecoder {
    handle: *mut c_void,
}

// SAFETY: AmbeDecoder is Send because the underlying C object
// doesn't use thread-local storage and each decoder is independent.
unsafe impl Send for AmbeDecoder {}

impl AmbeDecoder {
    /// Create a new AMBE decoder configured for DMR mode
    pub fn new() -> Option<Self> {
        let _lock = AMBE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let handle = unsafe { ambe_decoder_create() };
        if handle.is_null() {
            None
        } else {
            Some(Self { handle })
        }
    }

    /// Decode 9 bytes of AMBE data to 160 PCM samples (20ms at 8kHz)
    ///
    /// # Arguments
    /// * `ambe_data` - 9 bytes of AMBE encoded data
    ///
    /// # Returns
    /// Tuple of (160 signed 16-bit PCM samples, error count), or None on error
    pub fn decode(&self, ambe_data: &[u8; 9]) -> Option<([i16; 160], i32)> {
        let _lock = AMBE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let mut samples_out = [0i16; 160];
        let errors = unsafe {
            ambe_decode_dmr(self.handle, ambe_data.as_ptr(), samples_out.as_mut_ptr())
        };

        if errors >= 0 {
            Some((samples_out, errors))
        } else {
            None
        }
    }

    /// Set the output gain for decoding
    ///
    /// Default is 3.5. The original mbelib default was 7, which causes heavy
    /// clipping on loud signals. Lower values reduce output amplitude.
    pub fn set_gain(&self, gain: f32) {
        let _lock = AMBE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
        unsafe { ambe_set_decoder_gain(self.handle, gain) };
    }
}

impl Drop for AmbeDecoder {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            let _lock = AMBE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
            unsafe { ambe_decoder_destroy(self.handle) };
        }
    }
}

impl Default for AmbeDecoder {
    fn default() -> Self {
        Self::new().expect("Failed to create AMBE decoder")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encoder_creation() {
        let encoder = AmbeEncoder::new();
        assert!(encoder.is_some());
    }

    #[test]
    fn test_encode_silence() {
        let encoder = AmbeEncoder::new().unwrap();
        let samples = [0i16; 160];
        let result = encoder.encode(&samples);
        assert!(result.is_some());
    }

    #[test]
    fn test_decoder_creation() {
        let decoder = AmbeDecoder::new();
        assert!(decoder.is_some());
    }

    #[test]
    fn test_encode_decode_roundtrip() {
        let encoder = AmbeEncoder::new().unwrap();
        let decoder = AmbeDecoder::new().unwrap();

        // Create a simple sine wave
        let mut samples = [0i16; 160];
        for (i, sample) in samples.iter_mut().enumerate() {
            let t = i as f32 / 8000.0;
            *sample = (f32::sin(2.0 * std::f32::consts::PI * 440.0 * t) * 16000.0) as i16;
        }

        // Encode
        let ambe = encoder.encode(&samples).expect("Encoding failed");

        // Decode
        let (decoded, errors) = decoder.decode(&ambe).expect("Decoding failed");

        println!("Roundtrip errors: {}", errors);
        // Just verify we got output - exact match not expected due to lossy codec
        assert!(!decoded.iter().all(|&x| x == 0), "Decoded audio is all zeros");
    }
}
