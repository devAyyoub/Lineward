# ADR-011 · Multiinquilino: discriminador de organización y aislamiento con Row Level Security

- **Estado:** Aceptado
- **Fecha:** 2026-10-04

## Contexto

La sección 17.3 describe una evolución posible hacia un SaaS multiinquilino, es decir, una sola instalación que sirve a varias organizaciones con sus datos aislados. La sección 2.3 deja claro que el multiinquilino **no es un objetivo funcional**: no habrá gestión de organizaciones ni facturación. Lo que sí se decide ahora es que el esquema de base de datos no impida esa evolución.

El momento obliga. La primera migración de Flyway es una tarea de la Fase 0, y añadir un discriminador de organización a posteriori es una de las refactorizaciones más caras que existen: toca todas las tablas, todos los índices, todas las consultas, todos los tests y cada endpoint. Decidirlo después del primer esquema significa pagarlo entero.

El 2026-10-04 se decide que **todas las tablas de negocio llevan una columna `org_id` desde la primera migración**. Esta decisión asume de forma consciente el coste que la sección 17.3 llama "complejidad SaaS prematura", a cambio de eliminar el retrofit.

Pero la columna por sí sola no aísla nada. Solo hace posible aislar. El aislamiento depende de que **cada consulta** filtre por ella, y ahí está el problema real que este ADR resuelve:

```sql
-- Lo que escribe alguien que olvida el filtro
SELECT id, email FROM identity WHERE department = 'Treasury';
-- Devuelve identidades de todas las organizaciones

-- Lo correcto
SELECT id, email FROM identity WHERE department = 'Treasury' AND org_id = :org;
```

Tres razones por las que esto ocurrirá si no hay un mecanismo:

1. **Hay que acertar en todas las consultas**, y en un IGA serán cientos: pantallas, informes, contadores, trabajos de agregación, exportación de evidencia, evaluación de SoD.
2. **Las más fáciles de olvidar no son las listas**, sino los contadores, los informes y los procesos programados que se ejecutan sin usuario conectado, donde no hay un "inquilino actual" obvio del que tirar.
3. **Los tests pasan.** Con una sola organización en los datos de prueba, una consulta que olvida el filtro devuelve exactamente lo mismo que una correcta. El fallo aparece con el segundo cliente real, en producción, y lo descubre el cliente.

La gravedad es máxima en este dominio concreto: el dato que se filtra es quién tiene acceso a qué, es decir, el mapa para atacar a la otra organización. Está registrado como amenaza de divulgación en la sección 9.

**El criterio de este ADR:** "acordarse de filtrar" no es un control de seguridad. Hace falta un mecanismo que no se pueda olvidar.

## Opciones consideradas

**1. Solo la columna, filtrado por disciplina.** Cada consulta añade su `AND org_id = :org` y las revisiones de código lo vigilan.

Es la opción que paga toda la complejidad del multiinquilino y no compra nada de su seguridad. Un único olvido en cualquier consulta de las cientos que habrá es una fuga entre clientes, y el punto 3 del contexto garantiza que ningún test lo detecte. Descartada.

**2. Clase base de repositorio.** Una clase propia de la que heredan todos los repositorios y que inyecta el filtro.

Es la más simple y no añade dependencias. Su defecto es estructural: protege solo lo que hereda de ella, y nada impide escribir una clase que no herede, ni una consulta nativa, ni un informe. Vuelve a depender de que el programador use la vía correcta, que es el problema original con un paso más.

**3. Filtro global de Hibernate.** Hibernate, que es la librería que traduce objetos Java a SQL, permite declarar filtros que se añaden automáticamente a las consultas que pasan por él.

Cubre más que la opción 2 y se configura en un sitio. Pero su frontera es la librería: SQL nativo, informes, procesos de migración y cualquier acceso que no pase por Hibernate se lo saltan en silencio. Además el filtro hay que activarlo por sesión, y una sesión sin activar no avisa, simplemente no filtra.

**4. Row Level Security de PostgreSQL.** La base de datos aplica el filtro por su cuenta a toda consulta que llegue, sea desde Hibernate, desde SQL nativo o desde un cliente de línea de comandos.

Es la única de las cuatro que sigue protegiendo cuando el error está en el código de la aplicación, que es donde van a estar los errores. Y es la única que un auditor puede verificar mirando la base de datos en lugar de revisando código.

## Decisión

Se elige **Row Level Security (RLS) de PostgreSQL** como mecanismo de aislamiento, con `org_id` como discriminador en todas las tablas de negocio.

### Mecánica concreta

**Activación por tabla**, en la propia migración que crea la tabla:

```sql
ALTER TABLE identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity FORCE ROW LEVEL SECURITY;

CREATE POLICY identity_tenant_isolation ON identity
    USING (org_id = current_setting('app.current_org', true)::uuid);
```

**El inquilino actual se fija por transacción**, no por sesión:

```sql
SELECT set_config('app.current_org', :orgId, true);  -- is_local = true
```

El tercer parámetro a `true` hace que el valor dure solo hasta el final de la transacción. Es imprescindible porque el grupo de conexiones (connection pool) reutiliza conexiones entre peticiones: un valor de ámbito de sesión se quedaría pegado a la conexión y la siguiente petición, de otro cliente, heredaría el inquilino anterior. Eso convertiría el mecanismo de aislamiento en el origen de la fuga.

**Dos roles de base de datos**, y esta es la parte que hace que lo demás funcione:

| Rol | Para qué | Sujeto a RLS |
|---|---|---|
| `lineward_owner` | Propietario de las tablas. Lo usa Flyway para las migraciones | No, por ser propietario |
| `lineward_app` | Lo usa la aplicación en tiempo de ejecución. No es propietario de nada | Sí |

