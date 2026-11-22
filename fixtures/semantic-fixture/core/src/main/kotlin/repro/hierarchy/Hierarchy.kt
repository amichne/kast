package repro.hierarchy

interface FixtureSink { fun transmit() = Unit }
class AlphaSink : FixtureSink { override fun transmit() = Unit }
class BetaSink : FixtureSink { override fun transmit() = Unit }
class GammaSink : FixtureSink { override fun transmit() = Unit }
class DeltaSink : FixtureSink
