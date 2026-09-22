# ADR-002 · Idiomas: código en inglés, documentación de trabajo en español, cara pública en inglés

- **Estado:** Aceptado
- **Fecha:** 2026-09-22

## Contexto

El proyecto sirve a dos propósitos que tiran en direcciones opuestas.

Como **material de aprendizaje**, el documento maestro, los ADR y el diario de errores se escriben para que Ayyoub entienda y sepa defender cada pieza (sección 1.1). Ese trabajo se hace mejor en la lengua en la que se piensa, y además alimenta directamente los posts del blog.

Como **escaparate profesional**, el repositorio se dirige a reclutadores técnicos, arquitectos de seguridad y auditores (sección 2.4), y el idioma de facto tanto del software libre como de la literatura IAM es el inglés. Todos los estándares que el proyecto implementa (RFC de OAuth, SCIM, LDAP, token exchange) están en inglés, y sus términos no tienen traducción establecida en castellano: nadie dice "punto de decisión de política" en una conversación real, se dice PDP.

La sección 10.4 ya fijaba "nombres en inglés, documentación en español", pero esa regla no resolvía el caso de la documentación **pública**, que es justo lo que la pregunta abierta de la sección 16 planteaba. Escribir el README obliga a zanjarlo.

## Opciones consideradas

**1. Todo en español.** Coherente con `ayyoub.dev` y con el resto de la documentación, y auténtico. Pero obliga a traducir términos técnicos que nadie traduce, y reduce el alcance del repositorio justo en el público que más interesa. Un arquitecto de seguridad que llega desde LinkedIn y encuentra un README en español asume que el proyecto no está pensado para él.

**2. Todo en inglés.** Máximo alcance y coherencia con el ecosistema. El coste es alto y recae donde más duele: escribir en inglés los razonamientos largos de los ADR y del diario de errores ralentiza el trabajo y empobrece el matiz, que es precisamente el valor de esos documentos. Se estaría pagando un impuesto permanente sobre el material de aprendizaje para beneficiar a un lector que quizá nunca llegue.

**3. Separación por función.** El idioma lo decide el destinatario del documento, no el proyecto. Inglés en lo que se publica hacia fuera, español en lo que sirve para aprender, inglés siempre en el código.

**4. Bilingüe completo.** Cada documento en las dos lenguas. Máximo alcance y máximo coste de mantenimiento. En un proyecto de una sola persona, la traducción es lo primero que se queda desactualizado, y una traducción desincronizada es peor que no tenerla, porque el lector no sabe cuál de las dos versiones manda.

## Decisión

Se elige la **opción 3, separación por función**, con estas reglas concretas.

**Siempre en inglés:**
- Código: identificadores, clases, métodos, paquetes, comentarios de código.
- Base de datos: nombres de tablas, columnas, índices y migraciones.
- API: rutas, campos de los DTO, códigos de error, eventos de dominio.
- Mensajes de log.
- Mensajes de commit, nombres de rama y títulos de pull request.
- `README.md` y, cuando existan, la página del proyecto, los diagramas C4 publicados y los subtítulos del vídeo de demostración.

**Siempre en español:**
- `docs/PROYECTO.md` completo.
- Los ADR de `docs/adr/`.
- El diario de errores y el registro de sesiones.
- El documento de mapeo de cumplimiento de `docs/compliance/`, con una excepción: las citas literales de normativa se mantienen en el idioma original de la fuente. El ENS es normativa española, DORA y NIS2 tienen versión oficial en castellano, y las guías del NIST y de OWASP solo existen en inglés.

**Sin traducir nunca:**
- Los términos y siglas del dominio (entitlement, joiner, birthright, drift, SoD, PDP, PEP) se usan en inglés incluso dentro de un texto en español. La sección 4 los define una vez y a partir de ahí se usan tal cual, que es como se habla en el sector.

## Consecuencias

- El `README.md` queda desacoplado del documento maestro y hay que **sincronizarlo a mano** cada vez que avance una fase. Es deuda de mantenimiento aceptada: el README debe llevar siempre el estado real de las fases, y nada lo comprueba de forma automática.
- Un lector internacional encuentra la portada en inglés y el detalle en español. Es una barrera real y consciente: el detalle en español es material de aprendizaje, no documentación de producto. Si algún día el proyecto busca contribuciones externas, habrá que traducir o al menos resumir en inglés el documento maestro y añadir un `CONTRIBUTING.md` en inglés.
- El idioma de la **interfaz de la consola** queda sin decidir. Por la regla de la cara pública le correspondería inglés, pero afecta a la experiencia de la demo y a las capturas de la web. Se decide en la fase que construya la consola, y si se aparta de esta regla se registra como ADR propio.
- La sección 10.4 del documento maestro queda subsumida por este ADR, que es más específico. Si las dos se contradicen en el futuro, manda el ADR.
- Queda por resolver el idioma de los posts del blog (sección 15). No lo decide este ADR porque el blog es de `ayyoub.dev`, no del repositorio, pero conviene que la decisión sea consciente: los posts son el vehículo principal de difusión del proyecto.
