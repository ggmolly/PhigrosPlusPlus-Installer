# everything the Makefile calls, loaded by direnv (.envrc)
let
  # pinned like mod/shell.nix, so everyone builds the same apk from the same sources
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs/archive/13e53762c5afaf384d4614e94527a9ccfafe41d0.tar.gz";
    sha256 = "sha256-pgG/CO+txdf2sA2z6DsM+2G3F9ajfIp/3BMC4wfKMDY=";
  };
  pkgs = import nixpkgs { config = { allowUnfree = true; android_sdk.accept_license = true; }; };
  sdk = (pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "35" ];
    buildToolsVersions = [ "35.0.0" ];
  }).androidsdk;
in
pkgs.mkShell {
  packages = with pkgs; [ android-tools jdk17_headless zip nodejs curl ];
  ANDROID_HOME = "${sdk}/libexec/android-sdk";
}
