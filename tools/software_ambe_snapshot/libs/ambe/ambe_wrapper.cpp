// ambe_wrapper.cpp - C wrapper implementation for AMBE encoder/decoder

#include "ambe_wrapper.h"
#include "mbeenc.h"
#include "Golay24128.h"
#include "vocoder_tables.h"
#include "../mbelib/mbelib.h"
#include <cstring>
#include <cstdio>

// Decoder state structure
struct AmbeDecoderState {
    mbe_parms cur_mp;
    mbe_parms prev_mp;
    mbe_parms prev_mp_enhanced;
    float gain;   // output gain (mbelib default was 7, we default to 3.5)
};

extern "C" {

AmbeEncoder ambe_encoder_create(void) {
    MBEEncoder* encoder = new MBEEncoder();
    encoder->set_dmr_mode();
    encoder->set_gain_adjust(2.5f);
    return static_cast<AmbeEncoder>(encoder);
}

void ambe_encoder_destroy(AmbeEncoder encoder) {
    if (encoder) {
        delete static_cast<MBEEncoder*>(encoder);
    }
}

int ambe_encode_dmr(AmbeEncoder encoder, const int16_t* samples, uint8_t* ambe_out) {
    if (!encoder || !samples || !ambe_out) {
        return -1;
    }

    MBEEncoder* enc = static_cast<MBEEncoder*>(encoder);

    // MBEEncoder expects non-const samples, but doesn't modify them
    int16_t samples_copy[160];
    memcpy(samples_copy, samples, 160 * sizeof(int16_t));

    // Encode - output is 9 bytes for DMR mode
    enc->encode(samples_copy, ambe_out);

    return 0;
}

void ambe_set_gain(AmbeEncoder encoder, float gain) {
    if (encoder) {
        static_cast<MBEEncoder*>(encoder)->set_gain_adjust(gain);
    }
}

// ============ Decoder Implementation ============

AmbeDecoder ambe_decoder_create(void) {
    AmbeDecoderState* state = new AmbeDecoderState();

    // Initialize mbe parameters
    mbe_initMbeParms(&state->cur_mp, &state->prev_mp, &state->prev_mp_enhanced);

    // Default gain: 3.5 (mbelib original was 7, which causes heavy clipping)
    state->gain = 3.5f;

    return static_cast<AmbeDecoder>(state);
}

void ambe_decoder_destroy(AmbeDecoder decoder) {
    if (decoder) {
        delete static_cast<AmbeDecoderState*>(decoder);
    }
}

int ambe_decode_dmr(AmbeDecoder decoder, const uint8_t* ambe_in, int16_t* samples_out) {
    if (!decoder || !ambe_in || !samples_out) {
        return -1;
    }

    AmbeDecoderState* state = static_cast<AmbeDecoderState*>(decoder);

    // De-interleave the DMR AMBE frame (72 bits = 9 bytes)
    // Extract a, b, c parts using tables from vocoder_tables.h
    unsigned int a = 0U;
    unsigned int b = 0U;
    unsigned int c = 0U;

    unsigned int MASK = 0x800000U;
    for (unsigned int i = 0U; i < 24U; i++, MASK >>= 1) {
        unsigned int aPos = DMR_A_TABLE[i];
        if (READ_BIT(ambe_in, aPos))
            a |= MASK;
    }

    MASK = 0x400000U;
    for (unsigned int i = 0U; i < 23U; i++, MASK >>= 1) {
        unsigned int bPos = DMR_B_TABLE[i];
        if (READ_BIT(ambe_in, bPos))
            b |= MASK;
    }

    MASK = 0x1000000U;
    for (unsigned int i = 0U; i < 25U; i++, MASK >>= 1) {
        unsigned int cPos = DMR_C_TABLE[i];
        if (READ_BIT(ambe_in, cPos))
            c |= MASK;
    }

    // Golay decode 'a' part with proper error correction
    unsigned int aData = CGolay24128::decode24128(a);

    // De-whiten 'b' using PRNG indexed by full 12-bit aData
    unsigned int prng = PRNG_TABLE[aData] >> 1;
    b ^= prng;

    // Golay decode 'b' with proper error correction
    unsigned int bData = CGolay24128::decode23127(b);

    // Build 49-bit AMBE data for mbelib
    char ambe_d[49];
    memset(ambe_d, 0, sizeof(ambe_d));

    // Pack a_data (12 bits) into ambe_d[0-11]
    for (int i = 0; i < 12; i++) {
        ambe_d[i] = (aData >> (11 - i)) & 1;
    }

    // Pack b_data (12 bits) into ambe_d[12-23]
    for (int i = 0; i < 12; i++) {
        ambe_d[12 + i] = (bData >> (11 - i)) & 1;
    }

    // Pack c (25 bits) into ambe_d[24-48]
    for (int i = 0; i < 25; i++) {
        ambe_d[24 + i] = (c >> (24 - i)) & 1;
    }

    // Decode AMBE to float audio using mbelib (bypasses mbelib's hardcoded gain=7)
    int errs = 0;
    int errs2 = 0;
    char err_str[64];
    err_str[0] = '\0';

    float float_buf[160];
    mbe_processAmbe2450Dataf(float_buf, &errs, &errs2, err_str, ambe_d,
                             &state->cur_mp, &state->prev_mp, &state->prev_mp_enhanced, 3);

    // Apply configurable gain and convert float → i16 (replaces mbe_floattoshort)
    float g = state->gain;
    for (int i = 0; i < 160; i++) {
        float audio = g * float_buf[i];
        if (audio > 32760.0f) {
            audio = 32760.0f;
        } else if (audio < -32760.0f) {
            audio = -32760.0f;
        }
        samples_out[i] = (int16_t)(audio);
    }

    // Move parameters for next frame
    mbe_moveMbeParms(&state->cur_mp, &state->prev_mp);

    return errs + errs2;
}

void ambe_set_decoder_gain(AmbeDecoder decoder, float gain) {
    if (decoder) {
        static_cast<AmbeDecoderState*>(decoder)->gain = gain;
    }
}

} // extern "C"
