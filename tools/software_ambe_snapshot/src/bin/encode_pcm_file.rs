//! 把外部 PCM 文件编码为语音帧序列，并做确定性与解码回放核对。
//!
//! 与 freeze_vectors 使用同一个编码器实现，只是输入来自文件而非内置用例，
//! 便于为新的测试素材生成冻结向量。
//!
//! 用法：
//!   encode_pcm_file <输入.pcm_s16le> <输出目录> <名称>
//!
//! 输入必须是 8 千赫兹、16 位有符号小端单声道，长度为 160 采样的整数倍。

use h13_software_ambe_vectors::ambe_ffi::{AmbeDecoder, AmbeEncoder};
use std::env;
use std::fs;
use std::io::{self, Write};
use std::path::PathBuf;

const SAMPLES_PER_FRAME: usize = 160;
const AMBE_BYTES_PER_FRAME: usize = 9;

fn main() -> io::Result<()> {
    let args: Vec<String> = env::args().collect();
    if args.len() < 4 {
        eprintln!("用法: encode_pcm_file <输入.pcm_s16le> <输出目录> <名称>");
        std::process::exit(2);
    }
    let input = PathBuf::from(&args[1]);
    let outdir = PathBuf::from(&args[2]);
    let name = &args[3];
    fs::create_dir_all(&outdir)?;

    let raw = fs::read(&input)?;
    if raw.len() % 2 != 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "PCM字节数为奇数"));
    }
    let pcm: Vec<i16> = raw
        .chunks_exact(2)
        .map(|b| i16::from_le_bytes([b[0], b[1]]))
        .collect();
    if pcm.len() % SAMPLES_PER_FRAME != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            format!("PCM采样数 {} 不是 {} 的整数倍", pcm.len(), SAMPLES_PER_FRAME),
        ));
    }

    // 两次独立编码，确认同一输入在全新编码器实例间输出一致
    let first = encode_all(&pcm)?;
    let second = encode_all(&pcm)?;
    if first != second {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "在全新编码器实例间输出不确定",
        ));
    }

    let frames = first.len() / AMBE_BYTES_PER_FRAME;
    if frames % 12 != 0 {
        eprintln!(
            "提示: 帧数 {} 不能同时被 3 和 4 整除，两种打包格式无法直接对照",
            frames
        );
    }

    let (decoded, errors) = decode_all(&first)?;
    let nonzero = decoded.iter().filter(|&&s| s != 0).count();

    fs::write(outdir.join(format!("{name}.ambe9_sequence.bin")), &first)?;
    write_pcm(&outdir.join(format!("{name}.decoded.pcm_s16le")), &decoded)?;
    write_wav(&outdir.join(format!("{name}.decoded.wav")), &decoded)?;

    println!("输入      {}", input.display());
    println!("PCM       {} 字节，{} 采样", raw.len(), pcm.len());
    println!("语音帧    {} 个（每帧 20 毫秒，共 {:.2} 秒）", frames, frames as f64 * 0.02);
    println!("三帧一包  {} 包（60 毫秒节拍）", frames / 3);
    println!("四帧一包  {} 包（80 毫秒节拍）", frames / 4);
    println!("帧流字节  {}", first.len());
    println!("确定性    两次独立编码输出一致");
    println!("解码核对  错误 {}，非零采样 {}", errors, nonzero);
    println!("帧流SHA   {}", sha256_hex(&first).to_uppercase());
    println!("输出目录  {}", outdir.display());
    Ok(())
}

fn encode_all(pcm: &[i16]) -> io::Result<Vec<u8>> {
    let encoder = AmbeEncoder::new()
        .ok_or_else(|| io::Error::new(io::ErrorKind::Other, "创建编码器失败"))?;
    let mut out = Vec::with_capacity(pcm.len() / SAMPLES_PER_FRAME * AMBE_BYTES_PER_FRAME);
    for frame in pcm.chunks_exact(SAMPLES_PER_FRAME) {
        let encoded = encoder
            .encode(frame)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "编码失败"))?;
        out.extend_from_slice(&encoded);
    }
    Ok(out)
}

fn decode_all(encoded: &[u8]) -> io::Result<(Vec<i16>, usize)> {
    let decoder = AmbeDecoder::new()
        .ok_or_else(|| io::Error::new(io::ErrorKind::Other, "创建解码器失败"))?;
    let mut pcm = Vec::new();
    let mut errors = 0_usize;
    for chunk in encoded.chunks_exact(AMBE_BYTES_PER_FRAME) {
        let mut frame = [0_u8; AMBE_BYTES_PER_FRAME];
        frame.copy_from_slice(chunk);
        match decoder.decode(&frame) {
            // 解码返回样本与该帧的错误计数
            Some((samples, frame_errors)) => {
                pcm.extend_from_slice(&samples);
                errors += frame_errors.max(0) as usize;
            }
            None => {
                errors += 1;
                pcm.extend(std::iter::repeat(0_i16).take(SAMPLES_PER_FRAME));
            }
        }
    }
    Ok((pcm, errors))
}

fn write_pcm(path: &std::path::Path, pcm: &[i16]) -> io::Result<()> {
    let mut bytes = Vec::with_capacity(pcm.len() * 2);
    for sample in pcm {
        bytes.extend_from_slice(&sample.to_le_bytes());
    }
    fs::write(path, bytes)
}

fn write_wav(path: &std::path::Path, pcm: &[i16]) -> io::Result<()> {
    let data_len = (pcm.len() * 2) as u32;
    let mut file = fs::File::create(path)?;
    file.write_all(b"RIFF")?;
    file.write_all(&(36 + data_len).to_le_bytes())?;
    file.write_all(b"WAVEfmt ")?;
    file.write_all(&16_u32.to_le_bytes())?;
    file.write_all(&1_u16.to_le_bytes())?;
    file.write_all(&1_u16.to_le_bytes())?;
    file.write_all(&8000_u32.to_le_bytes())?;
    file.write_all(&16000_u32.to_le_bytes())?;
    file.write_all(&2_u16.to_le_bytes())?;
    file.write_all(&16_u16.to_le_bytes())?;
    file.write_all(b"data")?;
    file.write_all(&data_len.to_le_bytes())?;
    for sample in pcm {
        file.write_all(&sample.to_le_bytes())?;
    }
    Ok(())
}

/// 最小实现的 SHA-256，避免为一个哈希引入额外依赖。
fn sha256_hex(data: &[u8]) -> String {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
        0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
        0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
        0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];
    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c,
        0x1f83d9ab, 0x5be0cd19,
    ];
    let mut msg = data.to_vec();
    let bitlen = (data.len() as u64) * 8;
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bitlen.to_be_bytes());

    for block in msg.chunks_exact(64) {
        let mut w = [0_u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                block[i * 4], block[i * 4 + 1], block[i * 4 + 2], block[i * 4 + 3],
            ]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let (mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh) =
            (h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]);
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh = g; g = f; f = e;
            e = d.wrapping_add(t1);
            d = c; c = b; b = a;
            a = t1.wrapping_add(t2);
        }
        h[0] = h[0].wrapping_add(a); h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c); h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e); h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g); h[7] = h[7].wrapping_add(hh);
    }
    h.iter().map(|v| format!("{v:08x}")).collect()
}