### La trampa que invalida RLS si no se conoce

La documentación de PostgreSQL es explícita:

> "Superusers and roles with the `BYPASSRLS` attribute always bypass the row security system when accessing a table. Table owners normally bypass row security as well, though a table owner can choose to be subject to row security with `ALTER TABLE ... FORCE ROW LEVEL SECURITY`."

Es decir: **en la configuración por defecto, en la que la aplicación se conecta con el mismo usuario que creó las tablas, RLS no hace absolutamente nada.** Se activa, no da ningún error, y no filtra. Es el peor fallo posible: un control de seguridad que parece estar puesto y no lo está.

Por eso la decisión incluye las dos defensas a la vez, que son independientes: `FORCE ROW LEVEL SECURITY` en cada tabla, y un rol de aplicación que no es propietario. Con cualquiera de las dos basta; con las dos, un error de configuración en una no deja el sistema abierto.

### Consecuencia inmediata para el diseño del esquema

La documentación también dice:

> "Referential integrity checks, such as unique or primary key constraints and foreign key references, always bypass row security to ensure that data integrity is maintained."

Esto abre un canal de fuga que RLS no tapa y que hay que tapar en el diseño de las tablas. Si `identity` tuviera una restricción `UNIQUE (email)` global, insertar una identidad con un correo que ya existe **en otra organización** devolvería un error de restricción duplicada. La fila es invisible, pero el error confirma que existe. Eso es una fuga de información aunque no se vea ni una sola fila.

**Regla, por tanto, desde la primera migración:** toda restricción de unicidad de una tabla de negocio incluye `org_id` como primera columna.

```sql
-- Mal: revela la existencia de filas de otras organizaciones
UNIQUE (email)
-- Bien
UNIQUE (org_id, email)
```

Lo mismo aplica a las claves ajenas: una clave ajena que apunte a una fila de otra organización no sería rechazada por RLS. La coherencia entre organizaciones es responsabilidad del dominio, no de la base de datos.

## Consecuencias

**Lo que se gana**

- El aislamiento no depende de recordar nada. Una consulta que olvida el filtro no devuelve datos de otro cliente: devuelve menos filas de las esperadas.
- **Fallo cerrado por defecto.** Si nadie fija `app.current_org`, `current_setting` con el segundo parámetro a `true` devuelve `NULL`, la comparación da `NULL`, y la política no deja pasar ninguna fila. El caso "me he olvidado de poner el inquilino" se comporta como "prohibido", no como "todo permitido".
- Protege también el SQL nativo, los informes y cualquier consulta que no pase por Hibernate.
- Es verificable por un auditor sin leer código.

**Lo que se pierde, o lo que hay que vigilar**

- **Depurar es más incómodo.** Cuando una consulta devuelve cero filas no hay ningún error que lo explique: las filas simplemente no están. El primer sospechoso ante un "no aparece nada" pasa a ser el inquilino no fijado.
- **Hay que fijar el inquilino en cada transacción**, incluida cada petición HTTP, cada mensaje consumido y cada trabajo programado. Los trabajos programados son el punto delicado, porque no tienen usuario del que deducirlo y hay que decidir explícitamente sobre qué organizaciones iteran.
- **Los tests cambian de forma, y esto es lo que convierte el mecanismo en una garantía.** Los datos de prueba tienen que contener **siempre al menos dos organizaciones**, y tiene que existir un test que afirme que desde una no se ve nada de la otra. Sin esa segunda organización en los datos, los tests vuelven a ser incapaces de detectar una fuga, y da igual el mecanismo que haya debajo.
- **Rendimiento.** La política añade un predicado a cada consulta, así que `org_id` necesita estar en los índices. No se espera un problema a la escala de la demo (unas 5.000 identidades), pero es algo que medir en la Fase 8 y no asumir.
- **Flyway funciona sin cambios** porque corre como propietario, que no está sujeto a RLS. A cambio, las migraciones pueden ver y tocar datos de todas las organizaciones, lo que es correcto para una migración y peligroso para cualquier otro uso de ese rol. El rol propietario no se usa en tiempo de ejecución.
- **No convierte a Lineward en multiinquilino.** Sigue siendo lo que dice la sección 2.3: no hay gestión de organizaciones ni aislamiento como funcionalidad. Durante las fases 0 a 8 habrá una sola organización en uso, y el mecanismo estará ahí sin dar servicio a nadie. Ese es exactamente el coste que se aceptó.

**Pendiente, y relacionado**

- La cadena de hash del registro de auditoría (M8) necesita decidir si es una global o una por organización. Depende de esta decisión y es zona reservada, así que se resuelve con el modelo de dominio (sección 16).

## Fuentes consultadas

Verificadas el 2026-10-04 contra la documentación de PostgreSQL 18:

- [Row Security Policies](https://www.postgresql.org/docs/18/ddl-rowsecurity.html), para el bypass del propietario, `FORCE ROW LEVEL SECURITY` y el bypass de las comprobaciones de integridad referencial.
- [SET](https://www.postgresql.org/docs/18/sql-set.html), para el ámbito de transacción de `SET LOCAL`.
- [Customized Options](https://www.postgresql.org/docs/18/runtime-config-custom.html), para confirmar que PostgreSQL acepta parámetros de nombre compuesto como `app.current_org`.
- [System Administration Functions](https://www.postgresql.org/docs/18/functions-admin.html), para las firmas de `current_setting(setting_name, missing_ok)` y `set_config(setting_name, new_value, is_local)`.
