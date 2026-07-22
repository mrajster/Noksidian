#!/usr/bin/env python3
# -*- coding: ascii -*-
"""nokcrypt.py -- desktop companion tool for Noksidian encrypted vaults.

Implements the exact byte formats from CONTRACTS-CRYPTO.md:

  dk      = PBKDF2-HMAC-SHA256(UTF8(password), salt16, iterations, dkLen=64)
  encKey  = dk[0:32]   (AES-256 key)
  macKey  = dk[32:64]  (HMAC-SHA256 key)

  _vault.nkv descriptor (60 bytes):
    "NKV1" | ver 0x01 | kdf 0x01 | iterations int32BE | 0x10 | salt16 |
    0x20 | HMAC-SHA256(macKey, "noksidian-check-v1")

  encrypted file (NKE1):
    "NKE1" | ver 0x01 | flags 0x00 | IV16 | AES-256-CTR(encKey, IV, plain) |
    HMAC-SHA256(macKey, header||IV||ct)          (encrypt-then-MAC)

  CTR counter block increments as one 128-bit big-endian integer (wraparound).
  Decrypt verifies the MAC FIRST (hmac.compare_digest), then runs CTR.

Python 3, stdlib only. AES-256 is embedded pure Python; when the
'cryptography' package is importable it is used as a fast path unless the
environment variable NOKCRYPT_PUREPY=1 forces the pure-Python AES.

Subcommands:
  init DIR --password PW [--iterations N]      create DIR/_vault.nkv
  encrypt DIR [--password PW]                  encrypt vault in place, recursive
  decrypt DIR [--password PW]                  decrypt vault in place, recursive
  cat FILE [--password PW]                     decrypt one file to stdout
  enc FILE [--password PW]                     encrypt one file in place
  dec FILE [--password PW]                     decrypt one file in place
  selftest                                     assert every contract test vector

Hidden (testing/interop): init --salt HEX, enc --iv HEX, --vault DIR on the
single-file commands. Wrong password / corrupt input exits 2 with a clear
message, never a traceback.
"""

import argparse
import getpass
import hashlib
import hmac
import os
import struct
import sys

MAGIC_NKV = b"NKV1"
MAGIC_NKE = b"NKE1"
CHECK_MSG = b"noksidian-check-v1"
VAULT_FILE = "_vault.nkv"
DEFAULT_ITERATIONS = 8192
NKV_LEN = 60          # full descriptor
NKE_HEADER_LEN = 22   # magic4 + ver1 + flags1 + iv16
NKE_MIN_LEN = NKE_HEADER_LEN + 32  # header + mac, empty plaintext

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    HAVE_CRYPTOGRAPHY = True
except Exception:  # pragma: no cover - absent/broken optional dependency
    HAVE_CRYPTOGRAPHY = False


class VaultError(Exception):
    """User-facing error: bad input, wrong password, corrupt data."""


# ---------------------------------------------------------------------------
# Embedded pure-Python AES-256 (encrypt-only; CTR needs only the forward
# cipher). FIPS-197, Nk=8, Nr=14. T-table implementation for speed.
# ---------------------------------------------------------------------------

_SBOX = bytes.fromhex(
    "637c777bf26b6fc53001672bfed7ab76"
    "ca82c97dfa5947f0add4a2af9ca472c0"
    "b7fd9326363ff7cc34a5e5f171d83115"
    "04c723c31896059a071280e2eb27b275"
    "09832c1a1b6e5aa0523bd6b329e32f84"
    "53d100ed20fcb15b6acbbe394a4c58cf"
    "d0efaafb434d338545f9027f503c9fa8"
    "51a3408f929d38f5bcb6da2110fff3d2"
    "cd0c13ec5f974417c4a77e3d645d1973"
    "60814fdc222a908846eeb814de5e0bdb"
    "e0323a0a4906245cc2d3ac629195e479"
    "e7c8376d8dd54ea96c56f4ea657aae08"
    "ba78252e1ca6b4c6e8dd741f4bbd8b8a"
    "703eb5664803f60e613557b986c11d9e"
    "e1f8981169d98e949b1e87e9ce5528df"
    "8ca1890dbfe6426841992d0fb054bb16"
)


