// ambe_wrapper.h - C wrapper for AMBE encoder/decoder
// This provides a C interface to the C++ MBEEncoder and mbelib decoder

#ifndef AMBE_WRAPPER_H
#define AMBE_WRAPPER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Opaque handle to the encoder
typedef void* AmbeEncoder;

// Opaque handle to the decoder
typedef void* AmbeDecoder;

// ============ Encoder Functions ============

// Create a new AMBE encoder for DMR mode
AmbeEncoder ambe_encoder_create(void);

// Destroy the encoder
void ambe_encoder_destroy(AmbeEncoder encoder);

// Encode 160 PCM samples (16-bit signed, 8kHz) to 9 bytes AMBE
// samples: input PCM samples (160 samples = 20ms)
// ambe_out: output AMBE data (9 bytes for DMR)
// Returns 0 on success, -1 on error
int ambe_encode_dmr(AmbeEncoder encoder, const int16_t* samples, uint8_t* ambe_out);

// Set gain adjustment (default 2.5 for DMR)
void ambe_set_gain(AmbeEncoder encoder, float gain);

// ============ Decoder Functions ============

// Create a new AMBE decoder for DMR mode
AmbeDecoder ambe_decoder_create(void);

// Destroy the decoder
void ambe_decoder_destroy(AmbeDecoder decoder);

// Decode 9 bytes AMBE to 160 PCM samples (16-bit signed, 8kHz)
// ambe_in: input AMBE data (9 bytes for DMR)
// samples_out: output PCM samples (160 samples = 20ms)
// Returns number of bit errors corrected, or -1 on error
int ambe_decode_dmr(AmbeDecoder decoder, const uint8_t* ambe_in, int16_t* samples_out);

// Set decoder output gain (default 3.5, mbelib original was 7)
// Lower values reduce output amplitude and prevent clipping distortion
void ambe_set_decoder_gain(AmbeDecoder decoder, float gain);

#ifdef __cplusplus
}
#endif

#endif // AMBE_WRAPPER_H
