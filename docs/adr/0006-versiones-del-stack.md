# ADR-006 · Versiones del stack: Java 25, Spring Boot 4.1, PostgreSQL 18, Keycloak 26.7

- **Estado:** Aceptado
- **Fecha:** 2026-09-25
- **Versiones verificadas el:** 2026-09-25

## Contexto

Antes de escribir la primera línea de código hay que fijar números. El esqueleto de Spring Boot, el `docker-compose.yml`, el primer esquema de Flyway y la CI necesitan todos la misma respuesta, y si cada uno la responde por su cuenta acaban divergiendo.

La decisión no es "cuál es la última versión de cada cosa". Es doble:

1. **En qué ventana de soporte se entra.** El proyecto está estimado en cinco a ocho meses de trabajo paralelo (sección 11) y la demo pública debe quedar viva después. Una pieza cuyo soporte expira a mitad del roadmap obliga a una migración no planificada en el peor momento.
2. **Qué combinaciones se han comprobado que funcionan juntas.** El riesgo de un stack no vive en las piezas, vive en el producto cartesiano. Cada versión por separado puede ser estable y el conjunto no arrancar. Durante la verificación de este ADR apareció un caso exacto, descrito más abajo.

Fuerzas que influyen:

- **La sección 7.2 ya expresaba una preferencia** ("Java LTS más reciente, 25 si todo es compatible, si no 21") y la marcaba con un aviso de verificar. Este ADR ejecuta esa verificación en lugar de heredar la hipótesis.
- **La Fase 6 depende de funcionalidades concretas del IdP**, no solo de que exista: token exchange (RFC 8693), CIBA y DPoP. Elegir la versión de Keycloak sin mirar el estado de soporte de esas tres cosas sería trasladar un riesgo del mes seis al mes cero sin haberlo mirado.
- **El proyecto es material de portfolio.** Un stack que en una entrevista se justifica con "puse lo último" resta. Uno que se justifica con ventanas de soporte y compatibilidades verificadas suma.
- **La lección ya está en el diario de errores** (sección 14, entrada del Anexo A): escribir en el documento lo que se cree que soporta una herramienta es una hipótesis, no un hecho. Por eso este ADR lleva fecha de verificación, no solo fecha de decisión.

### Alcance

Este ADR cubre **backend e infraestructura**: JDK, herramienta de construcción, framework, modularidad, base de datos, migraciones, IdP y librerías de test.

Deja **fuera el frontend** (Node, Next.js, TypeScript) y las piezas que dependen de decisiones todavía pendientes: broker de mensajes (ADR-003), motor de políticas (ADR-004) y framework de IA en Java (ADR-005). Fijar hoy la versión de Next.js sería fijar un número para una pieza que no se toca hasta la Fase 1 y que habrá caducado cuando llegue el momento. Cada una se fija cuando se empieza a usar, con su verificación propia.

## Opciones consideradas

**1. La última versión publicada de cada pieza.** Atractivo por simple y por el argumento de "empiezo el proyecto hoy, empiezo con lo de hoy".

Aplicado literalmente el 2026-09-25 habría elegido **Java 27**, publicado hace diez días. Java 27 es una versión **non LTS**: recibe actualizaciones hasta marzo de 2027, cuando la sustituye Java 28. Eso significa un salto de versión de JDK obligatorio a los seis meses de arrancar, en plena Fase 2 o 3. Además, Spring Boot 4.1.1 declara compatibilidad **hasta Java 26 inclusive**, así que Java 27 no está cubierto por el framework que se va a usar.

La misma lógica aplicada a PostgreSQL habría llevado a esperar a la versión 19, que está en Beta 4 desde el 24 de septiembre de 2026 con GA prevista en octubre, es decir, a bloquear el esqueleto varias semanas para estrenar una versión mayor el día de su publicación.

Se descarta: "lo último" y "lo que tiene soporte" no son lo mismo, y confundirlos es el error que este ADR existe para evitar.

**2. LTS conservador: Java 21 y Spring Boot 3.5.** La opción de quien prioriza estabilidad sobre todo lo demás. Java 21 es LTS y está probadísimo, y Spring Boot 3.x es la línea que más documentación y más respuestas de terceros tiene acumuladas.

Tiene dos problemas. El primero es de calendario: el soporte open source de Spring Boot 3.5 **terminó el 30 de junio de 2026**, es decir, ya. Seguir en 3.x exigiría soporte comercial de Tanzu, que no aplica aquí. Arrancar un proyecto nuevo en una línea sin soporte comunitario es empezar endeudado.

