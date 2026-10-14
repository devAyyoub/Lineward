# ADR-013 · Modelo de dominio de las Fases 1 y 2

- **Estado:** Aceptado
- **Fecha:** 2026-10-14

## Contexto

La primera migración de Flyway es la última tarea de la Fase 0 que bloquea trabajo real, y no se puede escribir sin decidir antes la forma de las entidades. ADR-011 ya fijó que toda tabla de negocio lleva un discriminador de organización y que el aislamiento se aplica con Row Level Security, pero no dijo qué tablas hay.

El alcance de este ADR son los módulos M1 (identidades y fuente autoritativa), M2 (agregación, correlación y reconciliación) y M3 (conectores y aprovisionamiento), que son los de las Fases 1 y 2. Los módulos de la Fase 3 en adelante no se modelan aquí, pero el modelo se diseña sabiendo que llegarán.

Dos costuras estaban señaladas como bloqueantes y se resuelven aquí: la **fuente autoritativa por tipo de identidad** y la relación de responsabilidad, es decir si **manager y sponsor son la misma pieza**. Las dos se resuelven juntas porque son la misma pregunta vista desde dos lados, y eso no se vio hasta ponerlas una al lado de la otra.

Un matiz de método que condiciona el alcance: Flyway es versionado, así que no hace falta crear todas las tablas en la primera migración. Lo que hace falta es que el modelo esté decidido. Las tablas de la Fase 1 van en la primera migración y las de la Fase 2 en la suya.

## Decisiones

### 1. Las entidades y a qué módulo pertenece cada tabla

La propiedad de cada tabla importa porque la sección 7.3 del documento de trabajo prohíbe que un módulo lea las tablas de otro, y porque decide dónde puede haber una clave ajena (decisión 9).

| Módulo | Tablas | Fase |
|---|---|---|
| `shared` | `organization` | 1 |
| `identity` | `source_system`, `source_record`, `identity`, `identity_responsibility` | 1 |
| `access` | `application`, `entitlement`, `access_assignment` | 1 |
| `aggregation` | `account`, `account_entitlement`, `aggregation_run`, `reconciliation_finding` | 1 |
| `audit` | `audit_event`, `audit_chain` | 1 |
| `provisioning` | `provisioning_task`, `outbox_message` | 2 |

Quedan fuera, por pertenecer a servicios aparte con su propio almacén: el simulador de RRHH, la aplicación SCIM de demostración y la aplicación inestable. Si sus tablas vivieran en el esquema de Lineward, la fuente autoritativa y la plataforma de gobierno serían la misma base de datos, y entonces la agregación consistiría en leerse a sí misma, que es precisamente lo que el proyecto quiere demostrar que no hace.

### 2. `organization` existe como tabla

ADR-011 obliga a la columna `org_id`, no a una tabla. Se crea la tabla de todos modos, con lo mínimo: identificador, nombre y fecha de alta. El motivo no es la integridad referencial, que ADR-011 ya avisa de que no protege nada entre organizaciones porque las claves ajenas se saltan RLS, sino que un `org_id` sin destino es un UUID que no se puede resolver a nada cuando se miran datos a mano.

La tabla lleva su propia política de RLS, con el discriminador sobre su clave primaria:

```sql
CREATE POLICY organization_tenant_isolation ON organization
    USING (id = current_setting('app.current_org')::uuid);
```

Así la aplicación nunca puede enumerar las organizaciones que no son la suya. No habría sido una fuga de accesos, pero sí de la lista de clientes.

### 3. Toda identidad tiene exactamente una fuente autoritativa, y se modela como dato

La ficha de M1 describe la fuente autoritativa como una propiedad por tipo de identidad: RRHH para una persona, un registro de cuentas de servicio para una cuenta de servicio, el registro de agentes para un agente. La tentación es escribir eso como un `switch` por tipo en el código. Se rechaza, porque entonces cada tipo nuevo es una rama nueva en la lógica de ciclo de vida, que es exactamente lo que el principio 8 de la sección 3.3 prohíbe.

