package com.iacamara.app.ai

import android.graphics.PointF
import android.graphics.RectF
import com.iacamara.app.camera.FrameStats
import com.iacamara.app.camera.LiveExposure
import com.iacamara.app.camera.WhiteBalance

/** Dónde debería ir el sujeto según la escena. */
enum class CompositionGoal {
    /** Sujeto en un punto fuerte de los tercios. */
    SUBJECT_THIRDS,
    /** Horizonte en el tercio inferior (más cielo). */
    HORIZON_LOW,
    /** Horizonte en el tercio superior (más primer plano). */
    HORIZON_HIGH,
    /** Composición libre. */
    NONE,
}

/**
 * La "receta" que aplicaría un fotógrafo profesional en cada escena.
 * @param shutterNs velocidad fija (la app calcula el ISO con su fotómetro). null = automática.
 */
data class Recipe(
    val lensFactor: Float?,
    val ev: Float = 0f,
    val wb: WhiteBalance = WhiteBalance.AUTO,
    val shutterNs: Long? = null,
)

private const val S = 1_000_000_000L

enum class Scene(
    val title: String,
    val why: String,
    val tips: List<String>,
    val recipe: Recipe?,
    val goal: CompositionGoal,
) {
    GENERAL(
        "Escena general", "No hay un tema dominante.",
        listOf("Busca un sujeto claro: una foto cuenta una sola historia.", "Coloca lo importante sobre un punto fuerte de la cuadrícula."),
        null, CompositionGoal.SUBJECT_THIRDS,
    ),
    PORTRAIT(
        "Retrato", "Detecté un rostro.",
        listOf(
            "La app enfoca y mide la luz en la cara automáticamente.",
            "Ojos sobre la línea superior de los tercios.",
            "El 2× no deforma la cara como el gran angular.",
            "Luz de lado o de ventana suaviza la piel; evita el sol de mediodía.",
        ),
        Recipe(lensFactor = 2f, ev = 0.3f, shutterNs = S / 125), CompositionGoal.SUBJECT_THIRDS,
    ),
    PET(
        "Mascota", "Detecté un perro o un gato.",
        listOf(
            "Baja a la altura de sus ojos: la foto se vuelve más íntima.",
            "1/500 s congela movimientos rápidos de cabeza y cola.",
            "Enfoca los ojos; si están nítidos, la foto funciona.",
        ),
        Recipe(lensFactor = 1f, shutterNs = S / 500), CompositionGoal.SUBJECT_THIRDS,
    ),
    ANIMAL(
        "Animal", "Detecté un animal o un ave.",
        listOf(
            "El teleobjetivo 3× te deja fotografiar sin asustarlo.",
            "1/1000 s congela aleteos y saltos.",
            "Deja espacio delante de hacia donde mira o se mueve.",
        ),
        Recipe(lensFactor = 3f, shutterNs = S / 1000), CompositionGoal.SUBJECT_THIRDS,
    ),
    MACRO(
        "Macro · Detalle", "Estás muy cerca de algo pequeño.",
        listOf(
            "Usa el lente 1×: en tu S22+ es el que enfoca más cerca con mejor calidad.",
            "Acércate hasta ~10 cm y toca el punto exacto que quieres nítido.",
            "Apoya los codos: de cerca cualquier temblor se nota.",
            "Para insectos, mejor temprano: se mueven menos con el frío.",
        ),
        Recipe(lensFactor = 1f, ev = -0.3f, shutterNs = S / 250), CompositionGoal.SUBJECT_THIRDS,
    ),
    SUNSET(
        "Atardecer · Amanecer", "Cielo con luz cálida y baja.",
        listOf(
            "Bajo la exposición −0.7 para que el cielo conserve sus colores.",
            "Blancos en «Sol» para no «corregir» los tonos dorados.",
            "Horizonte en el tercio inferior: el protagonista es el cielo.",
            "Con 3× el sol se ve más grande y el paisaje se comprime.",
        ),
        Recipe(lensFactor = 1f, ev = -0.7f, wb = WhiteBalance.DAYLIGHT), CompositionGoal.HORIZON_LOW,
    ),
    BEACH(
        "Playa · Agua", "Arena, mar o agua brillante.",
        listOf(
            "La arena brillante engaña al fotómetro y la deja gris: subo +0.3 para que se vea luminosa.",
            "El 0.6× captura toda la amplitud de la costa.",
            "Horizonte recto (usa el nivel) y en un tercio, nunca al centro.",
        ),
        Recipe(lensFactor = 0.6f, ev = 0.3f, wb = WhiteBalance.DAYLIGHT), CompositionGoal.HORIZON_LOW,
    ),
    LANDSCAPE(
        "Paisaje", "Montañas, campo, árboles o cielo abierto.",
        listOf(
            "El 0.6× da sensación de amplitud.",
            "Busca algo interesante en primer plano: roca, flor, camino.",
            "Horizonte en un tercio; arriba si el suelo es más interesante que el cielo.",
        ),
        Recipe(lensFactor = 0.6f, ev = -0.3f), CompositionGoal.HORIZON_HIGH,
    ),
    CITY(
        "Ciudad · Arquitectura", "Edificios y líneas.",
        listOf(
            "Mantén el teléfono recto (nivel verde) para que los edificios no se «caigan».",
            "Usa las líneas de la calle para guiar la mirada.",
            "El 0.6× entra completo en espacios estrechos.",
        ),
        Recipe(lensFactor = 0.6f), CompositionGoal.SUBJECT_THIRDS,
    ),
    FOOD(
        "Comida", "Detecté un plato o comida.",
        listOf(
            "Luz de ventana de lado: da textura y apetito.",
            "Desde arriba (90°) para platos planos, a 45° para hamburguesas o bebidas.",
            "Subo la exposición +0.3 para un aspecto más fresco.",
        ),
        Recipe(lensFactor = 1f, ev = 0.3f), CompositionGoal.SUBJECT_THIRDS,
    ),
    NIGHT(
        "Noche · Poca luz", "Hay muy poca luz.",
        listOf(
            "El 1× es tu lente más luminoso (f/1.8): úsalo de noche.",
            "Fijo 1/15 s y la app sube el ISO lo justo: apoya el teléfono.",
            "Busca fuentes de luz (faroles, letreros) como protagonistas.",
        ),
        Recipe(lensFactor = 1f, ev = -0.3f, shutterNs = S / 15), CompositionGoal.SUBJECT_THIRDS,
    ),
}

