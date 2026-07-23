# Noksidian — Encrypted Vault Contracts (addendum to CONTRACTS.md)

All language/API constraints from CONTRACTS.md apply unchanged (Java 1.3, CLDC 1.1, ASCII sources,
`nok.core.*` imports no `javax.*`). All integers below are BIG-ENDIAN. All new core classes live in
`nok.core` and are desktop-unit-tested.

## Threat model & scope

- Protects note/file CONTENTS at rest on the phone/memory card and inside the GitHub repo.
- File and folder NAMES stay plaintext (wikilink resolution + sane repo browsing; documented).
- Per-vault password. Wrong password / tampered file must be detected (MAC) — never garbage output.
- No secure RNG exists on CLDC: IVs on the phone are derived SIV-style (below). Any IV source is
  format-legal (desktop tool uses os.urandom).

## Key derivation

```
dk      = PBKDF2-HMAC-SHA256( UTF8(password), salt16, iterations, dkLen=64 )
encKey  = dk[0..32)     (AES-256 key)
macKey  = dk[32..64)    (HMAC-SHA256 key)
```
Default iterations: 8192.

## Vault descriptor — file `_vault.nkv` at the VAULT ROOT (synced like any binary file; UI hides it)

```
off len  value
0   4    magic "NKV1" (ASCII)
4   1    version = 0x01
5   1    kdf id  = 0x01 (PBKDF2-HMAC-SHA256)
6   4    iterations (int32 BE)
10  1    salt length = 16
11  16   salt
27  1    check length = 32
28  32   check = HMAC-SHA256(macKey, ASCII "noksidian-check-v1")
```
Total 60 bytes. `check` lets the app detect a wrong password before touching any file.

## Encrypted file format (any file type; applied to whole file bytes)

```
off   len  value
0     4    magic "NKE1" (ASCII)
4     1    version = 0x01
5     1    flags = 0x00
6     16   IV = initial CTR counter block
22    N    ciphertext = AES-256-CTR(encKey, IV, plaintext)
22+N  32   mac = HMAC-SHA256(macKey, bytes[0 .. 22+N))   (encrypt-then-MAC over header+IV+ct)
```
CTR keystream block i encrypts counterblock = (IV + i) where the 16-byte block increments as one
128-bit BE integer with wraparound. Decrypt: verify MAC FIRST (constant-time compare: OR of XORs),
then CTR (CTR decrypt == encrypt).

Phone IV derivation (deterministic, entropy-starved-device-safe):
`IV = first 16 bytes of HMAC-SHA256(macKey, ASCII "nok-iv" || SHA256(plaintext) || UTF8(path) || int64BE(timeMillis) || int32BE(counter))`
where counter is a per-session incrementing int. Desktop tools may use random IVs instead.

## New classes (exact public signatures)

```java
public final class Sha256 {                       // pure SHA-256 (FIPS 180-4)
    public Sha256();
    public void update(byte[] d, int off, int len);
    public byte[] digest();                       // finalizes; instance not reusable after
    public static byte[] hash(byte[] data);
}

public final class Hmac {
    public static byte[] sha256(byte[] key, byte[] msg);   // RFC 2104 with SHA-256
}

public final class Aes {                          // AES-256 only (Nk=8, Nr=14)
    public Aes(byte[] key32);
    public void encryptBlock(byte[] in, int inOff, byte[] out, int outOff);
    public static void ctr(byte[] key32, byte[] iv16, byte[] data, int off, int len); // in-place
}

public final class Pbkdf2 {
    public static byte[] hmacSha256(byte[] password, byte[] salt, int iterations, int dkLen);
}

public final class VaultCrypto {
    public static final int DEFAULT_ITERATIONS = 8192;
    public static byte[] deriveKeys(String password, byte[] salt16, int iterations); // 64 bytes
    public static byte[] newDescriptor(String password, byte[] salt16, int iterations); // 60-byte _vault.nkv
    public static VaultCrypto open(byte[] descriptor, String password); // throws IllegalArgumentException("wrong password") / ("bad descriptor")
    public VaultCrypto(byte[] encKey32, byte[] macKey32);               // for tests
    public static boolean isEncrypted(byte[] data);                     // NKE1 magic + minimum length
    public static boolean isDescriptor(byte[] data);                    // NKV1 magic
    public byte[] encrypt(byte[] plain, String path, long timeMillis);  // derives IV per spec (internal counter)
    public byte[] encryptWithIv(byte[] plain, byte[] iv16);             // fixed IV — tests/interop only
    public byte[] decrypt(byte[] data);   // throws IllegalArgumentException("mac mismatch") / ("bad header")
}
```

