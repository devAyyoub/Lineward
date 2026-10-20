# ADR-014 · Postura de seguridad del desarrollo

- **Estado:** Aceptado
- **Fecha:** 2026-10-21

## Contexto

Lineward es una plataforma de gobierno de identidades y accesos. Eso la convierte en un objetivo de valor inusualmente alto, por dos motivos que conviene decir explícitamente:

1. **Concentra la respuesta a "quién tiene acceso a qué".** Un atacante que lee esa base de datos obtiene el mapa de la organización entera sin tocar ningún sistema destino.
2. **Guarda las credenciales de los conectores**, que son de administrador en el directorio corporativo y en el proveedor de identidad. Son la credencial más valiosa de toda la instalación, y por eso el robo de credenciales de conector está en el modelo de amenazas desde el primer día.

A la vez, el proyecto tiene un objetivo de aprendizaje y de portfolio, y lo construye una persona en tiempo parcial. Una postura de seguridad que exija ceremonia constante no se cumple: se abandona en la tercera semana y queda escrita como si se cumpliera, que es peor que no haberla escrito.

Hay además una tensión real que esta decisión tiene que resolver y no esconder: **el código enrevesado es en sí mismo un problema de seguridad.** Nadie revisa lo que no entiende, y los agujeros viven donde la revisión no llega. Así que "más controles" y "más seguro" no son sinónimos.

### Lo que provocó esta decisión

Hasta el 2026-10-21 había controles de seguridad serios (aislamiento por organización verificado, registro de auditoría que la aplicación no puede editar, mínimo privilegio en los roles de base de datos) y ninguna declaración de **cómo** se decide en seguridad. Tres controles de cadena de suministro estaban diferidos a la última fase con este argumento, escrito el 2026-10-14:

> un backend de dos clases no da hallazgos útiles

El argumento resultó ser falso para dos de los tres, y la forma en que se descubrió es la parte que vale la pena registrar. Al añadir el escaneo de dependencias, encontró **diez vulnerabilidades el mismo día**, siete en la biblioteca que parsea todas las entradas JSON y tres en el servidor embebido, incluida una de salto de autenticación. El error de razonamiento era confundir código pequeño con superficie pequeña: dos clases arrastran 63 dependencias que no ha auditado nadie de este lado, y ese número no crece con el código propio.

## Opciones consideradas

**1. Dejar la seguridad para una fase de endurecimiento al final.** Es lo que el roadmap hacía. Ventaja real: no gasta tiempo en controles que todavía no tienen nada que proteger, y el análisis estático de un proyecto vacío solo produce ruido. Inconveniente: trata la seguridad como una capa que se aplica encima, cuando las decisiones que más la determinan (dónde vive la frontera de autorización, si un control se puede saltar) se toman al principio y después cuestan un rediseño. Y, demostrado empíricamente, deja pasar vulnerabilidades reales de terceros durante meses.

**2. Endurecer todo desde el principio.** Análisis estático, revisión formal, modelado de amenazas por entrega. Inconveniente decisivo para un proyecto de una persona: el coste por entrega es alto, el beneficio en las fases tempranas es bajo, y lo previsible es que se degrade hasta ser una formalidad. Un control que se cumple a medias informa peor que uno que no existe, porque figura como puesto.

**3. Prioridad en seguridad, con el coste pagado en diseño y no en complejidad.** La elegida.

## Decisión

**La seguridad tiene prioridad cuando entra en conflicto con otra cosa, y se paga con diseño, no con complejidad añadida.** El código tiene que seguir siendo claro, porque la claridad es parte del control.

Siete reglas concretas, que son lo que de verdad decide esta decisión:

