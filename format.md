# Pile file format, version 2

This document specifies the pile binary format as implemented by
[oriumgames/pile](https://github.com/oriumgames/pile), in the depth this project implements it: the **solid** file mode
for worlds and structures in full, and the reader's share of **indexed** mode. Indexed layout details this project only
consumes (checkpoint chains, dictionaries, compaction) are specified by upstream's `format/format.md` §5 and summarised
in §11 here.

Every rule below was checked against the upstream Go source at commit
`f9a5461` and cites the function that enforces it, in the form
`file.go:func`. The Go implementation is the reference: where this document and the implementation disagree, the
implementation wins and this document has a bug. Upstream's own `format/format.md` is the primary specification; this is
a second, independently verified statement of the same format, and nothing here is meant to differ from it.

- Header magic: the four bytes `P I L E`
- Footer magic: the four bytes `E L I P`
- Version: 2 (`format.go:Version`)
- Compression: Zstandard, one frame per file (§2.5)
- Checksums: xxHash64, seed 0 (`format.go:checkpointHash`)

A pile file stores the chunks of one Bedrock dimension, or one structure. Nothing in the file says which dimension: that
is the file's name (`overworld.pile`, `nether.pile`, `end.pile`, `dim<id>.pile`;
`worldfiles.go:DimPath`), and nothing else in the format needs it.

---

## 1. Primitives

All fixed-width integers are **little-endian** (`buffer.go:writer`,
`buffer.go:reader`).

| name                             | encoding                                                                                                                                                                                      |
|----------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `u8`, `u16`, `u32`, `u64`, `i32` | fixed-width little-endian                                                                                                                                                                     |
| `uvarint`                        | unsigned LEB128, 7 bits per byte, high bit continues. MUST be minimal; decoders reject overlong encodings (`buffer.go:reader.uvarint`)                                                        |
| `svarint`                        | zigzag LEB128: `v` maps to `uvarint((v << 1) ^ (v >> 63))`. MUST be minimal (`buffer.go:reader.svarint`)                                                                                      |
| `string`                         | `uvarint` length + UTF-8 bytes. Length > 65 535 is rejected, and so are bytes that are not valid UTF-8 (`buffer.go:reader.str`). Strings are ordered bytewise wherever the format orders them |
| `blob`                           | `uvarint` length + raw bytes. Length > 16 777 216 is rejected (`buffer.go:reader.blob`)                                                                                                       |
| `bitset(n)`                      | `ceil(n/8)` bytes; bit `i` is bit `i%8` of byte `i/8`, LSB first. Padding bits above `n` MUST be zero (`buffer.go:bitsetTail`)                                                                |

**Section-local block index.** A section is a 16×16×16 cube. Local position (x, y, z), each in [0, 15], has linear index

```
i = (x << 8) | (z << 4) | y        // 0 ≤ i ≤ 4095
```

y varies fastest, then z, then x. This is Bedrock's sub-chunk storage order and dragonfly's (`unsafe.go:unpackStorage`,
dragonfly
`chunk/paletted_storage.go:paletteIndex`).

