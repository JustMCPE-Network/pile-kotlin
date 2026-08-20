package net.justmcpe.pile.format

/** Base of every error the format layer reports. */
public open class PileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The file violates the format: every decode error that is not one of the typed refusals below. */
public class CorruptFileException(message: String, cause: Throwable? = null) : PileException(message, cause)

public class UnsupportedVersionException(version: Int) :
    PileException("unsupported format version $version, this reader handles ${Limits.VERSION}")

public class UnsupportedModeException(mode: Int) :
    PileException("unsupported body mode $mode, this reader handles solid files only")

public class UnknownFlagsException(flags: Int) :
    PileException("unknown required feature flags 0x%08x".format(flags))

public class ChecksumMismatchException : PileException("body checksum mismatch")

/**
 * The decode would have exceeded the caller's [DecodeOptions.maxDecodedBytes]. The file is not being
 * called invalid: a wider budget, or another caller, decodes it.
 */
public class DecodeBudgetException(used: Long, limit: Long) :
    PileException("decode reached $used bytes, the caller's limit is $limit")

/** The content handed to the writer would encode into a file its own reader refuses. */
public class InvalidContentException(message: String) : PileException(message)

internal fun corrupt(message: String): Nothing = throw CorruptFileException(message)
