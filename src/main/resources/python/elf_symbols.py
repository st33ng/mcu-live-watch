#!/usr/bin/env python3
"""ELF symbol extractor for the MCU Live Watch CLion plugin.

Reads ``.symtab`` (and DWARF, when present) and prints one symbol per line.

Two output formats:

  * ``--json`` -- one JSON object per line, locale-independent, preferred by the plugin:
        {"name": "htim15", "addr": "0x20000b0c", "size": 76, "type": "TIM_TypeDef"}
  * default -- the original human-readable form:
        htim15 - Адрес: 0x20000b0c - Размер: 76 байт - Тип данных: TIM_TypeDef

Differences from the original main.py:

  * ``--json`` output for reliable parsing (the plugin still accepts the legacy form).
  * A symbol whose DWARF type cannot be resolved no longer aborts the whole run; it is emitted with
    an empty type.
  * ``--demangle`` failures fall back to the raw name instead of raising.
  * ``--min-size`` lets the caller skip zero/oversized entries, which keeps the table usable on
    large binaries.
  * stderr is used for diagnostics only; stdout carries data exclusively.

Requires: pip install pyelftools
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from typing import Dict, List, Optional, Tuple

from elftools.dwarf.descriptions import describe_form_class
from elftools.elf.elffile import ELFFile

ArrayLike = Tuple[str, object]


def demangle(name: str) -> str:
    """Runs c++filt on one symbol; returns the input unchanged when c++filt is unavailable."""
    try:
        result = subprocess.run(
            ["c++filt", name], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5
        )
        return result.stdout.decode("utf-8", errors="replace").strip() or name
    except (OSError, subprocess.SubprocessError):
        return name


def create_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Extract symbols from an ELF file")
    parser.add_argument("-p", "--filepath", metavar="ELFFILE", required=True, help="Input .elf file")
    parser.add_argument("-d", "--demangle", action="store_true", help="Demangle C++ names (slow)")
    parser.add_argument("-t", "--types", action="store_true", help="Add DWARF variable types")
    parser.add_argument("--structures", action="store_true", help="Show structure/class contents")
    parser.add_argument(
        "-s", "--sort", choices=["name", "size", "address", "none"], default="none",
        help="Sort symbols",
    )
    parser.add_argument("--reverse", action="store_true", help="Reverse the sort order")
    parser.add_argument("--amount", type=int, default=None, metavar="N", help="Show only first N")
    parser.add_argument("--find", type=str, default=None, metavar="SUBSTRING", help="Filter by substring")
    parser.add_argument("--json", dest="as_json", action="store_true", help="Emit JSON lines")
    parser.add_argument("--min-size", type=int, default=1, metavar="BYTES", help="Skip smaller symbols")
    return parser


def extract_variable_types(elf: ELFFile, show_structures: bool = False):
    if not elf.has_dwarf_info():
        return {}, {}

    dwarfinfo = elf.get_dwarf_info()
    var_types: Dict[str, str] = {}
    type_details: Dict[str, object] = {}

    def resolve_type(die):
        if die is None:
            return "unknown", None
        if die.tag == "DW_TAG_subprogram":
            return "method", None
        if "DW_AT_name" in die.attributes:
            return die.attributes["DW_AT_name"].value.decode("utf-8", errors="replace"), die
        if "DW_AT_type" in die.attributes:
            type_attr = die.attributes["DW_AT_type"]
            if describe_form_class(type_attr.form) == "reference":
                return resolve_type(die.get_DIE_from_attribute("DW_AT_type"))
        return "unknown", None

    for cu in dwarfinfo.iter_CUs():
        for die in cu.iter_DIEs():
            try:
                if die.tag == "DW_TAG_variable":
                    if "DW_AT_name" in die.attributes and "DW_AT_type" in die.attributes:
                        name = die.attributes["DW_AT_name"].value.decode("utf-8", errors="replace")
                        type_die = die.get_DIE_from_attribute("DW_AT_type")
                        dtype, type_die = resolve_type(type_die)
                        var_types[name] = dtype
                        if type_die is not None and type_die.tag in (
                            "DW_TAG_structure_type", "DW_TAG_class_type",
                        ):
                            type_details[dtype] = type_die
                elif die.tag in (
                    "DW_TAG_structure_type", "DW_TAG_class_type", "DW_TAG_enumeration_type",
                ) and "DW_AT_name" in die.attributes:
                    type_name = die.attributes["DW_AT_name"].value.decode("utf-8", errors="replace")
                    type_details[type_name] = die
            except Exception:
                # A malformed DIE must not abort the whole extraction.
                continue

    return var_types, type_details


def get_type_size(die) -> int:
    if die is None:
        return 0

    size_attr = die.attributes.get("DW_AT_byte_size")
    if size_attr:
        return size_attr.value

    if die.tag == "DW_TAG_pointer_type":
        return 4

    if die.tag == "DW_TAG_array_type":
        type_attr = die.attributes.get("DW_AT_type")
        base_die = resolve_type_die(die, type_attr) if type_attr else None
        base_size = get_type_size(base_die) if base_die else 1
        for child in die.iter_children():
            if child.tag == "DW_TAG_subrange_type":
                upper_attr = child.attributes.get("DW_AT_upper_bound")
                if upper_attr:
                    return base_size * (upper_attr.value + 1)
        return base_size

    if die.tag == "DW_TAG_enumeration_type":
        return 4

    type_attr = die.attributes.get("DW_AT_type")
    if type_attr:
        base_die = resolve_type_die(die, type_attr)
        return get_type_size(base_die)

    return 0


def describe_type(die) -> str:
    if die is None:
        return "<no type>"

    try:
        if die.tag == "DW_TAG_subprogram":
            name_attr = die.attributes.get("DW_AT_name")
            return f"method {name_attr.value.decode()}" if name_attr else "method <anonymous>"

        if die.tag == "DW_TAG_base_type":
            name_attr = die.attributes.get("DW_AT_name")
            return name_attr.value.decode() if name_attr else "<base-type>"

        if die.tag == "DW_TAG_pointer_type":
            base_type_attr = die.attributes.get("DW_AT_type")
            base_die = resolve_type_die(die, base_type_attr) if base_type_attr else None
            return f"{describe_type(base_die)}*"

        if die.tag in ("DW_TAG_structure_type", "DW_TAG_class_type"):
            name_attr = die.attributes.get("DW_AT_name")
            type_name = name_attr.value.decode() if name_attr else "<anonymous>"
            return f"{'class' if die.tag == 'DW_TAG_class_type' else 'struct'} {type_name}"

        if die.tag == "DW_TAG_typedef":
            name_attr = die.attributes.get("DW_AT_name")
            return name_attr.value.decode() if name_attr else "<typedef>"

        if die.tag == "DW_TAG_const_type":
            base_type_attr = die.attributes.get("DW_AT_type")
            base_die = resolve_type_die(die, base_type_attr) if base_type_attr else None
            return f"const {describe_type(base_die)}"

        if die.tag == "DW_TAG_enumeration_type":
            name_attr = die.attributes.get("DW_AT_name")
            return f"enum {name_attr.value.decode()}" if name_attr else "enum <anonymous>"
    except Exception:
        return f"<{die.tag}>"

    return f"<{die.tag}>"


def resolve_type_die(die, type_attr):
    try:
        ref_class = describe_form_class(type_attr.form)
        if ref_class == "reference":
            return die.get_DIE_from_attribute("DW_AT_type")
        if ref_class == "reference_addr":
            ref_addr = type_attr.value
            for cu in die.dwarfinfo.iter_CUs():
                if cu.cu_offset <= ref_addr < cu.cu_offset + cu["unit_length"] + 4:
                    return cu.get_DIE_from_refaddr(ref_addr)
    except Exception:
        return None
    return None


def process_struct_or_class(die, indent: int = 0) -> None:
    name_attr = die.attributes.get("DW_AT_name")
    type_name = name_attr.value.decode() if name_attr else "<anonymous>"
    type_kind = "Class" if die.tag == "DW_TAG_class_type" else "Struct"
    size_attr = die.attributes.get("DW_AT_byte_size")
    type_size = size_attr.value if size_attr else 0

    indent_str = "  " * indent
    print(f"{indent_str}{type_kind} {type_name} (size: {type_size} bytes):")

    for child in die.iter_children():
        if child.tag == "DW_TAG_inheritance":
            type_attr = child.attributes.get("DW_AT_type")
            if type_attr:
                base_die = resolve_type_die(child, type_attr)
                print(f"{indent_str}  Inherits from {describe_type(base_die)}")
                if base_die is not None:
                    process_struct_or_class(base_die, indent + 2)

    for child in die.iter_children():
        if child.tag != "DW_TAG_member":
            continue
        member_name = child.attributes.get("DW_AT_name")
        member_offset = child.attributes.get("DW_AT_data_member_location")
        type_attr = child.attributes.get("DW_AT_type")

        name = member_name.value.decode() if member_name else "<anon>"
        offset = member_offset.value if member_offset else "?"
        typename = "<unknown>"
        type_size = 0

        if type_attr:
            type_die = resolve_type_die(child, type_attr)
            typename = describe_type(type_die)
            type_size = get_type_size(type_die)

            if type_die is not None and type_die.tag in ("DW_TAG_structure_type", "DW_TAG_class_type"):
                print(f"{indent_str}  {name} ({typename}, size: {type_size} bytes) @ offset {offset}")
                process_struct_or_class(type_die, indent + 2)
                continue
            if type_die is not None and type_die.tag == "DW_TAG_enumeration_type":
                print(f"{indent_str}  {name} ({typename}, size: {type_size} bytes) @ offset {offset}")
                process_enumeration_type(type_die, indent + 2)
                continue

        print(f"{indent_str}  {name} ({typename}, size: {type_size} bytes) @ offset {offset}")

    print(f"{indent_str}{type_kind} end")


def process_enumeration_type(die, indent: int = 0) -> None:
    name_attr = die.attributes.get("DW_AT_name")
    enum_name = name_attr.value.decode() if name_attr else "<anonymous>"
    size_attr = die.attributes.get("DW_AT_byte_size")
    enum_size = size_attr.value if size_attr else 0

    indent_str = "  " * indent
    print(f"{indent_str}Enum {enum_name} (size: {enum_size} bytes):")

    for child in die.iter_children():
        if child.tag == "DW_TAG_enumerator":
            enumerator_name = child.attributes["DW_AT_name"].value.decode()
            enumerator_value = child.attributes["DW_AT_const_value"].value
            print(f"{indent_str}  {enumerator_name} = {enumerator_value}")

    print(f"{indent_str}Enum end")


def collect_symbols(args) -> Tuple[List[dict], dict]:
    with open(args.filepath, "rb") as handle:
        elf = ELFFile(handle)

        symbol_table = elf.get_section_by_name(".symtab")
        if symbol_table is None:
            raise ValueError(".symtab section not found in the ELF file")

        variable_types: Dict[str, str] = {}
        type_details: Dict[str, object] = {}
        if args.types or args.structures:
            if elf.has_dwarf_info():
                variable_types, type_details = extract_variable_types(elf, args.structures)
            else:
                print(
                    "ELF file has no DWARF (.debug_info); types are unavailable.",
                    file=sys.stderr,
                )

        symbols = []
        for symbol in symbol_table.iter_symbols():
            symbol_size = symbol["st_size"]
            if symbol_size < max(args.min_size, 1):
                continue

            raw_name = symbol.name
            if not raw_name:
                continue

            symbol_name = demangle(raw_name) if args.demangle else raw_name
            dtype = variable_types.get(raw_name, "") if (args.types or args.structures) else ""

            symbols.append(
                {
                    "name": symbol_name,
                    "addr": hex(symbol["st_value"]),
                    "size": symbol_size,
                    "type": dtype,
                }
            )

    if args.sort != "none":
        key = "addr" if args.sort == "address" else args.sort
        if key == "addr":
            symbols = sorted(symbols, key=lambda s: int(s["addr"], 16), reverse=args.reverse)
        else:
            symbols = sorted(symbols, key=lambda s: s[key], reverse=args.reverse)
        if args.amount is not None:
            symbols = symbols[: args.amount]

    if args.find:
        symbols = [s for s in symbols if args.find in s["name"]]

    return symbols, type_details


def emit(symbols: List[dict], type_details: dict, args) -> None:
    for symbol in symbols:
        if args.as_json:
            # ensure_ascii keeps the line valid even if a symbol contains non-ASCII bytes.
            print(json.dumps(symbol, ensure_ascii=True))
        else:
            print(
                f"{symbol['name']} - Адрес: {symbol['addr']} - Размер: {symbol['size']} байт"
                f" - Тип данных: {symbol['type'] or 'N/A'}"
            )
            if args.structures and symbol["type"] in type_details:
                process_struct_or_class(type_details[symbol["type"]], indent=1)


def main(argv: Optional[List[str]] = None) -> int:
    args = create_parser().parse_args(argv)
    try:
        symbols, type_details = collect_symbols(args)
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    emit(symbols, type_details, args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
