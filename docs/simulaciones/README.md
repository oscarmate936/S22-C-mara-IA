# Simulaciones con fotos reales

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
| `reporte.txt` | Métricas (PSNR, alineación, fotómetro, escenas) |