def _mul2(x):
    x <<= 1
    if x & 0x100:
        x ^= 0x11B
    return x & 0xFF


_TE0 = tuple(((_mul2(s) << 24) | (s << 16) | (s << 8) | (_mul2(s) ^ s))
             for s in _SBOX)
_TE1 = tuple(((t >> 8) | ((t & 0xFF) << 24)) for t in _TE0)
_TE2 = tuple(((t >> 8) | ((t & 0xFF) << 24)) for t in _TE1)
_TE3 = tuple(((t >> 8) | ((t & 0xFF) << 24)) for t in _TE2)


class Aes256:
    """AES-256 forward cipher (block encrypt only)."""

    __slots__ = ("_rk",)

    def __init__(self, key32):
        if len(key32) != 32:
            raise ValueError("AES-256 key must be 32 bytes")
        rk = list(struct.unpack(">8I", key32))
        rcon = 1
        for i in range(8, 60):
            t = rk[i - 1]
            if i % 8 == 0:
                t = ((_SBOX[(t >> 16) & 0xFF] << 24)
                     | (_SBOX[(t >> 8) & 0xFF] << 16)
                     | (_SBOX[t & 0xFF] << 8)
                     | _SBOX[(t >> 24) & 0xFF]) ^ (rcon << 24)
                rcon = _mul2(rcon)
            elif i % 8 == 4:
                t = ((_SBOX[(t >> 24) & 0xFF] << 24)
                     | (_SBOX[(t >> 16) & 0xFF] << 16)
                     | (_SBOX[(t >> 8) & 0xFF] << 8)
                     | _SBOX[t & 0xFF])
            rk.append(rk[i - 8] ^ t)
        self._rk = rk

    def encrypt_block(self, block16):
        rk = self._rk
        s0, s1, s2, s3 = struct.unpack(">4I", block16)
        s0 ^= rk[0]
        s1 ^= rk[1]
        s2 ^= rk[2]
        s3 ^= rk[3]
        k = 4
        for _ in range(13):  # Nr-1 full rounds
            t0 = (_TE0[s0 >> 24] ^ _TE1[(s1 >> 16) & 0xFF]
                  ^ _TE2[(s2 >> 8) & 0xFF] ^ _TE3[s3 & 0xFF] ^ rk[k])
            t1 = (_TE0[s1 >> 24] ^ _TE1[(s2 >> 16) & 0xFF]
                  ^ _TE2[(s3 >> 8) & 0xFF] ^ _TE3[s0 & 0xFF] ^ rk[k + 1])
            t2 = (_TE0[s2 >> 24] ^ _TE1[(s3 >> 16) & 0xFF]
                  ^ _TE2[(s0 >> 8) & 0xFF] ^ _TE3[s1 & 0xFF] ^ rk[k + 2])
            t3 = (_TE0[s3 >> 24] ^ _TE1[(s0 >> 16) & 0xFF]
                  ^ _TE2[(s1 >> 8) & 0xFF] ^ _TE3[s2 & 0xFF] ^ rk[k + 3])
            s0, s1, s2, s3 = t0, t1, t2, t3
            k += 4
        b = _SBOX  # final round: SubBytes + ShiftRows + AddRoundKey
        t0 = ((b[s0 >> 24] << 24) | (b[(s1 >> 16) & 0xFF] << 16)
              | (b[(s2 >> 8) & 0xFF] << 8) | b[s3 & 0xFF]) ^ rk[k]
        t1 = ((b[s1 >> 24] << 24) | (b[(s2 >> 16) & 0xFF] << 16)
              | (b[(s3 >> 8) & 0xFF] << 8) | b[s0 & 0xFF]) ^ rk[k + 1]
        t2 = ((b[s2 >> 24] << 24) | (b[(s3 >> 16) & 0xFF] << 16)
              | (b[(s0 >> 8) & 0xFF] << 8) | b[s1 & 0xFF]) ^ rk[k + 2]
        t3 = ((b[s3 >> 24] << 24) | (b[(s0 >> 16) & 0xFF] << 16)
              | (b[(s1 >> 8) & 0xFF] << 8) | b[s2 & 0xFF]) ^ rk[k + 3]
        return struct.pack(">4I", t0, t1, t2, t3)