1. **El control va en el sitio que no se puede saltar, no en el sitio que recuerda no saltarlo.** Una comprobación en la frontera es un muro; una convención de equipo, una nota en la documentación o una instrucción en un prompt son peticiones. Row Level Security de PostgreSQL impide la consulta sin filtrar; "acuérdate de filtrar por organización" no impide nada.
2. **Todo control entra con su prueba negativa hecha al menos una vez.** Hay que haberlo visto fallar cuando debe fallar. Si la prueba negativa se puede automatizar, se automatiza. El motivo está en la consecuencia de abajo: hay controles cuyo fallo es indistinguible del éxito.
3. **Mínimo privilegio también hacia dentro.** El rol de aplicación no es propietario de ninguna tabla, el token de la integración continua es de solo lectura salvo en el trabajo que necesita escribir, y cada componente recibe lo justo.
4. **Cero secretos en el código, en la configuración y en el historial.** Y solo datos sintéticos, generados de forma reproducible.
5. **Antes de añadir una comprobación, mirar si el diseño puede hacer el fallo imposible.** Un tipo que no admite el estado inválido vale más que una validación que hay que acordarse de llamar.
6. **Ninguna ruta privilegiada "de confianza".** Basta un atajo del tipo "si el componente es interno, nos saltamos la comprobación" para que la propiedad de seguridad desaparezca y no vuelva.
7. **Lo que no está, se dice.** Un control a medias descrito como completo es peor que su ausencia, porque nadie lo vigila. Esta decisión incluye por eso la lista de lo que **no** hay.

### Controles de cadena de suministro, adelantados

Del diferimiento de la última fase se adelantan los dos que no tocan ni una línea de código, y se mantiene diferido el tercero:

| Control | Estado | Por qué |
|---|---|---|
| Escaneo de dependencias que tumba la construcción | Adelantado | Busca avisos publicados sobre código de terceros, así que aplica desde la primera dependencia |
| Vigilancia continua y actualizaciones automáticas de versiones | Adelantado | Una dependencia se vuelve peligrosa sin que nadie toque el repositorio |
| Análisis estático del código propio | Sigue diferido | Aquí el argumento original sí vale: todavía no hay lógica de dominio que analizar |

### Lo que esta decisión no autoriza

No autoriza a parar el proyecto para endurecer lo que todavía no existe, ni a duplicar validaciones por si acaso, ni a convertir cada entrega en una auditoría. Fija el orden de prioridad en un conflicto; no es un permiso para ampliar el alcance.

## Consecuencias

**Lo que se gana.** Las decisiones de seguridad quedan tomadas donde son baratas. Los controles existentes están probados al revés y no solo escritos. Y la cadena de suministro pasa a estar vigilada de forma continua en lugar de revisada una vez al final.

**Lo que cuesta.** Dos versiones gestionadas por el BOM de Spring Boot están sobrescritas a mano, lo que rompe localmente la garantía de compatibilidad que da el BOM. Es la excepción que ADR-006 permite, con el motivo escrito junto a cada una y la obligación de revertirla cuando una versión del framework las gestione. Una sobrescritura que sobrevive a su motivo es cómo un proyecto se ancla a una dependencia vieja sin que nadie lo decida.

**Lo que hay que vigilar, y es el hallazgo técnico que justifica la regla 2.** Un control puede fallar por falta de datos y declararlo como éxito. Ocurrió dos veces el mismo día, con dos herramientas de fabricantes distintos:

- El escáner de dependencias informó de "ningún problema" después de descartar, en silencio, los dieciséis paquetes que había encontrado. No podía resolver sus versiones.
- El grafo de dependencias de la plataforma de alojamiento tenía veinticinco paquetes, quince de ellos sin versión, y no contenía ninguna de las dos bibliotecas donde estaban los diez hallazgos.

La causa es la misma y es incómoda: **declarar las versiones de forma centralizada es buena práctica, y es lo que ADR-006 defiende, y deja a los lectores ingenuos sin nada que leer.** Una decisión de ingeniería correcta puede desactivar en silencio una herramienta de seguridad, sin error, sin aviso y con un informe de normalidad.

La contramedida es operativa y aplica a cualquier control futuro: **de un control no se lee el veredicto, se lee cuántas cosas dice haber comprobado.** Un control de cobertura sin un número de cobertura no es un control. En este caso, dar a las dos herramientas el árbol de dependencias ya resuelto por la herramienta de construcción pasó el escaneo de 0 a 63 paquetes y el grafo de 10 a 199 versiones.

**Qué revisa esta decisión.** Dos disparadores nuevos, además de los de ADR-006: que el escaneo de dependencias señale una versión gestionada por el BOM, y que aparezca cualquier control cuyo informe de éxito no venga acompañado de un recuento de lo comprobado.