Se modela con dos tablas:

- **`source_system`**: el catálogo de fuentes. Cada fila tiene un código (`hr`, `agent-registry`, `service-registry`), un nombre y una **naturaleza**: `EXTERNAL` si la verdad vive en un sistema de fuera, `INTERNAL` si la verdad la escribe Lineward.
- **`source_record`**: lo que esa fuente afirma sobre una identidad. Una fila por identidad y por fuente, actualizada en su sitio, con el identificador externo, la carga útil cruda en JSONB, un hash de la carga para detectar cambios sin compararla campo a campo, y el instante de la afirmación.

`source_record` es **obligatorio para toda identidad, incluidas las internas.** Para un empleado, la afirmación la escribe el conector de RRHH. Para un agente de IA de la Fase 6, la escribe la consola al registrarlo. La diferencia entre los dos casos es quién escribe la fila, no que una exista y la otra no. Eso deja un solo camino de código para "qué se sabe de esta identidad y quién lo dice", que es lo que hace que el gobierno sea uno y no tres.

En la Fase 6, dar de alta el gobierno de agentes es **insertar una fila en `source_system`**, no crear tablas. La tabla de conceptos candidatos de la sección 7.4 dice que cada entidad nueva específica de agentes es una señal de alarma; esta decisión es la que hace que no haya ninguna.

### 4. Guardar lo que dijo la fuente, y no solo lo que Lineward decidió

Es la consecuencia de la decisión 3 y merece su propio apartado porque la alternativa parecía más barata. La alternativa era detectar los eventos JML comparando la carga de RRHH con la propia tabla `identity`, sin guardar nada más.

Se rechaza por un fallo concreto. `identity` es la vista gobernada, y la escribe Lineward: una corrección manual, una campaña de certificación o un módulo futuro pueden cambiarla. Si la detección de JML compara contra ella, el siguiente volcado de RRHH ve una diferencia que RRHH no ha causado y **dispara un evento Mover falso**, con su recálculo de accesos y su aprovisionamiento. El fallo no avisa: produce trabajo plausible.

Guardar la afirmación aparte resuelve además dos casos límite que la ficha de M1 ya exigía:

- **Baja retroactiva.** RRHH dice el día 10 que alguien se fue el día 3. La afirmación lleva las fechas que la fuente declara, así que se puede distinguir "cuándo pasó" de "cuándo nos lo dijeron", que es la primera pregunta de un auditor.
- **Reingesta idempotente.** Volver a procesar el mismo volcado compara dos hashes y no hace nada, en lugar de recorrer campos.

El coste es una tabla con una fila por identidad y por fuente. No crece con cada ejecución: la afirmación se actualiza en su sitio y el historial vive en el registro de auditoría, que es append-only por diseño.

### 5. Una sola relación de responsabilidad: manager y sponsor son la misma pieza

Se decide **una tabla `identity_responsibility`** con un rol, en lugar de una columna `manager_id` en `identity` y un `sponsor_id` en el perfil del agente.

```text
identity_responsibility(
    org_id, id,
    subject_identity_id,        -- la identidad gobernada
    responsible_identity_id,    -- quién responde por ella
    role,                       -- MANAGER | OWNER | SPONSOR
    origin,                     -- ASSERTED (lo dice la fuente) | ASSIGNED (lo decidió alguien en Lineward)
    valid_from, valid_to
)
```

Lo que compra, y es lo que decide la elección:

- **La lógica de baja se escribe una vez.** Es el criterio que la sección 7.4 pedía explícitamente.
- **Resuelve el caso límite de "cambio de manager sin cambio de puesto"** sin tocar `identity`, y deja historial, que es lo que permite contestar "quién era su manager cuando se aprobó este acceso". Con una columna, ese dato se pierde en cada cambio.
- **La Fase 6 no añade tablas.** Un sponsor es una fila con `role = SPONSOR`.
- **`origin` separa lo que afirma la fuente de lo que se decidió aquí.** Un manager viene de RRHH y se sobreescribe en cada volcado; un sponsor se asigna en Lineward y un volcado de RRHH no debe pisarlo. Con una sola columna `manager_id` no hay forma de distinguirlo, y el conector acabaría borrando asignaciones humanas.

