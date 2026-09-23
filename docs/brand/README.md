# Identidad visual de Lineward

Fuente única de la marca. Si algo de la consola, del README o de la página del proyecto no coincide con lo que dice este archivo, manda este archivo.

El porqué de cada decisión está en [ADR-009](../adr/0009-identidad-visual.md). La dirección del logo venía fijada desde [ADR-007](../adr/0007-nombre-del-proyecto.md).

## La marca

Un ojo de cerradura macizo partido por una ranura horizontal. La ranura es la **guarda** (*ward*), el obstáculo interno de una cerradura que solo deja pasar la llave correcta, que es la segunda mitad del nombre.

La marca es masa, no línea. No tiene trazos, ni contornos, ni esquinas redondeadas. Esa es la razón por la que sobrevive a 16 píxeles, y es innegociable.

| Archivo | Cuándo se usa |
|---|---|
| `lineward-mark.svg` | Uso general a partir de 24 px: cabeceras, README, composiciones con el nombre. |
| `lineward-mark-simple.svg` | Por debajo de 24 px. Cierra la ranura, que a ese tamaño ya no cabe y se emborrona. |
| `lineward-tile.svg` | Avatar del repositorio y cualquier sitio que exija una superficie cuadrada. Pastilla maciza con el ojo de cerradura calado. |
| `favicon.svg` | Pestaña del navegador. Es la versión simplificada, y cambia sola a tinta clara en tema oscuro. |

Todos llevan el color escrito en el archivo. Para usarlos en línea dentro de un componente, sustituye el `fill` por `currentColor` y controla el color desde CSS.

## Color

Nombres en inglés porque son los que acabarán siendo variables CSS.

### Tema claro

| Token | Valor | Uso |
|---|---|---|
| `ground` | `#F5F4F0` | Fondo de página. Hueso cálido, nunca blanco puro. |
| `surface` | `#FFFFFF` | Tarjetas y tablas sobre el fondo. |
| `ink` | `#15161A` | Texto principal y marca. Casi negro, ligeramente frío. |
| `ink-muted` | `#3A3B41` | Texto secundario y descripciones. |
| `ink-subtle` | `#5A5B61` | Etiquetas, pies y metadatos. |
| `hairline` | `#DDDCD6` | Bordes y separadores. |
| `accent` | `#8A6229` | Latón apagado. Enlaces y acentos puntuales. |

### Tema oscuro

| Token | Valor | Uso |
|---|---|---|
| `ground` | `#15161A` | Fondo de página. |
| `surface` | `#1D1E23` | Tarjetas. |
| `ink` | `#F5F4F0` | Texto principal y marca en negativo. |
| `ink-muted` | `#C9CAD0` | Texto secundario. |
| `ink-subtle` | `#A9AAB0` | Etiquetas y metadatos. |
| `hairline` | `#3A3B41` | Bordes. |
| `accent` | `#C89A55` | Latón aclarado para que contraste sobre fondo oscuro. |

El acento **no se usa para texto pequeño** en ningún tema. Es para enlaces, subrayados y marcas, no para párrafos.

## Tipografía

| Papel | Familia | Ajustes |
|---|---|---|
| Nombre y titulares | Space Grotesk 500 | `letter-spacing: -0.03em` en el nombre, `-0.02em` en titulares |
| Texto corrido e interfaz | Space Grotesk 400 | Sin ajuste |
| Etiquetas, datos, identificadores | IBM Plex Mono 400 y 500 | Mayúsculas con `letter-spacing: 0.14em` en las etiquetas |

La mono no es decorativa: Lineward enseña identificadores, valores nativos de entitlements, hashes de auditoría y marcas de tiempo, y todo eso se lee mejor en ancho fijo.

⚠️ En la consola, las fuentes se autoalojan o se cargan con `next/font`. No se enlaza a Google Fonts en producción, por no depender de un tercero y por no filtrar la IP de cada visitante.

## Reglas de uso

- **Área de respeto:** media altura de la marca libre por cada lado.
- **Tamaño mínimo:** 24 px para la versión con ranura. Por debajo, la simplificada.
- **Composición horizontal:** la marca mide en torno a 1,35 veces el cuerpo tipográfico del nombre, y la separación entre ambos es un tercio de la altura de la marca.
- **Una sola tinta, siempre.** Sin degradados, sin sombras, sin contornos, sin relieve.
- **No se rota, no se estira, no se recorta** y no se mete dentro de otra forma que no sea la pastilla.

## Accesibilidad

Los valores de texto están elegidos para cumplir WCAG AA, 4.5:1 en texto normal.

`ink-subtle` (`#5A5B61`) es el gris más claro que se puede usar para texto pequeño sobre `ground`. Un gris más claro queda por debajo del umbral, que es el error más común al copiar paletas de otros sitios.
