#!/bin/bash
echo "========================================================="
echo "   FORENSE DE DEPENDENCIAS RUST (COZO & ORT) - WSL"
echo "========================================================="

# 1. Encontrar dónde diablos está el código fuente descargado de COZO
echo "[*] Localizando fuente de CozoDB..."
# Buscamos en los checkouts de git de cargo
COZO_ROOT=$(find ~/.cargo/git/checkouts -name "cozo-*" -type d -print -quit 2>/dev/null)

if [ -z "$COZO_ROOT" ]; then
    echo "ERROR: No encontré la carpeta de Cozo en ~/.cargo/git/checkouts"
    # Intento fallback en registry
    COZO_ROOT=$(find ~/.cargo/registry/src -name "cozo-*" -type d -print -quit 2>/dev/null)
fi

echo "Raíz encontrada (aprox): $COZO_ROOT"

# Encontrar el checkout específico (la versión hasheada que se está usando)
# Buscamos el archivo lib.rs dentro de esa raíz para asegurarnos
COZO_LIB=$(find "$COZO_ROOT" -name "lib.rs" | grep "/src/lib.rs" | head -n 1)
COZO_SRC_DIR=$(dirname "$COZO_LIB")

echo "Archivo principal: $COZO_LIB"
echo "---------------------------------------------------------"

echo "[*] EXPORTACIONES PÚBLICAS EN lib.rs (Top 40 lineas):"
cat "$COZO_LIB" | grep -v "^//" | head -n 40

echo "---------------------------------------------------------"
echo "[*] BÚSQUEDA DE ESTRUCTURA 'Db':"
grep -r "pub struct Db" "$COZO_SRC_DIR/../" | head -n 5

echo "---------------------------------------------------------"
echo "[*] BÚSQUEDA DE CONSTRUCTORES DE Db ('fn new'):"
# Buscamos dentro de la definición de Db o impl Db
grep -r "pub fn new" "$COZO_SRC_DIR" | grep -C 2 "Db" | head -n 20

echo "---------------------------------------------------------"
echo "[*] BÚSQUEDA DE MOTORES DE ALMACENAMIENTO (Storage):"
grep -r "pub struct .*Storage" "$COZO_SRC_DIR" | head -n 20

echo "========================================================="
echo "[*] VERIFICANDO ORT (ONNX RUNTIME)"
ORT_LIB=$(find ~/.cargo/registry/src -name "ort-*" -type d 2>/dev/null | sort -r | head -n 1)/src/lib.rs
if [ -f "$ORT_LIB" ]; then
    echo "Fuente ORT: $ORT_LIB"
    echo "Buscando Session:"
    grep -r "pub struct Session" $(dirname "$ORT_LIB") | head -n 5
    echo "Buscando Environment:"
    grep -r "pub struct Environment" $(dirname "$ORT_LIB") | head -n 5
else 
    echo "No se encontró fuente de ORT en registry estándar."
fi
echo "========================================================="