El coste es una unión para saber quién responde por una identidad, y una restricción de unicidad para los casos en que la lógica necesita exactamente un responsable: `UNIQUE (org_id, subject_identity_id, role)` entre las filas vigentes.

### 6. La regla que une las dos costuras

Las decisiones 3 y 5 parecían dos tareas y son una. La diferencia entre un empleado y un agente no está en la relación de responsabilidad ni en el tipo de identidad, sino en **de dónde viene la autoridad para que esa identidad exista**:

> Una identidad cuya autoridad de existencia es un sistema externo sobrevive a la vacante de su responsable. Una identidad cuya autoridad de existencia es una persona, no.

Escrito contra el modelo: si `source_system.kind = EXTERNAL`, quedarse sin responsable es un hallazgo que hay que resolver, y la identidad sigue activa porque la fuente externa sigue afirmando que existe. Si `kind = INTERNAL`, quedarse sin responsable **suspende la identidad**, porque no queda nadie que afirme que debe existir.

Esto tiene tres consecuencias que valen la pena:

1. La suspensión automática del agente al darse de baja su sponsor, que el principio 2 de la sección 6.2 exige, **no se implementa como un caso especial de los agentes.** Sale de la regla general. Una ruta de código que solo se ejecuta para agentes es una ruta que nadie prueba hasta que falla.
2. La cuenta de servicio sin propietario, que la ficha de M2 pide detectar, es el mismo hallazgo que el agente huérfano. Un informe, no dos.
3. La regla se expresa contra `source_system.kind`, no contra `identity.type`. Añadir un tipo de identidad en el futuro no obliga a tocarla.

### 7. Los tres tipos de identidad: tabla común y extensión por tipo

`identity` lleva una columna `type` con `HUMAN`, `SERVICE` y `AGENT`, y las particularidades de cada tipo van en una tabla de extensión uno a uno cuando llegue su fase (`agent_profile` en la Fase 6). Es lo que ya insinuaba la sección 7.4 y se confirma.

Se rechazan las dos alternativas: una sola tabla con todas las columnas de todos los tipos acaba con la mitad nulas y sin forma de obligar a que el sponsor de un agente exista; una tabla por tipo obliga a escribir tres veces cada consulta de certificación, de SoD y de auditoría, que es el fallo que el principio 8 describe.

En la Fase 1 solo existe `HUMAN` y no hace falta ninguna extensión.

### 8. Estado esperado y estado real son tablas distintas

Es el corazón de M2: la deriva solo se puede detectar si el modelo guarda las dos cosas por separado.

| | Tabla | Nivel | Quién la escribe |
|---|---|---|---|
| **Real** | `account`, `account_entitlement` | Cuenta | El conector, al agregar |
| **Esperado** | `access_assignment` | Identidad | Lineward, al calcular |

`access_assignment` relaciona una identidad con un entitlement y lleva **por qué lo tiene**: `source` (`BIRTHRIGHT`, `REQUEST`, `ROLE`, `MANUAL`, `LEGITIMATED`) y `source_detail` con el código de la regla, la solicitud o el rol concreto. Esa columna es la implementación del principio 2 de la sección 3.3, el linaje del acceso: la pantalla "por qué tiene este acceso" es una consulta a esta tabla, no un cálculo.

En la Fase 1 solo se usan `BIRTHRIGHT` y `LEGITIMATED`. En la Fase 3, los roles de negocio y las solicitudes se convierten en orígenes nuevos de filas de esta misma tabla, no en una tabla paralela.

