#!/usr/bin/env python3

from __future__ import annotations

import tempfile
import unittest
import sys
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parent))
from compare_sct_dat_applications import analyze, common_prefix_bytes, common_suffix_bytes
from index_sct_dat import parse_dat


class CompareSctDatApplicationsTests(unittest.TestCase):
    def test_common_edges_do_not_overlap(self) -> None:
        left = b"abcXabc"
        right = b"abcYabc"
        prefix = common_prefix_bytes(left, right)
        self.assertEqual(prefix, 3)
        self.assertEqual(common_suffix_bytes(left, right, prefix), 3)

    def test_reports_same_coded_payload_change_without_cross_coded_pair(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            left = root / "left.dat"
            right = root / "right.dat"
            left.write_text("E 0x0800\ni 0x0000 0x0002 0x0003\n0x0001\n0x0002\n", encoding="ascii")
            right.write_text("E 0x0800\ni 0x0000 0x0002 0x0003\n0x0001\n0x0003\n", encoding="ascii")

            report = analyze(left, right)

        self.assertEqual(report["changed_payload_section_ordinals"], [0])
        self.assertEqual(report["cross_coded_section_ordinals"], [])
        self.assertEqual(report["same_length_changed_section_ordinals"], [0])
        self.assertEqual(report["sections"][0]["differing_bytes_in_common_length"], 1)

    def test_entryless_section_dat_requires_explicit_opt_in(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vocoder.dat"
            path.write_text("i 0x0000 0x0001 0x0002\n0xabcd\n", encoding="ascii")
            with self.assertRaisesRegex(ValueError, "no entry point"):
                parse_dat(path)
            document, payloads = parse_dat(path, require_entry=False)

        self.assertIsNone(document["entry"])
        self.assertEqual(document["section_count"], 1)
        self.assertEqual(payloads, [bytes.fromhex("cd ab")])


if __name__ == "__main__":
    unittest.main()
