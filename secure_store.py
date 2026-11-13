#!/usr/bin/env python3
"""
secure_store.py - Security primitives for dc-

Provides:
  * AES-256-GCM encryption of the token file at rest (transparent migration).
  * PBKDF2-HMAC-SHA256 password hashing for workspace credentials.
  * Atomic, permission-restricted file writes.

The encryption key is read from ``DC_SECRET_KEY`` when set, otherwise a
random key is created in ``.dc_secret`` next to this file (git-ignored).
Set ``DC_SECRET_KEY`` in container/cloud deployments so the token file
survives restarts.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import os
import secrets
import stat
import sys
import tempfile

TOKEN_MAGIC = "DCENC1"
TOKEN_HEADER = "# dc- token store (AES-256-GCM encrypted). Manage via the dc- UI or TUI.\n"
KEY_FILENAME = ".dc_secret"
PBKDF2_ITERATIONS = 600_000
_HASH_PREFIX = "pbkdf2_sha256"


class TokenStoreError(RuntimeError):
    """Raised when an encrypted token store cannot be read or decrypted."""


# ---------------------------------------------------------------------------
# Key management
# ---------------------------------------------------------------------------
def _base_dir() -> str:
    return os.path.dirname(os.path.abspath(__file__))


def _key_path() -> str:
    override = os.environ.get("DC_SECRET_FILE", "").strip()
    if override:
        return os.path.expanduser(override)
    return os.path.join(_base_dir(), KEY_FILENAME)


def _read_or_create_local_key() -> bytes:
    path = _key_path()
    try:
        with open(path, "rb") as f:
            raw = f.read().strip()
        if raw:
            return hashlib.sha256(raw).digest()
    except FileNotFoundError:
        pass
    except OSError as exc:
        raise TokenStoreError(f"Cannot read secret key file {path}: {exc}") from exc

    raw = base64.urlsafe_b64encode(secrets.token_bytes(32))
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
        try:
            os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)
        except OSError:
            pass
        sys.stderr.write(
            f"[secure_store] Created local encryption key: {path}\n"
            "[secure_store] Back it up or set DC_SECRET_KEY; without it encrypted tokens cannot be read.\n"
        )
    except FileExistsError:
        with open(path, "rb") as f:
            raw = f.read().strip()
    except OSError as exc:
        raise TokenStoreError(f"Cannot create secret key file {path}: {exc}") from exc
    return hashlib.sha256(raw).digest()


def _secret_key() -> bytes:
    secret = os.environ.get("DC_SECRET_KEY", "").strip()
    if secret:
        return hashlib.sha256(secret.encode("utf-8")).digest()
    return _read_or_create_local_key()


def crypto_available() -> bool:
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: F401
        return True
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Token file encryption
# ---------------------------------------------------------------------------
def _encrypt_text(text: str) -> str:
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError as exc:  # pragma: no cover - depends on environment
        raise TokenStoreError(
            "cryptography is required for encrypted token storage. "
            "Install it with: pip install cryptography"
        ) from exc
    nonce = secrets.token_bytes(12)
    blob = AESGCM(_secret_key()).encrypt(nonce, text.encode("utf-8"), TOKEN_MAGIC.encode())
    return base64.b64encode(nonce + blob).decode("ascii")


def _decrypt_text(encoded: str) -> str:
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError as exc:  # pragma: no cover
        raise TokenStoreError("cryptography is required to read encrypted tokens") from exc
    try:
        data = base64.b64decode(encoded.strip(), validate=True)
        nonce, blob = data[:12], data[12:]
        return AESGCM(_secret_key()).decrypt(nonce, blob, TOKEN_MAGIC.encode()).decode("utf-8")
    except TokenStoreError:
        raise
    except Exception as exc:
        raise TokenStoreError(
            "Could not decrypt tokens.txt. The encryption key is missing or wrong. "
            "Restore .dc_secret / set DC_SECRET_KEY, or restore a token backup."
        ) from exc


def is_encrypted(raw: str) -> bool:
    return any(line.strip() == TOKEN_MAGIC for line in raw.splitlines()[:5])


def read_token_lines(path: str, migrate: bool = True) -> list[str]:
    """Return the lines of a token file, decrypting it if necessary.

    Plaintext files are migrated to the encrypted format automatically when
    the ``cryptography`` package is available.
    """
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        raw = f.read()

    if is_encrypted(raw):
        payload = []
        seen_magic = False
        for line in raw.splitlines():
            if not seen_magic:
                if line.strip() == TOKEN_MAGIC:
                    seen_magic = True
                continue
            payload.append(line)
        return _decrypt_text("".join(payload)).splitlines()

    lines = raw.splitlines()
    if migrate and raw.strip() and crypto_available():
        try:
            write_token_lines(path, lines)
        except Exception as exc:  # keep plaintext readable if migration fails
            sys.stderr.write(f"[secure_store] Token encryption migration skipped: {exc}\n")
    return lines


def write_token_lines(path: str, lines: list[str], encrypt: bool | None = None) -> None:
    """Atomically write token lines, encrypted unless disabled or unsupported."""
    text = "\n".join(str(line).rstrip("\r\n") for line in lines)
    if lines:
        text += "\n"
    if encrypt is None:
        encrypt = crypto_available()
    if encrypt:
        content = TOKEN_HEADER + TOKEN_MAGIC + "\n" + _encrypt_text(text) + "\n"
    else:
        content = text
    _atomic_write(path, content.encode("utf-8"))


def append_token_lines(path: str, lines: list[str]) -> None:
    existing = read_token_lines(path)
    existing.extend(lines)
    write_token_lines(path, existing)


# ---------------------------------------------------------------------------
# Password hashing
# ---------------------------------------------------------------------------
def hash_password(password: str) -> str:
    if password is None:
        password = ""
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, PBKDF2_ITERATIONS)
    return "$".join(
        (
            _HASH_PREFIX,
            str(PBKDF2_ITERATIONS),
            base64.b64encode(salt).decode("ascii"),
            base64.b64encode(digest).decode("ascii"),
        )
    )


def is_password_hash(value: str) -> bool:
    return isinstance(value, str) and value.startswith(_HASH_PREFIX + "$")


def verify_password(password: str, stored: str) -> bool:
    """Constant-time check supporting hashed and legacy plaintext values."""
    if not isinstance(password, str) or not isinstance(stored, str) or not stored:
        return False
    if not is_password_hash(stored):
        return hmac.compare_digest(password, stored)
    try:
        prefix, iterations, salt_b64, digest_b64 = stored.split("$", 3)
        if prefix != _HASH_PREFIX:
            return False
        salt = base64.b64decode(salt_b64)
        expected = base64.b64decode(digest_b64)
        actual = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, int(iterations))
        return hmac.compare_digest(actual, expected)
    except Exception:
        return False


def password_needs_rehash(stored: str) -> bool:
    if not is_password_hash(stored):
        return True
    try:
        return int(stored.split("$", 3)[1]) < PBKDF2_ITERATIONS
    except Exception:
        return True


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def mask_token(token: str, visible: int = 4) -> str:
    if not token:
        return ""
    token = str(token)
    if len(token) <= visible:
        return "*" * len(token)
    return "*" * (len(token) - visible) + token[-visible:]


def atomic_write_text(path: str, text: str) -> None:
    """Atomically write text with owner-only permissions where supported."""
    _atomic_write(path, text.encode("utf-8"))


def _atomic_write(path: str, data: bytes) -> None:
    directory = os.path.dirname(os.path.abspath(path)) or "."
    os.makedirs(directory, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(prefix=".tmp-", dir=directory)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        try:
            os.chmod(tmp_path, stat.S_IRUSR | stat.S_IWUSR)
        except OSError:
            pass
        os.replace(tmp_path, path)
    finally:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:
                pass
