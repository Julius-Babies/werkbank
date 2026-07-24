# Install

## Development setup

Building and installing the CLI locally is fully handled by Gradle tasks (group `werkbank`).
There is no need to manually create directories, copy binaries or edit your shell config —
the tasks take care of all of it.

### Available tasks

| Task                               | `cli.dev` | Installs as          |
|------------------------------------|-----------|----------------------|
| `./gradlew :cli:updateLocalCli`    | `false`   | `~/.local/bin/wb`    |
| `./gradlew :cli:updateLocalDevCli` | `true`    | `~/.local/bin/wbdev` |

Each task:

1. Sets `cli.dev` accordingly in `local.properties`.
2. Links the debug executable for the host's Kotlin/Native target
   (e.g. `:cli:linkDebugExecutableMacosArm64`, `linkDebugExecutableLinuxArm64` or
   `linkDebugExecutableLinuxX64` — resolved from the host OS/architecture).
3. Copies the binary to `~/.local/bin` (creating the directory if missing) as `wb` / `wbdev`
   and makes it executable.
4. Checks whether `~/.local/bin` is on your `PATH`. If not, it appends
   `export PATH="$HOME/.local/bin:$PATH"` to the shell configs found in your home directory
   (`.zshrc`, `.bashrc`, `.bash_profile`, `.profile`) and logs what it did.

After the first install, restart your shell (or `source` the updated config) so the new
`PATH` entry takes effect.

### Usage

```bash
# production-like build, installed as `wb`
./gradlew :cli:updateLocalCli

# development build (cli.dev=true), installed as `wbdev`
./gradlew :cli:updateLocalDevCli

wb --help
wbdev --help
```