El segundo es de objetivos. La sección 2.2 fija como objetivo de aprendizaje el dominio de **virtual threads** y la concurrencia en Java moderna, y la Fase 2 incluye un benchmark de paralelismo. Java 21 los tiene, pero renunciar a dos años de mejoras del lenguaje y de la JVM sin ganar nada en soporte, porque 25 también es LTS y con ventana más larga, no tiene contrapartida.

**3. LTS más reciente del JDK, línea estable más reciente del framework.** Java en su LTS vigente, Spring Boot en la línea con soporte open source más largo por delante, base de datos en la última versión mayor con un año de madurez acumulado, y todo lo demás heredado del BOM de Spring Boot salvo excepción justificada.

Es la opción elegida, y es la que expresaba la sección 7.2 antes de verificarla.

### El hallazgo que decidió la línea de Spring Boot

La verificación destapó un fallo real y documentado: una aplicación con **Spring Boot 4.0.2 y PostgreSQL 18.1 no arranca**. Lanza `Unsupported Database: PostgreSQL 18.1` durante la inicialización de beans, porque la versión de Flyway que gestiona el BOM de Boot 4.0.2 (Flyway 11.14.1) es anterior a PostgreSQL 18 y no la reconoce. La incidencia 49012 del repositorio de Spring Boot se cerró como no válida, lo que significa que el arreglo corresponde a quien monta el stack: subir de línea o sobrescribir a mano la propiedad de versión de Flyway.

La línea 4.1 gestiona Flyway 12.4.0 y no tiene el problema.

Esto importa más allá del número, por tres motivos:

- Da un argumento para elegir la línea 4.1 sobre la 4.0 que no depende de las fechas de soporte.
- Ilustra dónde rompen de verdad las versiones nuevas de una base de datos: **primero en las herramientas que la inspeccionan** (migraciones, drivers de test, clientes administrativos) y no en el motor ni en el driver JDBC. Flyway consulta la versión del servidor y decide si la soporta, así que una versión mayor recién publicada la rompe antes que ninguna consulta SQL.
- Es la razón concreta por la que la política de PostgreSQL de este ADR no es "subir a la última", sino "subir cuando las herramientas que la inspeccionan la declaren soportada".

## Decisión

### Versiones fijadas

| Pieza | Versión | Soporte hasta | Motivo |
|---|---|---|---|
| **JDK** | Java **25** (LTS), distribución Eclipse Temurin | sept 2028 | GA el 16 sept 2025, LTS vigente y más reciente. Spring Boot 4 declara soporte de primera clase para 25. La siguiente LTS es Java 29 en sept 2027 |
| **Construcción** | Apache Maven **3.9.16**, vía Maven Wrapper | rama estable | Maven 4.0.0 sigue en release candidate (rc-6) y su propia documentación lo declara no apto para producción |
| **Framework** | Spring Boot **4.1.x** (4.1.1) | soporte OSS jul 2027 | Línea GA el 30 jun 2026. La 4.0 pierde soporte OSS el 31 dic 2026 y arrastra el fallo de Flyway con PostgreSQL 18 |
| **Modularidad** | Spring Modulith **2.1.x** | alineado con Boot 4.1 | La línea 2.0 subió su base a Boot 4 y Spring Framework 7. Se consume vía su propio BOM |
| **Base de datos** | PostgreSQL **18** (18.6) | nov 2030 | GA el 25 sept 2025, un año de madurez. Cinco años de soporte por delante |
| **IdP** | Keycloak **26.7.x** (26.7.3) | ver política de la 26.x | Última línea estable. La 26.7.0 salió el 9 jul 2026 |
| **Migraciones** | Flyway **12.4.0** | heredado del BOM de Boot 4.1 | Cubre PostgreSQL 18 |
| **Driver JDBC** | PostgreSQL JDBC **42.7.13** | heredado del BOM | |
| **Tests con infraestructura** | Testcontainers **2.0.5** | heredado del BOM | |

Las tres últimas no se declaran en el `pom.xml`: se toman del BOM de Spring Boot y se anotan aquí solo para que quede registro de qué se verificó.

### Herramienta de construcción: Maven

Se elige **Maven 3.9.16** sobre Gradle. Es el estándar de facto del ecosistema Spring, el BOM y el POM padre de Spring Boot encajan de forma directa, y la documentación oficial y las respuestas de terceros aparecen antes en Maven. Gradle es mejor en construcciones incrementales y en proyectos multimódulo grandes, pero Lineward es un **monolito modular**, un solo artefacto desplegable con módulos internos verificados por tests de arquitectura, así que no llega a cobrar esa ventaja.