def _ctr_purepy(key32, iv16, data):
    """AES-256-CTR, pure Python. Counter = 128-bit BE integer, wraps mod 2^128."""
    if not data:
        return b""
    enc = Aes256(key32).encrypt_block
    c0 = int.from_bytes(iv16, "big")
    mask = (1 << 128) - 1
    n = (len(data) + 15) // 16
    ks = bytearray()
    for i in range(n):
        ks += enc(((c0 + i) & mask).to_bytes(16, "big"))
    x = int.from_bytes(data, "big") ^ int.from_bytes(ks[: len(data)], "big")
    return x.to_bytes(len(data), "big")


def _ctr_cryptography(key32, iv16, data):
    encryptor = Cipher(algorithms.AES(key32), modes.CTR(iv16)).encryptor()
    return encryptor.update(data) + encryptor.finalize()


def _purepy_forced():
    return os.environ.get("NOKCRYPT_PUREPY", "") not in ("", "0")


def aes256_ctr(key32, iv16, data, backend="auto"):
    """backend: 'auto' (env-aware), 'pure', or 'cryptography'."""
    if len(iv16) != 16:
        raise ValueError("CTR IV must be 16 bytes")
    if backend == "pure":
        return _ctr_purepy(key32, iv16, data)
    if backend == "cryptography":
        if not HAVE_CRYPTOGRAPHY:
            raise ValueError("cryptography package not available")
        return _ctr_cryptography(key32, iv16, data)
    if HAVE_CRYPTOGRAPHY and not _purepy_forced():
        return _ctr_cryptography(key32, iv16, data)
    return _ctr_purepy(key32, iv16, data)


def active_backend():
    if HAVE_CRYPTOGRAPHY and not _purepy_forced():
        return "cryptography"
    return "pure-python"


# ---------------------------------------------------------------------------
# Vault primitives (NKV1 descriptor, NKE1 file format)
# ---------------------------------------------------------------------------

def derive_keys(password, salt16, iterations):
    """-> (encKey32, macKey32) per contract."""
    dk = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"),
                             salt16, iterations, 64)
    return dk[:32], dk[32:]


def _check_value(mac_key):
    return hmac.new(mac_key, CHECK_MSG, hashlib.sha256).digest()


def build_descriptor(password, salt16, iterations):
    if len(salt16) != 16:
        raise VaultError("salt must be exactly 16 bytes")
    if not 1 <= iterations <= 0x7FFFFFFF:
        raise VaultError("iterations must be in 1..2^31-1")
    _, mac_key = derive_keys(password, salt16, iterations)
    return (MAGIC_NKV + b"\x01\x01" + struct.pack(">i", iterations)
            + b"\x10" + salt16 + b"\x20" + _check_value(mac_key))


def is_descriptor(data):
    return data[:4] == MAGIC_NKV


def is_encrypted(data):
    return data[:4] == MAGIC_NKE and len(data) >= NKE_MIN_LEN


def open_descriptor(data, password):
    """Validate descriptor + password -> (encKey32, macKey32)."""
    if (len(data) != NKV_LEN or data[:4] != MAGIC_NKV or data[4] != 0x01
            or data[5] != 0x01 or data[10] != 16 or data[27] != 32):
        raise VaultError("bad descriptor")
    iterations = struct.unpack(">i", data[6:10])[0]
    if iterations <= 0:
        raise VaultError("bad descriptor")
    salt = data[11:27]
    enc_key, mac_key = derive_keys(password, salt, iterations)
    if not hmac.compare_digest(_check_value(mac_key), data[28:60]):
        raise VaultError("wrong password")
    return enc_key, mac_key


def encrypt_bytes(enc_key, mac_key, plain, iv16=None, backend="auto"):
    if iv16 is None:
        iv16 = os.urandom(16)
    if len(iv16) != 16:
        raise VaultError("IV must be exactly 16 bytes")
    body = (MAGIC_NKE + b"\x01\x00" + iv16
            + aes256_ctr(enc_key, iv16, plain, backend))
    return body + hmac.new(mac_key, body, hashlib.sha256).digest()