**Light nibble arrays.** 4096 4-bit values in 2048 bytes: value `i` lives in byte `i >> 1`, even `i` in the low nibble,
odd `i` in the high nibble (`format.go:lightArrayLen`; the layout is dragonfly's, the format stores the array verbatim).

**Morton key.** Records are ordered by a 64-bit Z-order key over chunk coordinates (`morton.go:mortonKey`):

```
key(x, z) = spread(u32(x) ^ 0x80000000) | spread(u32(z) ^ 0x80000000) << 1
```

where `spread` moves bit `i` of a 32-bit input to bit `2i` of the output. The XOR maps signed coordinates into unsigned
space so the order is total over the whole int32 range.

**NBT.** Every NBT blob is little-endian Bedrock NBT (numbers little-endian, string lengths `u16`, array and list
lengths `i32`): one compound with an empty root name. These are validity rules, enforced on read
(`nbtvalidate.go:validateNBT`, `nbtvalidate.go:nbtWalker.payload`):

- the root tag is `TAG_Compound` with an empty name;
- compound keys are unique and strictly ascending bytewise;
- an empty list declares element type `TAG_End`; a non-empty list never does;
- an NBT string (value or key) is at most 32 767 bytes. The length field is a
  `u16`, but Bedrock readers in practice take it as a signed int16, so the reachable ceiling is the stated one
  (`nbt.go:maxNBTStringWrite`);
- nesting is bounded: the root compound's payload is at depth 0, every value inside a container (scalars included) is
  one deeper than the container, and a payload at depth 65 or more is rejected (`nbtvalidate.go:maxNBTDepth`, checked at
  the top of `nbtWalker.payload` for every tag type);
- a blob decodes into at most 1 048 576 containers, where every list or compound nested inside another counts one and
  the root counts nothing (`nbtvalidate.go:nbtWalker.count`);
- every declared length fits in the bytes that remain, checked before any element is read;
- no bytes follow the root compound's `TAG_End`.

Tag types used: byte (1), short (2), int (3), long (4), float (5), double (6), byte_array (7), string (8), list (9),
compound (10), int_array (11), long_array (12). Array tags and lists of the same numeric type are different tags, both
occur in vanilla content, and a decoder MUST keep them apart: the writer re-emits each value with the tag it was read as
(`nbt.go:nbtTagType`).

The canonical NBT writer (`nbt.go:marshalNBT`) emits keys sorted bytewise and
`TAG_End` as the element type of every empty list. Identical content therefore encodes to identical bytes, which is what
the collection orders of §4.8 rest on.

World and chunk **user data are not NBT**: they are opaque byte strings the format never parses, and any bytes within
the blob limit are valid (`decode.go:readMetaBlobs`, `encode.go:validateWorldData`).

---

## 2. Container

```
+-------------------------+
| header      16 bytes    |  never compressed
+-------------------------+
| body                    |  one unit: one zstd frame, or raw when flag Uncompressed
+-------------------------+
| footer      44 bytes    |  never compressed
+-------------------------+
```

A file shorter than 60 bytes is rejected (`decode.go:parseFrame`).

### 2.1 Header

| offset | type    | field        | value                                                                                                                                                               |
|--------|---------|--------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0      | 4 bytes | magic        | `PILE`                                                                                                                                                              |
| 4      | u16     | version      | 2                                                                                                                                                                   |
| 6      | u8      | kind         | 0 = world, 1 = structure                                                                                                                                            |
| 7      | u8      | mode         | 0 = solid, 1 = indexed                                                                                                                                              |
| 8      | u32     | flags        | §2.3                                                                                                                                                                |
| 12     | i32     | blockVersion | the Bedrock block-state version the palette is expressed at. MUST be non-zero: zero is the value a palette override uses to mean "the palette's own version" (§3.1) |

`decode.go:parseFrame` checks, in this order: magic, version (any other value fails with the unsupported-version error,
never a partial read), kind (values above 1 are rejected), blockVersion non-zero, flags (any bit outside the known set
is rejected), the default-biome reference being zero when its flag is clear, and mode. A structure is always solid; the
solid readers reject mode 1 with the unsupported-mode error. There is no forward-compatibility lane: a reader of one
version refuses every other version's files.

The block version this project's fixtures carry is 18040335 (dragonfly
`chunk.CurrentBlockVersion` at v0.11.1; `pile version` prints it).

### 2.2 Footer

| offset | type    | field                 |
|--------|---------|-----------------------|
| 0      | u64     | checkpoint hash, §2.4 |
| 8      | u64     | dirOffset; solid: 0   |
| 16     | u64     | dirLength; solid: 0   |
| 24     | u64     | generation; solid: 0  |
| 32     | u64     | prevFooter; solid: 0  |
| 40     | 4 bytes | magic                 | `ELIP` |

In a solid file the four control words carry no information and MUST be zero; a reader rejects a non-zero one
(`decode.go:parseFrame`), so one file has one encoding. The footer magic differs from the header magic for the benefit
of indexed-mode recovery scans.

### 2.3 Flags

| bit   | name            | meaning                                                                                                                                                                                                                                   |
|-------|-----------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0     | StoreLight      | chunk records carry baked light (§4.6). In solid mode MUST be clear when no section carries any: the writer clears it (`encode.go:WriteWorld`) and the reader rejects a file that sets it over light-free records (`decode.go:ReadWorld`) |
| 1     | Stats           | the meta block carries a stats compound (§4.2)                                                                                                                                                                                            |
| 2     | reserved        | MUST be zero                                                                                                                                                                                                                              |
| 3     | DefaultBiome    | bits 16–31 hold a global biome palette reference used as the default (§4.7)                                                                                                                                                               |
| 4     | Uncompressed    | the body is stored raw                                                                                                                                                                                                                    |
| 5–15  | reserved        | MUST be zero. Bits 5–7 once held a dimension field; it was removed before the freeze                                                                                                                                                      |
| 16–31 | defaultBiomeRef | meaningful only with bit 3 set, and MUST be zero when it is clear                                                                                                                                                                         |

