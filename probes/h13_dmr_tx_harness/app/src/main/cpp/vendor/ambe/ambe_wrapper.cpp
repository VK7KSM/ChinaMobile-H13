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

namespace {

const int kDmrRowFirst[36] = {
    0,1,0,1,0,1, 0,1,0,1,0,1, 0,1,0,1,0,1,
    0,1,0,1,0,2, 0,2,0,2,0,2, 0,2,0,2,0,2
};
const int kDmrColumnFirst[36] = {
    23,10,22,9,21,8, 20,7,19,6,18,5, 17,4,16,3,15,2,
    14,1,13,0,12,10, 11,9,10,8,9,7, 8,6,7,5,6,4
};
const int kDmrRowSecond[36] = {
    0,2,0,2,0,2, 0,2,0,3,0,3, 1,3,1,3,1,3,
    1,3,1,3,1,3, 1,3,1,3,1,3, 1,3,1,3,1,3
};
const int kDmrColumnSecond[36] = {
    5,3,4,2,3,1, 2,0,1,13,0,12, 22,11,21,10,20,9,
    19,8,18,7,17,6, 16,5,15,4,14,3, 13,2,12,1,11,0
};

void deinterleave_dmr(const uint8_t input[9], char frame[4][24]) {
    memset(frame, 0, 4 * 24);
    for (int bit = 0; bit < 72; bit += 2) {
        const int index = bit / 2;
        frame[kDmrRowFirst[index]][kDmrColumnFirst[index]] =
                READ_BIT(input, bit) ? 1 : 0;
        frame[kDmrRowSecond[index]][kDmrColumnSecond[index]] =
                READ_BIT(input, bit + 1) ? 1 : 0;
    }
}

void pack_ambe49(const char data[49], uint8_t output[9]) {
    memset(output, 0, 9);
    for (int bit = 0; bit < 49; ++bit) {
        WRITE_BIT(output, bit, data[bit] & 1);
    }
}

void float_to_pcm(const float input[160], float gain, int16_t output[160]) {
    for (int i = 0; i < 160; ++i) {
        float audio = gain * input[i];
        if (audio > 32760.0f) {
            audio = 32760.0f;
        } else if (audio < -32760.0f) {
            audio = -32760.0f;
        }
        output[i] = static_cast<int16_t>(audio);
    }
}

}  // namespace

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

int ambe_encode_49bit_packed9(AmbeEncoder encoder, const int16_t* samples,
        uint8_t* ambe_out) {
    if (!encoder || !samples || !ambe_out) {
        return -1;
    }
    MBEEncoder* enc = static_cast<MBEEncoder*>(encoder);
    // set_49bit_mode()的输出位序不是mbelib所用的规范49位参数顺序。
    // 先生成已经由历史向量验证的软件DMR码字，再由同一软件信道解码器
    // 提取规范49位；这样后续privacy仍发生在FEC之前。
    enc->set_dmr_mode();
    // MBEEncoder::encode()先用codeword[0..71]保存逐位数据，最后才把
    // DMR码字压回前9字节，因此这里必须保留完整72字节工作区。
    uint8_t channel[72] = {};
    int16_t samples_copy[160];
    memcpy(samples_copy, samples, 160 * sizeof(int16_t));
    enc->encode(samples_copy, channel);
    return ambe_channel_decode_to_49bit_packed9(channel, ambe_out);
}

int ambe_channel_encode_49bit_packed9(const uint8_t* packed49,
        uint8_t* dmr_out) {
    if (!packed49 || !dmr_out) {
        return -1;
    }
    MBEEncoder encoder;
    encoder.encode_dmr_from_49bit(packed49, dmr_out);
    return 0;
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

int ambe_channel_decode_to_49bit_packed9(const uint8_t* dmr_in,
        uint8_t* packed49_out) {
    if (!dmr_in || !packed49_out) {
        return -1;
    }
    char frame[4][24];
    char data[49] = {};
    deinterleave_dmr(dmr_in, frame);
    mbe_eccAmbe3600x2450C0(frame);
    mbe_demodulateAmbe3600x2450Data(frame);
    mbe_eccAmbe3600x2450Data(frame, data);
    pack_ambe49(data, packed49_out);
    return 0;
}

int ambe_decode_49bit_packed9(AmbeDecoder decoder, const uint8_t* packed49,
        int16_t* samples_out) {
    if (!decoder || !packed49 || !samples_out) {
        return -1;
    }
    AmbeDecoderState* state = static_cast<AmbeDecoderState*>(decoder);
    char ambe_d[49];
    for (int bit = 0; bit < 49; ++bit) {
        ambe_d[bit] = READ_BIT(packed49, bit) ? 1 : 0;
    }

    int errs = 0;
    int errs2 = 0;
    char err_str[64];
    err_str[0] = '\0';

    float float_buf[160];
    mbe_processAmbe2450Dataf(float_buf, &errs, &errs2, err_str, ambe_d,
                             &state->cur_mp, &state->prev_mp, &state->prev_mp_enhanced, 3);

    float_to_pcm(float_buf, state->gain, samples_out);

    return errs + errs2;
}

int ambe_decode_dmr(AmbeDecoder decoder, const uint8_t* ambe_in,
        int16_t* samples_out) {
    uint8_t packed49[9] = {};
    if (ambe_channel_decode_to_49bit_packed9(ambe_in, packed49) != 0) {
        return -1;
    }
    return ambe_decode_49bit_packed9(decoder, packed49, samples_out);
}

int ambe_decode_dmr_reference(AmbeDecoder decoder, const uint8_t* ambe_in,
        int16_t* samples_out) {
    if (!decoder || !ambe_in || !samples_out) {
        return -1;
    }
    AmbeDecoderState* state = static_cast<AmbeDecoderState*>(decoder);
    char frame[4][24];
    char data[49] = {};
    char error_text[64] = {};
    int errors = 0;
    int total_errors = 0;
    deinterleave_dmr(ambe_in, frame);
    mbe_processAmbe3600x2450Frame(samples_out, &errors, &total_errors,
            error_text, frame, data, &state->cur_mp, &state->prev_mp,
            &state->prev_mp_enhanced, 3);
    return total_errors;
}

void ambe_set_decoder_gain(AmbeDecoder decoder, float gain) {
    if (decoder) {
        static_cast<AmbeDecoderState*>(decoder)->gain = gain;
    }
}

} // extern "C"