def decrypt_bytes(enc_key, mac_key, data, backend="auto"):
    """MAC first (constant-time), then CTR. Raises VaultError."""
    if len(data) < NKE_MIN_LEN or data[:4] != MAGIC_NKE:
        raise VaultError("bad header")
    body, mac = data[:-32], data[-32:]
    if not hmac.compare_digest(hmac.new(mac_key, body, hashlib.sha256).digest(), mac):
        raise VaultError("mac mismatch")
    if data[4] != 0x01 or data[5] != 0x00:
        raise VaultError("bad header")
    return aes256_ctr(enc_key, body[6:22], body[22:], backend)


# ---------------------------------------------------------------------------
# Filesystem helpers
# ---------------------------------------------------------------------------

SKIP_DIRS = frozenset((".noksidian", ".obsidian"))


def _skip_entry(name):
    return name in SKIP_DIRS or name.startswith(".git")


def iter_vault_files(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if not _skip_entry(d))
        for fn in sorted(filenames):
            if fn == VAULT_FILE or _skip_entry(fn):
                continue
            yield os.path.join(dirpath, fn)


def read_file(path):
    with open(path, "rb") as f:
        return f.read()


def write_file_atomic(path, data):
    tmp = path + ".nokcrypt.tmp"
    try:
        with open(tmp, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        try:
            os.chmod(tmp, os.stat(path).st_mode & 0o7777)
        except OSError:
            pass
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def get_password(args, confirm=False):
    if getattr(args, "password", None) is not None:
        return args.password
    if not sys.stdin.isatty() and not os.environ.get("NOKCRYPT_ALLOW_PIPE_PROMPT"):
        raise VaultError("no --password given and stdin is not a terminal")
    pw = getpass.getpass("Vault password: ")
    if confirm:
        if getpass.getpass("Confirm password: ") != pw:
            raise VaultError("passwords do not match")
    return pw


def find_vault_root(start_dir, explicit=None):
    """Walk upward from start_dir looking for _vault.nkv."""
    if explicit is not None:
        if not os.path.isfile(os.path.join(explicit, VAULT_FILE)):
            raise VaultError("no %s in --vault directory '%s'" % (VAULT_FILE, explicit))
        return os.path.abspath(explicit)
    d = os.path.abspath(start_dir)
    while True:
        if os.path.isfile(os.path.join(d, VAULT_FILE)):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            raise VaultError(
                "no %s found in '%s' or any parent (use --vault DIR)"
                % (VAULT_FILE, start_dir))
        d = parent


def load_vault_keys(vault_dir, password):
    desc_path = os.path.join(vault_dir, VAULT_FILE)
    if not os.path.isfile(desc_path):
        raise VaultError("no %s in '%s' (run 'init' first)" % (VAULT_FILE, vault_dir))
    return open_descriptor(read_file(desc_path), password)


def parse_hex(value, expected_len, what):
    try:
        raw = bytes.fromhex(value)
    except ValueError:
        raise VaultError("%s must be hex" % what)
    if len(raw) != expected_len:
        raise VaultError("%s must be %d hex chars (%d bytes)"
                         % (what, expected_len * 2, expected_len))
    return raw


# ---------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------

def cmd_init(args):
    vault_dir = os.path.abspath(args.dir)
    desc_path = os.path.join(vault_dir, VAULT_FILE)
    if os.path.exists(desc_path):
        raise VaultError("'%s' already exists; refusing to overwrite" % desc_path)
    if not 1 <= args.iterations <= 0x7FFFFFFF:
        raise VaultError("--iterations must be in 1..2^31-1")
    salt = (parse_hex(args.salt, 16, "--salt") if args.salt is not None
            else os.urandom(16))
    password = get_password(args, confirm=(args.password is None))
    descriptor = build_descriptor(password, salt, args.iterations)
    os.makedirs(vault_dir, exist_ok=True)
    with open(desc_path, "wb") as f:
        f.write(descriptor)
    print("initialized vault: %s" % desc_path)
    print("  iterations: %d   salt: %s" % (args.iterations, salt.hex()))
    print("  AES backend: %s" % active_backend())
    return 0


def _walk_transform(args, mode):
    """mode: 'encrypt' or 'decrypt'. Recursive, in-place, magic-sniffed."""
    root = os.path.abspath(args.dir)
    if not os.path.isdir(root):
        raise VaultError("'%s' is not a directory" % args.dir)
    password = get_password(args)
    enc_key, mac_key = load_vault_keys(root, password)  # wrong password stops here
    done = skipped = failed = 0
    for path in iter_vault_files(root):
        rel = os.path.relpath(path, root)
        try:
            data = read_file(path)
            if is_descriptor(data):
                print("  skip  %s (vault descriptor)" % rel)
                skipped += 1
                continue
            already = is_encrypted(data)
            if mode == "encrypt":
                if already:
                    print("  skip  %s (already encrypted)" % rel)
                    skipped += 1
                    continue
                out = encrypt_bytes(enc_key, mac_key, data)
                verb = "enc  "
            else:
                if not already:
                    print("  skip  %s (not encrypted)" % rel)
                    skipped += 1
                    continue
                out = decrypt_bytes(enc_key, mac_key, data)
                verb = "dec  "
            write_file_atomic(path, out)
            print("  %s %s (%d -> %d bytes)" % (verb, rel, len(data), len(out)))
            done += 1
        except (VaultError, OSError) as e:
            print("  FAIL  %s: %s" % (rel, e))
            failed += 1
    label = "encrypted" if mode == "encrypt" else "decrypted"
    print("%s %d file(s), skipped %d, failed %d  [backend: %s]"
          % (label, done, skipped, failed, active_backend()))
    if failed:
        raise VaultError("%d file(s) failed" % failed)
    return 0


def cmd_encrypt(args):
    return _walk_transform(args, "encrypt")


def cmd_decrypt(args):
    return _walk_transform(args, "decrypt")


def _single_file_keys(args, path):
    vault_dir = find_vault_root(os.path.dirname(os.path.abspath(path)),
                                getattr(args, "vault", None))
    return load_vault_keys(vault_dir, get_password(args))


def cmd_cat(args):
    data = read_file(args.file)
    if is_descriptor(data):
        raise VaultError("'%s' is a vault descriptor, not a note" % args.file)
    if not is_encrypted(data):
        # codec-seam semantics: files without NKE1 magic pass through untouched
        print("nokcrypt: note: '%s' is not encrypted; passing through"
              % args.file, file=sys.stderr)
        sys.stdout.buffer.write(data)
        return 0
    enc_key, mac_key = _single_file_keys(args, args.file)
    sys.stdout.buffer.write(decrypt_bytes(enc_key, mac_key, data))
    sys.stdout.buffer.flush()
    return 0


def cmd_enc(args):
    data = read_file(args.file)
    if is_descriptor(data):
        raise VaultError("refusing to encrypt vault descriptor '%s'" % args.file)
    if is_encrypted(data):
        raise VaultError("'%s' is already encrypted" % args.file)
    iv = parse_hex(args.iv, 16, "--iv") if args.iv is not None else None
    enc_key, mac_key = _single_file_keys(args, args.file)
    out = encrypt_bytes(enc_key, mac_key, data, iv16=iv)
    write_file_atomic(args.file, out)
    print("enc %s (%d -> %d bytes, iv=%s)"
          % (args.file, len(data), len(out), out[6:22].hex()))
    return 0


def cmd_dec(args):
    data = read_file(args.file)
    if not is_encrypted(data):
        raise VaultError("'%s' is not an NKE1 encrypted file" % args.file)
    enc_key, mac_key = _single_file_keys(args, args.file)
    out = decrypt_bytes(enc_key, mac_key, data)
    write_file_atomic(args.file, out)
    print("dec %s (%d -> %d bytes)" % (args.file, len(data), len(out)))
    return 0


# ---------------------------------------------------------------------------
# Selftest -- every required vector from CONTRACTS-CRYPTO.md
# ---------------------------------------------------------------------------

class SelftestFailure(Exception):
    pass


def _st(cond, msg):
    if not cond:
        raise SelftestFailure(msg)


def _expect_error(fn, what, want_msg=None):
    try:
        fn()
    except VaultError as e:
        if want_msg is not None:
            _st(str(e) == want_msg,
                "%s: expected error %r, got %r" % (what, want_msg, str(e)))
        return
    raise SelftestFailure("%s: expected VaultError, none raised" % what)


def _selftest_sha256():
    vecs = [
        (b"", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        (b"abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
        (b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
         "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"),
    ]
    for msg, want in vecs:
        _st(hashlib.sha256(msg).hexdigest() == want, "SHA-256(%r)" % msg)


def _selftest_hmac():
    vecs = [  # RFC 4231 test cases 1-4, full 32-byte outputs
        (b"\x0b" * 20, b"Hi There",
         "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
        (b"Jefe", b"what do ya want for nothing?",
         "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
        (b"\xaa" * 20, b"\xdd" * 50,
         "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
        (bytes(range(1, 26)), b"\xcd" * 50,
         "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"),
    ]
    for i, (key, msg, want) in enumerate(vecs, 1):
        got = hmac.new(key, msg, hashlib.sha256).hexdigest()
        _st(got == want, "HMAC-SHA256 RFC4231 case %d" % i)


def _selftest_pbkdf2():
    vecs = [
        ("passwd", b"salt", 1,
         "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
         "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"),
        # NOTE: CONTRACTS-CRYPTO.md prose says P="password", but the required
        # 64-byte hex is the canonical RFC 7914 vector, whose password is
        # "Password" (capital P). The byte-exact hex is authoritative.
        ("Password", b"NaCl", 80000,
         "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56"
         "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"),
    ]
    for pw, salt, c, want in vecs:
        got = hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"), salt, c, 64)
        _st(got.hex() == want, "PBKDF2(P=%r,c=%d)" % (pw, c))
        want_b = bytes.fromhex(want)
        _st(derive_keys(pw, salt, c) == (want_b[:32], want_b[32:]),
            "derive_keys split for P=%r" % pw)


def _selftest_aes_block():
    key = bytes.fromhex("000102030405060708090a0b0c0d0e0f"
                        "101112131415161718191a1b1c1d1e1f")
    pt = bytes.fromhex("00112233445566778899aabbccddeeff")
    ct = Aes256(key).encrypt_block(pt)
    _st(ct.hex() == "8ea2b7ca516745bfeafc49904b496089",
        "AES-256 FIPS-197 C.3 block (got %s)" % ct.hex())


_CTR_KEY = bytes.fromhex("603deb1015ca71be2b73aef0857d7781"
                         "1f352c073b6108d72d9810a30914dff4")
_CTR_IV = bytes.fromhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
_CTR_PT = bytes.fromhex("6bc1bee22e409f96e93d7e117393172a"
                        "ae2d8a571e03ac9c9eb76fac45af8e51"
                        "30c81c46a35ce411e5fbc1191a0a52ef"
                        "f69f2445df4f9b17ad2b417be66c3710")
_CTR_CT = bytes.fromhex("601ec313775789a5b7a7f504bbf3d228"
                        "f443e3ca4d62b59aca84e990cacaf5c5"
                        "2b0930daa23de94ce87017ba2d84988d"
                        "dfc9c58db67aada613c2dd08457941a6")


def _selftest_ctr(backend):
    got = aes256_ctr(_CTR_KEY, _CTR_IV, _CTR_PT, backend)
    _st(got == _CTR_CT, "SP 800-38A F.5.5 CTR encrypt [%s]" % backend)
    _st(aes256_ctr(_CTR_KEY, _CTR_IV, _CTR_CT, backend) == _CTR_PT,
        "SP 800-38A F.5.5 CTR decrypt [%s]" % backend)
    # partial final block + counter wraparound
    _st(aes256_ctr(_CTR_KEY, _CTR_IV, _CTR_PT[:37], backend) == _CTR_CT[:37],
        "CTR partial final block [%s]" % backend)
    wrap_iv = b"\xff" * 16
    ref = _ctr_purepy(_CTR_KEY, wrap_iv, b"\x00" * 48)
    _st(aes256_ctr(_CTR_KEY, wrap_iv, b"\x00" * 48, backend) == ref,
        "CTR 128-bit counter wraparound [%s]" % backend)


def _selftest_descriptor():
    salt = bytes(range(16))
    desc = build_descriptor("hunter2", salt, 1000)
    _st(len(desc) == NKV_LEN, "descriptor length == 60")
    _st(desc[:4] == MAGIC_NKV and desc[4] == 1 and desc[5] == 1,
        "descriptor magic/version/kdf")
    _st(struct.unpack(">i", desc[6:10])[0] == 1000, "descriptor iterations BE")
    _st(desc[10] == 16 and desc[11:27] == salt and desc[27] == 32,
        "descriptor salt block")
    _st(is_descriptor(desc), "is_descriptor(descriptor)")
    enc_key, mac_key = open_descriptor(desc, "hunter2")
    _st((enc_key, mac_key) == derive_keys("hunter2", salt, 1000),
        "descriptor round-trip keys")
    _expect_error(lambda: open_descriptor(desc, "wrong"),
                  "wrong password", "wrong password")
    bad = bytearray(desc)
    bad[59] ^= 0x01  # corrupt check
    _expect_error(lambda: open_descriptor(bytes(bad), "hunter2"),
                  "corrupted check", "wrong password")
    _expect_error(lambda: open_descriptor(b"XXXX" + desc[4:], "hunter2"),
                  "bad magic", "bad descriptor")
    _expect_error(lambda: open_descriptor(desc[:59], "hunter2"),
                  "short descriptor", "bad descriptor")


def _selftest_roundtrip(enc_key, mac_key, backend):
    for n in (0, 1, 15, 16, 17, 100 * 1024):
        plain = bytes((i * 89 + 41) & 0xFF for i in range(n))
        blob = encrypt_bytes(enc_key, mac_key, plain, backend=backend)
        _st(len(blob) == NKE_HEADER_LEN + n + 32, "NKE1 size for n=%d" % n)
        _st(is_encrypted(blob), "is_encrypted(blob) n=%d" % n)
        _st(decrypt_bytes(enc_key, mac_key, blob, backend) == plain,
            "round-trip n=%d [%s]" % (n, backend))
    if HAVE_CRYPTOGRAPHY:  # cross-backend byte-exact with a fixed IV
        iv = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
        msg = b"interop check between AES backends"
        _st(encrypt_bytes(enc_key, mac_key, msg, iv, "pure")
            == encrypt_bytes(enc_key, mac_key, msg, iv, "cryptography"),
            "pure vs cryptography byte-exact ciphertext")


def _selftest_tamper(enc_key, mac_key):
    plain = b"# Secret note\nnothing to see here.\n"
    iv = bytes.fromhex("0f0e0d0c0b0a09080706050403020100")
    blob = encrypt_bytes(enc_key, mac_key, plain, iv)
    for i in range(len(blob)):  # single-bit flip at EVERY byte -> rejected
        for bit in (0x01, 0x80):
            t = bytearray(blob)
            t[i] ^= bit
            try:
                decrypt_bytes(enc_key, mac_key, bytes(t))
                raise SelftestFailure("bit flip byte %d bit %#x not detected"
                                      % (i, bit))
            except VaultError as e:
                if i >= 4:  # outside magic: MAC must be what rejects it
                    _st(str(e) == "mac mismatch",
                        "flip at byte %d: expected 'mac mismatch', got %r"
                        % (i, str(e)))
    for cut in (len(blob) - 1, NKE_MIN_LEN - 1, 10, 0):
        _expect_error(lambda c=cut: decrypt_bytes(enc_key, mac_key, blob[:c]),
                      "truncation to %d" % cut)
    # wrong-key MAC rejection
    _expect_error(lambda: decrypt_bytes(enc_key, b"\x00" * 32, blob),
                  "wrong mac key", "mac mismatch")
    _st(not is_encrypted(b"# plain markdown\n\nhello [[world]]\n"),
        "is_encrypted(plain markdown) is False")
    _st(not is_encrypted(b"NKE"), "is_encrypted(short junk) is False")
    _st(not is_encrypted(MAGIC_NKE + b"\x01\x00" + b"\x00" * 16),
        "is_encrypted(below minimum length) is False")


def cmd_selftest(_args):
    steps = []

    def add(name, fn):
        steps.append((name, fn))

    add("SHA-256 hashlib sanity (3 vectors)", _selftest_sha256)
    add("HMAC-SHA256 RFC 4231 cases 1-4", _selftest_hmac)
    add("PBKDF2-HMAC-SHA256 both contract vectors", _selftest_pbkdf2)
    add("AES-256 block FIPS-197 C.3 (embedded pure Python)", _selftest_aes_block)
    add("AES-256-CTR SP 800-38A F.5.5 [pure-python]",
        lambda: _selftest_ctr("pure"))
    if HAVE_CRYPTOGRAPHY:
        add("AES-256-CTR SP 800-38A F.5.5 [cryptography]",
            lambda: _selftest_ctr("cryptography"))
    add("NKV1 descriptor round-trip / wrong password / corruption",
        _selftest_descriptor)

    salt = bytes.fromhex("101112131415161718191a1b1c1d1e1f")
    enc_key, mac_key = derive_keys("correct horse", salt, 1000)
    add("NKE1 encrypt->decrypt round-trips 0/1/15/16/17/100K [pure-python]",
        lambda: _selftest_roundtrip(enc_key, mac_key, "pure"))
    if HAVE_CRYPTOGRAPHY:
        add("NKE1 encrypt->decrypt round-trips 0/1/15/16/17/100K [cryptography]",
            lambda: _selftest_roundtrip(enc_key, mac_key, "cryptography"))
    add("bit-flip -> MAC failure, truncation, magic sniff",
        lambda: _selftest_tamper(enc_key, mac_key))

    total = len(steps)
    print("nokcrypt selftest  (auto backend: %s, cryptography %s, NOKCRYPT_PUREPY=%s)"
          % (active_backend(),
             "available" if HAVE_CRYPTOGRAPHY else "NOT available",
             os.environ.get("NOKCRYPT_PUREPY", "")))
    for i, (name, fn) in enumerate(steps, 1):
        try:
            fn()
        except SelftestFailure as e:
            print("[%d/%d] %s ... FAIL" % (i, total, name))
            print("SELFTEST FAILED: %s" % e)
            return 1
        print("[%d/%d] %s ... ok" % (i, total, name))
    print("ALL SELFTESTS PASS")
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _add_password(sp):
    sp.add_argument("--password", metavar="PW",
                    help="vault password (otherwise prompted)")


def _add_vault(sp):
    sp.add_argument("--vault", metavar="DIR", help=argparse.SUPPRESS)


def build_parser():
    p = argparse.ArgumentParser(
        prog="nokcrypt",
        description="Noksidian encrypted-vault companion tool (NKV1/NKE1).")
    sub = p.add_subparsers(dest="cmd", metavar="COMMAND")
    sub.required = True

    sp = sub.add_parser("init", help="create _vault.nkv in DIR")
    sp.add_argument("dir")
    _add_password(sp)
    sp.add_argument("--iterations", type=int, default=DEFAULT_ITERATIONS,
                    metavar="N", help="PBKDF2 iterations (default %d)"
                    % DEFAULT_ITERATIONS)
    sp.add_argument("--salt", help=argparse.SUPPRESS)  # hidden: testing only
    sp.set_defaults(func=cmd_init)

    sp = sub.add_parser("encrypt", help="encrypt all vault files in DIR (in place)")
    sp.add_argument("dir")
    _add_password(sp)
    sp.set_defaults(func=cmd_encrypt)

    sp = sub.add_parser("decrypt", help="decrypt all vault files in DIR (in place)")
    sp.add_argument("dir")
    _add_password(sp)
    sp.set_defaults(func=cmd_decrypt)

    sp = sub.add_parser("cat", help="decrypt one file to stdout")
    sp.add_argument("file")
    _add_password(sp)
    _add_vault(sp)
    sp.set_defaults(func=cmd_cat)

    sp = sub.add_parser("enc", help="encrypt a single file in place")
    sp.add_argument("file")
    _add_password(sp)
    _add_vault(sp)
    sp.add_argument("--iv", help=argparse.SUPPRESS)  # hidden: interop tests
    sp.set_defaults(func=cmd_enc)

    sp = sub.add_parser("dec", help="decrypt a single file in place")
    sp.add_argument("file")
    _add_password(sp)
    _add_vault(sp)
    sp.set_defaults(func=cmd_dec)

    sp = sub.add_parser("selftest", help="run all contract test vectors")
    sp.set_defaults(func=cmd_selftest)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except VaultError as e:
        print("nokcrypt: error: %s" % e, file=sys.stderr)
        return 2
    except OSError as e:
        print("nokcrypt: error: %s" % e, file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("nokcrypt: interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
