# ADR-007 · Nombre del proyecto: Lineward

- **Estado:** Aceptado
- **Fecha:** 2026-09-22

## Contexto

El proyecto nació con el nombre provisional Cerbero, tomado del perro de tres cabezas que guarda las puertas del Hades. La metáfora es correcta para una plataforma de gobierno de accesos, pero el nombre estaba pendiente de comprobar colisiones.

Las restricciones reales del proyecto son modestas: hace falta un repositorio dentro del espacio de nombres personal de GitHub, una página en `ayyoub.dev` y unas coordenadas Maven bajo el dominio propio. No hacen falta `.com`, npm ni PyPI. Pero sí importan dos cosas: que el nombre no colisione con nadie visible en IAM o seguridad, porque el proyecto es el escaparate de una futura línea de auditoría IAM freelance, y que el nombre no prometa algo distinto de lo que el proyecto hace.

## Opciones consideradas

**1. Kerberos.** Descartado por dos motivos independientes, cualquiera de ellos suficiente. Es marca registrada del MIT, que prohíbe expresamente el uso comercial sin permiso escrito ("Kerberos... are trademarks of the Massachusetts Institute of Technology. No commercial use of these trademarks may be made without prior written permission of MIT"). Y nombra un protocolo de autenticación, justo lo que la sección 2.3 declara como no objetivo: la autenticación se delega en Keycloak.

**2. Cerberus.** La grafía inglesa está saturada en seguridad: Cerberus Cyber Security, Cerberus Cyber Solutions, la app Cerberus de MDM, el Project Cerberus de Microsoft y Cerberus Capital Management. En GitHub, el repositorio más popular con ese nombre son plantillas de correo con más de 5.000 estrellas.

**3. Cerbero.** Mejor que Cerberus, pero existe Cerbero Labs, que vende Cerbero Suite, una herramienta comercial de análisis de malware y forense activa desde 2011, y es dueña de `cerbero.io`. Los cuatro dominios principales están registrados. Además, el repositorio de seguridad más visible llamado `cerbero` es un atacante del protocolo Kerberos.

**4. Aldaba.** El llamador de una puerta, el objeto con el que se pide permiso para entrar. Metáfora excelente y espacios de nombres técnicos libres, pero existe Aldaba, una consultora tecnológica gallega en activo desde 2003, homologada en el programa Empresa Cibersegura de Galicia. Misma categoría de servicio y mismo mercado que la línea freelance prevista.

**5. Linaje.** Nace del principio 2 de la sección 3.3, el linaje del acceso. Sin colisión encontrada, pero pierde la posibilidad de un logo con identidad y la jota es difícil para un angloparlante.

**6. Lineward.** Compuesto de *lineage* y *ward*.

Se filtraron alrededor de 75 candidatos en cinco tandas, comprobando dominios, npm, PyPI, GitHub y presencia de empresas. El resultado fue concluyente: ninguna palabra de diccionario con significado aprovechable está libre, ni en español, ni en inglés, ni en latín. Es la razón por la que la propia industria usa compuestos inventados: SailPoint, CyberArk, ForgeRock, BeyondTrust, Saviynt, Okta.

## Decisión

Se elige **Lineward**.

El nombre compone *lineage* y *ward*, y la segunda mitad aporta tres sentidos que sirven a la vez:

- *To ward*: guardar, custodiar.
- *A ward*: persona bajo la tutela de un guardián, que es exactamente la relación entre un agente de IA y su sponsor humano en el módulo M10.
- *The wards of a lock*: las guardas de una cerradura, los obstáculos internos que solo deja pasar la llave correcta. Vocabulario real de cerrajería y origen del logo.

La primera mitad nombra lo que distingue a este proyecto de cualquier otro IGA: la capacidad de responder por qué una identidad tiene un acceso, quién lo aprobó y cuándo caduca.

Comprobado el 2026-09-22: `lineward.dev`, `lineward.io`, npm y PyPI libres, dos repositorios homónimos en GitHub sin actividad relevante y ninguna empresa encontrada con ese nombre. La comprobación de empresas fue por búsqueda web, no por consulta al registro de marcas; queda pendiente verificar en EUIPO antes de cualquier uso comercial.

## Consecuencias

- El paquete raíz pasa a ser `dev.ayyoub.lineward` y el repositorio a `lineward`.
- El logo se replantea: la guarda de una cerradura o el dentado de una llave, plano y monocromo, en lugar de la criatura de dos cabezas. Gana legibilidad a 16 píxeles.
- El sufijo *-ward* se lee en inglés como dirección (skyward, windward), así que el sentido de guardián no llega solo. Hay que contarlo en la página del proyecto.
- Al ser un compuesto inventado, el nombre no se explica por sí mismo y necesita siempre una línea de contexto. A cambio, no compite con nadie en las búsquedas.
- Queda pendiente la verificación en EUIPO si el nombre llega a usarse para vender servicios.
