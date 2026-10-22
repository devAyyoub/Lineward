# ADR-003 · Transporte de eventos y broker de mensajes

- **Estado:** Propuesto
- **Fecha:** 2026-10-22
- **Decisión diferida a:** Fase 2, donde ya tiene su casilla en el roadmap. Se acepta o se rechaza cuando el aprovisionamiento real exista, no antes
- **Se reabre:** por cualquiera de los seis disparadores del apartado final, y no por intuición

## Contexto

La pregunta estaba planteada como "Kafka, RabbitMQ u outbox con sondeo", y ese enunciado esconde lo que resultó ser la mitad de la decisión: **no hay un transporte, hay tres**, con requisitos distintos y plazos distintos. Separarlos es lo que convierte una elección de infraestructura en tres preguntas, dos de ellas ya contestadas.

**1. Entre módulos del mismo proceso.** El módulo de identidades detecta un alta y el de accesos calcula lo que le corresponde por serlo. Resuelto el 2026-10-22 y ya construido: el evento se publica **dentro** de la transacción que guarda el cambio y el oyente es síncrono, así que o se guardan las dos cosas o ninguna. No hay mensaje en vuelo y no hay nada que perder. La condición que lo sostiene está escrita en el propio evento: **ningún oyente puede hacer nada que una vuelta atrás no deshaga.** La consecuencia práctica es que este proyecto no usa `@ApplicationModuleListener` de Spring Modulith, que es asíncrona y posterior a la confirmación, y es justamente esa renuncia la que hace innecesario su registro de publicación de eventos, es decir la tabla con la que el framework garantiza la entrega.

**2. Hacia los sistemas destino.** Crear la cuenta en Okta o en el directorio. Esto no puede ir dentro de una transacción, porque el destino es remoto, lento y a veces está caído. **Ya está decidido:** ADR-013 coloca `provisioning_task` y `outbox_message` en el módulo de aprovisionamiento, con discriminador de organización, y la Fase 2 tiene las casillas del patrón outbox, la idempotencia, los reintentos y la cola de mensajes fallidos. Así que el outbox no es lo que está sin decidir.

**3. Un broker de mensajes por encima**, es decir un proceso aparte que guarda la cola, como Kafka o RabbitMQ. **Esto es lo único que este ADR deja abierto.**

**Un factor nuevo desde el 2026-10-21**, que es el día en que apareció la plataforma de despliegue: Lineward corre en tres entornos, y Kafka en un solo nodo pide del orden de 1,5 a 2 GB por entorno frente a unos cientos de MB de RabbitMQ, en un host de 32 GB. En un homelab eso es un argumento técnico legítimo y no un capricho. **Sigue sin medir**, y medirlo es parte del disparador 6.

**Y lo que un broker no resolvería**, que conviene decirlo antes de las opciones: no haría seguro el aprovisionamiento hacia fuera. Que el destino esté caído exige reintentos, idempotencia y cola de fallidos de todas formas, y eso vive en la tabla de tareas. Un broker mueve mensajes; no hace que Okta responda.

## Opciones consideradas

| Opción | A favor | En contra |
|---|---|---|
| **Kafka** | Retención y reproducción del histórico, que encaja con la evidencia de auditoría; estándar en integración de eventos | 1,5 a 2 GB por entorno, por tres entornos; opera mal en un nodo; resuelve un problema de escala que este proyecto no tiene |
| **RabbitMQ** | Mucho más ligero; enrutado flexible; suficiente para colas de trabajo | Componente operativo más que mantener, actualizar y vigilar, sin ningún consumidor que hoy lo necesite; no da reproducción del histórico |
| **Solo outbox y sondeo** | Cero infraestructura nueva; el estado vive en PostgreSQL, que ya hay que respaldar y vigilar; una tabla se inspecciona con SQL, que es la mejor herramienta de diagnóstico disponible | Latencia mínima marcada por el intervalo del sondeo; carga periódica sobre la base de datos; un segundo consumidor independiente obligaría a replantearlo |

## Decisión

**Provisionalmente, la tercera: solo outbox y sondeo, sin broker.** Y el estado es Propuesto y no Aceptado a propósito, porque esto no se acepta por escrito sino construyéndolo: se cierra cuando la casilla de la Fase 2 esté hecha y el criterio de esa fase se cumpla, que es que tumbar un sistema destino a mitad de una operación no pierda ni duplique nada.

