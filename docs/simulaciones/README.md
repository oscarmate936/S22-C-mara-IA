# Simulaciones con fotos reales

La prueba `IaClosedLoopTest` simula el modo IA en circuito cerrado: una cámara virtual con el automático de un teléfono, y el analizador y el cerebro reales ajustándola ciclo a ciclo.

Se recrean las condiciones físicas de una toma y se pasan por el **mismo código** de la app:
- ruido de sensor con poca luz (fotones + lectura);
- pulso de la mano (desplazamiento ±12 px y giro de hasta 0.25°);
- objetos que cruzan el encuadre;
- desenfoque según la distancia (macro).

Para regenerarlas, ejecuta `./gradlew testDebugUnitTest`. Los resultados quedan en `app/build/sim/`.

Fotos de prueba (dominio público / CC0, de scikit-image): astronauta (NASA), gato Chelsea, café, cohete (NASA), campo profundo del Hubble (NASA).

| Archivo | Qué muestra |
|---|---|
| `cmp_noche_gato.png` | 1 toma vs. 8 tomas (primera versión, con motas sueltas) |
| `cmp_noche_gato_v2.png` | 8 tomas con detección de movimiento por zonas (sin motas) |
| `cmp_noche_fantasma.png` | Un objeto cruzó en 3 de 8 tomas: no aparece en el resultado |
| `cmp_macro_gato.png` | Mejor toma sola vs. apilado de 5 enfoques |
| `cmp_ia_contraluz.png` | Contraluz: cámara sola · IA v2 (cara bien expuesta) · IA v2 + HDR (ventana recuperada) |
| `cmp_ia_nieve.png` | Nieve: cámara sola (gris) vs. IA v2 (blanca, sin quemar) |
| `cmp_amanecer.png` | Amanecer: antes (resplandor quemado) vs. ahora con HDR (color recuperado, misma silueta) |
| `cmp_noche_v1_v2.png` | Modo noche con giro de la mano: v1 (izq.) vs. v2 con alineación por zonas (der.) |
| `cmp_momento_cara.png` | Foto de grupo: zona dañada (izq.) corregida con la cara de otra toma alineada (der.), sin unión visible |
| `cmp_retrato.png` | Modo retrato: original vs. fondo desenfocado (luces convertidas en discos, sujeto intacto, sin halos) |
| `cmp_superres_1_vs_8.png` | Zoom digital 2×: una toma (izq.) vs. súper resolución con 8 tomas (der.) |
| `cmp_noche_v2_tono_local.png` | Noche v2: escena oscura con ventana iluminada (izq.) y tono local (der.): sombras +1.8 pasos, ventana sin quemar, sin halo |
| `cmp_exposicion_larga.png` | Exposición larga: una toma (izq.) vs. 10 tomas (der.): agua de seda y estela del faro continua |
| `cmp_astro.png` | Astro: una toma (izq.) vs. 10 tomas alineadas a las estrellas (der.): estrellas puntuales, cielo limpio, sin píxeles calientes |
| `cmp_enderezado.png` | Foto torcida 4° (izq.) y enderezada automáticamente (der.) |
| `cmp_perspectiva.png` | Verticales convergentes (izq.) corregidas a paralelas (der.) |
| `cmp_zonas_cielo.png` | Retoque de cielo: más profundo y con color; el suelo no cambia |
| `cmp_zonas_piel.png` | Retrato oscuro: solo la cara se aclara (el fondo queda igual) |
| `reporte.txt` | Métricas (PSNR, alineación, fotómetro, escenas) |