`format.go:knownFlags` is `StoreLight | Stats | DefaultBiome | Uncompressed |
0xFFFF0000`; anything else fails with the unknown-flags error. A structure header MUST set no flag but Uncompressed
(`structure.go:ReadStructure`).

### 2.4 Checkpoint hash

```
preimage = header (16 bytes) || stored body || footer bytes 8..44
hash     = xxHash64(preimage, seed 0)
```

`format.go:checkpointHash`. The stored body is the bytes between header and footer exactly as they sit on disk:
compressed when the body is compressed. The hash therefore covers blockVersion, the flags (and with them the
default-biome reference) and the footer's control words, so a flipped bit anywhere but the hash field itself fails
`decode.go:parseFrame` with the checksum error. It is per file. There is no per-chunk hash in solid mode.

xxHash64 is keyless: a file that verifies is well-formed, not trustworthy.

### 2.5 Zstandard frame

Unless flag Uncompressed is set, the body is one Zstandard frame that begins at file offset 16 and ends 44 bytes before
the end of the file. A reader MUST refuse a frame whose window size exceeds 8 MiB (`zstdpool.go:maxZstdWindow`, applied
with `WithDecoderMaxWindow`) and MUST stop decoding a body that would exceed 512 MiB (`zstdpool.go:maxDecodedBody`,
applied with
`WithDecoderMaxMemory`). An uncompressed body past 512 MiB is equally invalid (`decode.go:decompressBody`). A frame is
not required to declare its content size; a reader must bound a streaming decode by counting bytes out.

What the reference writer produces (`zstdpool.go`, klauspost/compress v1.19.1, single-threaded unless
`FastCompression`): one frame from
`Encoder.EncodeAll` with a content checksum and, for bodies small enough, a single-segment frame declaring its content
size. Level mapping:

