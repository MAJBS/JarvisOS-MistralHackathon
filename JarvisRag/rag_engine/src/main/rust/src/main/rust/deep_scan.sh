#!/bin/bash
# deep_scan.sh - Búsqueda profunda de estructuras en el código fuente de Cozo

echo "🔍 Buscando la raíz del código fuente de Cozo..."
# Buscamos cualquier directorio que empiece por cozo- dentro de checkouts
BASE_DIR=$(find ~/.cargo/git/checkouts -name "cozo-*" -type d -print -quit 2>/dev/null)

if [ -z "$BASE_DIR" ]; then
    echo "❌ No se encontró el código fuente en git/checkouts."
    exit 1
fi

echo "✅ Raíz encontrada: $BASE_DIR"
echo "---------------------------------------------------"

echo "🔎 Buscando definición de 'struct Db':"
# Buscamos recursivamente "pub struct Db" pero EXCLUYENDO carpetas de target o tests
grep -r "pub struct Db" "$BASE_DIR" | grep -v "target" | grep -v "test" | head -n 5

echo "---------------------------------------------------"
echo "🔎 Buscando definición de 'SqliteStorage':"
grep -r "pub struct SqliteStorage" "$BASE_DIR" | grep -v "target" | head -n 5

echo "---------------------------------------------------"
echo "🔎 Buscando constructores de 'SqliteStorage' (impl ... fn new):"
# Encontramos el archivo donde se define SqliteStorage y buscamos sus métodos new
SQLITE_FILE=$(grep -r -l "pub struct SqliteStorage" "$BASE_DIR" | head -n 1)
if [ -n "$SQLITE_FILE" ]; then
    echo "📄 Archivo detectado: $SQLITE_FILE"
    grep "fn new" "$SQLITE_FILE" -C 2
else
    echo "❌ No se encontró el archivo de SqliteStorage."
fi

echo "---------------------------------------------------"
echo "🔎 Buscando módulos exportados en cozo/src/lib.rs:"
# Intentamos encontrar el lib.rs del crate principal 'cozo' (no core, no swift)
MAIN_LIB=$(find "$BASE_DIR" -wholename "*/cozo/src/lib.rs" | head -n 1)
if [ -n "$MAIN_LIB" ]; then
    echo "📄 $MAIN_LIB"
    cat "$MAIN_LIB" | grep "pub use"
else
    echo "⚠️ No se encontró cozo/src/lib.rs, buscando en cozo-core..."
    CORE_LIB=$(find "$BASE_DIR" -wholename "*/cozo-core/src/lib.rs" | head -n 1)
    if [ -n "$CORE_LIB" ]; then
        echo "📄 $CORE_LIB"
        cat "$CORE_LIB" | grep "pub use"
    fi
fi