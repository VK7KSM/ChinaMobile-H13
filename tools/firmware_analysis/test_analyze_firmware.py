import unittest

from analyze_firmware import (
    DECOMPRESS_SCATTERLOAD,
    decompress_scatterload,
    discover_initialized_data,
    thumb_bl_target,
    thumb_immediate_memory_access,
    thumb_struct_field_xrefs,
)


class ScatterloadDecompressionTests(unittest.TestCase):
    def test_literal_count_is_encoded_plus_one(self) -> None:
        image, consumed, overrun = decompress_scatterload(b"\x04\x00ABC", 3)
        self.assertEqual(image, list(b"ABC"))
        self.assertEqual(consumed, 5)
        self.assertEqual(overrun, 0)

    def test_extended_literal_count(self) -> None:
        image, consumed, overrun = decompress_scatterload(b"\x10\x04ABC", 4)
        self.assertEqual(image, list(b"ABC\x00"))
        self.assertEqual(consumed, 5)
        self.assertEqual(overrun, 0)

    def test_zero_run(self) -> None:
        image, consumed, overrun = decompress_scatterload(b"\x31", 3)
        self.assertEqual(image, [0, 0, 0])
        self.assertEqual(consumed, 1)
        self.assertEqual(overrun, 0)

    def test_back_reference_run(self) -> None:
        stream = b"\x04\x00ABC\x19\x03"
        image, consumed, overrun = decompress_scatterload(stream, 6)
        self.assertEqual(image, list(b"ABCABC"))
        self.assertEqual(consumed, len(stream))
        self.assertEqual(overrun, 0)

    def test_prefix_dependency_is_preserved_as_unknown(self) -> None:
        image, consumed, overrun = decompress_scatterload(b"\x19\x01", 3, 1)
        self.assertEqual(image, [None, None, None])
        self.assertEqual(consumed, 2)
        self.assertEqual(overrun, 0)

    def test_zero_literal_encoding_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "underflow"):
            decompress_scatterload(b"\x00\x00\x00", 1)


class ScatterloadDiscoveryTests(unittest.TestCase):
    def test_finds_valid_scatter_entry(self) -> None:
        base = 0x08006000
        data = bytearray(b"\xff" * 0x100)
        source = base + 0x80
        entry = (source, 0x200000C0, 0x3D4, DECOMPRESS_SCATTERLOAD)
        data[0x20:0x30] = __import__("struct").pack("<IIII", *entry)
        region = discover_initialized_data(bytes(data), base)
        self.assertEqual(region.table_address, base + 0x20)
        self.assertEqual(region.source, source)
        self.assertEqual(region.destination, 0x200000C0)
        self.assertEqual(region.size, 0x3D4)

    def test_rejects_ambiguous_entries(self) -> None:
        base = 0x08006000
        data = bytearray(b"\xff" * 0x100)
        entry = (base + 0x80, 0x200000C0, 0x20, DECOMPRESS_SCATTERLOAD)
        packed = __import__("struct").pack("<IIII", *entry)
        data[0x20:0x30] = packed
        data[0x40:0x50] = packed
        with self.assertRaisesRegex(ValueError, "expected one"):
            discover_initialized_data(bytes(data), base)


class ThumbCallDecodingTests(unittest.TestCase):
    def test_backward_bl_target(self) -> None:
        self.assertEqual(thumb_bl_target(0xF7F0, 0xFDC7, 0x0801D932), 0x0800E4C4)

    def test_forward_bl_target(self) -> None:
        self.assertEqual(thumb_bl_target(0xF002, 0xF8B0, 0x0801E9AC), 0x08020B10)

    def test_non_bl_opcode_is_rejected(self) -> None:
        self.assertIsNone(thumb_bl_target(0x2000, 0x4770, 0x08000000))


class ThumbStructureFieldTests(unittest.TestCase):
    def test_byte_load_immediate(self) -> None:
        self.assertEqual(
            thumb_immediate_memory_access(0x7908), ("ldrb", 0, 1, 4)
        )

    def test_direct_field_reference(self) -> None:
        data = b"\x00\x49\x08\x79\x00\x10\x00\x20"
        self.assertEqual(
            thumb_struct_field_xrefs(data, 0x08000000, 0x20001000, 4),
            [(0x08000000, 0x08000002, "ldrb", 0)],
        )

    def test_different_field_is_not_reported(self) -> None:
        data = b"\x00\x49\x08\x79\x00\x10\x00\x20"
        self.assertEqual(
            thumb_struct_field_xrefs(data, 0x08000000, 0x20001000, 5), []
        )


if __name__ == "__main__":
    unittest.main()
