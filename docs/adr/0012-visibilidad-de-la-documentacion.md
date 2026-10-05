# ADR-012 · Visibilidad de la documentación: el documento de trabajo pasa a un repositorio privado

- **Estado:** Aceptado
- **Fecha:** 2026-10-05

## Contexto

El repositorio es público desde el primer día. Fue una decisión deliberada y su motivo está registrado: el historial completo forma parte de lo que el proyecto demuestra, y trabajar en público obliga a higiene de secretos en cada commit.

Lo que no se previó es que **un solo documento acabara haciendo dos trabajos distintos para dos públicos distintos.** `docs/PROYECTO.md` creció hasta unas 2.300 líneas y dentro había dos cosas que no se parecen en nada:

1. **Un registro de ingeniería:** arquitectura, glosario del dominio, fichas de módulo, estándares implementados, modelo de amenazas, estrategia de pruebas. Escrito para que lo lea un extraño.
2. **Un cuaderno de laboratorio y un documento de negocio:** las reglas de colaboración con el agente de desarrollo, un registro por sesión de qué se delegó y qué hubo que corregir, un diario de errores, y una estrategia de producto y comercialización. Escrito para el autor.

El segundo grupo **no tiene lector en un repositorio público.** No es información secreta: no hay credenciales, ni datos personales, ni información de terceros ni de ningún empleador. Pero publicarlo tiene coste y no tiene beneficio. A nadie que entre a evaluar la ingeniería le sirve un registro de lo que el autor no entendió a la primera, ni una estrategia comercial que declara hacia dónde apuntaría el proyecto.

**El momento forzó la decisión.** Borrar un archivo en un commit nuevo no lo quita del historial: cualquiera puede leer las versiones anteriores para siempre. Quitarlo de verdad exige reescribir el historial y hacer force push, y eso solo es limpio mientras el repositorio sea pequeño y nadie dependa de él. En el momento de decidir había **21 commits, un solo autor y cero forks, estrellas y watchers**. Esa ventana se cierra en cuanto alguien clona o bifurca.

## Opciones consideradas

**1. Dejarlo todo público.** Hay un argumento real a favor: trabajar en abierto es en sí mismo un diferenciador, y un documento de esta calidad impresiona al lector adecuado. Un ingeniero senior que lea un diario de errores honesto se fía más, no menos.

El problema es que ese lector es minoría, y hay una parte del contenido sin ninguna contrapartida. La estrategia de producto no gana nada siendo pública y pierde dos cosas: le enseña a un competidor dónde está el hueco al que se apuntaría, y le enseña a un empleador un plan que no le concierne.

**2. Sacarlo de aquí en adelante, sin reescribir el historial.** Evita el force push, que siempre es incómodo. Pero no resuelve el problema que pretende resolver: lo ya publicado sigue siendo legible indefinidamente. Es la opción que da la sensación de haber actuado sin haber actuado.

**3. Partir el documento en dos.** Lo que es escaparate se queda público como documento de arquitectura; lo demás se va. Es la opción con mejor resultado final y la más costosa: obliga a mantener dos archivos y a decidir en cada sesión dónde va cada párrafo, que es precisamente el tipo de tarea manual que se olvida y genera divergencia entre los dos.

Se descarta **por ahora, no para siempre**: vuelve como pregunta abierta, porque el repositorio público sí necesita en algún momento su propio documento de arquitectura.

**4. Cerrar el repositorio entero hasta el corte mínimo digno.** Lo más simple de ejecutar. Revierte la decisión de publicar desde el primer día y pierde el historial visible, que era justamente su motivo. Se descarta por desproporcionada: el problema no es que el proyecto sea visible, es que un archivo concreto lo es.

## Decisión

Se elige la **opción 2 con reescritura de historial**, ejecutada el 2026-10-05.

**Qué se movió:** `CLAUDE.md` y `docs/PROYECTO.md`, a un repositorio privado llamado `lineward-notes`, **conservando su historial y sus fechas**. El historial del documento es el historial del proyecto, y perderlo habría sido un coste mayor que el que se quería evitar.

**Qué se queda público:** los ADRs, el README, la marca, el código y la infraestructura. Y los seis archivos de comandos de `.claude/skills/`, por decisión explícita: son plantillas de prompt, no contienen estrategia ni registro de sesiones ni puntos débiles, y cómo se construye un proyecto con un agente de desarrollo es parte de lo que este proyecto se propone demostrar.

**Mecanismo:**

- El repositorio privado se clona en `docs/private/`, que el público ignora. Un repositorio de git dentro de una carpeta ignorada es invisible para el de fuera, así que los dos conviven en el mismo árbol de trabajo sin estorbarse y sin submódulos.
- En la raíz pública queda un `CLAUDE.md` de ocho líneas cuyo único trabajo es encadenar las importaciones hacia el privado. **Las dos importaciones se declaran en la raíz y no dentro del archivo privado**, porque no se verificó si una importación anidada se resuelve contra el archivo que la declara o contra la raíz del proyecto. Declarándolas arriba, la pregunta deja de importar. Si se hubiera encadenado y resolviera de otra forma, el fallo habría sido silencioso.
- El historial público se reescribió con `git filter-repo --invert-paths` y se publicó con force push, después de una copia de seguridad completa.

## Consecuencias

**Lo que se gana.** El repositorio público contiene solo lo que está escrito para que lo lea un extraño. Los ADRs más el README son ese registro, y los ADRs ya estaban redactados con esa intención.

**El coste real, y hay que decirlo.** Los ADRs anteriores a esta separación (002, 006, 007, 008 y 009) citan secciones de un documento que ya no es público: "ver sección 7.2", "ver sección 2.4". Cada ADR reexpone el razonamiento que necesita, así que siguen siendo comprensibles, pero quedan incompletos para quien llegue de fuera. No se corrigen, porque los ADR históricos no se reescriben. Esto es lo que justifica la pregunta abierta sobre un documento de arquitectura público, que es la opción 3 aplazada.

**Una fuente de divergencia nueva.** Dos repositorios significan que un commit en uno no cubre el otro, y el privado se queda atrás **en silencio**, precisamente porque está ignorado. Es el mismo problema que el diario de este proyecto ya tiene registrado tres veces en tres herramientas distintas: una copia del estado presentada con el aplomo del estado. Mitigación: el ritual de cierre de tarea recuerda explícitamente los commits de los dos repositorios. Es una mitigación de proceso, no técnica, lo cual significa que puede fallar.

**La reescritura no es una garantía criptográfica.** GitHub puede conservar objetos huérfanos alcanzables por su identificador durante un tiempo, y cualquier clon hecho antes de la reescritura conserva el historial viejo. Es aceptable aquí porque el contenido nunca fue secreto. Conviene dejar escrito el criterio para la próxima vez: **si lo filtrado hubiera sido una credencial, la respuesta correcta habría sido rotarla, no reescribir el historial.** Reescribir sirve para quitar lo que no debía estar; no sirve para deshacer una filtración.

**La ventana está cerrada.** Repetir esta operación más adelante, con forks o con contribuciones externas, sería mucho más caro o imposible. Esta clase de decisión tiene fecha de caducidad y conviene tomarla temprano, igual que la licencia.

**No cambia nada de la licencia.** El código sigue siendo público bajo Apache 2.0, y la puerta a una licencia dual que ADR-008 dejó abierta sigue abierta mientras haya un único titular del copyright. Esta decisión separa el cuaderno de trabajo del escaparate; **no es un paso hacia cerrar el código**, y conviene que no se lea así.
