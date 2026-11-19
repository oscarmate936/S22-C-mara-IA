# Calibración profesional (versión 0.13)

La cámara se calibró con colecciones de fotos reales, para que los resultados se parezcan a los de
un fotógrafo profesional y no al automático de un teléfono (saturado, quemado). Las fotos de estas
colecciones son solo para investigación: **no se incluyen en el repositorio**; aquí están los
scripts, las pruebas y los resultados.

| Colección | Qué tiene | Qué se calibró | Resultado (fotos no usadas para ajustar) |
|---|---|---|---|
| **MIT-Adobe FiveK** (experto C) | 1 000 fotos con la edición de un fotógrafo profesional | Exposición por escena, altas luces, límite de color, curva de tono | Color "de celular" p95 49 → **41** con el perfil natural (profesional: 42); atardeceres 50 → **40** (profesional 42) |
| **KonIQ-10k** | 4 000 fotos con la nota media de cientos de personas | Pesos de la calificación de la foto | Correlación con la nota humana **0.29 → 0.49** (1 000 fotos de prueba) |
| **ADE20K** | 2 000 fotos con cielo y vegetación marcados a mano | Umbrales de las máscaras de zonas | Cielo IoU **0.34 → 0.63**, vegetación 0.37 → 0.40 (400 fotos de prueba) |

## Lo que hacen los profesionales (FiveK, 606 fotos de calibración)

| Escena | Exposición vs. automático | Altas luces (p99 típico) | Color p95 (CIELAB) |
|---|---|---|---|
| Atardecer / amanecer | −0.4 EV | 0.57 (lineal) | 27 |
| Paisaje | −0.5 EV | 0.62 | 35 |
| Ciudad | −0.1 EV | 0.72 | 27 |
| Retrato | −0.16 EV | 0.72 | 29 |
| Noche | −0.2 EV | 0.67 | 32 |
| Todas | −0.28 EV | 0.68 | 28 |

Lo que más añaden es **contraste local** (0.76 → 1.04), no saturación: el color apenas cambia
respecto a la toma original y en retratos incluso baja.

## Cómo se aplica en la app

- **Cerebro fotógrafo:** exposición base por escena (tabla anterior) y, en exteriores, si lo más
  claro de la escena pasa del nivel en que los profesionales dejan las luces, baja hasta 0.5 EV.
- **Perfil natural (en la cámara del S22+):** curva de tono con hombro suave desde 0.55 hasta 0.96 en
  el blanco (el automático lo lleva a 1.0 y quema el cielo), realce de bordes ligero y reducción de
  ruido de alta calidad, si el sensor acepta esos controles.
- **Perfil natural (en cada foto):** el color se limita con una curva suave al nivel de los
  profesionales para esa escena; los colores normales no se tocan y la piel se protege.
- **Calificación:** pesos ajustados a la nota humana; de noche y al atardecer una foto oscura es correcta.
- **Máscaras de zonas:** umbrales ajustados; los cielos cálidos no reciben retoque de cielo (así
  nunca se satura un atardecer).

## Reproducir

```
# FiveK: tools/calibracion/fivek_stats.py <carpeta> <salida.json> 8   (bloques 0–7 para calibrar)
#        tools/calibracion/fivek_stats.py <salida.json>               (constantes para Calibration.kt)
# KonIQ: CALIB_DIR=... ./gradlew testDebugUnitTest --tests '*ScoreFeaturesTest*'
#        tools/calibracion/ajustar_nota.py <koniq_features.csv>
# ADE20K y validación: CALIB_DIR=... ./gradlew testDebugUnitTest --tests '*calib*'
```

Informes: `perfil_natural_fivek.txt`, `nota_koniq.txt`, `mascaras_ade20k.txt`.

**Límite honesto:** la curva de tono de la cámara se calibró con FiveK, pero su efecto exacto en el
S22+ depende de cómo su procesador aplique el control; hay que comprobarlo con fotos reales del
teléfono. Se puede desactivar en Ajustes › Perfil natural.