La deriva es la diferencia entre los dos conjuntos, calculada por identidad y aplicación una vez que la correlación ha unido las cuentas con las identidades. Hay dos direcciones y las dos son hallazgos distintos: un acceso real que no está esperado (alguien lo concedió por fuera) y un acceso esperado que no está en el sistema destino (el aprovisionamiento falló o alguien lo quitó a mano).

### 9. Los hallazgos se persisten, con su estado y su reconciliación

Se crea `reconciliation_finding` con un `type` (`ORPHAN_ACCOUNT`, `DORMANT_ACCOUNT`, `UNOWNED_IDENTITY`, `DRIFT_UNEXPECTED_GRANT`, `DRIFT_MISSING_GRANT`), la referencia al objeto, un estado, y las marcas `first_detected_at` y `last_seen_at`.

Calcularlos al consultar habría sido más simple y nunca se queda desactualizado, pero M2 pide **actuar** sobre la deriva (revocar, legitimar creando la solicitud que faltó, o marcar como excepción) y eso es estado que hay que guardar. Además, "desde cuándo existe este hallazgo" es una pregunta de auditoría y no se puede contestar sin persistirlo.

La contrapartida es que un hallazgo persistido puede quedarse obsoleto, así que cada ejecución de agregación **reconcilia**: lo que sigue cumpliéndose actualiza `last_seen_at`, y lo que ya no se cumple se cierra como `RESOLVED_EXTERNALLY`. Sin ese paso, la lista de hallazgos se convierte en un cementerio y nadie la mira, que es el mismo fallo que la aprobación en bloque sin mirar de M7.

### 10. Las reglas viven en el repositorio, no en tablas

Las reglas de correlación y las de birthright se declaran en archivos de configuración versionados y se cargan como tipos de Java, no como filas.

La ficha de M2 las pide "configurables y ordenadas por prioridad", y eso admite las dos formas. Se eligen los archivos por tres razones:

- **Frecuencia real.** Una regla de correlación se ajusta en el alta de un sistema y luego casi nunca. No justifica una interfaz de administración con su CRUD y su auditoría de ediciones.
- **Son zona reservada y hay que testearlas.** En el repositorio se testean en CI por construcción. En una tabla, el caso que importa es el de los datos de producción, que ningún test ve.
- **Control de cambios.** Un cambio de regla en git tiene revisor, diferencia y vuelta atrás. En una tabla tiene una fila de auditoría y ningún revisor. Y una regla editable en caliente invita a tocarla para que una demostración salga bien, que es como se envenena un motor de correlación.

Lo que **sí** se persiste es el resultado de aplicarlas, porque sin eso no hay linaje: `account` guarda qué regla la correlacionó, cuándo y con qué método (`RULE` o `MANUAL`), y `access_assignment` guarda qué regla de birthright originó cada acceso.

Queda revisable en la Fase 3 si la consola necesita editarlas, y entonces será un ADR nuevo.

### 11. Claves, identificadores y unicidad

- **Clave primaria:** UUID subrogado en todas las tablas, generado con **`uuidv7()`**. Verificado el 2026-10-14 contra el contenedor real (PostgreSQL 18.6): `uuidv7()`, `uuidv4()` y `uuid_extract_timestamp()` están en el núcleo, sin extensiones. Se elige v7 sobre `gen_random_uuid()`, que es v4, por dos motivos: un UUID v7 empieza por una marca de tiempo, así que las inserciones caen al final del índice en lugar de repartirse al azar por todo el árbol, y además la clave lleva dentro su fecha de creación, recuperable con `uuid_extract_timestamp()`. Lo segundo no sustituye a una columna `created_at`, que sigue existiendo porque un identificador no es un dato de negocio, pero sirve para diagnosticar.
- **Toda restricción de unicidad empieza por `org_id`**, por la regla de ADR-011: una unicidad global filtra la existencia de filas de otras organizaciones a través del error de restricción duplicada, aunque RLS esconda la fila.
- **Las claves naturales viven en `source_record`**, con `UNIQUE (org_id, source_system_id, external_id)`. `identity` no lleva identificador externo: se llega a ella por la afirmación. Así hay un solo sitio donde vive el identificador de empleado y no dos que puedan discrepar.
- Las demás: `UNIQUE (org_id, code)` en `application` y `source_system`, `UNIQUE (org_id, application_id, native_id)` en `account`, `UNIQUE (org_id, application_id, native_value)` en `entitlement`, `UNIQUE (org_id, account_id, entitlement_id)` en `account_entitlement`.
- **El correo no lleva restricción de unicidad**, y es deliberado. Es un atributo que afirma la fuente y un criterio de correlación, y la ficha de M1 pide tratar datos de RRHH incompletos o contradictorios. Una restricción aquí impediría ingerir datos malos, cuando lo que se quiere es ingerirlos y señalarlos. Un caso límite de M2 es justo ese: dos identidades que casan con la misma cuenta.

