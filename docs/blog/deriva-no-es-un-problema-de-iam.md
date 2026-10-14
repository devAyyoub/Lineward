---
estado: borrador
publico: ingenieros de backend y profesionales de IAM. El gancho funciona para cualquiera que use git
fase: 0
titulo_alternativo: "Git me dijo que tenía seis commits sin subir. Era mentira"
---

> **Borrador.** Está escrito entero para que se pueda reescribir encima, no para publicarlo tal cual. Tres cosas quedan por decidir y están marcadas al final.

# La deriva no es un problema de gobierno de identidades, es de todos los días

Llevo tres semanas construyendo una plataforma de gobierno de identidades. En ese tiempo me he creído cuatro veces algo falso con total convicción: tres me las dijo una herramienta y la cuarta me la dije yo. Las cuatro son el mismo error, y resulta que es el error que da sentido a la categoría de software que estoy construyendo. Vale la pena contarlo en orden.

## La primera vez

Abrí el proyecto por la mañana y git me informó de que tenía seis commits sin subir.

```
$ git branch -avv
* main                 [origin/main: adelante 6] docs: ...
  remotes/origin/main  docs: ...        <- un commit de hace días
```

Era mentira. Los seis estaban en GitHub desde la noche anterior.

La explicación, cuando se ve, es obvia y además está documentada: `refs/remotes/origin/main` no es el estado del servidor. Es una copia local de dónde estaba esa rama **la última vez que este clon habló con el servidor**. Si el push se hizo desde otro sitio, o si la referencia no se ha refrescado, lo que git te enseña es historia.

Lo interesante no es el fallo. Es que ningún comando de git avisa de que lo que muestra puede estar caducado. `git branch -avv` presenta el dato viejo con exactamente la misma cara que habría presentado el bueno. Para preguntarle al servidor de verdad hay otro comando:

```
$ git ls-remote origin
```

Ese sí abre una conexión y pregunta. Dos comandos que parecen contestar a la misma pregunta, y solo uno la contesta.

## La segunda vez

Días después estaba fijando las versiones del stack. Elegí Spring Boot y PostgreSQL 18, las dos estables, las dos dentro de su ventana de soporte. Y resulta que juntas no arrancan:

```
Unsupported Database: PostgreSQL 18.1
```

No es un aviso que se pueda ignorar, es un fallo de arranque. La causa: la versión de Flyway, que es la herramienta que aplica las migraciones de la base de datos, llevaba dentro una lista de versiones soportadas escrita antes de que PostgreSQL 18 existiera. Al conectarse, Flyway consulta la versión del servidor, la compara con su lista y se niega.

El motor funcionaba perfectamente. El driver también, porque un driver solo habla el protocolo y el protocolo no cambia entre versiones mayores. Lo que estaba desactualizado era **la idea que la herramienta tenía del mundo**, y esa idea viajaba dentro del jar.

De aquí salió una regla que sigo usando: una versión mayor de base de datos rompe primero las herramientas que la **inspeccionan** (migraciones, clientes administrativos, contenedores de test) y no las que simplemente la usan. Y un corolario incómodo: verificar pieza a pieza da una falsa tranquilidad, porque cada pieza es correcta. La superficie de riesgo de un stack es el producto de sus versiones, no la lista.

## La tercera vez

Una semana más tarde fui a confirmar esas mismas versiones y consulté la API de búsqueda de Maven Central, que es donde viven los paquetes de Java. Me dijo que la última versión de Spring Boot era la 3.5.3.

Nueve días antes yo había fijado la 4.1.x, verificándolo contra la documentación oficial. Una de las dos cosas estaba mal, y la hipótesis cómoda era que mi decisión estaba construida sobre una versión inventada.

Estuve a punto de rehacer el trabajo. Lo que lo salvó fue consultar el `maven-metadata.xml` del propio repositorio, que es el listado real de lo publicado:

```
$ curl -s https://repo1.maven.org/maven2/org/springframework/boot/spring-boot/maven-metadata.xml
```

Ahí estaba la 4.1.1 como última estable. **El buscador es un índice sobre el repositorio; el repositorio es el repositorio.** Cuando los dos discrepan, manda el segundo.

## La cuarta vez

Y la de esta semana, que es la más tonta y la más instructiva. Creé un commit, le retoqué el mensaje con `git commit --amend` para ajustarlo al estilo del proyecto, y al pushear:

```
! [rejected]  main -> main (non-fast-forward)
```

Enmendar no modifica un commit, crea otro con identificador distinto. El original ya estaba publicado, así que el repositorio local y el servidor acabaron contando la misma historia con commits diferentes.

Aquí la copia caducada no era un archivo ni un índice. Era **una creencia**: dar por hecho que ese commit seguía siendo solo local. Ninguna herramienta me engañó esta vez; me engañé yo con el mismo mecanismo.

## El patrón

