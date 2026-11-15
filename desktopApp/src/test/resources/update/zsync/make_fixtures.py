#!/usr/bin/env python3
"""
Writes the zsync control files ZsyncFixtures.kt checks against: real zsyncmake output for data the
tests regenerate. Run with zsyncmake on PATH: python3 make_fixtures.py <scratch folder>.
"""
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
MASK = (1 << 64) - 1


def xorshift(seed, size):
    """The same bytes as ZsyncFixtures.xorshift: xorshift64, each state's eight bytes little-endian."""
    state, out = seed, bytearray()
    while len(out) < size:
        state ^= (state << 13) & MASK
        state ^= state >> 7
        state ^= (state << 17) & MASK
        out += state.to_bytes(8, "little")
    return bytes(out[:size])


def sample():
    pattern = b"ViveNotes block " * (50_000 // 16 + 1)
    return xorshift(1, 100_000) + bytes(20_000) + pattern[:50_000] + xorshift(2, 130_017)


def main():
    scratch = Path(sys.argv[1])
    scratch.mkdir(parents=True, exist_ok=True)
    for name, data, block in [("sample.bin", sample(), 2048), ("large.bin", xorshift(3, 26_000_000), 65536)]:
        source = scratch / name
        source.write_bytes(data)
        # A fixed time, so the MTime line does not change between runs.
        os.utime(source, (1_700_000_000, 1_700_000_000))
        subprocess.run(["zsyncmake", "-b", str(block), "-u", f"https://example.com/{name}", "-f", name,
                        "-o", HERE / f"{name}.zsync", source], check=True)


if __name__ == "__main__":
    main()
