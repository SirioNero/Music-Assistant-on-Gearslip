"""Gli errori di aiodns, per chi li intercetta."""


class DNSError(Exception):
    """Un nome che non si è potuto risolvere: (codice, messaggio), come in aiodns."""
