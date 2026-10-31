#!/usr/bin/env bash

# Installs sccache. A failed or corrupt download must never leave a broken binary in
# PATH (build.py picks up any `sccache` it finds), so everything is validated first and
# the build simply continues without a compiler cache if installation fails.

# $1=variant
# $2=install_dir
# $3=exe
install_from_gh() {
  local variant="$1" dir="$2" exe="$3"
  local token="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
  local auth=()
  [ -n "$token" ] && auth=(-H "Authorization: Bearer $token")

  local ver
  ver=$(curl -fsSL --retry 5 --retry-delay 2 "${auth[@]}" \
    'https://api.github.com/repos/mozilla/sccache/releases/latest' | jq -r .tag_name) || return 1
  if [ -z "$ver" ] || [ "$ver" = "null" ]; then
    return 1
  fi

  local url="https://github.com/mozilla/sccache/releases/download/${ver}/sccache-${ver}-${variant}.tar.gz"
  local tmp
  tmp=$(mktemp -d)
  curl -fsSL --retry 5 --retry-delay 2 "$url" -o "$tmp/sccache.tar.gz" || return 1
  tar xzf "$tmp/sccache.tar.gz" -C "$tmp" --wildcards "*/$exe" || return 1

  local found
  found=$(find "$tmp" -type f -name "$exe" | head -n 1)
  [ -n "$found" ] || return 1
  chmod +x "$found"
  # make sure the binary can really run on this machine
  "$found" --version >/dev/null 2>&1 || return 1

  mkdir -p "$dir"
  install -m 755 "$found" "$dir/$exe" 2>/dev/null || sudo install -m 755 "$found" "$dir/$exe" || return 1
  rm -rf "$tmp"
}

ok=0
if [ "$RUNNER_OS" = "macOS" ]; then
  brew install sccache && ok=1
elif [ "$RUNNER_OS" = "Linux" ]; then
  install_from_gh x86_64-unknown-linux-musl /usr/local/bin sccache && ok=1
elif [ "$RUNNER_OS" = "Windows" ]; then
  install_from_gh x86_64-pc-windows-msvc "$USERPROFILE/.cargo/bin" sccache.exe && ok=1
fi

if [ "$ok" != "1" ]; then
  echo "::warning::Could not install sccache, continuing without a compiler cache"
  # never leave a broken binary behind
  rm -f /usr/local/bin/sccache
fi
exit 0
