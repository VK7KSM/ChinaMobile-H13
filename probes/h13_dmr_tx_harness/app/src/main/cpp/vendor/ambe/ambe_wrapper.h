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

// Encode 160 PCM samples to 9 bytes of packed 49-bit vocoder parameters
// (MSB-first). This is not DMR 72-bit air codeword / encode_dmr().
int ambe_encode_49bit_packed9(AmbeEncoder encoder, const int16_t* samples,
        uint8_t* ambe_out);

// Add DMR FEC, whitening and interleaving to one packed 49-bit frame.
int ambe_channel_encode_49bit_packed9(const uint8_t* packed49,
        uint8_t* dmr_out);

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

// Decode one DMR channel frame through the same mbelib frame path used by
// the historical chan_d_to_wav reference tool.
int ambe_decode_dmr_reference(AmbeDecoder decoder, const uint8_t* ambe_in,
        int16_t* samples_out);

// Decode one packed 49-bit vocoder frame directly to 160 PCM samples.
int ambe_decode_49bit_packed9(AmbeDecoder decoder, const uint8_t* packed49,
        int16_t* samples_out);

// Remove DMR interleaving/FEC and return one packed 49-bit vocoder frame.
int ambe_channel_decode_to_49bit_packed9(const uint8_t* dmr_in,
        uint8_t* packed49_out);

// Set decoder output gain (default 3.5, mbelib original was 7)
// Lower values reduce output amplitude and prevent clipping distortion
void ambe_set_decoder_gain(AmbeDecoder decoder, float gain);

#ifdef __cplusplus
}
#endif

#endif // AMBE_WRAPPER_H
