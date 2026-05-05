from __future__ import annotations

import argparse
import csv
import hashlib
import os
import re
import shutil
import struct
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_WORKER_YSM_ROOT = REPO_ROOT / "test-server" / "freesia-worker" / "config" / "yes_steve_model"
DEFAULT_FIXTURE = REPO_ROOT / "test-server" / "direct-paper" / "plugins" / "PaperYSM" / "captures" / "native-cache" / "freesia-from-velocity"
DEFAULT_SNAPSHOT_DIR = REPO_ROOT / "test-server" / "freesia-worker" / "cache-export-snapshots"
TYPE3_ENTRY_PRELUDE = bytes.fromhex("f8e893f095701958b7e215")
KEY_BYTES = 56
MASK64 = (1 << 64) - 1


@dataclass
class CacheRow:
    token_hex: str
    file: str
    name: str
    bytes: int
    format: int = 0


@dataclass
class ModelFile:
    path: Path
    relative: str
    sha256: str
    bytes: int


@dataclass
class CacheFile:
    path: Path
    original_name: str
    bytes: int
    mtime: float
    sha256: str
    format: int


@dataclass
class Type3Entry:
    row: CacheRow
    token: bytes
    name: str
    flag_a: int
    flag_b: int
    format: int
    start: int
    end: int


class MT19937_64:
    NN = 312
    MM = 156
    MATRIX_A = 0xB5026F5AA96619E9
    UM = 0xFFFFFFFF80000000
    LM = 0x7FFFFFFF

    def __init__(self, seed: int) -> None:
        self.mt = [0] * self.NN
        self.index = self.NN + 1
        self.seed(seed)

    def seed(self, seed: int) -> None:
        self.mt[0] = seed & MASK64
        for i in range(1, self.NN):
            prev = self.mt[i - 1]
            self.mt[i] = (6364136223846793005 * (prev ^ (prev >> 62)) + i) & MASK64
        self.index = self.NN

    def next_long(self) -> int:
        if self.index >= self.NN:
            self.twist()
        x = self.mt[self.index]
        self.index += 1
        x ^= (x >> 29) & 0x5555555555555555
        x ^= (x << 17) & 0x71D67FFFEDA60000
        x &= MASK64
        x ^= (x << 37) & 0xFFF7EEE000000000
        x &= MASK64
        x ^= x >> 43
        return x & MASK64

    def twist(self) -> None:
        for i in range(0, self.NN - self.MM):
            x = (self.mt[i] & self.UM) | (self.mt[i + 1] & self.LM)
            self.mt[i] = (self.mt[i + self.MM] ^ (x >> 1) ^ (0 if (x & 1) == 0 else self.MATRIX_A)) & MASK64
        for i in range(self.NN - self.MM, self.NN - 1):
            x = (self.mt[i] & self.UM) | (self.mt[i + 1] & self.LM)
            self.mt[i] = (self.mt[i + (self.MM - self.NN)] ^ (x >> 1) ^ (0 if (x & 1) == 0 else self.MATRIX_A)) & MASK64
        x = (self.mt[self.NN - 1] & self.UM) | (self.mt[0] & self.LM)
        self.mt[self.NN - 1] = (self.mt[self.MM - 1] ^ (x >> 1) ^ (0 if (x & 1) == 0 else self.MATRIX_A)) & MASK64
        self.index = 0


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_segment(value: str, fallback: str = "item") -> str:
    cleaned = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value).strip().rstrip(".")
    return (cleaned or fallback)[:96]


def fixture_rel(parts: list[str]) -> str:
    return "/".join(safe_segment(part) for part in parts if part)


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while shift < 70:
        if offset >= len(data):
            raise ValueError("unexpected end of varint")
        b = data[offset]
        offset += 1
        value |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return value, offset
        shift += 7
    raise ValueError("varint too large")


def write_varint(value: int) -> bytes:
    remaining = value & MASK64
    out = bytearray()
    while (remaining & ~0x7F) != 0:
        out.append((remaining & 0x7F) | 0x80)
        remaining >>= 7
    out.append(remaining & 0x7F)
    return bytes(out)


def write_string(value: str) -> bytes:
    data = value.encode("utf-8")
    return write_varint(len(data)) + data


def read_string(data: bytes, offset: int) -> tuple[str, int]:
    length, offset = read_varint(data, offset)
    if length <= 0 or length > 4096:
        raise ValueError(f"bad string length {length}")
    end = offset + length
    if end > len(data):
        raise ValueError("string exceeds data")
    return data[offset:end].decode("utf-8"), end


def read_cache_format(path: Path) -> int:
    data = path.read_bytes()[:64]
    offset = 0
    values = []
    for _ in range(5):
        value, offset = read_varint(data, offset)
        values.append(value)
    return int(values[4])


