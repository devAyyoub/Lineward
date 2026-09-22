# ADR-008 · Licencia: Apache License 2.0

- **Estado:** Aceptado
- **Fecha:** 2026-09-22

## Contexto

Lineward se publica como software libre. La licencia no es un trámite administrativo: es el contrato con quien lea, use o redistribuya el código, y tiene una propiedad incómoda, es **irrevocable para todo lo ya publicado bajo ella**. Quien reciba hoy una versión bajo una licencia conserva esos derechos para siempre sobre esa versión, aunque mañana se cambie de licencia.

Hoy la decisión es barata porque Ayyoub es el **titular único del copyright**. En el momento en que se acepte la primera contribución externa, relicenciar dejará de ser una decisión unilateral y pasará a necesitar el permiso de cada contribuyente. Por eso se decide ahora y no más adelante.

Fuerzas que influyen:

- **El público objetivo** (sección 2.4) incluye arquitectos de seguridad y responsables de cumplimiento que trabajan en empresas grandes, muchas de ellas con políticas internas explícitas sobre qué licencias pueden tocar sus ingenieros.
- **El proyecto es el escaparate de una futura línea de auditoría IAM freelance** (sección 2.2), así que conviene no cerrar la puerta a un uso comercial propio.
- **ADR-007** dedicó un esfuerzo considerable a elegir un nombre sin colisiones. Sería incoherente regalar después ese nombre con la licencia.
- **Lineward se integra con Keycloak**, que se distribuye bajo Apache License 2.0. La compatibilidad de licencias con las dependencias no es opcional.

Este ADR recoge criterio de ingeniería, no asesoramiento jurídico.

## Opciones consideradas

**1. MIT.** Unas quince líneas que caben en una pantalla: haz lo que quieras, conserva el aviso de copyright, no hay garantía. Su virtud es que no necesita interpretación, y por eso es la licencia más extendida.

Su carencia es lo que no dice. **No menciona las patentes**, y existe discusión jurídica sobre si concede licencia de patentes de forma implícita. **No menciona las marcas**, de modo que nada impide a un tercero redistribuir el proyecto sin cambios y seguir llamándolo Lineward. Para un proyecto cuyo tema central es el control de accesos, elegir la licencia que deja los flancos sin cubrir manda una señal equivocada.

**2. Apache License 2.0.** Es MIT más tres piezas que aquí importan:

- **Sección 3, concesión expresa de patentes**, con cláusula de represalia: quien inicie un litigio de patentes alegando que el software las infringe pierde automáticamente la licencia de patentes concedida.
- **Sección 6, marcas**, que niega de forma explícita cualquier concesión sobre los nombres comerciales y las marcas del titular. Es exactamente la protección que ADR-007 dejó sin cubrir.
- **Sección 4, condiciones de redistribución**, que obliga a conservar los avisos y a propagar el archivo `NOTICE` si existe. La atribución queda más estructurada que con la línea suelta de MIT.

Su único inconveniente real es la longitud, doscientas líneas de texto legal frente a quince. No es un problema práctico: las licencias estándar se reconocen por el nombre y GitHub las identifica de forma automática.

**3. AGPL 3.0.** Copyleft de red. La GPL clásica activa la obligación de publicar el fuente cuando se **distribuye** el software. Un proveedor que ofrece el software como servicio en la nube nunca distribuye nada, porque el usuario solo se conecta por red, así que la obligación no llega a activarse. A ese hueco se le llamó durante años la laguna ASP, y la AGPL existe para cerrarlo: quien ofrezca el software a través de una red debe ofrecer también su código fuente a esos usuarios.

Es una licencia excelente **cuando protege un modelo de negocio**, típicamente el de una empresa que teme que un proveedor cloud absorba su producto sin devolver nada. Lineward no tiene ese modelo de negocio. A cambio, su coste es concreto y va en contra del objetivo principal: numerosas políticas corporativas prohíben a sus ingenieros incorporar, y a veces incluso examinar, código bajo AGPL. Elegirla reduciría el número de personas del público objetivo que se atreverían a clonar el repositorio.

## Decisión

Se elige **Apache License 2.0**.

Cubre el flanco de las patentes, protege el nombre que ADR-007 costó elegir, no genera fricción en entornos corporativos y coincide con la licencia de Keycloak, que es una dependencia central de la arquitectura. Para un proyecto cuyo mensaje es el rigor en el control de accesos, es la opción coherente.

La AGPL se descarta por resolver un problema que el proyecto no tiene, a un precio que se paga justo en el objetivo principal. MIT se descarta por omisión, no por defecto: es una buena licencia que aquí deja sin cubrir dos cosas que importan.

Se aplica el texto íntegro y sin modificar, con el apéndice relleno con el año y el titular, en un archivo `LICENSE` en la raíz del repositorio.

## Consecuencias

- **Compatibilidad hacia arriba resuelta.** Se pueden incorporar dependencias Apache 2.0, MIT y BSD sin conflicto.
- **Compatibilidad hacia abajo con matiz.** Según la Free Software Foundation, Apache 2.0 es compatible con GPLv3 pero **no con GPLv2**. Si alguna vez se plantea una dependencia GPLv2 sin la cláusula "o posterior", hay que estudiarlo antes, no después.
- **La sección 6 no registra ninguna marca.** Evita que la licencia se interprete como una cesión del nombre, que es distinto de proteger el nombre frente a terceros. Para eso haría falta un registro en la EUIPO, que ADR-007 ya dejó anotado como pendiente si el nombre llega a usarse para vender servicios.
- **Licencia dual posible mientras el titular sea único.** Si en el futuro hay una versión comercial, se puede ofrecer el mismo código bajo otra licencia a quien pague por ella. Esa puerta se cierra en cuanto entren contribuciones externas sin un acuerdo previo, así que **antes de aceptar la primera contribución externa hay que decidir si se adopta un DCO o un CLA**, y añadir un `CONTRIBUTING.md`.
- **No se crea archivo `NOTICE` por ahora.** Apache 2.0 no obliga a crearlo, solo a propagarlo si existe. Crear uno vacío de contenido útil solo añade obligaciones a quien redistribuya. Se creará cuando haya algo real que declarar, por ejemplo código de terceros incorporado al repositorio.
- **Queda pendiente decidir si se ponen cabeceras de licencia en cada archivo fuente.** El apéndice de la licencia lo recomienda, muchos proyectos no lo hacen y algunos lo automatizan en CI. Se decide al crear el esqueleto del backend, porque es más barato hacerlo desde el primer archivo que retroactivamente sobre cientos.
- **La demo pública queda cubierta.** Apache 2.0 no impone obligaciones por ofrecer el software a través de una red, así que desplegar la demo en el homelab no genera ninguna obligación adicional.