Maven 4 se descarta de forma explícita: sigue en release candidate y su propia documentación advierte de que no es apto para producción. Se revisará cuando llegue a GA, sin prisa.

Se usa el **Maven Wrapper** (`mvnw`) para que la versión de Maven quede fijada en el repositorio y la construcción local y la de CI sean idénticas. Una construcción que depende de qué Maven tenga instalado cada máquina no es reproducible.

### Política: qué se fija explícitamente y qué se hereda

Esta parte importa tanto como los números, porque es lo que evita que el stack derive con el tiempo.

**Se fija de forma explícita**, porque son las piezas de las que dependen todas las demás:

| Qué | Dónde vive el número |
|---|---|
| Versión de Java | propiedad del `pom.xml`, toolchain de Maven, imagen base del `Dockerfile` y `setup-java` en la CI |
| Versión de Maven | Maven Wrapper (`.mvn/wrapper/maven-wrapper.properties`) |
| Spring Boot | versión del POM padre |
| Spring Modulith | propiedad de versión de su BOM |
| PostgreSQL y Keycloak | etiquetas de imagen en `docker-compose.yml` y en los contenedores de Testcontainers |

**Se hereda del BOM de Spring Boot** todo lo demás: Flyway, driver JDBC, Testcontainers, Jackson y el resto del árbol gestionado. Sobrescribir una versión gestionada es una excepción que exige motivo escrito, porque rompe la garantía de compatibilidad que da el BOM, que es precisamente para lo que existe. El caso legítimo es el inverso al del hallazgo: si el BOM gestiona una versión incompatible con una pieza que sí hemos elegido nosotros, se sobrescribe y se anota por qué.

**Se fija una versión concreta, nunca una etiqueta móvil.** Ni `latest`, ni `postgres:18` a secas si se puede ser más preciso. Una etiqueta móvil convierte cada `docker compose pull` en un cambio de versión silencioso, y además rompe el objetivo de reproducibilidad de la sección 2.2. Este es el mismo problema de **deriva** del módulo M2 aplicado al entorno de desarrollo: lo que crees que estás ejecutando y lo que ejecutas divergen sin que nada avise.

### Sobre Keycloak y la Fase 6

Estado de soporte verificado el 2026-09-25 en la documentación de Keycloak, para las tres funcionalidades de las que depende la Fase 6:

| Funcionalidad | Estado | Lectura |
|---|---|---|
| Token exchange estándar V2 (RFC 8693) | **Soportado**, activado por defecto | La base existe y es estable |
| Token exchange interno legacy | **Preview y obsoleto**, se eliminará | No construir nada sobre él |
| Semántica de delegación (claim de actor) | **Experimental**, vía "Token Exchange Delegation" con claim `may_act` | Es la pieza que sostiene el principio 3 de la sección 6.2, y exige preautorizar al actor |
| Suplantación del sujeto en V2 | **No implementado todavía** | Afecta al diseño de la delegación |
| CIBA | Documentado como implementado, sin marca de preview | Disponible para la aprobación humana |
| DPoP | **Preview** | La sección 6.2 ya lo trataba como condicional, y con razón |

Conclusión: la versión elegida **no bloquea** la Fase 6, pero el aviso de la sección 6.2 tenía fundamento. Lo soportado y lo experimental no coinciden con lo que el documento daba por hecho. La verificación a fondo sigue siendo una tarea de la Fase 6, y el diseño de la delegación tendrá que partir del mecanismo de preautorización que Keycloak ofrece de verdad, no del que se supuso.

Nota aparte: Keycloak 26.7.0 introdujo una **API SCIM en preview**. No sustituye al trabajo de M3. El servidor SCIM y el conector SCIM de Lineward existen como objetivo de aprendizaje del estándar (secciones 2.2 y 5, M3), no como carencia de Keycloak.

## Consecuencias

