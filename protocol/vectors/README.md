# Shared protocol vectors

## M0 timing vectors

`m0_timing.json` is the canonical test input for the Python reference and portable C++17 timing implementation.

## Wire protocol v1 vectors

`wire_protocol.json` is the canonical byte-level fixture set for `docs/06_Protocol_and_Data_Schema.md`.

Coverage:
- All 15 control message types, with payload values in documented wire order.
- A 960-frame PCM16 stereo chunk at sample index 96,000 with the gap flag set.
- A 960-frame silence chunk.
- An unknown safely length-framed type and unknown outer flag bit.
- Truncated prefix, undersized length, oversized frame and truncated body.
- Fixed 38-byte UDP time-sync request/response, including 16-byte request padding, negative signed timestamps and maximum unsigned identifiers.

All multibyte fields are little-endian. Frame decoding validates the prefix, maximum size and exact frame length. Unknown safely framed message types are preserved. Typed message payload validation belongs to the typed codec/session layer. UDP decoding validates size, magic, version and packet type; session and receiver identity validation belongs to the session layer.

Python tests load the JSON directly. CMake generates the C++ vector header from the same JSON through `tools/generate_cpp_wire_vectors.py`; generated headers are build artifacts and must not be committed. Expected bytes must be reviewed against the protocol table before updating.
