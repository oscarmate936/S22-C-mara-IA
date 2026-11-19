#!/usr/bin/env bash
# Descarga fotos de ejemplo de proyectos públicos de visión por computadora (solo para pruebas
# locales; no se suben al repositorio). Uso: tools/descargar_imagenes.sh [carpeta]
set -u
DEST="${1:-app/build/fotos_prueba}"
mkdir -p "$DEST"
while read -r name url; do
  [ -z "$name" ] && continue
  if [ ! -s "$DEST/$name" ]; then
    curl -sfL -m 60 -o "$DEST/$name" "$url" && echo "ok   $name" || { echo "falló $name"; rm -f "$DEST/$name"; }
  fi
done <<'LIST'
retrato_grace.jpg https://raw.githubusercontent.com/matplotlib/matplotlib/main/lib/matplotlib/mpl-data/sample_data/grace_hopper.jpg
perro1.jpg https://raw.githubusercontent.com/pytorch/vision/main/gallery/assets/dog1.jpg
perro2.jpg https://raw.githubusercontent.com/pytorch/vision/main/gallery/assets/dog2.jpg
quokka.jpg https://raw.githubusercontent.com/aleju/imgaug/master/imgaug/quokka.jpg
playa_cometas.jpg https://raw.githubusercontent.com/AlexeyAB/darknet/master/data/kite.jpg
jirafas_sabana.jpg https://raw.githubusercontent.com/AlexeyAB/darknet/master/data/giraffe.jpg
persona_campo.jpg https://raw.githubusercontent.com/AlexeyAB/darknet/master/data/person.jpg
caballos.jpg https://raw.githubusercontent.com/AlexeyAB/darknet/master/data/horses.jpg
aguila.jpg https://raw.githubusercontent.com/AlexeyAB/darknet/master/data/eagle.jpg
perro_bici.jpg https://raw.githubusercontent.com/AlexeyAB/darknet/master/data/dog.jpg
playa_tf.jpg https://raw.githubusercontent.com/tensorflow/models/master/research/object_detection/test_images/image2.jpg
perros_tf.jpg https://raw.githubusercontent.com/tensorflow/models/master/research/object_detection/test_images/image1.jpg
autobus_calle.jpg https://raw.githubusercontent.com/ultralytics/ultralytics/main/ultralytics/assets/bus.jpg
personas_zidane.jpg https://raw.githubusercontent.com/ultralytics/ultralytics/main/ultralytics/assets/zidane.jpg
frutas.jpg https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/fruits.jpg
edificio.jpg https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/building.jpg
casa.jpg https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/home.jpg
mariposa.jpg https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/butterfly.jpg
futbol.jpg https://raw.githubusercontent.com/opencv/opencv/4.x/samples/data/messi5.jpg
LIST
ls "$DEST" | wc -l