def derive_hashes_from_cache_name(name: str, runtime_key: bytes) -> tuple[int, int]:
    if not re.fullmatch(r"[0-9a-fA-F]{40}", name):
        raise ValueError(f"worker cache file name is not 40 hex chars: {name}")
    buffer = bytearray.fromhex(name)
    for i in range(len(buffer)):
        buffer[i] ^= runtime_key[i % len(runtime_key)]
    seed = struct.unpack_from("<I", buffer, 0)[0]
    mt = MT19937_64(seed)
    first = struct.unpack_from("<Q", buffer, 4)[0] ^ mt.next_long()
    second = struct.unpack_from("<Q", buffer, 12)[0] ^ mt.next_long()
    return first & MASK64, second & MASK64


def token_from_cache_name(name: str, runtime_key: bytes) -> bytes:
    first, second = derive_hashes_from_cache_name(name, runtime_key)
    return write_varint(first) + write_varint(second)


def read_cache_map(path: Path) -> list[CacheRow]:
    rows: list[CacheRow] = []
    if not path.exists():
        return rows
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        for row in reader:
            rows.append(CacheRow(
                token_hex=row["tokenHex"].strip().lower(),
                file=row["file"].strip(),
                name=row["name"].strip(),
                bytes=int(row["bytes"]),
            ))
    return rows


