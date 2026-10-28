"""
orjson in Python, per il server di Music Assistant dentro Xonos.

orjson è scritto in Rust e per Android non c'è. Qui c'è quello che Music Assistant e le sue librerie
(mashumaro, aiosendspin) gli chiedono: dumps con le sue opzioni e loads, sopra il modulo json di
Python, che codifica e decodifica in C. Gli stessi risultati per i tipi che usano: date e ore in
RFC 3339, enum col loro valore, dataclass come dizionari, UUID come testo, NaN come null.
"""

from __future__ import annotations

import dataclasses
import datetime
import enum
import json
import math
import uuid
from typing import Any

__version__ = "3.11.6+xonos"

OPT_INDENT_2 = 1
OPT_NAIVE_UTC = 1 << 1
OPT_NON_STR_KEYS = 1 << 2
OPT_OMIT_MICROSECONDS = 1 << 3
OPT_SERIALIZE_NUMPY = 1 << 4
OPT_SORT_KEYS = 1 << 5
OPT_STRICT_INTEGER = 1 << 6
OPT_UTC_Z = 1 << 7
OPT_PASSTHROUGH_SUBCLASS = 1 << 8
OPT_PASSTHROUGH_DATETIME = 1 << 9
OPT_APPEND_NEWLINE = 1 << 10
OPT_PASSTHROUGH_DATACLASS = 1 << 11
OPT_SERIALIZE_DATACLASS = 0
OPT_SERIALIZE_UUID = 0


class JSONDecodeError(json.JSONDecodeError):
    """Come quello di orjson: è anche un json.JSONDecodeError e un ValueError."""


class JSONEncodeError(TypeError):
    """Come quello di orjson: è un TypeError."""


class Fragment:
    """Un pezzo di JSON già pronto, messo così com'è."""

    __slots__ = ("contents",)

    def __init__(self, contents: bytes | str) -> None:
        self.contents = contents.encode() if isinstance(contents, str) else bytes(contents)


def dumps(obj: Any, default: Any = None, option: int | None = None) -> bytes:
    """Come orjson.dumps: JSON compatto in UTF-8, in bytes."""
    option = option or 0
    fragments: dict[str, bytes] = {}

    def convert(value: Any) -> Any:
        if isinstance(value, Fragment):
            key = f"\x00xonos-fragment-{len(fragments)}\x00"
            fragments[key] = value.contents
            return key
        if isinstance(value, datetime.datetime):
            if option & OPT_PASSTHROUGH_DATETIME:
                return _call_default(default, value)
            return _datetime(value, option)
        if isinstance(value, datetime.date):
            if option & OPT_PASSTHROUGH_DATETIME:
                return _call_default(default, value)
            return value.isoformat()
        if isinstance(value, datetime.time):
            if option & OPT_PASSTHROUGH_DATETIME:
                return _call_default(default, value)
            if value.tzinfo is not None:
                raise JSONEncodeError("datetime.time must not have tzinfo set")
            text = value.isoformat()
            return text.split(".")[0] if option & OPT_OMIT_MICROSECONDS else text
        if isinstance(value, uuid.UUID):
            return str(value)
        if isinstance(value, enum.Enum):
            return value.value
        if dataclasses.is_dataclass(value) and not isinstance(value, type):
            if option & OPT_PASSTHROUGH_DATACLASS:
                return _call_default(default, value)
            return {f.name: getattr(value, f.name) for f in dataclasses.fields(value)}
        return _call_default(default, value)

    kwargs: dict[str, Any] = {
        "default": convert,
        "ensure_ascii": False,
        "allow_nan": False,
        "sort_keys": bool(option & OPT_SORT_KEYS),
    }
    if option & OPT_INDENT_2:
        kwargs["indent"] = 2
    else:
        kwargs["separators"] = (",", ":")
    try:
        text = json.dumps(obj, **kwargs)
    except ValueError:
        # NaN e infinito: orjson li scrive come null.
        text = json.dumps(_without_nan(obj), **kwargs)
    except TypeError as err:
        if "keys must be" not in str(err):
            raise JSONEncodeError(str(err)) from err
        # Chiavi che non sono testo: orjson le accetta con OPT_NON_STR_KEYS.
        if not option & OPT_NON_STR_KEYS:
            raise JSONEncodeError("Dict key must be str") from err
        text = json.dumps(_str_keys(obj, option), **kwargs)
    data = text.encode()
    for key, contents in fragments.items():
        data = data.replace(json.dumps(key, ensure_ascii=False).encode(), contents, 1)
    if option & OPT_APPEND_NEWLINE:
        data += b"\n"
    return data


def loads(obj: bytes | bytearray | memoryview | str) -> Any:
    """Come orjson.loads."""
    if isinstance(obj, memoryview):
        obj = obj.tobytes()
    try:
        return json.loads(obj)
    except json.JSONDecodeError as err:
        raise JSONDecodeError(err.msg, err.doc, err.pos) from None
    except UnicodeDecodeError as err:
        raise JSONDecodeError(str(err), "", 0) from None


def _call_default(default: Any, value: Any) -> Any:
    if default is None:
        raise JSONEncodeError(f"Type is not JSON serializable: {type(value).__name__}")
    try:
        return default(value)
    except TypeError as err:
        raise JSONEncodeError(str(err)) from err


def _datetime(value: datetime.datetime, option: int) -> str:
    if value.tzinfo is None and option & OPT_NAIVE_UTC:
        value = value.replace(tzinfo=datetime.UTC)
    if option & OPT_OMIT_MICROSECONDS:
        value = value.replace(microsecond=0)
    text = value.isoformat()
    if option & OPT_UTC_Z and text.endswith("+00:00"):
        text = text[:-6] + "Z"
    return text


def _without_nan(value: Any) -> Any:
    if isinstance(value, float) and (math.isnan(value) or math.isinf(value)):
        return None
    if isinstance(value, dict):
        return {key: _without_nan(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_without_nan(item) for item in value]
    return value


def _str_keys(value: Any, option: int) -> Any:
    if isinstance(value, dict):
        return {_key(key, option): _str_keys(item, option) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_str_keys(item, option) for item in value]
    return value


def _key(key: Any, option: int) -> str:
    if isinstance(key, str):
        return key
    if isinstance(key, enum.Enum):
        return _key(key.value, option)
    if isinstance(key, bool):
        return "true" if key else "false"
    if key is None:
        return "null"
    if isinstance(key, datetime.datetime):
        return _datetime(key, option)
    if isinstance(key, (datetime.date, datetime.time)):
        return key.isoformat()
    return str(key)