**El argumento que hace barato diferirlo es que el outbox no es la alternativa a un broker, es su requisito previo.** Escribir el evento en la misma transacción que el cambio de datos hace falta igual, con broker o sin él: es el patrón que existe precisamente para que una base de datos y una mensajería no se contradigan. Así que construir el outbox primero no es apostar por una de las tres opciones, es construir la parte que las tres comparten. Si alguno de los disparadores de abajo se cumple, se añade el broker **detrás** del outbox y nada de lo construido se tira.

De las tres entregas del contexto, la 1 no necesita infraestructura y la 2 necesita exactamente lo que ADR-013 ya colocó. Añadir hoy un broker sería operar un componente en tres entornos para cero consumidores.

## Disparadores de reapertura

Están aquí porque una decisión diferida sin condiciones de reapertura no es una decisión diferida, es una decisión olvidada. **Ninguno de los seis se cumple hoy.**

| Nº | Disparador | Cómo se detecta, para que no dependa de la intuición |
|---|---|---|
| 1 | **Un módulo pasa a ser un proceso aparte** | Por la decisión explícita de extraerlo (sección 17.3). En cuanto el emisor y el receptor son procesos distintos, el argumento central de este ADR deja de valer |
| 2 | **Un consumidor fuera de Lineward quiere los eventos**, por ejemplo el sistema de vigilancia de seguridad de un cliente | Por una petición concreta de alguien, no por suponer que la querrán. Si llega, el candidato natural es RabbitMQ y no Kafka, salvo que pidan histórico |
| 3 | **Más de un consumidor independiente del mismo evento**, con velocidades y modos de fallo distintos | Contando consumidores. Hoy hay cero, y con uno solo el sondeo es trivialmente correcto |
| 4 | **El sondeo es el cuello de botella** | Midiendo dos cosas en la Fase 2, junto al benchmark de virtual threads que ya está en la lista: la latencia entre detectar un evento y aprovisionarlo, y la carga del sondeo sobre PostgreSQL. **El umbral hay que fijarlo con el primer número, no antes** |
| 5 | **Hace falta reentregar histórico**, por ejemplo reproducir seis meses de eventos para una auditoría | Por un requisito de evidencia. Una tabla lo puede hacer mientras se conserve; la retención de un broker lo resuelve de otra forma y conviene comparar entonces |
| 6 | **Cambia el destino de despliegue** y el presupuesto de memoria del homelab deja de ser un argumento | Cuando cambie. Y entonces hay que **medir de verdad** el consumo de Kafka frente a RabbitMQ, porque el 1,5 a 2 GB de hoy es una estimación sin comprobar y no se puede usar para cerrar nada |

**Lo que no reabre esto**, y queda dicho para no volver a discutirlo: que se pierda un evento interno, porque con el oyente dentro de la transacción no hay evento en vuelo; y que falle el aprovisionamiento hacia un sistema destino, porque eso es trabajo de los reintentos y de la idempotencia, no del transporte.

## Consecuencias

- **No se opera ningún componente de mensajería** en los tres entornos de la plataforma, y el estado de las entregas pendientes vive en PostgreSQL, que ya hay que respaldar y vigilar. Una cola que se inspecciona con SQL es además la mejor situación posible para diagnosticar un aprovisionamiento que no llegó.
- **Hay un suelo de latencia** marcado por el intervalo del sondeo. Para altas, cambios y bajas de personal eso es irrelevante, porque la fuente es un extracto que se lee una vez al día. Dejaría de serlo si alguna vez hay que revocar accesos en segundos, que es el escenario del interruptor de emergencia de la Fase 6 y hay que mirarlo cuando se diseñe.
- **Queda una obligación concreta al cerrar la Fase 2:** escribir aquí la latencia medida, para que el disparador 4 tenga un número y no una sensación.
- **El coste de equivocarse es bajo y asimétrico a favor**, que es lo que justifica diferir: añadir un broker detrás de un outbox existente es trabajo nuevo, no trabajo rehecho. Al revés, montar hoy un broker que nadie consume sería trabajo que habría que desmontar.