/** Un sujeto en el visor, en coordenadas normalizadas 0..1. */
data class Subject(
    val box: RectF,
    val label: String,
    val isFace: Boolean,
    /** Punto medio entre los ojos (si la IA los encontró). */
    val eyes: PointF? = null,
    /** Zona donde se mide la luz: en un rostro, la piel (mejillas y nariz), sin pelo ni fondo. */
    val meterBox: RectF = box,
    /** Identificador de seguimiento: el mismo mientras sea el mismo sujeto. */
    val trackId: Int? = null,
) {
    /** Punto que debe ir sobre el punto fuerte (los ojos en un rostro). */
    val anchorX: Float get() = eyes?.x ?: box.centerX()
    val anchorY: Float get() = eyes?.y ?: if (isFace) box.top + box.height() * 0.42f else box.centerY()

    companion object {
        /** Construye el sujeto "rostro" con la mayor precisión que permitan los puntos de la cara. */
        fun fromFace(f: FaceInfo): Subject {
            val b = f.box
            val le = f.leftEye
            val re = f.rightEye
            if (le == null || re == null) {
                // Sin puntos: centro de la cara (evita pelo arriba y fondo a los lados).
                val meter = RectF(b.left + b.width() * 0.25f, b.top + b.height() * 0.40f, b.right - b.width() * 0.25f, b.top + b.height() * 0.80f)
                return Subject(b, "Persona", true, null, meter, f.trackId)
            }
            val eyes = PointF((le.x + re.x) / 2, (le.y + re.y) / 2)
            val eyeDist = kotlin.math.hypot(le.x - re.x, le.y - re.y).coerceAtLeast(0.01f)
            val mouthY = f.mouth?.y ?: (eyes.y + eyeDist * 1.1f)
            // Mejillas y nariz: entre los ojos (en horizontal) y desde debajo de los ojos hasta la boca.
            val meter = RectF(
                minOf(le.x, re.x), eyes.y + eyeDist * 0.25f,
                maxOf(le.x, re.x), maxOf(mouthY - eyeDist * 0.1f, eyes.y + eyeDist * 0.5f),
            )
            return Subject(b, "Persona", true, eyes, meter, f.trackId)
        }
    }
}

