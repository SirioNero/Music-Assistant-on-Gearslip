"""Gli errori di aiodns, per chi li intercetta."""


class DNSError(Exception):
    """A name that couldn't be resolved: (code, message), as in aiodns."""
