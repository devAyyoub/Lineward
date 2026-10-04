# ADR-001 · Monolito modular frente a microservicios

- **Estado:** Aceptado
- **Fecha:** 2026-10-04

## Contexto

Hay que decidir la forma del despliegue antes de crear el esqueleto del backend, porque esa decisión determina si el proyecto es un POM de Maven con paquetes por módulo de negocio o varios artefactos desplegables por separado. Escribir el esqueleto primero sería tomar la decisión con los dedos y documentarla después.

**Fuerzas del dominio.** Un IGA tiene un grafo de dependencias inusualmente tupido. `identity` alimenta a `access`, que alimenta a `provisioning`, que alimenta a `audit`. Y `sod` no consulta a uno: necesita ver al mismo tiempo los entitlements efectivos de una identidad, las reglas de conflicto y las excepciones vigentes para poder decidir. Un dominio con dependencias fuertes es exactamente el que peor se parte en servicios.

**Fuerzas de consistencia.** El flujo de alta de la sección 7.5 crea una identidad, calcula sus accesos de nacimiento, genera tareas de aprovisionamiento y registra eventos de auditoría. Eso tiene que ser atómico o quedar registrado como pendiente de forma fiable, nunca a medias. Con una sola base de datos es una transacción. Repartido en servicios son transacciones distribuidas y compensaciones desde el primer día, antes de que exista una sola funcionalidad.

**Fuerzas del proyecto.** Lo construye una persona en paralelo a una jornada completa (sección 11). La sección 2.2 exige que todo se levante con un comando. Y la sección 1.1 exige que Ayyoub entienda cada pieza, lo que descarta por sí solo cualquier arquitectura cuyo coste de operación consuma el tiempo que debería ir a aprender el dominio.

**Fuerza de escaparate.** El proyecto tiene que demostrar ingeniería de nivel senior (sección 2.5). Eso sube el listón: no basta con elegir lo cómodo, hay que elegirlo con argumento y, sobre todo, **hay que poder demostrar que los límites existen de verdad**. Un monolito que se autodenomina modular sin nada que lo verifique es un monolito normal con buena prensa.

**Fuerza nueva, del 2026-10-04.** La dirección de producto de la sección 17 añade dos posibilidades que antes no existían: un reparto Open Core (parte del código abierto, parte comercial) y la extracción de un plano de control de agentes. Ninguna de las dos exige microservicios hoy, pero las dos exigen que los límites entre módulos sean reales, porque un reparto futuro se haría justo por esas líneas. Esta fuerza refuerza la decisión en lugar de cambiarla.

## Opciones consideradas

**1. Monolito sin límites internos**, con paquetes por capa técnica (`controllers`, `services`, `repositories`).

Es lo más rápido para empezar y lo que sale por defecto si nadie decide nada. Su problema es conocido y tiene nombre: cualquier clase puede llamar a cualquier clase, así que las dependencias crecen en todas las direcciones hasta que mover una pieza obliga a tocar media aplicación. En este proyecto tiene además dos costes específicos: destruiría la opción de un reparto Open Core, porque no habría líneas por donde cortar, y eliminaría uno de los argumentos de portfolio más valiosos, que es poder enseñar límites verificados.

**2. Monolito modular con límites verificados.** Una sola aplicación desplegable, módulos internos con fronteras explícitas, y tests que fallan cuando alguien las cruza.

**3. Microservicios desde el inicio.** Un servicio por módulo o por grupo de módulos.

Tres costes, por orden de gravedad. El primero es que convierte el flujo de alta en una transacción distribuida y la consulta de linaje del acceso ("por qué tiene este permiso") en una llamada en abanico a varios servicios. El segundo es que `sod` necesita una vista consistente de varios módulos a la vez para decidir, y en un sistema distribuido esa vista no existe sin más trabajo. El tercero es el coste de operación y observabilidad, que para una persona es tiempo robado al dominio. Descartada, y no por tamaño: por la forma del grafo de dependencias.

**4. Híbrido: monolito más PDP separado desde el día uno.** Sacar M9 a su propio servicio desde el principio, porque es el único con un perfil de latencia distinto.

Es la opción menos mala de las descartadas y probablemente sea el futuro, pero hoy es prematura: el PDP no existe hasta la Fase 5 y no hay ninguna medición que justifique separarlo. Separar por intuición es exactamente lo que el principio 7 de la sección 3.3 prohíbe.

## Decisión

**Monolito modular**, con los límites verificados en integración continua.

### Mecánica

- **Paquetes por módulo de negocio, no por capa técnica**, como ya establece la sección 10.4. La estructura es la de la sección 7.3, bajo `dev.ayyoub.lineward`.
- **Spring Modulith 2.1.x** (versión fijada en ADR-006) establece la convención: cada subpaquete directo del paquete principal es un módulo, y su paquete base es su API pública.
- **Las dependencias permitidas se declaran** con `@ApplicationModule(allowedDependencies = ...)` en el `package-info.java` de cada módulo. Un módulo sin la anotación puede depender de cualquier otro, así que declararlas es lo que convierte el diseño en una restricción.
- **Un test ejecuta `ApplicationModules.of(LinewardApplication.class).verify()`** y corre en cada push. Es el test que hace que los límites existan; sin él, la sección 7.3 es un dibujo.
- **`audit` escucha eventos de todos y nadie depende de `audit`**, como ya dice la sección 7.3. Esto es lo que hace que el registro de auditoría sea creíble: ningún módulo puede alterar lo que se audita de él.
- **`shared` se mantiene plano**, sin subpaquetes, para que su contenido siga siendo accesible desde los demás módulos. Si en algún momento necesita subpaquetes, habrá que declararlo módulo abierto con `@ApplicationModule(type = Type.OPEN)`, y conviene resistirse: un `shared` que crece es el sitio donde se esconde el acoplamiento que los tests ya no ven.