Salt generation on the phone (no secure RNG): salt = first 16 bytes of
SHA256(int64BE(timeMillis) || int64BE(freeMemory) || int32BE(objectHash) || UTF8(user password))
— computed by the CALLER of newDescriptor (UI glue), not by VaultCrypto; newDescriptor takes salt
as an argument for testability.

## Required test vectors (test mains must assert these)

- SHA-256: empty -> e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855;
  "abc" -> ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad;
  "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" ->
  248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1.
- HMAC-SHA256: RFC 4231 test cases 1-4 (assert full 32-byte outputs).
- AES-256 block: FIPS-197 C.3 (key 000102...1f, plaintext 00112233445566778899aabbccddeeff ->
  8ea2b7ca516745bfeafc49904b496089).
- AES-256-CTR: NIST SP 800-38A F.5.5 (key 603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4,
  counter f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff, 4 blocks of the standard plaintext ->
  601ec313775789a5b7a7f504bbf3d228 f443e3ca4d62b59aca84e990cacaf5c5
  2b0930daa23de94ce87017ba2d84988d dfc9c58db67aada613c2dd08457941a6).
- PBKDF2-HMAC-SHA256: (P="passwd", S="salt", c=1, dkLen=64) ->
  55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783;
  (P="password", S="NaCl", c=80000, dkLen=64) ->
  4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d.
- VaultCrypto: descriptor round-trip (open with right password OK, wrong password throws,
  corrupted check throws); encrypt->decrypt round-trip (empty, 1 byte, 15/16/17 bytes, 100KB);
  single-bit flip anywhere -> "mac mismatch"; truncation -> throws; isEncrypted on plain markdown
  bytes -> false.

## Interop

`tools/nokcrypt.py` (Python 3, stdlib-only; embedded pure-Python AES fallback, uses the
`cryptography` package when importable) implements the SAME formats:
`init` (create _vault.nkv), `encrypt DIR` / `decrypt DIR` (in-place, recursive, skipping
`.noksidian/`, `.git*`, `.obsidian/`, `_vault.nkv`, already-in-target-state files), `cat FILE`,
`selftest` (all vectors above), plus hidden `--iv <hex>` on single-file `enc FILE`/`dec FILE`
subcommands for cross-implementation byte-exact testing against `encryptWithIv`.

## Integration rules (phase 2 — do NOT implement in phase 1)

- Files layer stays raw bytes. A codec seam above it decrypts on read / encrypts on write when the
  vault is encrypted (magic-sniff per file: files without NKE1 magic pass through untouched).
- Sync merges PLAINTEXT: decrypt base/local/remote before Merge.merge3, re-encrypt the merged
  result for both the local write and the PUSH. Base copies under .noksidian/base stay ciphertext.
- `_vault.nkv` itself: synced as a plain binary file, never merged, never encrypted, hidden in
  Library, excluded from encrypt-all migration.
- Unlock flow: startApp detects `_vault.nkv` -> masked password TextBox -> VaultCrypto.open ->
  keys held in RAM only; optional "remember" stores dk in RMS base64 (off by default, documented
  as reducing security to device-access level).
- Settings gains: "Encryption: set/change password" (walks vault re-encrypting with progress
  screen, then pushes), "Decrypt vault", iterations field (advanced). Change-password = decrypt
  with old keys + encrypt with new in one walk.
