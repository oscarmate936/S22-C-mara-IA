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
| `reporte.txt` | Métricas (PSNR, alineación, fotómetro, escenas) |