data class SceneResult(val scene: Scene, val confidence: Float, val evidence: List<String>)

/**
 * Combina lo que ve ML Kit con datos físicos de la cámara (luz, color, distancia de enfoque)
 * y estabiliza el resultado para que la escena no "parpadee".
 */
class SceneBrain {
    companion object {
        /** Calidez mínima del cielo para considerarlo un atardecer (calibrado con fotos reales). */
        const val SUNSET_SKY_WARMTH = 0.15f
        /** Cuánto pesa una lectura que refuerza una escena (≈ 3 lecturas por segundo). */
        const val MEMORY_RISE = 0.50f
        /** Cuánto se olvida por cada lectura sin evidencia de nada (lento: evita parpadeos). */
        const val MEMORY_FALL = 0.18f
        /** Cuánto se olvida cuando hay evidencia clara de otra escena (rápido: responde al cambio). */
        const val MEMORY_FALL_COMPETING = 0.40f
        /** Evidencia mínima acumulada para afirmar una escena concreta. */
        const val MIN_STRENGTH = 0.40f
        /** "Fuerza" de la escena general (lo que hay que superar para salir de ella). */
        const val GENERAL_STRENGTH = 0.30f
        /** Ventaja necesaria para cambiar de escena (evita parpadeos). */
        const val SWITCH_MARGIN = 0.08f
    }

    private var current: SceneResult = SceneResult(Scene.GENERAL, 0f, emptyList())
    /** Evidencia acumulada por escena (media móvil): la IA "recuerda" los últimos segundos. */
    private val memory = FloatArray(Scene.entries.size)
    private val lastEvidence = HashMap<Scene, List<String>>()