| `CompressionLevel` | klauspost level                                           |
|--------------------|-----------------------------------------------------------|
| None               | no frame; flag Uncompressed set                           |
| Fast               | `SpeedFastest`                                            |
| Default            | `SpeedDefault`                                            |
| Best               | `SpeedBestCompression` (the provider's default for saves) |

klauspost's levels are its own and do not correspond to the reference zstd library's numeric levels. None of this
constrains a reader, and none of it is part of the format's identity (§8). A second implementation compressing with
another encoder produces a different file for the same content, and that is expected; it must only keep the window at or
below 8 MiB so reference readers accept it.

---

## 3. Shared building blocks

**Who enforces a rule.** Most rules below are decoder-enforced. A few are writer-only because the evidence is not in the
file; palette order is the archetype, since reference counts are never stored. Writer-only rules are verified by
re-encoding and comparing (§8), and are marked.

### 3.1 Global block palette

```
count            uvarint          ≤ 1 048 576
entry[count]:
  name           string
  propN          uvarint          ≤ 64
  prop[propN]:                    keys strictly ascending bytewise
    key          string
    type         u8               0 = byte, 1 = int32, 2 = string
    value        u8 | i32 | string
overrideN        uvarint          ≤ count
override[overrideN]:
  indexDelta     uvarint          delta from the previous override's index; the
                                  first deltas from 0
  version        i32
```

Parsing is `palette.go:parseStatePalette`. Rules it enforces:

- `count` is bounded by the bytes remaining (two per entry) before anything is allocated;
- property keys are unique and strictly ascending; a repeated or descending key is rejected;
- unknown property type codes are rejected;
- override indices strictly ascend: a zero delta after the first is rejected, a running index that wraps past 2⁶⁴ is
  rejected, an index at or past
  `count` is rejected;
- an override version of zero is rejected, and so is one equal to the header's `blockVersion`: both are second encodings
  of "no override";
- two entries that encode identically at the same effective version are rejected (`palette.go:preservedStateKey` is the
  identity: name, properties and version).

An entry's effective version is its override when it has one and the header's `blockVersion` otherwise. Only preserved
unresolved states (§9 of upstream's document; §7 here) ever carry one; everything the writer resolved against its
registry is at the palette's own version. Boolean properties are encoded as type 0 with value 0 or 1
(`palette.go:encodeProps`).

**Writer order** (writer-only; `palette.go:blockPaletteBuilder.finalize`):

1. descending reference count, where the count is the number of local palettes (section layers in a world, cell layers
   in a structure) the state appears in, plus one per scheduled update referencing it. Counting is per occurrence,
   before the blob table deduplicates, and after trailing all-air layers (§4.3) have been dropped. Two aliases of one
   state inside one local palette count once (`encode.go:resolveColumn` calls `uncount`);
2. ascending bytewise comparison of the entry's encoded bytes, exactly as written above: the length-prefixed name
   followed by the property block;
3. ascending effective version, with zero meaning the palette's own.

Entries equal under all three are the same state and are merged before the sort, their counts summed. The override table
is emitted after the sort, indices ascending, and is empty for every file holding no preserved states.

Decoding resolves each entry against the runtime's registry after upgrading it from its effective version. Unresolvable
states decode as a placeholder (`minecraft:info_update`, falling back to air) while the entry itself is kept so it can
be re-emitted on save (`palette.go:decodeBlockPalette`). An implementation without a registry keeps every entry
verbatim; §7.

### 3.2 Global biome palette

```
count            uvarint          ≤ 1 048 576
name[count]      string
```

`palette.go:decodeBiomePalette`: every name MUST contain a `:` (bare names are invalid, not a second spelling), names
MUST be unique, and `count` is bounded by the bytes remaining. Unknown names decode as `minecraft:plains`
with the original kept for re-emission.

**Writer order** (writer-only; `palette.go:biomePaletteBuilder.finalize`):
descending reference count, where the count is the number of section-local biome palettes the name appears in over
**every** section of every chunk, counted before the default-biome elision of §4.7 removes any; then ascending bytewise
name. Counting before elision is what breaks the loop between
"which biome is elided" and "what the palette order is".

### 3.3 Section blob

The canonical encoding of one 16³ storage, a block layer or a section's biomes. Self-delimiting.

```
paletteN         uvarint          1 ≤ paletteN ≤ 65 536
ref[paletteN]    uvarint          global palette references, strictly ascending
width            u8               0 = uniform, 1 = u8, 2 = u16 little-endian
indices          4096 × width bytes   absent when width = 0
```

`blob.go:decodeOneBlob` enforces:

- `paletteN ≥ 1` and `paletteN ≤` bytes remaining;
- every reference ≤ 1 048 576, and references strictly ascend;
- `width = 0` iff `paletteN = 1`; `width = 1` requires `paletteN ≤ 256`;
  `width = 2` requires `paletteN > 256`. The narrowest sufficient width is the only valid one;
- every local palette entry is named by at least one index. An unused entry is not dead weight: the uniformity tests of
  §4.3 and §4.7 read `paletteN`, so an unused entry would bypass them;
- an index at or past `paletteN` is rejected where the blob is applied (`decode.go:blobIndices`).

Indices are byte-aligned on purpose: zstd compresses them near-optimally and decode is a copy plus a remap. Index `i`
follows §1's local order.

The writer (`blob.go:canonicalBlob`) remaps local entries through the final palette order, sorts references ascending,
folds local duplicates (two runtime IDs that describe one state) into one reference, and writes uniform when one
reference remains.

### 3.4 Section blob table

```
count            uvarint          ≤ 16 777 216
blob[count]      section blobs, concatenated
```

`blob.go:decodeBlobTable`: `count` is bounded by remaining bytes / 3; a blob whose bytes repeat an earlier blob's is
rejected. After the records are read, a blob no record references is rejected (`decode.go:ReadWorld`,
`structure.go:ReadStructure`).

Ids are assigned in **first-use order** over the stored units, and this is checked on the wire
(`decode.go:tableBlobSource`): the first reference seen is 0, and every later reference is either an id already seen or
exactly the next unseen id. The stream order is: records in Morton order; within a record, present block sections by
ascending index and within a section its layers by ascending layer number, then present biome sections by ascending
index. Within a structure: present cells by ascending cell index, layers ascending. Block and biome blobs share one
table; the use site says which palette a blob's references index.

---

## 4. Solid world body (kind 0, mode 0)

Body content, in order (`encode.go:WriteWorld`, `decode.go:ReadWorld`):

```
meta block       §4.1
block palette    §3.1
biome palette    §3.2
blob table       §3.4
chunkN           uvarint          ≤ 67 108 864
record[chunkN]   §4.3, strictly ascending Morton key
```

Records are read serially (boundaries are implicit). `decode.go:ReadWorld`
accumulates `dx`/`dz` in 64 bits and rejects a position outside int32, rejects a record whose Morton key is not strictly
greater than the previous one (which covers both duplicates and misordering), and rejects any bytes left after the last
record.

### 4.1 Meta block

```
settings         blob             NBT per §1, §6.1; may be empty
userData         blob             opaque; may be empty
stats            blob             present iff flag Stats; NBT per §1, §4.2
```

`decode.go:readMetaBlobs` validates the NBT blobs structurally and applies the settings schema of §6.1 and the stats
schema of §4.2 on read; the writer applies the same before encoding (`encode.go:validateWorldData`).

### 4.2 Stats compound

NBT compound with `chunks`, `filledSections`, `uniqueBlobs`, `blockStates`
and `biomes`, all longs (`encode.go:WriteWorld`, `encode.go:statsSchema`).
`filledSections` counts present block sections, `uniqueBlobs` is the blob table length, the other two are palette
lengths. A key that is present MUST carry the long tag; a missing key is valid; unknown keys are ignored
(`encode.go:checkStatsBlob`). Stats are derived and are not part of content identity (§8).

### 4.3 Chunk record

```
dx, dz           svarint          delta from the previous record; the first
                                  record deltas from (0, 0)
minSection       svarint          −2048 ≤ minSection, minSection + sectionN ≤ 2048
sectionN         uvarint          1 ≤ sectionN ≤ 4096; the chunk's whole
                                  vertical range, never trimmed
blockPresence    bitset(sectionN)
present sections, ascending:
  layerN         uvarint          1 ≤ layerN ≤ 255
  blobRef[layerN] uvarint
biomePresence    bitset(sectionN)
present biome sections, ascending:
  blobRef        uvarint
light            §4.6, present iff flag StoreLight
beN              uvarint          ≤ 1 048 576
be[beN]          §4.4
entN             uvarint          ≤ 1 048 576
ent[entN]:
  nbt            blob             §4.5
tick             svarint          the column's current tick
stN              uvarint          ≤ 1 048 576
st[stN]:
  packedXZ       u8               x = bits 0–3, z = bits 4–7, chunk-local
  y              svarint          absolute block Y
  blockRef       uvarint          global block palette reference
  at             svarint          absolute tick the update fires at
userData         blob             opaque chunk metadata
```

`decode.go:parseRecordBodyBudgeted` and `decode.go:applyRecord`. Rules:

- `sectionN = 0` is rejected; the span must lie inside section indices −2048..2047 (block Y is an int16 everywhere);
- a present section with `layerN = 0` is rejected; `layerN > 255` is rejected;
- every block-entity and scheduled-update Y MUST lie inside the record's span, and a scheduled update's `blockRef` MUST
  index the palette;
- a present section whose **last** stored layer is uniform air is rejected (`decode.go:checkSectionCanonical`). That one
  test enforces two rules: a section all of whose layers are air is absent (every presence bit clear), and trailing
  all-air layers are dropped. A layer holding a preserved unresolved state is content even when its placeholder resolves
  to air.

**Empty sections cost one bit.** A section every one of whose layers is uniform air has its presence bit clear and
nothing else. A fully empty record over a one-section span is eleven bytes: `dx dz minSection sectionN
presence biomePresence beN entN tick stN userData`, each one byte; over the overworld's 24 sections it is fifteen bytes,
as in the `world_empty_chunk`
vector, where the two bitsets are three bytes each. Such a record means "exists and is air", which is distinct from a
chunk that was never stored.

**Layers are semantic.** Layer 0 is the block, layer 1 is Bedrock's waterlogging layer. The writer drops trailing
all-air layers and keeps internal ones as ordinary uniform blobs referencing air (`encode.go:extractColumnRaw`,
`encode.go:airOnlyLayer`). A uniform-air layer 0 under a water layer 1 is stored, and a reader MUST NOT treat it as an
absent section (`world_waterlogged` vector). A layer is air only when every entry of its local palette is air and none
stands for a preserved state.

### 4.4 Block entity

```
packedXZ         u8               x = bits 0–3, z = bits 4–7
y                svarint          absolute block Y
nbt              blob             NBT compound
```

The `x`, `y`, `z` keys are stripped from the compound on write and reinjected as int tags holding absolute coordinates
on read (`encode.go:projectCollections`, `decode.go:applyRecord`). The block entity's identifier stays inside the NBT
(`id` by Bedrock convention). Block entities MUST strictly ascend in (y, z, x) (`decode.go:beAscends`), which also makes
positions unique.

### 4.5 Entity

One NBT compound per entity, stored whole. The format interprets only
`UniqueID`, a long, which the reference reads as the entity's stable id and takes verbatim; zero is legal. A compound
missing `UniqueID` or carrying it with another tag is foreign input: the reader substitutes a deterministic id derived
from the chunk position and index (`decode.go:syntheticEntityID`), and no conforming writer produces one, since the
writer always sets the key (`encode.go:projectCollections`). The reader does not check entity order; the writer sorts
them as §4.8 says.

### 4.6 Light

```
lightPresence    bitset(sectionN)
per set bit, ascending:
  flags          u8               bit 0 = block light, bit 1 = sky light; bits 2–7 zero
  blockLight     2048 bytes       iff bit 0
  skyLight       2048 bytes       iff bit 1
```

Present only when flag StoreLight is set. `flags` MUST NOT be zero and MUST NOT set a reserved bit
(`decode.go:parseRecordBodyBudgeted`). Light presence is independent of block presence: an all-air section can carry
full sky light. Light is a cache; readers may ignore it, and it is outside content identity.

### 4.7 Default biome

When flag DefaultBiome is set, `defaultBiomeRef` names a global biome palette entry, checked against the palette length
(`decode.go:ReadWorld`). A section whose biomes are uniformly that entry MUST be absent from `biomePresence`; the reader
rejects a present uniform section equal to the default (`decode.go:applyRecord`) and fills absent sections with it.
Without the flag, absent biome sections decode as `minecraft:plains`.

Writer rule (`encode.go:WriteWorld`): count uniform sections per biome; pick the biome with the most, ties broken by the
**lowest final palette reference**; set the flag whenever at least one uniform section exists and the reference fits in
16 bits, clear it otherwise. A world written with biomes skipped has an empty biome palette, no flag, and every biome
section absent; it decodes as plains everywhere.

### 4.8 Canonical orders

Writers sort (`encode.go:WriteWorld`, `encode.go:projectCollections`,
`encode.go:sortTicks`):

| collection               | order                                                        |
|--------------------------|--------------------------------------------------------------|
| records                  | Morton key                                                   |
| block palette            | §3.1                                                         |
| biome palette            | §3.2                                                         |
| block entities           | (y, z, x), then the encoded NBT as written (x/y/z stripped)  |
| entities                 | `UniqueID`, then the encoded NBT as written (`UniqueID` set) |
| scheduled updates        | (y, z, x), then `at`, then final block palette reference     |
| structure block entities | (y, z, x), then the encoded NBT                              |
| structure entities       | the encoded NBT alone                                        |

Uniqueness is a rule: at most one block entity per position, at most one scheduled update per (position, tick, block
reference). The reader enforces both by requiring strict ascent (`decode.go:beAscends`,
`decode.go:tickAscends`); the writer refuses duplicates before encoding (`encode.go:validateColumn`). Ties break on the
bytes that reach the file, not on the caller's value.

---

## 5. Structure files (kind 1, mode 0)

A free-standing box of blocks with a paste anchor. Body, in order (`structure.go:WriteStructure`,
`structure.go:ReadStructure`):

```
meta block       §4.1: settings MUST be empty, userData usable, no stats
block palette    §3.1
biome palette    §3.2, MUST have count 0
blob table       §3.4
sizeX,Y,Z        uvarint × 3      1 ≤ size ≤ 1 048 576 each
originX,Y,Z      svarint × 3      each within int32
cellPresence     bitset(cells)
present cells, ascending:
  layerN         uvarint          1 ≤ layerN ≤ 255
  blobRef[layerN] uvarint
beN              uvarint          ≤ 1 048 576
be[beN]:
  x, y, z        uvarint × 3      structure-local, each < size on its axis
  nbt            blob             x/y/z stripped as in §4.4
entN             uvarint          ≤ 1 048 576
ent[entN]:
  nbt            blob             Pos is structure-local
```

The header MUST set no flag but Uncompressed. Cells are 16³:
`cells{X,Y,Z} = ceil(size/16)`, the product computed in 64 bits and checked against 1 048 576 **before** allocation
(`structure.go:structureCellCount`); the presence bitset must also fit in the bytes remaining. Cell (cx, cy, cz)
has index `(cx * cellsZ + cz) * cellsY + cy` (`structure.go:CellIndex`). Positions inside a cell use §1's local index.
Cell rules are §4.3's: an all-air cell is absent, a present cell has at least one layer, trailing air layers are
dropped, internal ones kept (`structure.go:ReadStructure` reuses
`checkSectionCanonical`). Positions inside an edge cell but outside the box are air in every layer; the writer clears
them before encoding (`structure.go:clearPadding`), and a reader cannot check it. Block entities MUST strictly ascend in
(y, z, x) (`structure.go:structBEAscends`). Nothing may follow the last entity. Structures carry no biomes and no light.

---

## 6. Metadata compounds

Which fields exist is a convention: every field is optional and unknown keys are preserved. How a present field is
spelled is a rule: it MUST carry the tag below, enforced by writer and reader alike (`encode.go:settingsSchema`,
`encode.go:checkSettingsBlob`, called from `decode.go:readMetaBlobs`).

### 6.1 Settings

| key                          | tag    |
|------------------------------|--------|
| `name`                       | string |
| `spawnX`, `spawnY`, `spawnZ` | int    |
| `time`                       | long   |
| `timeCycle`                  | byte   |
| `rainTime`                   | long   |
| `raining`                    | byte   |
| `thunderTime`                | long   |
| `thundering`                 | byte   |
| `weatherCycle`               | byte   |
| `requiredSleepTicks`         | long   |
| `currentTick`                | long   |
| `defaultGameMode`            | int    |
| `difficulty`                 | int    |
| `tickRange`                  | int    |

The reference provider writes all sixteen keys on every save (`settings.go:settingsToNBT`); `defaultGameMode` and
`difficulty` are dragonfly's numeric ids (survival 0, creative 1, adventure 2, spectator 3; peaceful 0 through hard 3).

---

## 7. Preserved states

A state the reader cannot resolve is kept: the reference decodes it as a placeholder block and records (section, layer,
index, palette entry) in a per-column sidecar, plus an equivalent for scheduled updates and for biome names, and
re-emits the original entries wherever the placeholder still stands on save (`encode.go:injectUnknown`,
`decode.go:applyBlockBlob`). The entry keeps the version it was expressed at, which is why the override table of §3.1
exists.

A reader that does not resolve states against a registry at all keeps every palette entry verbatim and needs no sidecar;
the result on re-encode is the same as the reference's for any file whose states the reference resolves. It differs only
when the reference's registry upgrades a state to a newer name or form, because then the reference re-encodes the
upgraded state and the registry-free reader re-encodes the stored one. This project's conformance suite pins the
vectors, whose states are all current.

---

## 8. Limits and the decode budget

Validity ceilings (`format.go`). A file past one is invalid; raising one is a format revision.

| item                                                  | limit                             |
|-------------------------------------------------------|-----------------------------------|
| string length                                         | 65 535                            |
| NBT string length                                     | 32 767                            |
| blob length                                           | 16 MiB                            |
| NBT nesting depth                                     | 64                                |
| NBT containers per blob                               | 1 048 576                         |
| decompressed solid body                               | 512 MiB                           |
| zstd window                                           | 8 MiB                             |
| chunk records                                         | 67 108 864                        |
| section storages decoded per file                     | 4 194 304                         |
| global palette entries (block, biome)                 | 1 048 576                         |
| blob table entries                                    | 16 777 216                        |
| section blob local palette                            | 65 536                            |
| properties per state                                  | 64                                |
| sections per chunk                                    | 4 096, within indices −2048..2047 |
| layers per section                                    | 255                               |
| block entities, entities, scheduled updates per chunk | 1 048 576 each                    |
| structure cells                                       | 1 048 576                         |
| structure size per axis                               | 1 048 576                         |

Counts are additionally bounded by the input that remains before anything is allocated (`blob.go:decodeBlobTable`,
`palette.go:parseStatePalette`,
`decode.go:ReadWorld`), and capacity hints are capped at 4096 (`format.go:maxPrealloc`). Writers refuse content their
own reader would refuse (`encode.go:validateWorldData`, `encode.go:validateColumn`), including the aggregate body
ceiling checked before compression.

**Decode budget.** Separately from validity, a caller may cap what one decode materialises (`format.go:MaxDecodedBytes`,
`decode.go:storageBudget`). The cost model is one number per unit: a column 1 024 bytes, a section storage 128, a block
entity, entity or scheduled update 256. Each is charged before the unit is parsed: a record charges its column before
its body is read, a present section charges `layerN` storages before its references, a collection charges its declared
count before its first entry. The default is 5 GiB; a caller's value is clamped to what §8's ceilings permit and can
only tighten. A refusal under the budget is reported with a distinct error (`format.go:ErrDecodeBudget`) that does not
wrap the corruption error: the file may be conforming, and another caller will decode it. This project mirrors that with
two exception types.

---

## 9. Determinism and identity

The reference writer is deterministic: identical content, registry and options produce identical bytes
(`format/readme.md`), unless
`FastCompression` is set, which compresses with several threads and gives up byte identity. Everything above that makes
it so is canonical form:
Morton-sorted records, the palette orders of §3.1 and §3.2, canonical blobs, canonical NBT, the collection orders of
§4.8, the presence and elision rules of §4.3 and §4.7, first-use blob ids.

**Across implementations the compressed bytes are not guaranteed**, and this is resolved by the reference, not by
assumption: §4.8 of upstream's document says the compressed bytes are not part of the format's identity, the reference
encoder is klauspost's (§2.5) and another encoder at any level yields a different frame. What is guaranteed is the
**uncompressed body**:
two conforming writers given the same content produce the same body bytes. The reference exposes that as
`format.ContentHash` (`check.go:ContentHash`):
decode the file, re-encode it uncompressed with stats and light off, and xxHash64 (seed 0) the bytes between header and
footer. Two files with equal content hashes hold the same world whatever their compression, and the vectors pin the
value for each fixture.

Conformance for this project is therefore defined as:

1. every upstream vector and golden file decodes to the content the Go implementation decodes it to;
2. every uncompressed vector re-encodes to its exact bytes, and every fixture's content hash matches the reference's;
3. files this implementation writes pass `pile verify` and decode in Go to the same content, and `pile hash` reports the
   same content hash;
4. encoding the same content twice here yields identical bytes, compressed included, since zstd-jni at a fixed level and
   single thread is deterministic.

Byte identity between a file written here and one written by Go is **not**
claimed, and a test that asserted it would be asserting a property of two compressors.

---

## 10. Indexed mode, as implemented

An indexed file (mode 1) is append-only: frames after the header, located by a directory frame the footer names, one
checkpoint per footer. This project reads them fully and writes the simplest valid shape.

Reading (`wire/IndexedReader.kt`): the newest footer whose directory and referenced frames all validate is adopted,
scanning backward past torn bytes (§5.6); the directory prologue is the authority for flags and blockVersion and MUST
agree with the intact physical header; palette segments accumulate in directory order; record frames carry §4.3 records
with inline section blobs, no position, no default-biome elision; every frame's xxHash64 is checked; shared zstd
dictionaries are honoured. Flags `Stats` and `DefaultBiome` MUST be clear, and an empty palette segment is rejected
(§5.3).

Writing (`wire/IndexedEncoder.kt`): one checkpoint, palettes in first-seen order, every biome section stored. Append
re-frames every column as a new checkpoint and requires unchanged palettes and metadata; anything else is a full
rewrite. Indexed bytes are history-dependent and deliberately uncanonical; content identity is still §9's, and the
conformance suite pins it against upstream's `indexed_full` and `indexed_torn` vectors.

---

## 11. Outside the format

- **Dimension**: the file name. A world directory holds `overworld.pile` and optionally `nether.pile`, `end.pile`,
  `dim<id>.pile` (`worldfiles.go`). Metadata is duplicated into every dimension file; the provider reads the overworld's
  copy.
- **Snapshots**: not in the format. `Provider.Snapshot(name)` copies the dimension files into
  `<world>/snapshots/<name>/`; rollback copies them back (`snapshot.go`). A snapshot name may not contain a path
  separator or be
  `.`/`..`.
- **Player data**: never stored (`readme.md`).
- **Saving**: write to a temporary file, fsync, rename over the original, fsync the directory
  (`worldfiles.go:WorldFiles.Write`).

---

## Appendix: the minimal world, byte for byte

`world_minimal.pile` from upstream's vectors, 116 bytes: one column at (0, 0)
holding one section of stone. Verified against the file at commit `f9a5461`.

```
off  bytes                       field                     value
  0  50 49 4c 45                 header.magic              "PILE"
  4  02 00                       header.version            2
  6  00                          header.kind               world
  7  00                          header.mode               solid
  8  18 00 00 00                 header.flags              DefaultBiome | Uncompressed, ref 0
 12  0f 46 13 01                 header.blockVersion       18040335
 16  00                          settings blob             empty
 17  00                          userData blob             empty
 18  01                          blockPalette.count        1
 19  0f 6d 69 6e ... 6e 65       entry[0].name             "minecraft:stone"
 35  00                          entry[0].propN            0
 36  00                          overrideN                 0
 37  01                          biomePalette.count        1
 38  0f 6d 69 6e ... 61 6e       name[0]                   "minecraft:ocean"
 54  01                          blobTable.count           1
 55  01 00 00                    blob[0]                   paletteN 1, ref 0, width 0
 58  01                          chunkN                    1
 59  00 00                       dx, dz                    0, 0
 61  07                          minSection                −4
 62  01                          sectionN                  1
 63  01                          blockPresence             section 0 present
 64  01 00                       layerN 1, blobRef 0
 66  00                          biomePresence             none stored (uniform default)
 67  00 00                       beN, entN                 0, 0
 69  00                          tick                      0
 70  00                          stN                       0
 71  00                          userData blob             empty
 72  34 6d a7 8b 53 f5 a5 f7     footer.hash               xxHash64 per §2.4
 80  00 × 32                     dirOffset, dirLength, generation, prevFooter
112  45 4c 49 50                 footer.magic              "ELIP"
```

`world_empty_chunk.pile` (98 bytes) is the same envelope with an empty block palette, an empty blob table and one record
spanning sections −4..19 with every presence bit clear: the three-byte bitsets appear twice and nothing else changes.
