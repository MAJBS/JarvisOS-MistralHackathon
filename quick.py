import os
from collections import defaultdict

def analizar_directorio_completo(ruta_raiz):
    datos_archivos = []
    extensiones_peso = defaultdict(float)
    peso_total_bytes = 0

    print(f"--- Iniciando escaneo en: {ruta_raiz} ---\n")

    for raiz, dirs, archivos in os.walk(ruta_raiz):
        for nombre in archivos:
            ruta_completa = os.path.join(raiz, nombre)
            try:
                tamano = os.path.getsize(ruta_completa)
                _, ext = os.path.splitext(nombre)
                ext = ext.lower() if ext else 'sin_extension'
                
                peso_total_bytes += tamano
                extensiones_peso[ext] += tamano
                datos_archivos.append((ruta_completa, tamano, ext))
            except (OSError, PermissionError):
                continue

    if peso_total_bytes == 0:
        print("No se encontraron archivos o la carpeta está vacía.")
        return

    # 1. Mostrar Resumen de Extensiones que ocupan el 95%
    print(f"Peso Total de la Carpeta: {peso_total_bytes / (1024**2):.2f} MB\n")
    print(f"{'Extensión':<15} | {'Peso (MB)':<12} | {'% del Total':<10} | {'Acumulado %'}")
    print("-" * 60)

    # Ordenar extensiones por peso de mayor a menor
    ext_ordenadas = sorted(extensiones_peso.items(), key=lambda x: x[1], reverse=True)
    
    acumulado_porcentaje = 0
    encontrado_95 = False

    for ext, peso in ext_ordenadas:
        porcentaje = (peso / peso_total_bytes) * 100
        acumulado_porcentaje += porcentaje
        
        marcador = " <--- (Dentro del 95%)" if acumulado_porcentaje <= 95.5 else ""
        print(f"{ext:<15} | {peso / (1024**2):>10.2f} MB | {porcentaje:>9.2f}% | {acumulado_porcentaje:>10.2f}% {marcador}")
        
        if acumulado_porcentaje > 95 and not encontrado_95:
            print("-" * 60)
            print(f"^^^ Hasta aquí se concentra el 95% del peso total ^^^")
            encontrado_95 = True

    # 2. Guardar lista completa de archivos en un TXT para que no sature la consola
    archivo_log = "lista_completa_archivos.txt"
    with open(archivo_log, "w", encoding="utf-8") as f:
        f.write(f"LISTA COMPLETA DE ARCHIVOS EN {ruta_raiz}\n")
        f.write(f"{'Tamaño (MB)':<12} | {'Ruta'}\n")
        f.write("-" * 80 + "\n")
        # Ordenar archivos por tamaño para el reporte
        archivos_top = sorted(datos_archivos, key=lambda x: x[1], reverse=True)
        for ruta, tam, _ in archivos_top:
            f.write(f"{tam / (1024**2):>10.2f} MB | {ruta}\n")
    
    print(f"\n✅ Se ha generado '{archivo_log}' con la lista de todos los archivos ordenada por peso.")

if __name__ == "__main__":
    # Ajusta tu ruta aquí
    mi_ruta = r"D:/HACK" 
    analizar_directorio_completo(mi_ruta)