Cuatro casos, tres herramientas y una suposición mía, un solo error: **tratar una copia del estado como si fuera el estado.**

Y ninguna de las cuatro copias estaba corrupta. Todas eran correctas en el momento en que se crearon. El problema es el que tienen todas las cachés del mundo: no saben cuándo han dejado de ser ciertas, y nadie las presenta con una advertencia.

La pregunta útil no es "¿este dato es correcto?", porque la respuesta es casi siempre que sí, y además no la puedes comprobar sin hacer el trabajo que querías evitar. La pregunta útil son dos:

1. ¿Estoy leyendo el estado, o una copia del estado?
2. ¿Cuánto cuesta equivocarme?

La mentira de los seis commits me costó dos minutos. La misma confusión, en el sitio donde trabajo, cuesta bastante más.

## Y ahora la parte que no esperaba

Resulta que llevaba tres semanas tropezando con el problema central del software que estaba construyendo.

Lineward es una plataforma de **gobierno de identidades**, en inglés IGA, que es la disciplina que contesta a quién tiene acceso a qué, por qué lo tiene y si debería seguir teniéndolo. En una organización de cierto tamaño nadie puede responder a esas tres preguntas, porque las cuentas viven repartidas en decenas de sistemas desconectados.

Una plataforma así mantiene lo que se llama el **estado esperado**: a quién le corresponde cada permiso, según su puesto, según lo que se le aprobó, según el rol que tiene asignado. Es su base de datos y es muy fiable, en el sentido de que siempre contesta rápido y sin dudar.

El problema es que el estado esperado **no es el estado**. El estado real vive en los sistemas destino: en el directorio corporativo, en el proveedor de identidad, en cada aplicación. Y los dos divergen en cuanto alguien concede un permiso a mano, salta un proceso automático o deshace un cambio directamente en el sistema. A esa diferencia el sector la llama **deriva**.

Que es, exactamente, mi problema de las últimas tres semanas. La base de datos de la plataforma seguirá afirmando lo que ella misma escribió, con total convicción, igual que `git branch -avv`. Y no estará corrupta: estará contando con precisión cómo era el mundo la última vez que lo miró.

De ahí salen dos reglas de diseño que no son opinables:

**Una plataforma de gobierno de identidades que no vuelve a leer los sistemas destino periódicamente no gobierna, recita lo que ella misma escribió.** El proceso de volver a leerlos se llama **agregación**, y no es una tarea de mantenimiento ni un detalle de implementación: es lo que separa una plataforma de gobierno de una hoja de cálculo con buena interfaz.

**Las campañas de recertificación se hacen sobre el estado agregado, nunca sobre el esperado.** Una campaña de recertificación es cuando un responsable revisa los accesos de su equipo y confirma o revoca cada uno. Si lo que se le presenta para firmar es el estado esperado, está firmando un informe sobre un informe: está certificando que la plataforma es coherente consigo misma, que es verdad y no sirve de nada. Lo que un auditor quiere saber es qué puede hacer esa persona de verdad, hoy, en el sistema de pagos.

Y entonces aparece el hallazgo clásico, el que sale en casi todas las auditorías de control de acceso: la **cuenta huérfana**. Una cuenta que no se puede asociar a ninguna persona, normalmente porque esa persona se fue y el proceso de baja no llegó a ese sistema. No está en el estado esperado, porque según la plataforma esa persona ya no tiene nada. Está en el real, y suele seguir funcionando.

Es la mentira de los seis commits otra vez, con la diferencia de que esta no se arregla con `git ls-remote`.

## Lo que me llevo

Que esto le pase a un desarrollador cuatro veces en tres semanas, con herramientas que usa a diario y que no tienen ningún defecto, dice algo sobre lo inevitable del problema. Las cachés existen porque preguntar a la fuente es caro. La disciplina no consiste en no usarlas, consiste en saber en cada dato si estás leyendo el estado o una copia, y en ir a la fuente cuando la decisión lo merece.

En control de acceso, la decisión siempre lo merece.

---

## Pendiente de decidir antes de publicar

1. **Atribución del cuarto caso.** La enmienda del commit la hizo el agente de desarrollo con el que trabajo, no yo. Contarlo así abre el tema de trabajar con agentes, que tiene su propio post previsto, y puede desviar este. Las opciones son contarlo tal cual, contarlo en primera persona porque la decisión de delegar es mía igualmente, o quitarlo y dejar tres casos.
2. **Si este es el primer post publicado.** El plan era abrir con un post de presentación del proyecto. Este es mejor gancho técnico y no necesita que haya nada construido, pero no presenta Lineward.
3. **Enlaces y figuras.** Falta decidir si se enlazan los registros de decisión del repositorio, y si merece un diagrama del estado esperado frente al real. Con un diagrama se entiende la deriva en tres segundos, pero el texto ya lo explica y puede sobrar.
