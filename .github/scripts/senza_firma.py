"""Toglie il blocco di firma (schemi v2/v3) da un APK firmato: quello che resta va confrontato byte per
byte con l'APK non firmato. Uso: senza_firma.py firmato.apk uscita.apk"""
import struct
import sys

data = open(sys.argv[1], "rb").read()
eocd = data.rfind(b"PK\x05\x06")
cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
if data[cd_offset - 16:cd_offset] != b"APK Sig Block 42":
    sys.exit("nessun blocco di firma v2/v3")
size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
start = cd_offset - size - 8
out = bytearray(data[:start] + data[cd_offset:])
# Il central directory ora comincia dove cominciava il blocco di firma.
struct.pack_into("<I", out, len(out) - (len(data) - eocd) + 16, start)
open(sys.argv[2], "wb").write(out)
print(f"blocco di firma di {cd_offset - start} byte tolto")
