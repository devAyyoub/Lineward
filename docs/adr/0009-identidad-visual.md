# ADR-009 · Identidad visual: marca Ward, hueso cálido y latón

- **Estado:** Aceptado
- **Fecha:** 2026-09-24

## Contexto

ADR-007 eligió el nombre y dejó anotada la dirección del logo, la guarda de una cerradura o el dentado de una llave, plano y monocromo. No decidió nada más: ni forma concreta, ni color, ni tipografía.

Eso basta mientras el proyecto es solo documentación, y deja de bastar en cuanto aparecen superficies visibles. Ya hay una (el README público) y vienen tres más: la consola de Next.js, la página del proyecto en `ayyoub.dev` y el vídeo de la demo. Sin una decisión escrita, cada una acabaría con su propia paleta, que es el motivo por el que tantos proyectos personales parecen cuatro proyectos distintos.

Hay además una restricción específica del caso: la marca tiene que funcionar a 16 píxeles, porque el favicon y el avatar del repositorio son los sitios donde más gente la va a ver. Esa restricción manda sobre cualquier consideración estética.

## Opciones consideradas

**1. No decidir todavía y tirar de los valores por defecto del framework.** Tailwind o shadcn traen una paleta y una tipografía razonables, y no cuesta nada. El problema es que se reconocen de lejos: un proyecto con `slate-800`, `Inter` y tarjetas con borde izquierdo de color se lee como una plantilla, y el proyecto es un escaparate cuyo mensaje es el cuidado por el detalle. También aplaza una decisión que va a costar más cuando existan pantallas.

**2. Paleta convencional de producto de seguridad.** Azul corporativo, gris frío, tipografía de sistema. Nadie la critica y nadie la recuerda. Es exactamente lo que hace el software del sector, y precisamente por eso no distingue.

**3. Identidad propia y sobria.** Fondo hueso cálido en lugar de blanco, tinta casi negra ligeramente fría, un único acento de latón apagado, una grotesca con carácter para los titulares y una monoespaciada para los datos.

## Decisión

Se elige la **opción 3**, con estos componentes.

**Marca.** El ojo de cerradura macizo partido por la ranura de la guarda. Se eligió entre cuatro propuestas (ojo de cerradura partido, llave con dentado ascendente, monograma con muescas y pastilla con calado) y ganó por la prueba de tamaño: es la única que es masa en lugar de línea, y por tanto la única que no se deshace a 16 píxeles. La propuesta de la llave contaba mejor el nombre, uniendo linaje y guarda en el dentado, pero su dentado desaparece por debajo de 32 píxeles.

Se define además una **variante simplificada** sin ranura para tamaños por debajo de 24 píxeles, y una **pastilla** con el ojo de cerradura calado para avatar y superficies cuadradas.

**Color.** Fondo `#F5F4F0`, tinta `#15161A`, acento latón `#8A6229`, con su equivalencia para tema oscuro. El latón acompaña a la idea de llave y de cerradura sin caer en el dorado de lujo.

**Tipografía.** Space Grotesk para nombre, titulares e interfaz. IBM Plex Mono para etiquetas, identificadores y datos.

Los valores concretos viven en [`docs/brand/README.md`](../brand/README.md), que es la fuente única. Este ADR registra el porqué, no los hexadecimales.

## Consecuencias

- **La monoespaciada no es decoración.** Lineward va a mostrar identificadores de cuenta, valores nativos de entitlements, hashes de la cadena de auditoría y marcas de tiempo. Todo eso se compara mejor en ancho fijo, así que la elección tipográfica resuelve un problema real de lectura de la consola, no solo de estilo.
- **Contraste fijado por abajo.** `#5A5B61` es el gris más claro admisible para texto pequeño sobre el fondo hueso si se quiere cumplir WCAG AA. Queda escrito porque el error clásico es aclarar los grises hasta que "quedan finos" y dejar la interfaz por debajo del umbral.
- **El acento queda prohibido para texto pequeño.** El latón sobre fondo hueso no llega a 4.5:1 en cuerpos pequeños. Se usa en enlaces, marcas y elementos grandes.
- **Dos familias tipográficas implican una decisión de despliegue.** En la consola hay que autoalojarlas o cargarlas con `next/font`, nunca enlazar a Google Fonts en producción: evita depender de un tercero en el camino crítico y evita filtrar la IP de cada visitante a un servicio externo. Es coherente con un proyecto que habla de privacidad y de mínimo privilegio.
- **El tema oscuro está esbozado, no resuelto.** Existen los tokens y la marca en negativo, pero el comportamiento completo de la consola en oscuro se decidirá cuando exista la consola.
- **Queda pendiente el formato de mapa de bits.** Un `favicon.ico` de respaldo y las imágenes de previsualización para redes sociales se generarán cuando haya sitio web. El SVG no cubre esos casos.
- **La identidad limita a los ADR futuros.** Cualquier biblioteca de componentes que se elija para la consola tendrá que admitir tokens propios sin pelearse con ellos, lo que descarta las que traen una estética fuerte y poco maleable.
