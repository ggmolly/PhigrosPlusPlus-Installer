#!/usr/bin/env bash
# usage: fetch.sh <folder>, writes the latest Phigros++ release's libphigrospp.so and GAMES there, once its manifest
# (phigrospp.installer, see Release.java) is signed by the key of release.pub and the lib is the one it names
set -euo pipefail
HERE=$(dirname "$(dirname "$(realpath "$0")")")
OUT=${1:?usage: fetch.sh <folder>}
DOWNLOAD=https://github.com/ggmolly/PhigrosPlusPlus/releases/latest/download
mkdir -p "$OUT"
curl -fsSL -o "$OUT/phigrospp.installer" "$DOWNLOAD/phigrospp.installer"
curl -fsSL -o "$OUT/libphigrospp.so" "$DOWNLOAD/libphigrospp.so"
node -e '
  const crypto = require("crypto"), fs = require("fs");
  const [trusted, out] = process.argv.slice(1);
  const signed = fs.readFileSync(`${out}/phigrospp.installer`);
  const spki = Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), Buffer.from(fs.readFileSync(trusted, "ascii").trim(), "hex")]);
  const key = crypto.createPublicKey({ key: spki, format: "der", type: "spki" });
  if (!crypto.verify(null, signed.subarray(64), key, signed.subarray(0, 64))) throw new Error("the release isnt signed by release.pub");
  const fields = Object.fromEntries(signed.subarray(64).toString().trim().split("\n").map((l) => [l.slice(0, l.indexOf(" ")), l.slice(l.indexOf(" ") + 1)]));
  const [hash, size] = fields.lib.split(" "), lib = fs.readFileSync(`${out}/libphigrospp.so`);
  if (lib.length !== Number(size) || crypto.createHash("sha256").update(lib).digest("hex") !== hash) throw new Error("libphigrospp.so isnt the release one");
  fs.writeFileSync(`${out}/GAMES`, fields.games.split(" ").join("\n") + "\n");
  console.log(`release patch: Phigros++ ${fields.version} for Phigros ${fields.games}`);
' "$HERE/release.pub" "$OUT"
