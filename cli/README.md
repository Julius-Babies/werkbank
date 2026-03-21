# Install

## Development setup
### Create bin directory
```bash
mkdir -p ~/.local/bin
```

### Update zshrc
```bash
export PATH="$PATH:$HOME/.local/bin"

build_werkbank() {
  CURRENT_DIR="$(pwd)"
  cd $HOME/Documents/werkbank-cli/
  ./gradlew build
  cp /Users/julius/Documents/werkbank-cli/build/bin/macosArm64/debugExecutable/werkbank.CLI.kexe ~/.local/bin/wb
  cd $CURRENT_DIR
}
```