# Phigros++ Installer

The Android app that turns the Phigros you installed from the Play Store into
[Phigros++](https://github.com/ggmolly/PhigrosPlusPlus), a modded copy that lives next to the game. No game files are
downloaded: it patches the ones already on your phone.

## Installing Phigros++

1. Install Phigros from the Play Store.
2. Download `phigrospp-installer.apk` from the [latest release](https://github.com/ggmolly/PhigrosPlusPlus-Installer/releases/latest)
   and open it (allow your browser or file manager to install apps when Android asks).
3. Tap **Install Phigros++**. The installer fetches the latest Phigros++ and checks it against the release key, patches
   the game and installs it.

Phigros++ updates itself from inside the game. When the game updates, or Phigros++ needs a new app, open the installer
again and tap **Update**: your Phigros++ data is kept. Pull the screen down to check for a new release.

The **Details** box lists your device, the game's files and each step: include a screenshot of it when reporting a
problem.

Phigros++ is an unofficial mod, not affiliated with or endorsed by Pigeon Games.

## How it works

- `src/Release.java` reads the latest Phigros++ release's manifest (`phigrospp.installer`, signed by the key in
  `release.pub`, checked by `src/Ed25519.java`) and downloads the `libphigrospp.so` it names.
- `src/Apk.java`, `src/Elf.java` and `src/Badge.java` rewrite each split of the installed game: another package name, the
  game's `libmain.so` loading `libphigrospp.so`, the Phigros++ label and a ++ on the icon. Every other file is copied
  byte for byte.
- `src/Signer.java` signs the splits with a key made on the phone, `src/MainActivity.java` installs them together.

## Building

With [Nix](https://nixos.org) (or direnv): `nix-shell --run "make release-patch"` builds `out/phigrospp-installer.apk`
with the latest release's patch, `make` with the one built in `../mod`. `make install` puts it on the phone plugged in
over adb.

Releases are built by GitHub Actions from a `v*` tag matching `android:versionName`, signed with the installer's key.

## License

GPL-3.0-or-later, see `LICENSE`.