### 12. Atributos: columnas tipadas para lo que gobierna, JSONB para el resto

`identity` lleva columnas tipadas solo para los atributos sobre los que se decide algo: departamento, puesto, ubicación, centro de coste, tipo de empleado y las fechas de alta y baja. Son las que leen las reglas de correlación y de birthright, así que necesitan índice y necesitan que una errata en el nombre de un atributo sea un error de compilación y no un nulo silencioso.

Todo lo demás que mande la fuente se queda en `source_record.payload`, que es JSONB. La consecuencia agradable de la decisión 4 es que `identity` **no necesita una bolsa de atributos** propia: la verdad cruda ya está guardada enfrente.

Esto reinterpreta ligeramente la sección 7.2 del documento de trabajo, que justifica PostgreSQL por "JSONB para atributos flexibles". El JSONB se usa, pero en la afirmación de la fuente y no en la identidad gobernada. Queda dicho para que la contradicción no pase inadvertida.

### 13. Clave ajena solo dentro del módulo

La sección 7.3 prohíbe claves ajenas que cruzan un límite de módulo: la referencia se guarda como identificador suelto y la coherencia la garantiza el dominio. Aplicado a este esquema, la consecuencia es más amplia de lo que parece y conviene verla escrita:

| Referencia | Cruza | Clave ajena |
|---|---|---|
| `source_record.identity_id` → `identity` | No, las dos en `identity` | Sí |
| `identity_responsibility.*_identity_id` → `identity` | No | Sí |
| `account_entitlement.account_id` → `account` | No, las dos en `aggregation` | Sí |
| cualquier `.org_id` → `organization` | No, `organization` está en `shared` y todos pueden depender de `shared` | Sí |
| `account.identity_id` → `identity` | Sí, `aggregation` → `identity` | **No** |
| `account.application_id` → `application` | Sí, `aggregation` → `access` | **No** |
| `account_entitlement.entitlement_id` → `entitlement` | Sí | **No** |
| `access_assignment.identity_id` → `identity` | Sí, `access` → `identity` | **No** |
| `provisioning_task.account_id` → `account` | Sí | **No** |
| `audit_event.*` → cualquiera | Sí, y además nadie puede depender de `audit` | **No** |

Es decir: **la mayoría de las referencias interesantes de este esquema no pueden ser claves ajenas.** Eso es coherente con ADR-001 y con ADR-011, que ya trasladó la coherencia entre organizaciones al dominio, pero tiene un coste real que no se va a disimular: se pueden quedar filas apuntando a nada, y el valor de la plataforma es precisamente saber quién tiene acceso a qué.

La mitigación es explícita y entra con la Fase 1, no después: una convención de nombres (las columnas `_id` sin clave ajena son referencias blandas) y **un test de integridad referencial que las verifique todas**, ejecutado en CI contra la base de datos de prueba. Sin ese test, la regla de ADR-001 es una renuncia a la integridad en lugar de un desacoplamiento.

### 14. Auditoría: una cadena por organización

