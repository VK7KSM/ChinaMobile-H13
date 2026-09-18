#!/usr/bin/env python3
import re
import sys

from pypdf import PdfReader


TERMS = (
    "I/Q",
    "I2S",
    "HPI",
    "baseband",
    "debug",
    "test",
    "4FSK",
    "FSK",
    "LoRa",
    "ADC",
    "DAC",
    "PCM",
    "vocoder",
    "firmware",
    "DSP",
    "GPIO",
    "SPI",
    "UART",
    "modulation",
    "demodulation",
    "symbol",
)


def clean_line(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print(
            f"usage: {sys.argv[0]} SCT3258_datasheet.pdf [page,page,...]",
            file=sys.stderr,
        )
        return 2

    reader = PdfReader(sys.argv[1])
    if len(sys.argv) == 3:
        for value in sys.argv[2].split(","):
            page_number = int(value)
            text = (reader.pages[page_number - 1].extract_text() or "").replace(
                "\x00", " "
            )
            print(f"===== PDF PAGE {page_number} =====")
            print(text.strip())
        return 0

    print("page|chars|heading|terms")
    for page_number, page in enumerate(reader.pages, 1):
        text = (page.extract_text() or "").replace("\x00", " ")
        lines = [clean_line(line) for line in text.splitlines() if clean_line(line)]
        hits = [term for term in TERMS if term.lower() in text.lower()]
        heading = lines[0][:100] if lines else ""
        print(f"{page_number}|{len(text)}|{heading}|{','.join(hits)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
