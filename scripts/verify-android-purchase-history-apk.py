#!/usr/bin/env python3
"""Fail unless a release APK keeps purchase-history JSON names through R8.

Gson reads @SerializedName at runtime. Release shrinking renames every other
field, so a purchase-history key that is missing from the annotation does not
survive into the Account screen. This walks the APK's DEX annotations and
requires the BillingStatus and PurchaseHistoryItem keys from the minified build.
"""

import sys
import zipfile


# R8 renames com.google.gson.annotations.SerializedName. Identify it by the
# purchase-history key that was already annotated before the fix, then require
# the keys that release shrinking used to drop.
ANCHOR = "amount_cents"
REQUIRED = ("payments", "currency", "plan", "status", "amount_cents", "created_at")


def uleb128(data, offset):
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, offset
        shift += 7


def mutf8(raw):
    # DEX string data is MUTF-8. Embedded NUL is the two-byte sequence c0 80.
    return raw.replace(b"\xc0\x80", b"\x00").decode("utf-8", "replace")


def read_strings(data):
    string_ids_size = int.from_bytes(data[0x38:0x3C], "little")
    string_ids_off = int.from_bytes(data[0x3C:0x40], "little")
    strings = []
    for index in range(string_ids_size):
        data_off = int.from_bytes(data[string_ids_off + index * 4:string_ids_off + index * 4 + 4], "little")
        _, cursor = uleb128(data, data_off)
        end = data.index(0, cursor)
        strings.append(mutf8(data[cursor:end]))
    return strings


def read_types(data, strings):
    type_ids_size = int.from_bytes(data[0x40:0x44], "little")
    type_ids_off = int.from_bytes(data[0x44:0x48], "little")
    types = []
    for index in range(type_ids_size):
        descriptor_idx = int.from_bytes(data[type_ids_off + index * 4:type_ids_off + index * 4 + 4], "little")
        types.append(strings[descriptor_idx])
    return types


def encoded_value_string(data, offset, strings):
    arg_type = data[offset]
    offset += 1
    value_type = arg_type & 0x1F
    value_arg = arg_type >> 5
    size = value_arg + 1
    raw = data[offset:offset + size]
    offset += size
    if value_type != 0x17:
        return None, offset
    string_idx = int.from_bytes(raw, "little")
    return strings[string_idx], offset


def annotation_entries(data, offset, strings, types):
    """Return (annotation type, string value) pairs from one annotation set."""
    size = int.from_bytes(data[offset:offset + 4], "little")
    offset += 4
    found = []
    for _ in range(size):
        item_off = int.from_bytes(data[offset:offset + 4], "little")
        offset += 4
        cursor = item_off + 1  # skip visibility
        type_idx, cursor = uleb128(data, cursor)
        type_name = types[type_idx]
        element_count, cursor = uleb128(data, cursor)
        for _element in range(element_count):
            _, cursor = uleb128(data, cursor)  # element name
            value, next_cursor = encoded_value_string(data, cursor, strings)
            if value is not None:
                cursor = next_cursor
                found.append((type_name, value))
            else:
                _, cursor = skip_encoded_value(data, cursor)
    return found


def skip_encoded_value(data, offset):
    arg_type = data[offset]
    offset += 1
    value_type = arg_type & 0x1F
    value_arg = arg_type >> 5
    if value_type in (0x00, 0x1E, 0x1F):
        return None, offset
    if value_type == 0x1C:  # array
        count, offset = uleb128(data, offset)
        for _ in range(count):
            _, offset = skip_encoded_value(data, offset)
        return None, offset
    if value_type == 0x1D:  # annotation
        _, offset = uleb128(data, offset)
        count, offset = uleb128(data, offset)
        for _ in range(count):
            _, offset = uleb128(data, offset)
            _, offset = skip_encoded_value(data, offset)
        return None, offset
    return None, offset + value_arg + 1


def serialized_names(dex):
    strings = read_strings(dex)
    types = read_types(dex, strings)
    class_defs_size = int.from_bytes(dex[0x60:0x64], "little")
    class_defs_off = int.from_bytes(dex[0x64:0x68], "little")
    names = []
    for index in range(class_defs_size):
        base = class_defs_off + index * 32
        annotations_off = int.from_bytes(dex[base + 20:base + 24], "little")
        if annotations_off == 0:
            continue
        fields_size = int.from_bytes(dex[annotations_off + 4:annotations_off + 8], "little")
        cursor = annotations_off + 16
        for _ in range(fields_size):
            cursor += 4  # field_idx
            annotations_set_off = int.from_bytes(dex[cursor:cursor + 4], "little")
            cursor += 4
            if annotations_set_off:
                names.extend(annotation_entries(dex, annotations_set_off, strings, types))
    return names


def main(argv):
    if len(argv) != 2:
        print("usage: verify-android-purchase-history-apk.py <apk>", file=sys.stderr)
        return 2
    entries = []
    with zipfile.ZipFile(argv[1]) as apk:
        for entry in apk.namelist():
            if entry.startswith("classes") and entry.endswith(".dex"):
                entries.extend(serialized_names(apk.read(entry)))
    anchor_types = {type_name for type_name, value in entries if value == ANCHOR}
    if len(anchor_types) != 1:
        print(f"expected one annotation type for {ANCHOR}, found {sorted(anchor_types)}", file=sys.stderr)
        return 1
    kept = {value for type_name, value in entries if type_name in anchor_types}
    missing = [key for key in REQUIRED if key not in kept]
    if missing:
        print("release APK dropped purchase-history SerializedName values: " + ", ".join(missing), file=sys.stderr)
        return 1
    print("purchase-history SerializedName values kept: " + ", ".join(REQUIRED))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