    fun update(d: Detection, stats: FrameStats?, live: LiveExposure, front: Boolean): Pair<SceneResult, Subject?> {
        val scores = HashMap<Scene, Float>()
        val evidence = HashMap<Scene, MutableList<String>>()
        fun add(s: Scene, v: Float, why: String) {
            if (v > (scores[s] ?: 0f)) scores[s] = v
            evidence.getOrPut(s) { mutableListOf() }.let { if (why !in it) it += why }
        }

        // 1) Rostros
        val face = d.faces.maxByOrNull { it.width() * it.height() }
        if (face != null) {
            val area = face.width() * face.height()
            add(Scene.PORTRAIT, (0.7f + area * 3f).coerceAtMost(0.98f), "rostro")
        }

        // 2) Etiquetas de ML Kit
        for ((label, conf) in d.labels) {
            val es = Labels.spanish(label)
            Labels.sceneFor(label)?.let { (scene, weight) -> add(scene, conf * weight, es) }
        }

        // 3) Física de la escena
        if (stats != null) {
            val dark = stats.meanLuma < 0.2f
            val slow = live.shutterNs >= 1_000_000_000L / 20 || live.iso >= 1600
            if (dark && slow) add(Scene.NIGHT, 0.9f, "poca luz")
            // Atardecer: el cielo (la zona más luminosa) es naranja y hay cielo en la escena.
            // No se mira la foto entera: una piel o una mesa cálidas no son un atardecer.
            val sky = d.labels.any { it.first in Labels.SKY }
            if (sky && stats.highlightWarmth > SUNSET_SKY_WARMTH) {
                add(Scene.SUNSET, (0.75f + stats.highlightWarmth).coerceAtMost(0.97f), "cielo cálido")
            }
        }
        // Muy cerca: enfoque a menos de ~15 cm
        if (!front && live.focusDiopters > 6.5f) add(Scene.MACRO, 0.8f, "enfoque a ${(100f / live.focusDiopters).toInt()} cm")

        // Fusión temporal: cada lectura suma a la memoria de cada escena; una lectura dudosa
        // aislada no basta para cambiar, y una evidencia constante se impone en ~1 segundo.
        // ¿Hay evidencia clara de alguna escena en esta lectura?
        val strongest = scores.values.maxOrNull() ?: 0f
        for (sc in Scene.entries) {
            if (sc == Scene.GENERAL) continue
            val v = scores[sc] ?: 0f
            // Se convence rápido. Olvida despacio si simplemente no ve nada claro (medio segundo
            // sin ver al gato no lo borra), pero rápido si ve claramente OTRA cosa.
            val rate = when {
                v > memory[sc.ordinal] -> MEMORY_RISE
                strongest >= 0.55f && v < strongest -> MEMORY_FALL_COMPETING
                else -> MEMORY_FALL
            }
            memory[sc.ordinal] += (v - memory[sc.ordinal]) * rate
            evidence[sc]?.let { lastEvidence[sc] = it.take(3) }
        }
        val best = Scene.entries.filter { it != Scene.GENERAL }.maxByOrNull { memory[it.ordinal] }!!
        val bestStrength = memory[best.ordinal]
        val candidate = if (bestStrength >= MIN_STRENGTH) best else Scene.GENERAL
        fun strength(sc: Scene) = if (sc == Scene.GENERAL) GENERAL_STRENGTH else memory[sc.ordinal]
        if (candidate == current.scene || strength(candidate) > strength(current.scene) + SWITCH_MARGIN) {
            current = SceneResult(
                candidate,
                if (candidate == Scene.GENERAL) 1f - bestStrength else bestStrength,
                if (candidate == Scene.GENERAL) emptyList() else lastEvidence[candidate] ?: emptyList(),
            )
        } else {
            current = current.copy(confidence = strength(current.scene))
        }

        // Sujeto: rostro (con sus puntos) > objeto principal
        val faceInfo = d.faceDetails.maxByOrNull { it.box.width() * it.box.height() }
        val subject = when {
            faceInfo != null -> Subject.fromFace(faceInfo)
            d.prominent != null && d.prominent.width() * d.prominent.height() in 0.01f..0.85f -> {
                val p = d.prominent
                // Centro del objeto (los bordes del recuadro suelen incluir fondo).
                val meter = RectF(p.left + p.width() * 0.2f, p.top + p.height() * 0.2f, p.right - p.width() * 0.2f, p.bottom - p.height() * 0.2f)
                Subject(p, subjectName(d), false, null, meter, d.prominentTrackId)
            }
            else -> null
        }
        return current to subject
    }

    private fun subjectName(d: Detection): String {
        val specific = d.labels.firstOrNull { Labels.isSubject(it.first) }?.first
        return specific?.let { Labels.spanish(it) } ?: "Sujeto"
    }
}

/** Traducción de etiquetas de ML Kit y su relación con cada escena. */
object Labels {
    val SKY = setOf("Sky", "Cloud", "Sunset", "Horizon")