def write_cache_map(path: Path, rows: list[CacheRow]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(["tokenHex", "file", "name", "bytes"])
        for row in rows:
            writer.writerow([row.token_hex, row.file, row.name, row.bytes])


def find_first_token_offset(type3: bytes, rows: list[CacheRow]) -> int:
    token_values = [bytes.fromhex(row.token_hex) for row in rows]
    for offset in range(KEY_BYTES * 2, min(len(type3), KEY_BYTES * 2 + 128)):
        if any(type3.startswith(token, offset) for token in token_values):
            return offset
    raise ValueError("could not find first token offset in type3-body.bin")


def parse_existing_manifest(type3: bytes, rows: list[CacheRow]) -> tuple[int, dict[str, int]]:
    offset, entries, _tail = parse_manifest_entries(type3, rows)
    return offset, {entry.row.token_hex: entry.format for entry in entries}


def parse_manifest_entries(type3: bytes, rows: list[CacheRow]) -> tuple[int, list[Type3Entry], bytes]:
    token_to_row = {bytes.fromhex(row.token_hex): row for row in rows}
    offset = find_first_token_offset(type3, rows)
    start = offset
    entries: list[Type3Entry] = []
    formats: dict[str, int] = {}
    tokens = sorted(token_to_row, key=len, reverse=True)
    while offset < len(type3):
        matched = None
        for token in tokens:
            if type3.startswith(token, offset):
                matched = token
                break
        if matched is None:
            break
        row = token_to_row[matched]
        cursor = offset + len(matched)
        name, cursor = read_string(type3, cursor)
        flag_a, cursor = read_varint(type3, cursor)
        flag_b, cursor = read_varint(type3, cursor)
        fmt, cursor = read_varint(type3, cursor)
        row.format = int(fmt)
        entries.append(Type3Entry(
            row=row,
            token=matched,
            name=name,
            flag_a=int(flag_a),
            flag_b=int(flag_b),
            format=int(fmt),
            start=offset,
            end=cursor,
        ))
        offset = cursor
    return start, entries, type3[offset:]


def encode_manifest_entry(token: bytes, name: str, fmt: int) -> bytes:
    return token + write_string(name) + write_varint(0) + write_varint(0) + write_varint(fmt)


def scan_models(source: Path) -> tuple[list[ModelFile], list[ModelFile]]:
    files = sorted(source.rglob("*.ysm"), key=lambda p: str(p).lower())
    seen: set[str] = set()
    unique: list[ModelFile] = []
    duplicates: list[ModelFile] = []
    for path in files:
        digest = sha256_file(path)
        model = ModelFile(
            path=path,
            relative=path.relative_to(source).as_posix(),
            sha256=digest,
            bytes=path.stat().st_size,
        )
        if digest in seen:
            duplicates.append(model)
            continue
        seen.add(digest)
        unique.append(model)
    return unique, duplicates


def scan_cache_files(cache_dir: Path) -> list[CacheFile]:
    files = [path for path in cache_dir.iterdir() if path.is_file() and re.fullmatch(r"[0-9a-fA-F]{40}", path.name)]
    files.sort(key=lambda p: (p.stat().st_mtime, p.name))
    return [
        CacheFile(
            path=path,
            original_name=path.name.lower(),
            bytes=path.stat().st_size,
            mtime=path.stat().st_mtime,
            sha256=sha256_file(path),
            format=read_cache_format(path),
        )
        for path in files
    ]


def backup(path: Path) -> Path | None:
    if not path.exists():
        return None
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    backup_path = path.with_name(path.name + f".bak-{stamp}")
    shutil.copy2(path, backup_path)
    return backup_path


def write_tsv(path: Path, header: list[str], rows: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=header, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def read_tsv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open("r", encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def action_scan(args: argparse.Namespace) -> int:
    source = args.model_source_dir
    unique, duplicates = scan_models(source)
    print(f"source={source}")
    print(f"uniqueModels={len(unique)} duplicateModels={len(duplicates)}")
    for item in duplicates[:20]:
        print(f"duplicate\t{item.relative}\t{item.sha256}")
    return 0


def action_snapshot(args: argparse.Namespace) -> int:
    cache_dir = args.worker_cache_dir
    rows = []
    for item in scan_cache_files(cache_dir):
        rows.append({
            "name": item.original_name,
            "bytes": item.bytes,
            "mtime": item.mtime,
            "sha256": item.sha256,
        })
    args.snapshot_dir.mkdir(parents=True, exist_ok=True)
    out = args.snapshot_dir / f"{args.snapshot_name}.tsv"
    if not args.dry_run:
        write_tsv(out, ["name", "bytes", "mtime", "sha256"], rows)
    print(f"snapshot={out} cacheFiles={len(rows)} dryRun={args.dry_run}")
    return 0


def action_fixture(args: argparse.Namespace) -> int:
    fixture = args.fixture_dir
    cache_map_path = fixture / "cache-map.tsv"
    type3_path = fixture / "type3-body.bin"
    if not cache_map_path.exists() or not type3_path.exists():
        raise FileNotFoundError("fixture needs cache-map.tsv and type3-body.bin")

    old_rows = read_cache_map(cache_map_path)
    type3 = type3_path.read_bytes()
    entry_start, old_entries, source_tail = parse_manifest_entries(type3, old_rows)
    old_formats = {entry.row.token_hex: entry.format for entry in old_entries}
    prefix = type3[:entry_start]
    server_cache_key = prefix[:KEY_BYTES]
    tail_type3, tail_map = resolve_tail_paths(args, fixture)
    if tail_type3 is not None:
        if tail_map is None:
            raise RuntimeError("--tail-cache-map-path is required with --tail-type3-path")
        manifest_tail = load_type3_tail(tail_type3, tail_map)
    elif getattr(args, "auto_tail_backup", False) and len(source_tail) <= 1:
        tail_type3 = latest_backup(type3_path)
        tail_map = latest_backup(cache_map_path)
        manifest_tail = load_type3_tail(tail_type3, tail_map) if tail_type3 and tail_map else source_tail
    elif getattr(args, "drop_tail", False):
        manifest_tail = write_varint(0)
    else:
        manifest_tail = source_tail

    remove_needles = tuple(args.remove_name)
    kept_rows = [row for row in old_rows if not any(needle in row.name for needle in remove_needles)]
    for row in kept_rows:
        row.format = old_formats.get(row.token_hex, 0)

    models = getattr(args, "models_override", None)
    duplicates = getattr(args, "duplicates_override", None)
    if models is None or duplicates is None:
        models, duplicates = scan_models(args.model_source_dir)
    cache_files = getattr(args, "cache_files_override", None)
    if cache_files is None:
        cache_files = scan_cache_files(args.cache_dir)
    if len(cache_files) != len(models) and not args.allow_mismatch:
        raise RuntimeError(
            f"cache/model count mismatch: cacheFiles={len(cache_files)} uniqueModels={len(models)}. "
            "Use --allow-mismatch to pair by current order."
        )

    pair_count = min(len(cache_files), len(models))
    if pair_count == 0:
        raise RuntimeError("no cache/model pairs found")

    group = safe_segment(args.group)
    new_rows: list[CacheRow] = []
    report_rows: list[dict[str, object]] = []
    for index in range(pair_count):
        cache = cache_files[index]
        model = models[index]
        token = token_from_cache_name(cache.original_name, server_cache_key)
        rel_without_ext = model.relative[:-4] if model.relative.lower().endswith(".ysm") else model.relative
        model_name = f"{args.group}/{model.relative}"
        dest_rel = fixture_rel(["server-cache", group, *Path(rel_without_ext).parts]) + f"--{cache.original_name}.bin"
        dest_abs = fixture / Path(dest_rel.replace("/", os.sep))
        if not args.dry_run:
            dest_abs.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(cache.path, dest_abs)
        new_rows.append(CacheRow(
            token_hex=token.hex(),
            file=dest_rel,
            name=model_name,
            bytes=cache.bytes,
            format=cache.format,
        ))
        report_rows.append({
            "index": index,
            "model": model.relative,
            "modelSha256": model.sha256,
            "cacheFile": cache.original_name,
            "cacheSha256": cache.sha256,
            "cacheBytes": cache.bytes,
            "tokenHex": token.hex(),
            "format": cache.format,
            "paperFile": dest_rel,
        })

    manifest = bytearray(prefix)
    for row in kept_rows + new_rows:
        manifest += encode_manifest_entry(bytes.fromhex(row.token_hex), row.name, row.format)
    manifest += manifest_tail

    if not args.dry_run:
        backup(cache_map_path)
        backup(type3_path)
        write_cache_map(cache_map_path, kept_rows + new_rows)
        type3_path.write_bytes(bytes(manifest))
        report = fixture / "worker-cache-batches" / f"{group}-{datetime.now().strftime('%Y%m%d-%H%M%S')}.tsv"
        write_tsv(report, [
            "index", "model", "modelSha256", "cacheFile", "cacheSha256",
            "cacheBytes", "tokenHex", "format", "paperFile",
        ], report_rows)
        if getattr(args, "update_registry", False):
            registry_file = args.registry_file
            existing = read_tsv(registry_file)
            seen_hashes = {
                row["modelSha256"].strip().lower()
                for row in existing
                if row.get("modelSha256")
            }
            now = datetime.now().isoformat(timespec="seconds")
            for row in report_rows:
                model_hash = str(row["modelSha256"]).lower()
                if model_hash in seen_hashes:
                    continue
                existing.append({
                    "group": args.group,
                    "model": str(row["model"]),
                    "modelSha256": model_hash,
                    "cacheFile": str(row["cacheFile"]),
                    "cacheSha256": str(row["cacheSha256"]),
                    "paperFile": str(row["paperFile"]),
                    "registeredAt": now,
                })
                seen_hashes.add(model_hash)
            write_tsv(registry_file, [
                "group", "model", "modelSha256", "cacheFile", "cacheSha256", "paperFile", "registeredAt",
            ], existing)
    else:
        report = fixture / "worker-cache-batches" / f"{group}-dry-run.tsv"

    print(f"fixture={fixture}")
    print(f"keptOldRows={len(kept_rows)} removedOldRows={len(old_rows) - len(kept_rows)}")
    print(f"newRows={len(new_rows)} duplicateModels={len(duplicates)} unusedModels={len(models) - pair_count} unusedCacheFiles={len(cache_files) - pair_count}")
    print(f"type3Bytes={len(manifest)} tailBytes={len(manifest_tail)} cacheMapRows={len(kept_rows) + len(new_rows)} dryRun={args.dry_run}")
    print(f"report={report}")
    if len(models) != len(cache_files):
        print("warning=cache/model pairing used current order because counts differ")
    return 0


def action_export(args: argparse.Namespace) -> int:
    snapshot_file = args.snapshot_dir / f"{args.snapshot_name}.tsv"
    if not snapshot_file.exists():
        raise FileNotFoundError(f"snapshot not found: {snapshot_file}")

    registry_rows = read_tsv(args.registry_file)
    registered_hashes = {
        row["modelSha256"].strip().lower()
        for row in registry_rows
        if row.get("modelSha256")
    }
    registered_cache_names = {
        row["cacheFile"].strip().lower()
        for row in registry_rows
        if row.get("cacheFile")
    }
    registered_cache_hashes = {
        row["cacheSha256"].strip().lower()
        for row in registry_rows
        if row.get("cacheSha256")
    }

    baseline = read_tsv(snapshot_file)
    baseline_names = {
        row["name"].strip().lower()
        for row in baseline
        if row.get("name")
    }
    baseline_hashes = {
        row["sha256"].strip().lower()
        for row in baseline
        if row.get("sha256")
    }
    cache_files = [
        item
        for item in scan_cache_files(args.worker_cache_dir)
        if item.original_name.lower() not in baseline_names
        and item.sha256.lower() not in baseline_hashes
        and item.original_name.lower() not in registered_cache_names
        and item.sha256.lower() not in registered_cache_hashes
    ]

    models, duplicates = scan_models(args.model_source_dir)
    duplicate_registered = [
        model
        for model in models
        if model.sha256.lower() in registered_hashes
    ]
    if registered_hashes and not args.force_duplicate_models:
        models = [
            model
            for model in models
            if model.sha256.lower() not in registered_hashes
        ]

    if not cache_files:
        print(f"snapshot={snapshot_file}")
        print(f"workerCacheNewFiles=0 duplicateRegisteredModels={len(duplicate_registered)}")
        print("No new worker cache files to export.")
        return 0

    args.cache_files_override = cache_files
    args.models_override = models
    args.duplicates_override = duplicates
    args.update_registry = True
    result = action_fixture(args)
    print(f"snapshot={snapshot_file}")
    print(f"workerCacheNewFiles={len(cache_files)} duplicateRegisteredModels={len(duplicate_registered)}")
    return result


def type3_paths(args: argparse.Namespace) -> tuple[Path, Path, Path]:
    fixture = args.fixture_dir
    type3_path = args.type3_path or fixture / "type3-body.bin"
    cache_map_path = args.cache_map_path or fixture / "cache-map.tsv"
    return fixture, type3_path.resolve(), cache_map_path.resolve()


def latest_backup(path: Path) -> Path | None:
    candidates = sorted(
        path.parent.glob(path.name + ".bak-*"),
        key=lambda p: p.stat().st_mtime,
        reverse=True)
    return candidates[0] if candidates else None


def load_type3_tail(type3_path: Path, cache_map_path: Path) -> bytes:
    rows = read_cache_map(cache_map_path)
    body = type3_path.read_bytes()
    _entry_start, _entries, tail = parse_manifest_entries(body, rows)
    return tail


def resolve_tail_paths(args: argparse.Namespace, fixture: Path) -> tuple[Path | None, Path | None]:
    tail_type3 = getattr(args, "tail_type3_path", None)
    tail_map = getattr(args, "tail_cache_map_path", None)
    if tail_type3 is None and getattr(args, "auto_tail_backup", False):
        tail_type3 = latest_backup(fixture / "type3-body.bin")
    if tail_map is None and getattr(args, "auto_tail_backup", False):
        tail_map = latest_backup(fixture / "cache-map.tsv")
    if tail_type3 is not None:
        tail_type3 = tail_type3.resolve()
    if tail_map is not None:
        tail_map = tail_map.resolve()
    return tail_type3, tail_map


def print_type3_summary(
        fixture: Path,
        type3_path: Path,
        cache_map_path: Path,
        rows: list[CacheRow],
        body: bytes,
        entry_start: int,
        entries: list[Type3Entry],
        tail: bytes) -> None:
    missing_files = 0
    byte_mismatches = 0
    format_mismatches = 0
    name_mismatches = 0
    long_names = []
    token_lengths: dict[int, int] = {}
    formats: dict[int, int] = {}
    for entry in entries:
        row = entry.row
        token_lengths[len(entry.token)] = token_lengths.get(len(entry.token), 0) + 1
        formats[entry.format] = formats.get(entry.format, 0) + 1
        if row.name != entry.name:
            name_mismatches += 1
        name_bytes = len(entry.name.encode("utf-8"))
        if name_bytes > 80:
            long_names.append((name_bytes, len(entry.name), entry.name))
        cache_path = fixture / Path(row.file.replace("/", os.sep))
        if not cache_path.exists():
            missing_files += 1
            continue
        actual_bytes = cache_path.stat().st_size
        if actual_bytes != row.bytes:
            byte_mismatches += 1
        try:
            cache_format = read_cache_format(cache_path)
            if cache_format != entry.format:
                format_mismatches += 1
        except Exception:
            format_mismatches += 1

    print(f"fixture={fixture}")
    print(f"type3={type3_path} bytes={len(body)}")
    print(f"cacheMap={cache_map_path} rows={len(rows)}")
    print(f"entryStart={entry_start} prefixBytes={entry_start} prelude={body[KEY_BYTES * 2:entry_start].hex()}")
    print(f"entriesParsed={len(entries)} tailBytes={len(tail)} tailHex={tail[:32].hex()}")
    if b"RIFF" in tail and b"WEBP" in tail:
        print("tailLooksLike=webp-metadata")
    print(f"tokenLengths={dict(sorted(token_lengths.items()))}")
    print(f"formats={dict(sorted(formats.items()))}")
    print(f"nameMismatches={name_mismatches} missingFiles={missing_files} byteMismatches={byte_mismatches} formatMismatches={format_mismatches}")
    print(f"nameBytesOver80={len(long_names)}")
    for name_bytes, name_chars, name in sorted(long_names, reverse=True)[:12]:
        print(f"longName bytes={name_bytes} chars={name_chars} name={name}")


def action_type3_inspect(args: argparse.Namespace) -> int:
    fixture, type3_path, cache_map_path = type3_paths(args)
    rows = read_cache_map(cache_map_path)
    body = type3_path.read_bytes()
    entry_start, entries, tail = parse_manifest_entries(body, rows)
    print_type3_summary(fixture, type3_path, cache_map_path, rows, body, entry_start, entries, tail)

    if args.report:
        report_rows = []
        for index, entry in enumerate(entries):
            cache_path = fixture / Path(entry.row.file.replace("/", os.sep))
            report_rows.append({
                "index": index,
                "start": entry.start,
                "end": entry.end,
                "tokenHex": entry.row.token_hex,
                "tokenBytes": len(entry.token),
                "name": entry.name,
                "nameChars": len(entry.name),
                "nameBytes": len(entry.name.encode("utf-8")),
                "format": entry.format,
                "flagA": entry.flag_a,
                "flagB": entry.flag_b,
                "cacheFile": entry.row.file,
                "cacheBytes": entry.row.bytes,
                "fileExists": cache_path.exists(),
            })
        write_tsv(args.report, [
            "index", "start", "end", "tokenHex", "tokenBytes", "name", "nameChars", "nameBytes",
            "format", "flagA", "flagB", "cacheFile", "cacheBytes", "fileExists",
        ], report_rows)
        print(f"report={args.report}")
    return 0


def type3_slice_name(name: str, mode: str, group: str) -> str:
    if mode == "original":
        return name
    stripped = name
    prefix = group.rstrip("/") + "/"
    if stripped.startswith(prefix):
        stripped = stripped[len(prefix):]
    if mode == "drop-group":
        return stripped
    if mode == "basename":
        return Path(stripped.replace("\\", "/")).name
    if mode == "basename-no-ext":
        base = Path(stripped.replace("\\", "/")).name
        return base[:-4] if base.lower().endswith(".ysm") else base
    raise ValueError(f"unknown name mode: {mode}")


def action_type3_slice(args: argparse.Namespace) -> int:
    fixture, type3_path, cache_map_path = type3_paths(args)
    rows = read_cache_map(cache_map_path)
    body = type3_path.read_bytes()
    entry_start, entries, source_tail = parse_manifest_entries(body, rows)
    prefix = body[:entry_start]
    tail_type3, tail_map = resolve_tail_paths(args, fixture)
    if tail_type3 is not None:
        if tail_map is None:
            raise RuntimeError("--tail-cache-map-path is required with --tail-type3-path")
        tail = load_type3_tail(tail_type3, tail_map)
    elif args.preserve_tail:
        tail = source_tail
    else:
        tail = write_varint(0)

    old_entries = [
        entry
        for entry in entries
        if not entry.name.startswith(args.group.rstrip("/") + "/")
    ]
    group_entries = [
        entry
        for entry in entries
        if entry.name.startswith(args.group.rstrip("/") + "/")
    ]
    if args.match:
        group_entries = [entry for entry in group_entries if args.match in entry.name]
    selected_group = group_entries[args.start:]
    if args.limit is not None:
        selected_group = selected_group[:args.limit]
    selected = (old_entries if args.include_old else []) + selected_group
    if not selected:
        raise RuntimeError("type3 slice selected no entries")

    dest = Path(args.dest)
    if not dest.is_absolute():
        dest = fixture.parent / dest
    dest = dest.resolve()
    if dest == fixture.resolve():
        raise RuntimeError("refusing to write a type3 slice over the source fixture")
    if dest.exists() and not args.overwrite and not args.dry_run:
        raise RuntimeError(f"destination exists, use --overwrite: {dest}")

    new_rows: list[CacheRow] = []
    manifest = bytearray(prefix)
    for entry in selected:
        new_name = type3_slice_name(entry.name, args.name_mode, args.group)
        manifest += encode_manifest_entry(entry.token, new_name, entry.format)
        row = CacheRow(
            token_hex=entry.row.token_hex,
            file=entry.row.file,
            name=new_name,
            bytes=entry.row.bytes,
            format=entry.format,
        )
        new_rows.append(row)
    manifest += tail

    if not args.dry_run:
        if dest.exists() and args.overwrite:
            backup(dest / "cache-map.tsv")
            backup(dest / "type3-body.bin")
        dest.mkdir(parents=True, exist_ok=True)
        for small in ("type1-padding.txt", "type3-padding.txt"):
            src = fixture / small
            if src.exists():
                shutil.copy2(src, dest / small)
        for row in new_rows:
            src = fixture / Path(row.file.replace("/", os.sep))
            dst = dest / Path(row.file.replace("/", os.sep))
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
        write_cache_map(dest / "cache-map.tsv", new_rows)
        (dest / "type3-body.bin").write_bytes(bytes(manifest))

    print(f"source={fixture}")
    print(f"dest={dest}")
    print(f"selectedEntries={len(selected)} oldEntries={len(old_entries) if args.include_old else 0} groupEntries={len(selected_group)}")
    print(f"type3Bytes={len(manifest)} tailBytes={len(tail)} nameMode={args.name_mode} dryRun={args.dry_run}")
    print(f"testCommand=/ysm source default {dest.name}")
    print("thenCommand=/ysm sync")
    return 0


def action_type3_rebuild(args: argparse.Namespace) -> int:
    fixture, type3_path, cache_map_path = type3_paths(args)
    rows = read_cache_map(cache_map_path)
    body = type3_path.read_bytes()
    entry_start, entries, source_tail = parse_manifest_entries(body, rows)
    prefix = body[:entry_start]
    format_by_token = {entry.row.token_hex: entry.format for entry in entries}

    tail_type3, tail_map = resolve_tail_paths(args, fixture)
    if tail_type3 is not None:
        if tail_map is None:
            raise RuntimeError("--tail-cache-map-path is required with --tail-type3-path")
        tail = load_type3_tail(tail_type3, tail_map)
    elif args.preserve_tail:
        tail = source_tail
    else:
        tail = write_varint(0)
    if len(tail) <= 1 and args.require_rich_tail:
        raise RuntimeError("selected type3 tail is empty/simple; use --tail-type3-path or --auto-tail-backup")

    manifest = bytearray(prefix)
    rebuilt_rows: list[CacheRow] = []
    for row in rows:
        fmt = format_by_token.get(row.token_hex, row.format)
        manifest += encode_manifest_entry(bytes.fromhex(row.token_hex), row.name, fmt)
        rebuilt_rows.append(CacheRow(row.token_hex, row.file, row.name, row.bytes, fmt))
    manifest += tail

    dest = Path(args.dest) if args.dest else fixture
    if not dest.is_absolute():
        dest = fixture.parent / dest
    dest = dest.resolve()
    overwrite_source = dest == fixture.resolve()
    if dest.exists() and not overwrite_source and not args.overwrite and not args.dry_run:
        raise RuntimeError(f"destination exists, use --overwrite: {dest}")

    if not args.dry_run:
        dest.mkdir(parents=True, exist_ok=True)
        if overwrite_source:
            backup(cache_map_path)
            backup(type3_path)
        else:
            for small in ("type1-padding.txt", "type3-padding.txt"):
                src = fixture / small
                if src.exists():
                    shutil.copy2(src, dest / small)
            for row in rebuilt_rows:
                src = fixture / Path(row.file.replace("/", os.sep))
                dst = dest / Path(row.file.replace("/", os.sep))
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src, dst)
        write_cache_map(dest / "cache-map.tsv", rebuilt_rows)
        (dest / "type3-body.bin").write_bytes(bytes(manifest))

    print(f"source={fixture}")
    print(f"dest={dest}")
    print(f"entries={len(rebuilt_rows)} type3Bytes={len(manifest)} tailBytes={len(tail)} dryRun={args.dry_run}")
    if tail_type3 is not None:
        print(f"tailFrom={tail_type3}")
    print(f"testCommand=/ysm source default {dest.name}")
    print("thenCommand=/ysm sync")
    return 0


def normalize_legacy_args(argv: list[str]) -> list[str]:
    legacy = {
        "-Group": "--group",
        "-WorkerYsmRoot": "--worker-ysm-root",
        "-WorkerCacheDir": "--worker-cache-dir",
        "-ModelSourceDir": "--model-source-dir",
        "-PaperFixtureDir": "--fixture-dir",
        "-SnapshotDir": "--snapshot-dir",
        "-SnapshotName": "--snapshot-name",
        "-DryRun": "--dry-run",
        "-AllowMismatchedCounts": "--allow-mismatch",
        "-ForceDuplicateModels": "--force-duplicate-models",
        "-Dest": "--dest",
        "-Limit": "--limit",
        "-Start": "--start",
        "-Match": "--match",
        "-NameMode": "--name-mode",
        "-IncludeOld": "--include-old",
        "-Overwrite": "--overwrite",
        "-Report": "--report",
        "-Type3Path": "--type3-path",
        "-CacheMapPath": "--cache-map-path",
        "-TailType3Path": "--tail-type3-path",
        "-TailCacheMapPath": "--tail-cache-map-path",
        "-AutoTailBackup": "--auto-tail-backup",
        "-PreserveTail": "--preserve-tail",
        "-RequireRichTail": "--require-rich-tail",
        "-DropTail": "--drop-tail",
    }
    out = []
    for item in argv:
        out.append(legacy.get(item, item))
    return out


def resolve_args(argv: list[str]) -> argparse.Namespace:
    argv = normalize_legacy_args(argv)
    parser = argparse.ArgumentParser(description="PaperYSM worker cache batch helper")
    sub = parser.add_subparsers(dest="action", required=True)

    def add_common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--group", default="R18模型整合")
        p.add_argument("--worker-ysm-root", type=Path, default=DEFAULT_WORKER_YSM_ROOT)
        p.add_argument("--model-source-dir", type=Path)
        p.add_argument("--fixture-dir", type=Path, default=DEFAULT_FIXTURE)
        p.add_argument("--dry-run", action="store_true")

    scan = sub.add_parser("scan")
    add_common(scan)
    scan.set_defaults(func=action_scan)

    snapshot = sub.add_parser("snapshot")
    snapshot.add_argument("--worker-cache-dir", type=Path)
    snapshot.add_argument("--snapshot-dir", type=Path, default=DEFAULT_SNAPSHOT_DIR)
    snapshot.add_argument("--snapshot-name", default="baseline")
    snapshot.add_argument("--dry-run", action="store_true")
    snapshot.set_defaults(func=action_snapshot)

    export = sub.add_parser("export")
    add_common(export)
    export.add_argument("--worker-cache-dir", type=Path)
    export.add_argument("--snapshot-dir", type=Path, default=DEFAULT_SNAPSHOT_DIR)
    export.add_argument("--snapshot-name", default="baseline")
    export.add_argument("--registry-file", type=Path)
    export.add_argument("--allow-mismatch", action="store_true", default=True)
    export.add_argument("--force-duplicate-models", action="store_true")
    export.add_argument("--remove-name", action="append", default=["雪风2.4.2.ysm", "拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm"])
    export.add_argument("--drop-tail", action="store_true")
    export.add_argument("--auto-tail-backup", action="store_true", default=True)
    export.add_argument("--tail-type3-path", type=Path)
    export.add_argument("--tail-cache-map-path", type=Path)
    export.set_defaults(func=action_export)

    fixture = sub.add_parser("fixture")
    add_common(fixture)
    fixture.add_argument("--cache-dir", type=Path)
    fixture.add_argument("--snapshot-name", default="")
    fixture.add_argument("--allow-mismatch", action="store_true")
    fixture.add_argument("--remove-name", action="append", default=["雪风2.4.2.ysm", "拉菲Ⅱ/拉菲Ⅱ_v1.2.ysm"])
    fixture.add_argument("--drop-tail", action="store_true")
    fixture.add_argument("--auto-tail-backup", action="store_true")
    fixture.add_argument("--tail-type3-path", type=Path)
    fixture.add_argument("--tail-cache-map-path", type=Path)
    fixture.set_defaults(func=action_fixture)

    type3_inspect = sub.add_parser("type3-inspect")
    add_common(type3_inspect)
    type3_inspect.add_argument("--type3-path", type=Path)
    type3_inspect.add_argument("--cache-map-path", type=Path)
    type3_inspect.add_argument("--report", type=Path)
    type3_inspect.set_defaults(func=action_type3_inspect)

    type3_slice = sub.add_parser("type3-slice")
    add_common(type3_slice)
    type3_slice.add_argument("--type3-path", type=Path)
    type3_slice.add_argument("--cache-map-path", type=Path)
    type3_slice.add_argument("--dest", required=True)
    type3_slice.add_argument("--start", type=int, default=0)
    type3_slice.add_argument("--limit", type=int)
    type3_slice.add_argument("--match", default="")
    type3_slice.add_argument("--include-old", action="store_true")
    type3_slice.add_argument("--name-mode", choices=["original", "drop-group", "basename", "basename-no-ext"], default="original")
    type3_slice.add_argument("--overwrite", action="store_true")
    type3_slice.add_argument("--preserve-tail", action="store_true")
    type3_slice.add_argument("--auto-tail-backup", action="store_true")
    type3_slice.add_argument("--tail-type3-path", type=Path)
    type3_slice.add_argument("--tail-cache-map-path", type=Path)
    type3_slice.set_defaults(func=action_type3_slice)

    type3_rebuild = sub.add_parser("type3-rebuild")
    add_common(type3_rebuild)
    type3_rebuild.add_argument("--type3-path", type=Path)
    type3_rebuild.add_argument("--cache-map-path", type=Path)
    type3_rebuild.add_argument("--dest")
    type3_rebuild.add_argument("--overwrite", action="store_true")
    type3_rebuild.add_argument("--preserve-tail", action="store_true")
    type3_rebuild.add_argument("--auto-tail-backup", action="store_true")
    type3_rebuild.add_argument("--tail-type3-path", type=Path)
    type3_rebuild.add_argument("--tail-cache-map-path", type=Path)
    type3_rebuild.add_argument("--require-rich-tail", action="store_true")
    type3_rebuild.set_defaults(func=action_type3_rebuild)

    args = parser.parse_args(argv)
    if hasattr(args, "worker_ysm_root"):
        args.worker_ysm_root = args.worker_ysm_root.resolve()
    if getattr(args, "model_source_dir", None) is None and hasattr(args, "worker_ysm_root"):
        args.model_source_dir = args.worker_ysm_root / "custom" / args.group
    if getattr(args, "model_source_dir", None) is not None:
        args.model_source_dir = args.model_source_dir.resolve()
    if getattr(args, "fixture_dir", None) is not None:
        args.fixture_dir = args.fixture_dir.resolve()
    if getattr(args, "worker_cache_dir", None) is None and args.action in ("snapshot", "export"):
        args.worker_cache_dir = DEFAULT_WORKER_YSM_ROOT / "cache" / "server"
    if getattr(args, "worker_cache_dir", None) is not None:
        args.worker_cache_dir = args.worker_cache_dir.resolve()
    if getattr(args, "cache_dir", None) is None and args.action == "fixture":
        candidate = args.fixture_dir / "server-cache" / args.group
        fallback = args.fixture_dir / "server-cache" / "R18模型"
        args.cache_dir = candidate if candidate.exists() else fallback
    if getattr(args, "cache_dir", None) is not None:
        args.cache_dir = args.cache_dir.resolve()
    if getattr(args, "snapshot_dir", None) is not None:
        args.snapshot_dir = args.snapshot_dir.resolve()
    if getattr(args, "registry_file", None) is None and args.action == "export":
        args.registry_file = args.fixture_dir / "worker-cache-models.tsv"
    if getattr(args, "registry_file", None) is not None:
        args.registry_file = args.registry_file.resolve()
    if getattr(args, "type3_path", None) is not None:
        args.type3_path = args.type3_path.resolve()
    if getattr(args, "cache_map_path", None) is not None:
        args.cache_map_path = args.cache_map_path.resolve()
    if getattr(args, "report", None) is not None:
        args.report = args.report.resolve()
    if getattr(args, "tail_type3_path", None) is not None:
        args.tail_type3_path = args.tail_type3_path.resolve()
    if getattr(args, "tail_cache_map_path", None) is not None:
        args.tail_cache_map_path = args.tail_cache_map_path.resolve()

    for attr in ("model_source_dir", "fixture_dir", "worker_cache_dir", "cache_dir"):
        value = getattr(args, attr, None)
        if value is not None and not value.exists():
            raise FileNotFoundError(f"{attr.replace('_', '-')} not found: {value}")
    return args


def main(argv: list[str]) -> int:
    args = resolve_args(argv)
    return args.func(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(1)
