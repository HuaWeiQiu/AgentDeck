# Embedded Runtime Provenance

AgentDeck packages the following ARM64 files as independent native processes:

- `libproot.so`
- `libproot-loader.so`
- `libtalloc.so`

The checked-in bytes were taken from SimonSchubert/Kai commit
`99e16cb1d442697b8d4da76eebaf7f456c8e7451`, whose `build-proot.sh` builds:

- Termux PRoot commit `4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f`
- talloc `2.4.3`
- Android API 26 ARM64 output

Reproduction source:

- <https://github.com/SimonSchubert/Kai/tree/99e16cb1d442697b8d4da76eebaf7f456c8e7451>
- <https://github.com/termux/proot/tree/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f>
- <https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz>

PRoot is GPL-2.0 and is executed as a separate process. talloc is LGPL-3.0 and is
loaded by PRoot. Their complete license texts are packaged under
`android/app/src/main/assets/licenses/` and distributed in the APK. Exact binary
and license hashes are recorded in `third_party/embedded-runtime.sha256`.

The Ubuntu Base and OpenAI Codex archives are not committed into the APK. The
app downloads the fixed files declared in `EmbeddedRuntimeManifest`, limits the
received byte count, and verifies exact size and SHA-256 before extraction.