    private val map: Map<String, Pair<Scene, Float>> = mapOf(
        "Dog" to (Scene.PET to 1f), "Cat" to (Scene.PET to 1f), "Pet" to (Scene.PET to 0.9f),
        "Bird" to (Scene.ANIMAL to 1f), "Horse" to (Scene.ANIMAL to 1f), "Cattle" to (Scene.ANIMAL to 1f),
        "Animal" to (Scene.ANIMAL to 0.7f), "Duck" to (Scene.ANIMAL to 1f), "Fish" to (Scene.ANIMAL to 0.8f),
        "Insect" to (Scene.MACRO to 1f), "Butterfly" to (Scene.MACRO to 1f), "Bee" to (Scene.MACRO to 1f),
        "Flower" to (Scene.MACRO to 0.85f), "Petal" to (Scene.MACRO to 0.9f), "Moths and butterflies" to (Scene.MACRO to 1f),
        "Sunset" to (Scene.SUNSET to 1f), "Dusk" to (Scene.SUNSET to 1f), "Afterglow" to (Scene.SUNSET to 1f),
        "Beach" to (Scene.BEACH to 1f), "Sea" to (Scene.BEACH to 0.85f), "Lake" to (Scene.BEACH to 0.75f),
        "River" to (Scene.BEACH to 0.6f), "Surfing" to (Scene.BEACH to 0.8f), "Swimming" to (Scene.BEACH to 0.6f),
        "Waterfall" to (Scene.LANDSCAPE to 0.9f),
        "Mountain" to (Scene.LANDSCAPE to 1f), "Hill" to (Scene.LANDSCAPE to 0.9f), "Field" to (Scene.LANDSCAPE to 0.7f),
        "Forest" to (Scene.LANDSCAPE to 0.8f), "Tree" to (Scene.LANDSCAPE to 0.55f), "Sky" to (Scene.LANDSCAPE to 0.6f),
        "Cloud" to (Scene.LANDSCAPE to 0.6f), "Desert" to (Scene.LANDSCAPE to 0.9f), "Snow" to (Scene.LANDSCAPE to 0.7f),
        "Skyscraper" to (Scene.CITY to 1f), "Building" to (Scene.CITY to 0.8f), "Bridge" to (Scene.CITY to 0.8f),
        "Tower" to (Scene.CITY to 0.85f), "Road" to (Scene.CITY to 0.5f), "Church" to (Scene.CITY to 0.85f),
        "Food" to (Scene.FOOD to 1f), "Cake" to (Scene.FOOD to 1f), "Pizza" to (Scene.FOOD to 1f),
        "Bread" to (Scene.FOOD to 0.9f), "Dessert" to (Scene.FOOD to 1f), "Fruit" to (Scene.FOOD to 0.8f),
        "Vegetable" to (Scene.FOOD to 0.8f), "Coffee" to (Scene.FOOD to 0.8f), "Drink" to (Scene.FOOD to 0.6f),
        "Fireworks" to (Scene.NIGHT to 1f), "Moon" to (Scene.NIGHT to 0.8f),
    )

    private val es = mapOf(
        "Dog" to "Perro", "Cat" to "Gato", "Pet" to "Mascota", "Bird" to "Ave", "Horse" to "Caballo",
        "Cattle" to "Ganado", "Animal" to "Animal", "Duck" to "Pato", "Fish" to "Pez", "Insect" to "Insecto",
        "Butterfly" to "Mariposa", "Bee" to "Abeja", "Flower" to "Flor", "Petal" to "Pétalo",
        "Moths and butterflies" to "Mariposa", "Plant" to "Planta", "Sunset" to "Atardecer", "Dusk" to "Ocaso",
        "Afterglow" to "Arrebol", "Beach" to "Playa", "Sea" to "Mar", "Lake" to "Lago", "River" to "Río",
        "Surfing" to "Surf", "Swimming" to "Nado", "Waterfall" to "Cascada", "Mountain" to "Montaña",
        "Hill" to "Colina", "Field" to "Campo", "Forest" to "Bosque", "Tree" to "Árbol", "Sky" to "Cielo",
        "Cloud" to "Nubes", "Desert" to "Desierto", "Snow" to "Nieve", "Skyscraper" to "Rascacielos",
        "Building" to "Edificio", "Bridge" to "Puente", "Tower" to "Torre", "Road" to "Calle", "Church" to "Iglesia",
        "Food" to "Comida", "Cake" to "Pastel", "Pizza" to "Pizza", "Bread" to "Pan", "Dessert" to "Postre",
        "Fruit" to "Fruta", "Vegetable" to "Verdura", "Coffee" to "Café", "Drink" to "Bebida",
        "Fireworks" to "Fuegos artificiales", "Moon" to "Luna", "Horizon" to "Horizonte", "Car" to "Auto",
    )

    private val subjects = setOf(
        "Dog", "Cat", "Bird", "Horse", "Cattle", "Duck", "Fish", "Insect", "Butterfly", "Bee",
        "Flower", "Moths and butterflies", "Food", "Cake", "Pizza", "Dessert", "Fruit", "Coffee", "Car",
    )

    fun sceneFor(label: String) = map[label]
    fun spanish(label: String) = es[label] ?: label
    fun isSubject(label: String) = label in subjects
}