La sección 16 del documento de trabajo dejaba abierto si la cadena de hash del registro de auditoría es una global o una por organización, y lo ligaba a esta tarea. Se decide **una por organización.**

El motivo es el que ADR-011 ya anticipaba: la evidencia se exporta por cliente, y demostrar una cadena global obligaría a entregar hashes de eventos de otras organizaciones para que la verificación cuadrara. Eso convierte el entregable que cierra una auditoría en una fuga.

Implicaciones para el esquema de la Fase 1, aunque el hash no se calcule hasta la Fase 4:

- `audit_event` lleva `sequence` (monótona por organización), `prev_hash` y `hash`, las dos últimas nulas por ahora. Preverlas cuesta dos columnas; añadirlas después es una migración sobre datos ya escritos.
- `audit_chain` guarda una fila por organización con el último número de secuencia y el último hash. El escritor la bloquea con `SELECT ... FOR UPDATE` en la misma transacción, lo que **serializa a los escritores de auditoría dentro de una organización**. Es un coste conocido y se escribe aquí: es el precio de una cadena verificable, porque una cadena con huecos o con dos eventos en el mismo eslabón no es verificable. Se mide en la Fase 8.
- El registro es append-only por permisos, no por buena voluntad: la migración revoca `UPDATE` y `DELETE` sobre `audit_event` al rol de aplicación. El script de inicialización concede los cuatro permisos por omisión a las tablas futuras, así que esta revocación es necesaria y es fácil de olvidar.

## Consecuencias

**Lo que se gana**

- Las dos costuras bloqueantes quedan cerradas, y resueltas con una sola regla (decisión 6) en lugar de con dos casos especiales.
- La Fase 6 no necesita tablas nuevas para el gobierno de agentes: necesita una fila en `source_system` y una extensión uno a uno. Es el test que la sección 7.4 proponía para saber si el modelo de gobierno es uno o son dos.
- El linaje del acceso y el linaje de la correlación quedan en el esquema desde el principio, no añadidos después.
- La decisión de la cadena de hash deja de estar abierta y la primera migración ya tiene la forma correcta.

**Lo que se pierde, o lo que hay que vigilar**

- **Una unión más** para casi todo: el identificador de empleado está en `source_record` y el responsable en `identity_responsibility`. A la escala de la demostración (unas 5.000 identidades) no es un problema, y es algo que medir y no suponer.
- **Integridad referencial parcial.** El coste de ADR-001 aplicado al esquema. Depende del test de integridad de la decisión 13, que pasa a ser una tarea obligatoria de la Fase 1.
- **Escritores de auditoría serializados por organización.** Decisión 14.
- **La reconciliación de hallazgos es trabajo en cada ejecución.** Decisión 9.
- **Las reglas en archivos obligan a desplegar para cambiarlas.** Decisión 10, revisable en la Fase 3.

## Lo que queda abierto

- La forma concreta de las tablas de la Fase 2 (`provisioning_task` y `outbox_message`) se cierra en su migración. Lo decidido aquí es a qué módulo pertenecen y que `outbox_message` lleva discriminador de organización, con la consecuencia de que el publicador tiene que iterar organizaciones explícitamente, que es el punto delicado que ADR-011 señala para los procesos sin usuario conectado.
- La extensión `agent_profile` y la tabla de autoridad delegada son de la Fase 6 y no se diseñan aquí.

## Verificación sobre el contenedor real

Ejecutado el 2026-10-14 contra el PostgreSQL 18.6 de `infra/docker-compose.yml`, conectando con el rol propietario:

| Prueba | Resultado |
|---|---|
| `SELECT version()` | `PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2)`, coherente con ADR-006 |
| `SELECT uuidv7()` | Devuelve un UUID de versión 7. Disponible en el núcleo, sin extensiones |
| `SELECT gen_random_uuid()` | Disponible, se descarta a favor de v7 |
| Catálogo `pg_proc` | Confirma `uuidv4`, `uuidv7` y `uuid_extract_timestamp` como funciones del núcleo |
