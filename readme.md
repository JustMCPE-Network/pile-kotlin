# pile-kotlin

A JVM implementation of the [pile](https://github.com/oriumgames/pile) world format, plus a PowerNukkitX
`LevelProvider`: one `.pile` file per dimension, loaded in a single read, saved as a canonical full rewrite. The
upstream Go library serves dragonfly; this serves PNX and any other JVM Bedrock software.

Wire-compatible with upstream by test: files written by the Go implementation decode here, files written here pass
`pile verify`, and both implementations compute the same content hash for the same content.

## Modules

| module        |                                                                                                                                     |
|---------------|-------------------------------------------------------------------------------------------------------------------------------------|
| `pile-format` | the format itself: decode, canonical encode, indexed files, structures. Zero Minecraft dependencies; NBT payloads stay opaque bytes |
| `pile-pnx`    | the PNX adapter: `PileLevelProvider`, a builder for generated maps, structure bridging. `shadowJar` produces a drop-in plugin       |
| `conformance` | cross-implementation tests against upstream's vectors, golden files and CLI                                                         |

## Quick start

Reading and writing files:

```kotlin
val world = PileReader.readWorld(Files.readAllBytes(path))
val bytes = PileWriter.writeWorld(world, WriteOptions(Compression.BEST))
PileWriter.writeWorld(path, world)              // atomic temp + rename
val identity = PileWriter.contentHash(world)    // == upstream's format.ContentHash
```

Serving a world from PNX:

```kotlin
PileLevelProvider.register()                    // before levels load; or install the plugin jar
```

then give the world directory an `overworld.pile` and a `config.json` whose
`format` is `pile` and whose generator is `void`. Saves rewrite the file atomically; `readOnly` in `PileProviderOptions`
keeps a lobby pristine.

Opening files from strangers: pass `DecodeOptions(maxDecodedBytes = n)`. The format's own ceilings are set at what it
can represent, so a legal file of about a kilobyte decodes into gigabytes; the budget caps it, and a refusal under it is
a `DecodeBudgetException`, not a corruption verdict.

## What is here

- solid mode, read and write, byte-for-byte canonical: the conformance suite re-encodes every uncompressed upstream
  vector to its exact bytes, and the
  `SkipBiomes` and stats options reproduce their golden files the same way
- indexed mode as `IndexedPile`: the directory and palettes resident, columns decoded one frame at a time, per-chunk
  `store`, checkpoints, torn-write recovery, shared dictionaries, compaction. A 59,740-column world opens in under 100
  ms on about 13 MB of heap
- structures: read, write, `rotate` with the common Bedrock direction properties, padding cleared, canonical orders kept
- templates and instances: one decoded base world, copy-on-write in-memory levels that evaporate on close or persist
  with `saveAs`
- parallel encode and decode across columns; `fastCompression` trades byte determinism for multithreaded zstd, as
  upstream's option does
- load filters (`filterColumn`, `filterEntity`, `filterBlockEntity`) and skip options on the provider
- no-panic decoding: hostile input surfaces as typed exceptions, fuzzed by the test suite with truncated and bit-flipped
  files
- a decode budget equivalent to upstream's `MaxDecodedBytes`

Out of scope: block-state upgrades across Minecraft versions beyond what the runtime's own updater performs (keep worlds
current with upstream's
`pile upgrade`), mcdb conversion (upstream's CLI does it), player data (the format itself excludes it), dictionary
training.

## Notes

- Block entities PNX's registry does not know are dropped by the engine when a loaded chunk is saved back, exactly as
  its LevelDB provider drops them. Unloaded columns pass through byte-identically.
- The format specification lives in [format.md](format.md); every claim in it cites the upstream source it was verified
  against.

Based on [oriumgames/pile](https://github.com/oriumgames/pile) by Orium Games. MIT licensed;
see [license.md](license.md).