### Hallazgo que corrige la lectura de la sección 7.3

La sección 7.3 dibuja `connector` con cuatro subpaquetes: `keycloak`, `ldap`, `scim` y `flaky`. Verificado contra la documentación de Spring Modulith:

> "Any sub-package of the application module base package, is considered an _internal_ one. Code within those must not be referred to from other modules."

Es decir, **esos cuatro no son cuatro módulos: son el interior de un solo módulo `connector`**. La estructura dibujada es correcta, pero significa algo distinto de lo que podría parecer, y resulta que significa algo mejor: la interfaz común de conector (la SPI) vive en el paquete base y es lo único visible, mientras que las implementaciones concretas quedan ocultas. La consecuencia es que **ningún módulo puede hablar con el conector de Keycloak directamente**, ni por accidente ni por atajo. Todo el mundo pasa por la abstracción, y el test lo garantiza en vez de confiar en la revisión de código. Es justo la propiedad que M3 quiere demostrar.

## Consecuencias

**Lo que se gana**

- La atomicidad del flujo de alta sale gratis: una transacción de base de datos.
- Un solo artefacto que levantar, depurar y desplegar, lo que cumple el requisito de un comando de la sección 2.2.
- Mover una frontera es barato mientras el diseño es joven. Esta es la ventaja que más se subestima: en los primeros meses las fronteras **se van a mover**, porque el modelo de dominio todavía no está decidido, y moverlas dentro de un monolito es un refactor, mientras que entre servicios es una migración.
- Los límites se demuestran, no se afirman. Es material directo de entrevista y es la línea por la que se haría un reparto Open Core (sección 17.4).

**Lo que se pierde, o lo que hay que vigilar**

- **Un solo radio de impacto.** Un fallo grave tumba todo. Se mitiga con tests, no con arquitectura.
- **Escalado en bloque.** No se puede dar más máquina solo al PDP. Hasta la Fase 5 no hay nada que lo necesite.
- **Tentación permanente de atajar.** La diferencia entre un monolito modular y un monolito es un test. Si ese test se desactiva "temporalmente" para desbloquear algo, la arquitectura desaparece esa misma tarde. La sección 1.5 ya prohíbe desactivar tests; aquí es donde más va a doler cumplirlo.

**El límite de la verificación, que es lo más importante de este ADR**

Spring Modulith y ArchUnit analizan **tipos de Java**. No ven la base de datos. Dos módulos pueden estar perfectamente desacoplados en código y estar fusionados por SQL: basta que uno lea las tablas del otro, o que una clave ajena cruce la frontera. Ningún test lo detecta, ninguna herramienta avisa, y el acoplamiento es igual de real y bastante más difícil de deshacer.

De ahí tres reglas que acompañan a esta decisión y que valen desde la primera migración de Flyway:

1. **Cada módulo es dueño de sus tablas.** Los demás no las leen directamente.
2. **Sin claves ajenas que cruzen un límite de módulo.** La referencia se guarda como identificador y la coherencia la garantiza el dominio.
3. **Sin consultas que unan tablas de módulos distintos.** Si hace falta el dato, se pide por la API del módulo dueño o se recibe por evento.

Las tres son el precio real de mantener abierta la salida de emergencia, y las tres son invisibles a los tests, así que son disciplina revisada, no automatismo. Conviene que entren en la lista de comprobación de `/revisa`.

**La salida de emergencia**

**M9, el PDP, es el primer candidato a extraerse**, por ser el único componente con un perfil distinto del resto: decide en el camino crítico de cada petición, se mide en milisegundos y su carga no se parece a la de un trabajo de agregación nocturno.

La condición para extraerlo, escrita ahora y no cuando duela: que exista una **medición** en la Fase 5 que demuestre que la latencia o el patrón de escalado del PDP no se puede resolver dentro del monolito (con caché, con virtual threads o con índices). Sin número medido, no se extrae. Si llega ese día, las tres reglas de arriba son lo que hace que la extracción sea un trabajo de días y no de meses.

**Relación con otras decisiones**

- **ADR-011** decide que el aislamiento entre organizaciones vive en la base de datos con Row Level Security. Es independiente de este ADR y compatible: funciona igual con uno o con veinte despliegues, y la regla 1 de arriba (cada módulo dueño de sus tablas) no estorba a una política por tabla.
- **ADR-003**, el broker de mensajes, sigue pendiente y este ADR no lo prejuzga. Un monolito modular puede comunicar sus módulos con eventos en memoria, que es lo que Spring Modulith ofrece de serie, y eso basta hasta que el aprovisionamiento asíncrono de la Fase 2 obligue a decidir.

## Fuentes consultadas

Verificado el 2026-10-04:

- [Spring Modulith · Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html), para la convención de módulos, el carácter interno de los subpaquetes anidados, `@ApplicationModule(allowedDependencies = ...)`, los módulos abiertos y `ApplicationModules.of(...).verify()`.
- ADR-006 para las versiones de Spring Modulith y Spring Boot, verificadas el 2026-09-25.
