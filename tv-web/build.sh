#!/bin/bash
# Arma las apps de TV: LG (.ipk) y proyecto Samsung listo para firmar con Tizen Studio.
set -e
cd "$(dirname "$0")"
python3 build_channels.py
rm -rf dist && mkdir -p dist/webos dist/tizen
cp -r src/* dist/webos/ && cp webos/* dist/webos/
cp -r src/* dist/tizen/ && cp tizen/* dist/tizen/
# LG webOS: paquete .ipk (instalable en modo desarrollador o para subir a LG Seller Lounge)
"${ARES_PACKAGE:-ares-package}" dist/webos -o dist/ --no-minify
# Samsung Tizen: proyecto web para importar y firmar en Tizen Studio
(cd dist/tizen && zip -qr ../RedTicaTV-Samsung-proyecto.zip .)
ls -la dist