- **Habrá al menos un salto de línea de Spring Boot durante el proyecto.** El soporte OSS de 4.1 llega a julio de 2027 y la cadencia de Spring publica una línea nueva cada seis meses aproximadamente. La regla es permanecer en la línea elegida hasta que su ventana se acerque al final, no perseguir cada publicación. Subir de línea a mitad de fase gasta tiempo en algo que no enseña nada nuevo.
- **PostgreSQL 19 llega en octubre de 2026 y no se adopta en su GA.** PostgreSQL 18 tiene soporte hasta noviembre de 2030, así que no hay urgencia ninguna. El criterio para subir no es la fecha de publicación, es que Flyway y Testcontainers declaren la versión soportada, por el motivo del hallazgo descrito arriba. Cuando se suba, se sube primero en un trabajo de CI y después en desarrollo.
- **Java 27 existe y no se usa.** Es non LTS. La siguiente LTS, Java 29, llega en septiembre de 2027 y podría caer dentro de la vida del proyecto: subir será opcional, no obligatorio, porque Java 25 tiene actualizaciones hasta septiembre de 2028.
- **Keycloak no tiene concepto de LTS y publica con cadencia trimestral.** Hay que fijar la versión menor para tener reproducibilidad, y a la vez seguir las publicaciones de seguridad dentro de la línea 26.x, que son barata de aplicar. Es la pieza del stack que más atención continua va a pedir.
- **Las etiquetas de imagen viven en dos sitios y pueden divergir:** `docker-compose.yml` y los contenedores de Testcontainers. Si el test usa PostgreSQL 18 y la demo 19, los tests dejan de probar lo que se despliega, que es el peor fallo posible en una suite con infraestructura real. Al crear el esqueleto hay que darles una única fuente de verdad, por ejemplo una propiedad de Maven leída por los tests.
- **Este ADR caduca.** Todo lo que aquí dice "la más reciente" es cierto a fecha 2026-09-25 y falso en algún momento futuro sin que nada avise. Se revisa cuando se cumpla cualquiera de estas condiciones: se acerque el fin de soporte OSS de Spring Boot 4.1 (julio 2027), se quiera adoptar PostgreSQL 19, Maven 4 llegue a GA, o la Fase 6 descubra que el estado de soporte de Keycloak ha cambiado.
- **Queda pendiente, y no lo resuelve este ADR:** si se ponen cabeceras de licencia en cada archivo fuente (venía de ADR-008) y la decisión sobre el broker de mensajes (ADR-003), que afecta al `docker-compose.yml` pero no al esqueleto.

## Fuentes

Todas consultadas el 2026-09-25.

**Java**
- Oracle Java SE Support Roadmap: <https://www.oracle.com/java/technologies/java-se-support-roadmap.html>
- JDK 25: <https://openjdk.org/projects/jdk/25/>
- JDK 27: <https://openjdk.org/projects/jdk/27/>
- Releases disponibles en Eclipse Temurin: <https://api.adoptium.net/v3/info/available_releases>

**Spring Boot y Spring Modulith**
- Ciclos de release y ventanas de soporte: <https://endoflife.date/spring-boot>
- Requisitos de sistema: <https://docs.spring.io/spring-boot/system-requirements.html>
- Versiones gestionadas por el BOM: <https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html>
- `spring-boot-dependencies` en el tag v4.1.1, fuente autoritativa de las versiones heredadas: <https://github.com/spring-projects/spring-boot/blob/v4.1.1/platform/spring-boot-dependencies/build.gradle>
- Incidencia 49012, el fallo con PostgreSQL 18: <https://github.com/spring-projects/spring-boot/issues/49012>
- Spring Modulith 2.0 GA: <https://spring.io/blog/2025/11/21/spring-modulith-2-0-ga-1-4-5-and-1-3-11-released/>
- Spring Modulith 2.2 M1, 2.1.1 y 2.0.8: <https://spring.io/blog/2026/08/26/spring-modulith-2-2-m1-2-1-1-2-0-8-and-1-4-13-released/>

**PostgreSQL y Flyway**
- Ciclos de release y ventanas de soporte: <https://endoflife.date/postgresql>
- PostgreSQL 19 Beta 4: <https://www.postgresql.org/about/news/postgresql-19-beta-4-released-3386/>
- Bases de datos y versiones soportadas por Flyway: <https://documentation.red-gate.com/fd/supported-databases-and-versions-143754067.html>

**Keycloak**
- Keycloak 26.7.0: <https://www.keycloak.org/2026/07/keycloak-2670-released>
- Keycloak 26.7.3: <https://www.keycloak.org/2026/08/keycloak-2673-released>
- Token exchange y su estado de soporte: <https://www.keycloak.org/securing-apps/token-exchange>
- Capas OIDC, CIBA y DPoP: <https://www.keycloak.org/securing-apps/oidc-layers>

**Maven**
- Historial de releases: <https://maven.apache.org/docs/history.html>
- Página de descarga, con la versión recomendada: <https://maven.apache.org/download.cgi>
