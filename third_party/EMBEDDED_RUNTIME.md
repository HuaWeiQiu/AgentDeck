# Embedded Runtime Provenance

AgentDeck packages the following ARM64 and x86_64 files as independent native processes:

- `libproot.so`
- `libproot-loader.so`
- `libtalloc.so`

The checked-in bytes were taken from SimonSchubert/Kai commit
`99e16cb1d442697b8d4da76eebaf7f456c8e7451`, whose `build-proot.sh` builds:

- Termux PRoot commit `4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f`
- talloc `2.4.3`
- Android API 26 ARM64 and x86_64 output

Reproduction source:

- <https://github.com/SimonSchubert/Kai/tree/99e16cb1d442697b8d4da76eebaf7f456c8e7451>
- <https://github.com/termux/proot/tree/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f>
- <https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz>

PRoot is GPL-2.0 and is executed as a separate process. talloc is LGPL-3.0 and is
loaded by PRoot. Their complete license texts are packaged under
`android/app/src/main/assets/licenses/` and distributed in the APK. Exact binary
and license hashes are recorded in `third_party/embedded-runtime.sha256`.

The Ubuntu Base and OpenAI Codex archives are not committed into the APK. The
app selects the matching ABI from the version catalog in `EmbeddedRuntimeManifest`,
downloads the fixed files declared for that target, limits the received byte count,
and verifies exact size and SHA-256 before extraction.

### Download mirrors and network region

Each artifact lists **both** domestic and official HTTPS URLs in the catalog
(Tsinghua / Aliyun / USTC cdimage mirrors and `ghfast.top` for GitHub, plus
`cdimage.ubuntu.com` / `github.com`). At install time `NetworkRegionDetector`
classifies the device exit IP (Cloudflare `cdn-cgi/trace`, then ipinfo, then
locale/timezone soft hint; result cached 24h):

- **Mainland China (`CN`)**: try domestic URLs first, then official.
- **Overseas / other countries**: try official first, then domestic as fallback.

Guest apt sources and DNS (`embeddedAptMirrorBases`, `embeddedRuntimeResolvConf`)
use the same region ordering. Checksums are identical across mirrors; a failed
host never mixes partial bytes into the next URL.
